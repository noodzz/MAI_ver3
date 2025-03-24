package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import model.Cargo;
import model.CargoPool;
import model.LoadingConfiguration;
import model.Truck;
import util.LogHelper;

import java.io.*;
import java.util.*;

public class LoadingManagerAgent extends Agent {
    private LoadingConfiguration config;
    private List<Cargo> availableCargos;
    private Map<AID, Boolean> truckReadyStatus;
    private int readyTrucks = 0;
    private Map<Integer, Boolean> impossibleCargos = new HashMap<>();
    private Map<String, TruckReport> finalTruckReports = new HashMap<>();
    private boolean reportGenerated = false; // Флаг для отслеживания генерации отчета
    private boolean feasibilityCheckLogged = false; // Флаг для предотвращения повторных логов
    private boolean distributionStarted = false;
    private List<AID> consoleSubscribers = new ArrayList<>();
    // Inner class to store truck report data
    private static class TruckReport {
        int id;
        float capacity;
        float currentLoad;
        float loadPercentage;
        Set<String> cargoTypes = new HashSet<>();
        List<CargoEntry> cargos = new ArrayList<>();
        Set<Integer> processedCargoIds = new HashSet<>();
        Set<Integer> impossibleCargoIds = new HashSet<>();
        static class CargoEntry {
            int id;
            String type;
            float weight;

            CargoEntry(int id, String type, float weight) {
                this.id = id;
                this.type = type;
                this.weight = weight;
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Truck ID: ").append(id).append("\n");
            sb.append("Capacity: ").append(capacity).append("\n");
            sb.append("Current Load: ").append(currentLoad).append(" (").append(loadPercentage).append("%)\n");
            sb.append("Loaded Cargo Types: ").append(cargoTypes).append("\n");
            sb.append("Cargos: \n");

            for (CargoEntry cargo : cargos) {
                sb.append("  - Cargo ").append(cargo.id).append(" (Type: ").append(cargo.type)
                        .append(", Weight: ").append(cargo.weight).append(")\n");
            }

            return sb.toString();
        }
    }

    @Override
    protected void setup() {
        System.out.println("Loading Manager Agent " + getAID().getName() + " is ready.");
        LogHelper.setManagerAgent(this);
        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            config = (LoadingConfiguration) args[0];
            if (config.isUseDynamicIdealLoad()) {
                float totalTruckCapacity = 0f;
                float totalCargoWeight = 0f;
                for (Truck truck : config.getTrucks()) {
                    totalTruckCapacity += truck.getCapacity();
                }

                // Sum up all cargo weights
                for (Cargo cargo : config.getCargos()) {
                    totalCargoWeight += cargo.getWeight();
                }
                float dynamicIdealPercentage = Math.min((totalCargoWeight / totalTruckCapacity) * 100, 100);
                config.setIdealLoadPercentage(dynamicIdealPercentage);
                System.out.println("Dynamic ideal load percentage calculated: " + dynamicIdealPercentage + "%");
                System.out.println("Total truck capacity: " + totalTruckCapacity + ", Total cargo weight: " + totalCargoWeight);
            }
            availableCargos = new ArrayList<>(config.getCargos());
            impossibleCargos.clear(); // Очищаем предыдущие данные
            checkCargoFeasibility();
            System.out.println("Available cargos: " + availableCargos.size());
            truckReadyStatus = new HashMap<>();
        } else {
            System.err.println("ERROR: No configuration provided. Agent will terminate.");
            doDelete();
            return;
        }
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.or(
                        MessageTemplate.and(
                                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                                MessageTemplate.MatchContent("START_DISTRIBUTION")
                        ),
                        MessageTemplate.and(
                                MessageTemplate.MatchPerformative(ACLMessage.SUBSCRIBE),
                                MessageTemplate.MatchContent("CONSOLE_SUBSCRIBE")
                        )
                );

                ACLMessage msg = myAgent.receive(mt);
                if (msg != null) {
                    if (msg.getPerformative() == ACLMessage.REQUEST) {
                        // Запрос на начало распределения
                        if (!distributionStarted) {
                            distributionStarted = true;
                            System.out.println("Получен запрос на начало распределения от " +
                                    msg.getSender().getLocalName());

                            // Запускаем процесс создания агентов-грузовиков
                            startDistribution();
                        } else {
                            System.out.println("Игнорирую повторный запрос на распределение от " +
                                    msg.getSender().getLocalName());
                        }
                    } else if (msg.getPerformative() == ACLMessage.SUBSCRIBE) {
                        // Подписка на консольный вывод
                        AID subscriber = msg.getSender();
                        if (!consoleSubscribers.contains(subscriber)) {
                            consoleSubscribers.add(subscriber);
                            System.out.println("Добавлен новый подписчик на консоль: " +
                                    subscriber.getLocalName());
                        }
                    }
                } else {
                    block();
                }
            }
        });
        addBehaviour(new WakerBehaviour(this, 360000) { // 60 second timeout
            protected void onWake() {
                System.out.println("Master timeout reached. Finalizing all operations...");
                if (!reportGenerated) {
                    if (truckReadyStatus.isEmpty()) {
                        System.out.println("No trucks registered. Cannot generate report.");
                    } else {
                        requestTruckReports();
                    }
                }
            }
        });

        // Add behavior to handle cargo requests
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    String content = msg.getContent();
                    if (content.equals("REQUEST_CARGO")) {
                        // Send available cargo to the truck
                        ACLMessage reply = msg.createReply();
                        List<Cargo> filteredCargos = new ArrayList<>();
                        synchronized (availableCargos) {
                            for (Cargo c : availableCargos) {
                                if (!impossibleCargos.containsKey(c.getId())) {
                                    filteredCargos.add(c);
                                }
                            }
                        }

                        if (!filteredCargos.isEmpty()) {
                            try {
                                reply.setPerformative(ACLMessage.INFORM);
                                reply.setContentObject((Serializable) filteredCargos);
                                myAgent.send(reply);
                                System.out.println("Sent " + filteredCargos.size() + " cargos to " + msg.getSender().getLocalName());
                            } catch (IOException e) {
                                e.printStackTrace();
                                reply.setPerformative(ACLMessage.FAILURE);
                                reply.setContent("ERROR_PROCESSING_CARGO");
                                myAgent.send(reply);
                            }
                        } else {
                            reply.setPerformative(ACLMessage.REFUSE);
                            reply.setContent("NO_CARGO_AVAILABLE");
                            myAgent.send(reply);
                            System.out.println("No cargo available for " + msg.getSender().getLocalName());
                        }
                    } else if (content.startsWith("CLAIM_CARGO:")) {
                        // Process cargo claim from truck
                        int cargoId = Integer.parseInt(content.substring("CLAIM_CARGO:".length()));
                        Cargo claimedCargo = null;
                        synchronized (availableCargos) {
                            for (Cargo cargo : availableCargos) {
                                if (cargo.getId() == cargoId) {
                                    claimedCargo = cargo;
                                    break;
                                }
                            }
                            if (claimedCargo != null) {
                                availableCargos.remove(claimedCargo); // Удаляем груз
                                System.out.println("Cargo " + cargoId + " claimed by " + msg.getSender().getLocalName());
                            }
                        }

                        ACLMessage reply = msg.createReply();
                        if (claimedCargo != null) {
                            reply.setPerformative(ACLMessage.CONFIRM);
                            try {
                                reply.setContentObject(claimedCargo); // Отправляем сам груз
                            } catch (IOException e) {
                                e.printStackTrace();
                                reply.setPerformative(ACLMessage.FAILURE);
                                reply.setContent("ERROR_PROCESSING_CARGO");
                            }
                        } else {
                            reply.setPerformative(ACLMessage.DISCONFIRM);
                            reply.setContent("CARGO_NOT_AVAILABLE");
                            System.out.println("Cargo " + cargoId + " not available for " + msg.getSender().getLocalName());
                        }
                        myAgent.send(reply);
                    } else if (content.equals("TRUCK_READY")) {
                        // Mark truck as ready
                        AID sender = msg.getSender();
                        // Избегаем повторных сообщений при повторной отправке TRUCK_READY
                        if (!truckReadyStatus.containsKey(sender) || !truckReadyStatus.get(sender)) {
                            truckReadyStatus.put(sender, true);
                            readyTrucks++;
                            System.out.println("Truck " + sender.getLocalName() + " is ready. Total ready: " +
                                    readyTrucks + "/" + config.getTrucks().size());

                            // Check if all trucks are ready
                            if (readyTrucks == config.getTrucks().size() && !reportGenerated) {
                                // All trucks are ready, request reports
                                requestTruckReports();
                            }
                        }
                    }
                } else {
                    block();
                }
            }
        });

        addBehaviour(new TickerBehaviour(this, 10000) { // Проверка каждые 10 секунд
            protected void onTick() {
                int previousImpossibleCount = impossibleCargos.size();
                checkCargoFeasibility();

                // Логируем результаты только если что-то изменилось
                int newImpossibleCount = impossibleCargos.size();
                if (newImpossibleCount > previousImpossibleCount) {
                    System.out.println("[Менеджер] Невозможных грузов: " + newImpossibleCount +
                            ", доступных: " + availableCargos.size());
                }
            }
        });
    }

    private void checkCargoFeasibility() {
        int removedCount = 0;
        synchronized (availableCargos) {
            Iterator<Cargo> it = availableCargos.iterator();
            while (it.hasNext()) {
                Cargo cargo = it.next();
                if (impossibleCargos.containsKey(cargo.getId())) continue;

                boolean canBeLoaded = false;
                for (Truck truck : config.getTrucks()) {
                    if (truck.canAddCargo(cargo)) {
                        canBeLoaded = true;
                        break;
                    }
                }

                if (!canBeLoaded) {
                    impossibleCargos.put(cargo.getId(), true);
                    it.remove();
                    removedCount++;
                }
            }
        }

        // Выводим информацию только если удалены грузы
        if (removedCount > 0) {
            System.out.println("[Менеджер] Удалено невозможных грузов: " + removedCount);
        }
    }
    public void broadcastToConsole(String message) {
        if (consoleSubscribers.isEmpty()) return;

        ACLMessage broadcast = new ACLMessage(ACLMessage.INFORM);
        for (AID subscriber : consoleSubscribers) {
            broadcast.addReceiver(subscriber);
        }
        broadcast.setContent("CONSOLE_OUTPUT:" + message);
        send(broadcast);
    }
    private void requestTruckReports() {
        System.out.println("Requesting status reports from all trucks...");
        reportGenerated = true; // Устанавливаем флаг, чтобы избежать повторной генерации

        // Track which trucks we've received reports from
        final Set<AID> pendingTrucks = new HashSet<>(truckReadyStatus.keySet());

        // Request final status from all trucks
        ACLMessage statusRequest = new ACLMessage(ACLMessage.REQUEST);
        for (AID aid : truckReadyStatus.keySet()) {
            statusRequest.addReceiver(aid);
        }
        statusRequest.setContent("REPORT_STATUS");
        send(statusRequest);

        // Устанавливаем таймаут для сбора отчетов
        addBehaviour(new WakerBehaviour(this, 15000) { // 15 seconds timeout
            protected void onWake() {
                System.out.println("Report collection timeout reached. Trucks responded: " + finalTruckReports.size() +
                        " out of " + truckReadyStatus.size());
                generateFinalReport();
            }
        });

        // Добавляем поведение для сбора отчетов
        addBehaviour(new Behaviour() {
            private int reportsReceived = 0;

            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
                ACLMessage reply = myAgent.receive(mt);

                if (reply != null) {
                    // Try to parse the string report
                    String content = reply.getContent();
                    if (content != null && content.startsWith("TRUCK_DATA_START")) {
                        // This is a text format truck report
                        try {
                            TruckReport report = parseTruckReport(content);
                            finalTruckReports.put(reply.getSender().getLocalName(), report);
                            pendingTrucks.remove(reply.getSender());
                            reportsReceived++;
                            System.out.println("Received string report from " + reply.getSender().getLocalName() +
                                    ". Total reports: " + reportsReceived + "/" + truckReadyStatus.size());
                        } catch (Exception e) {
                            System.err.println("Error parsing truck report from " + reply.getSender().getLocalName() + ": " + e.getMessage());
                            e.printStackTrace();
                            pendingTrucks.remove(reply.getSender());
                        }
                    } else {
                        System.err.println("Received non-truck data from " + reply.getSender().getLocalName() + ": " + content);
                        pendingTrucks.remove(reply.getSender());
                    }
                } else {
                    block(100);
                }
            }

            public boolean done() {
                return pendingTrucks.isEmpty();
            }

            public int onEnd() {
                System.out.println("All reports collected. Proceeding to report generation.");
                generateFinalReport();
                return 0;
            }
        });
    }

    // Parse the truck report from string format
    private TruckReport parseTruckReport(String reportText) {
        TruckReport report = new TruckReport();
        boolean inCargosSection = false;

        for (String line : reportText.split("\n")) {
            line = line.trim();

            if (line.equals("CARGOS_START")) {
                inCargosSection = true;
                continue;
            } else if (line.equals("CARGOS_END")) {
                inCargosSection = false;
                continue;
            }

            if (inCargosSection) {
                // Parse cargo line: id:type:weight
                String[] cargoParts = line.split(":");
                if (cargoParts.length >= 3) {
                    int id = Integer.parseInt(cargoParts[0]);
                    String type = cargoParts[1];
                    float weight = Float.parseFloat(cargoParts[2]);
                    report.cargos.add(new TruckReport.CargoEntry(id, type, weight));
                    report.cargoTypes.add(type);
                }
            } else if (line.startsWith("ID:")) {
                report.id = Integer.parseInt(line.substring(3));
            } else if (line.startsWith("CAPACITY:")) {
                report.capacity = Float.parseFloat(line.substring(9));
            } else if (line.startsWith("CURRENT_LOAD:")) {
                report.currentLoad = Float.parseFloat(line.substring(13));
            } else if (line.startsWith("LOAD_PERCENTAGE:")) {
                report.loadPercentage = Float.parseFloat(line.substring(16));
            } else if (line.startsWith("PROCESSED_CARGO_IDS:")) {
                String idsStr = line.substring(19);
                if (!idsStr.isEmpty()) {
                    for (String idStr : idsStr.split(",")) {
                        if (!idStr.isEmpty()) {
                            try {
                                report.processedCargoIds.add(Integer.parseInt(idStr.trim()));
                            } catch (NumberFormatException e) {
                                System.err.println("Ошибка при парсинге ID груза: " + idStr);
                            }
                        }
                    }
                }
            } else if (line.startsWith("CARGO_TYPES:")) {
                String[] types = line.substring(12).split(",");
                for (String type : types) {
                    if (!type.isEmpty()) {
                        report.cargoTypes.add(type);
                    }
                }
            } else if (line.startsWith("IMPOSSIBLE_CARGO_IDS:")) {
                String idsStr = line.substring(21);
                if (!idsStr.isEmpty()) {
                    for (String idStr : idsStr.split(",")) {
                        if (!idStr.isEmpty()) {
                            try {
                                report.impossibleCargoIds.add(Integer.parseInt(idStr.trim()));
                            } catch (NumberFormatException e) {
                                System.err.println("Ошибка при парсинге ID невозможного груза: " + idStr);
                            }
                        }
                    }
                }
            }
        }

        return report;
    }

    private void generateFinalReport() {
        System.out.println("Generating final report...");
        System.out.println("Number of trucks with data: " + finalTruckReports.size());

        if (finalTruckReports.isEmpty()) {
            // (код для пустого отчета)
            return;
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter("loading_report.txt"))) {
            writer.println("TRUCK LOADING REPORT");
            writer.println("===================");
            writer.println();
            writer.println("Report generated at: " + new Date());
            writer.println();

            // Calculate statistics
            float totalCapacity = 0;
            float totalLoad = 0;

            List<String> truckNames = new ArrayList<>(finalTruckReports.keySet());
            Collections.sort(truckNames);

            // Считаем фактически загруженные грузы
            Set<Integer> allLoadedCargoIds = new HashSet<>();

            for (String truckName : truckNames) {
                TruckReport report = finalTruckReports.get(truckName);
                writer.println(report.toString());
                writer.println("------------------");

                totalCapacity += report.capacity;
                totalLoad += report.currentLoad;

                // Добавляем все грузы из отчета в общий список загруженных грузов
                for (TruckReport.CargoEntry cargo : report.cargos) {
                    allLoadedCargoIds.add(cargo.id);
                }
            }

            // Собираем все идентификаторы грузов из конфигурации
            Set<Integer> allCargoIds = new HashSet<>();
            for (Cargo cargo : config.getCargos()) {
                allCargoIds.add(cargo.getId());
            }

            // Невозможные грузы - это те, которые не были загружены ни в один грузовик
            Set<Integer> trueImpossibleCargos = new HashSet<>(allCargoIds);
            trueImpossibleCargos.removeAll(allLoadedCargoIds);

            writer.println("SUMMARY");
            writer.println("=======");
            writer.println("Total trucks: " + finalTruckReports.size());
            writer.println("Total capacity: " + totalCapacity);
            writer.println("Total load: " + totalLoad);
            if (totalCapacity > 0) {
                writer.println("Overall loading percentage: " + String.format("%.2f", totalLoad / totalCapacity * 100) + "%");
            } else {
                writer.println("Overall loading percentage: 0% (no capacity)");
            }
            writer.println("Ideal loading percentage: " + config.getIdealLoadPercentage() + "%");
            writer.println();

            // Обработанные грузы - это все загруженные грузы
            writer.println("Total processed cargos: " + allLoadedCargoIds.size());
            writer.println("Impossible cargos count: " + trueImpossibleCargos.size());
            writer.println("Remaining cargos: " + (allCargoIds.size() - allLoadedCargoIds.size()));

            writer.flush(); // Принудительная запись данных в файл
            System.out.println("Report successfully generated: loading_report.txt");

        } catch (IOException e) {
            System.err.println("Error writing report: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Завершаем агента после успешной генерации отчета
            addBehaviour(new WakerBehaviour(this, 2000) {
                protected void onWake() {
                    System.out.println("Loading Manager finishing operations...");
                    doDelete();
                }
            });
        }
    }
    // Добавьте этот метод в класс LoadingManagerAgent
    private void startDistribution() {
        System.out.println("Начинаю процесс распределения грузов...");

        try {
            // Получаем контейнер, в котором выполняется менеджер
            jade.wrapper.AgentContainer container = getContainerController();

            // Создаем список AID для всех грузовиков
            List<AID> allTruckAIDs = new ArrayList<>();
            for (Truck truck : config.getTrucks()) {
                String agentName = "truck-" + truck.getId();
                allTruckAIDs.add(new AID(agentName, AID.ISLOCALNAME));
            }

            // Теперь создаем агентов
            for (Truck truck : config.getTrucks()) {
                String agentName = "truck-" + truck.getId();
                jade.wrapper.AgentController agent = container.createNewAgent(
                        agentName,
                        "agents.TruckAgent",
                        new Object[]{
                                truck,
                                config.getIdealLoadPercentage(),
                                new ArrayList<>(allTruckAIDs)
                        });
                agent.start();
                System.out.println("Создан и запущен агент-грузовик: " + agentName);
            }

            System.out.println("Все агенты-грузовики запущены, распределение начато");

        } catch (Exception e) {
            System.err.println("Ошибка при запуске агентов-грузовиков: " + e.getMessage());
            e.printStackTrace();
        }
    }
}