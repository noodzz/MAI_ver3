package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import model.Cargo;
import model.LoadingConfiguration;
import model.Truck;

import java.io.*;
import java.util.*;

public class LoadingManagerAgent extends Agent {
    private LoadingConfiguration config;
    private List<Cargo> availableCargos;
    private Map<AID, Boolean> truckReadyStatus;
    private int readyTrucks = 0;
    private Map<Integer, Boolean> impossibleCargos = new HashMap<>();
    private Map<String, Truck> finalTrucks = new HashMap<>();
    private boolean reportGenerated = false; // Флаг для отслеживания генерации отчета
    private boolean feasibilityCheckLogged = false; // Флаг для предотвращения повторных логов

    @Override
    protected void setup() {
        System.out.println("Loading Manager Agent " + getAID().getName() + " is ready.");

        Object[] args = getArguments();
        if (args != null && args.length > 0) {
            config = (LoadingConfiguration) args[0];
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
                System.out.println("Report collection timeout reached. Trucks responded: " + finalTrucks.size() +
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
                    try {
                        Truck truck = (Truck) reply.getContentObject();
                        finalTrucks.put(reply.getSender().getLocalName(), truck);
                        pendingTrucks.remove(reply.getSender());
                        reportsReceived++;
                        System.out.println("Received report from " + reply.getSender().getLocalName() +
                                ". Total reports: " + reportsReceived + "/" + truckReadyStatus.size());
                    } catch (UnreadableException e) {
                        System.err.println("Error reading truck data from " + reply.getSender().getLocalName() + ": " + e.getMessage());
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

    private void generateFinalReport() {
        System.out.println("Generating final report...");
        System.out.println("Number of trucks with data: " + finalTrucks.size());

        if (finalTrucks.isEmpty()) {
            System.out.println("ERROR: No truck data available to generate report!");
            // Создаем пустой отчет с сообщением об ошибке
            try (PrintWriter writer = new PrintWriter(new FileWriter("loading_report.txt"))) {
                writer.println("TRUCK LOADING REPORT - ERROR");
                writer.println("===================");
                writer.println();
                writer.println("No truck data available to generate report.");
                writer.println("Possible causes:");
                writer.println("- No trucks reported their status");
                writer.println("- Communication errors between agents");
                System.out.println("Empty error report generated: loading_report.txt");
            } catch (IOException e) {
                System.err.println("Error writing report file: " + e.getMessage());
                e.printStackTrace();
            }
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

            List<String> truckNames = new ArrayList<>(finalTrucks.keySet());
            Collections.sort(truckNames);

            for (String truckName : truckNames) {
                Truck truck = finalTrucks.get(truckName);
                writer.println(truck.toString());
                writer.println("------------------");

                totalCapacity += truck.getCapacity();
                totalLoad += truck.getCurrentLoad();
            }

            writer.println("SUMMARY");
            writer.println("=======");
            writer.println("Total trucks: " + finalTrucks.size());
            writer.println("Total capacity: " + totalCapacity);
            writer.println("Total load: " + totalLoad);
            if (totalCapacity > 0) {
                writer.println("Overall loading percentage: " + String.format("%.2f", totalLoad / totalCapacity * 100) + "%");
            } else {
                writer.println("Overall loading percentage: 0% (no capacity)");
            }
            writer.println("Ideal loading percentage: " + config.getIdealLoadPercentage() + "%");
            writer.println();
            writer.println("Impossible cargos count: " + impossibleCargos.size());
            writer.println("Remaining cargos: " + availableCargos.size());

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
}