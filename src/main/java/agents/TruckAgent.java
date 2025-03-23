package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import model.Cargo;
import model.Truck;

import java.io.*;
import java.util.*;

public class TruckAgent extends Agent {
    private Truck truck;
    private float idealLoadPercentage;
    private boolean loadingComplete = false;
    private boolean exchangeInProgress = false;
    private boolean readyStatusSent = false;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 3;
    private Set<Integer> processedCargoIds = new HashSet<>();
    private int exchangeAttempts = 0;
    private static final int MAX_EXCHANGE_ATTEMPTS = 2;
    private float desiredExchangeWeight;
    private String direction;
    private String otherTruckId;
    private List<AID> otherTruckAIDs; // Добавьте это объявление
    private Map<AID, Boolean> truckReadyStatus = new HashMap<>();
    private Map<AID, Cargo> pendingTransfers = new HashMap<>();
    private static Set<Integer> recentlyTransferredCargoIds = Collections.synchronizedSet(new HashSet<>());    private long exchangeStartTime = -1;
    private static final long MAX_EXCHANGE_TIME = 60000;
    private static Set<Integer> globalTransferredCargoIds = new HashSet<>();


    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length > 2) {
            truck = (Truck) args[0];
            idealLoadPercentage = (float) args[1];
            otherTruckAIDs = new ArrayList<>((List<AID>) args[2]);
            // Удаляем себя из списка по имени
            otherTruckAIDs.removeIf(aid ->
                    aid.getLocalName().equals(getAID().getLocalName())
            );
            System.out.println("[DEBUG] Список получателей: " + otherTruckAIDs); // Логирование
        }

        System.out.println("Truck Agent " + getAID().getName() + " is ready. Capacity: " + truck.getCapacity());

        // Behavior to request cargo from the manager
        addBehaviour(new OneShotBehaviour(this) {
            public void action() {
                requestCargo();
            }
        });
        // Add a specific handler for TRUCK_READY_STATUS messages
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    String content = msg.getContent();
                    // Проверяем текстовые сообщения сначала
                    if (content != null && content.startsWith("TRUCK_READY_STATUS:")) {
                        String[] parts = content.split(":", 2);
                        if (parts.length == 2) {
                            String truckName = parts[1];
                            AID truckAID = new AID(truckName, AID.ISLOCALNAME);
                            truckReadyStatus.put(truckAID, true);
                            System.out.println("Грузовик " + truckName + " теперь в статусе READY");
                        }
                    } else if (content != null && content.equals("REPORT_STATUS")) {
                        System.out.println(getAID().getName() + " sending final report to manager");
                        ACLMessage reply = msg.createReply();
                        reply.setPerformative(ACLMessage.INFORM);
                        try {
                            reply.setContentObject(truck);
                            myAgent.send(reply);
                        } catch (IOException ioe) {
                            ioe.printStackTrace();
                        }
                    } else {
                        // Пробуем обрабатывать как объект
                        try {
                            Object contentObj = msg.getContentObject();
                            if (contentObj instanceof List) {
                                List<Cargo> availableCargos = (List<Cargo>) contentObj;
                                processCargos(availableCargos);
                            } else if (contentObj instanceof Cargo) {
                                Cargo receivedCargo = (Cargo) contentObj;
                                processCargo(receivedCargo, msg.getSender());
                            } else if (contentObj instanceof Truck) {
                                System.out.println("Получен объект Truck");
                            } else {
                                System.out.println("Получен объект с классом: " + contentObj.getClass().getName());
                            }
                        } catch (UnreadableException e) {
                            System.out.println("Получено неизвестное текстовое сообщение INFORM: " + content);
                        }
                    }
                } else {
                    block();
                }
            }
        });
        // Behavior to handle cargo response (INFORM, REFUSE, REPORT_STATUS)
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REFUSE);

                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    if (msg.getPerformative() == ACLMessage.REFUSE) {
                        loadingComplete = true;
                        checkLoadingProgress();
                    }
                } else {
                    block();
                }
            }
        });

        // Behavior to handle cargo claim confirmations (CONFIRM, DISCONFIRM)
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.CONFIRM),
                        MessageTemplate.MatchPerformative(ACLMessage.DISCONFIRM)
                );
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    if (msg.getPerformative() == ACLMessage.CONFIRM) {
                        // Проверяем, это сообщение с подтверждением получения груза или объект Cargo
                        String content = msg.getContent();
                        if (content != null && content.startsWith("CARGO_RECEIVED:")) {
                            // Обработка подтверждения получения груза
                            String[] parts = content.split(":");
                            if (parts.length > 1) {
                                int cargoId = Integer.parseInt(parts[1]);
                                System.out.println("[ПОДТВЕРЖДЕНИЕ] Груз " + cargoId +
                                        " успешно получен грузовиком " + msg.getSender().getLocalName());

                                // Удаляем груз из списка ожидающих подтверждения
                                pendingTransfers.remove(msg.getSender());
                            }
                        } else {
                            // Это подтверждение от LoadingManagerAgent с объектом Cargo
                            try {
                                Cargo receivedCargo = (Cargo) msg.getContentObject();
                                truck.addCargo(receivedCargo);
                                System.out.println(getAID().getName() + " загрузил груз " + receivedCargo.getId());

                                float loadPercentage = truck.getLoadPercentage();
                                if (Math.abs(loadPercentage - idealLoadPercentage) <= 10) {
                                    notifyManager();
                                } else {
                                    requestCargo();
                                }
                            } catch (UnreadableException e) {
                                e.printStackTrace();
                                requestCargo();
                            }
                        }
                    } else if (msg.getPerformative() == ACLMessage.DISCONFIRM) {
                        requestCargo();
                    }
                } else {
                    block();
                }
            }
        });

        // Behavior to handle exchange requests
        // Behavior to handle exchange requests
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    AID sender = msg.getSender();
                    String content = msg.getContent();
                    System.out.println(getAID().getName() + " получил запрос обмена: " + content +
                            " (readyStatus=" + readyStatusSent + ")");
                    if (content.startsWith("EXCHANGE_REQUEST")) {
                        String[] parts = content.split(":", 4); // Увеличили до 4 для нового параметра
                        if (parts.length >= 4) {
                            try {
                                desiredExchangeWeight = Float.parseFloat(parts[1]);
                                direction = parts[2]; // "NEED_MORE" или "NEED_LESS"
                                float otherTruckLoadPercentage = Float.parseFloat(parts[3]);

                                boolean canHelp = false;
                                String reason = "";

                                // Если у нас противоположное направление и отклонение от идеального, можем помочь
                                if (readyStatusSent) {
                                    canHelp = false;
                                    reason = "уже отправил READY статус";
                                }
                                else if (Math.abs(truck.getLoadPercentage() - idealLoadPercentage) <= 10) {
                                    canHelp = false;
                                    reason = "загрузка уже оптимальна (" + truck.getLoadPercentage() + "%)";
                                }
                                // Важно: смотрим на противоположность направлений
                                else if (direction.equals("NEED_MORE") &&
                                        truck.getLoadPercentage() > idealLoadPercentage) {
                                    // Мы перегружены, а другой грузовик хочет больше - хорошее совпадение
                                    if (!truck.getLoadedCargos().isEmpty() &&
                                            hasTransferableCargo()) {
                                        canHelp = true;
                                    } else {
                                        reason = "нет грузов для передачи или их нельзя безопасно удалить";
                                    }
                                }
                                else if (direction.equals("NEED_LESS") &&
                                        truck.getLoadPercentage() < idealLoadPercentage) {
                                    // Мы недогружены, а другой грузовик хочет отдать груз - хорошее совпадение
                                    float freeCapacity = truck.getCapacity() - truck.getCurrentLoad();
                                    if (freeCapacity > 0) {
                                        canHelp = true;
                                    } else {
                                        reason = "нет свободной емкости";
                                    }
                                }
                                else {
                                    reason = "несовместимые направления обмена";
                                }

                                // Отправляем ответ в любом случае (положительный или отрицательный)
                                if (canHelp) {
                                    ACLMessage reply = new ACLMessage(ACLMessage.PROPOSE);
                                    reply.addReceiver(msg.getSender());
                                    reply.setContent("EXCHANGE_POSSIBLE:" + truck.getLoadPercentage());
                                    System.out.println(getAID().getName() + " может помочь с обменом, отправка предложения: " + truck.getLoadPercentage());
                                    myAgent.send(reply);
                                } else {
                                    // Добавлен явный отказ вместо просто логирования
                                    ACLMessage decline = new ACLMessage(ACLMessage.REFUSE);
                                    decline.addReceiver(msg.getSender());
                                    decline.setContent("EXCHANGE_REFUSED:" + reason);
                                    myAgent.send(decline);
                                    System.out.println(getAID().getName() + " не может помочь с обменом. Причина: " + reason);
                                }
                            } catch (NumberFormatException e) {
                                System.out.println("[ОШИБКА] Неверный формат данных в сообщении обмена: " + content);
                            }
                        }
                    }
                } else {
                    block();
                }
            }
        });
        // Обработчик для отказов в обмене
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REFUSE);
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    String content = msg.getContent();
                    if (content != null && content.startsWith("EXCHANGE_REFUSED:")) {
                        // Регистрируем отказ, но продолжаем ждать другие ответы
                        String reason = content.substring("EXCHANGE_REFUSED:".length());
                        System.out.println("[ОБМЕН] Получен отказ от " + msg.getSender().getLocalName() +
                                ": " + reason);

                        // Если это последний ожидаемый ответ, проверяем статус обмена
                        // Это потребует учета ожидаемых ответов, что усложнит код
                    }
                } else {
                    block();
                }
            }
        });

        // Behavior to handle exchange proposals (PROPOSE)
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.PROPOSE);
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    String content = msg.getContent();
                    System.out.println(getAID().getName() + " получил предложение: " + content);

                    if (content.startsWith("EXCHANGE_POSSIBLE")) {
                        // Убираем проверку exchangeInProgress, обрабатываем все предложения
                        String[] parts = content.split(":");
                        if (parts.length >= 2) {
                            float otherLoadPercentage = Float.parseFloat(parts[1]);
                            System.out.println("[DEBUG] Направление обмена: " + direction +
                                    ", загрузка другого грузовика: " + otherLoadPercentage);
                            boolean willImproveOurSituation = false;
                            boolean willImproveTheirSituation = false;
                            // Проверяем, подходит ли предложение
                            boolean acceptProposal = false;

                            // Если наша загрузка уже оптимальная, не принимаем предложения
                            if (Math.abs(truck.getLoadPercentage() - idealLoadPercentage) <= 10) {
                                acceptProposal = false;
                                System.out.println("[DEBUG] Предложение отклонено - у нас уже оптимальная загрузка.");
                            }
                            // Если мы нуждаемся в большем грузе, а другой грузовик имеет больше чем идеально
                            else if (direction.equals("NEED_MORE") && otherLoadPercentage > idealLoadPercentage) {
                                acceptProposal = true;
                            }
                            // Если мы нуждаемся в меньшем грузе, а другой грузовик имеет меньше чем идеально
                            else if (direction.equals("NEED_LESS") && otherLoadPercentage < idealLoadPercentage) {
                                acceptProposal = true;
                            }

                            if (acceptProposal) {
                                // Принимаем предложение
                                exchangeInProgress = true;

                                ACLMessage typeRequest = new ACLMessage(ACLMessage.REQUEST);
                                typeRequest.addReceiver(msg.getSender());
                                typeRequest.setContent("REQUEST_CARGO_TYPES");
                                send(typeRequest);

                                final AID sender = msg.getSender();
                                addBehaviour(new WakerBehaviour(myAgent, 5000) { // 5 секунд на ответ
                                    protected void onWake() {
                                        // Создаем обработчик для получения типов грузов
                                        addBehaviour(new OneShotBehaviour(myAgent) {
                                            public void action() {
                                                MessageTemplate mtTypes = MessageTemplate.and(
                                                        MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                                                        MessageTemplate.MatchSender(sender));

                                                ACLMessage typesReply = myAgent.receive(mtTypes);

                                                if (typesReply != null && typesReply.getContent() != null &&
                                                        typesReply.getContent().startsWith("CARGO_TYPES:")) {
                                                    // Извлекаем типы грузов
                                                    String content = typesReply.getContent();
                                                    String typesStr = content.substring("CARGO_TYPES:".length());
                                                    String[] cargoTypes = typesStr.split(",");

                                                    // Проверяем совместимость
                                                    boolean compatible = true;
                                                    if (cargoTypes.length > 0 && !cargoTypes[0].isEmpty()) {
                                                        for (String cargoType : cargoTypes) {
                                                            for (Cargo existingCargo : truck.getLoadedCargos()) {
                                                                if (existingCargo.getIncompatibleTypes().contains(cargoType)) {
                                                                    compatible = false;
                                                                    System.out.println("[ПРОВЕРКА] Тип " + cargoType +
                                                                            " несовместим с грузом " + existingCargo.getId() +
                                                                            " (тип: " + existingCargo.getType() + ")");
                                                                    break;
                                                                }
                                                            }
                                                            if (!compatible) break;
                                                        }
                                                    }

                                                    if (compatible) {
                                                        // Продолжаем обмен
                                                        continueExchange(sender);
                                                    } else {
                                                        // Отклоняем из-за несовместимости
                                                        ACLMessage reject = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                                                        reject.addReceiver(sender);
                                                        reject.setContent("REJECT_REASON:Несовместимые типы грузов");
                                                        send(reject);
                                                        System.out.println("[ОТКАЗ] Предложение отклонено из-за несовместимости типов грузов");
                                                        exchangeInProgress = false;
                                                        checkLoadingProgress();
                                                    }
                                                } else {
                                                    // Если не получили информацию о типах, продолжаем обмен (рискованно)
                                                    System.out.println("[ПРЕДУПРЕЖДЕНИЕ] Не получена информация о типах грузов, продолжаем обмен");
                                                    continueExchange(sender);
                                                }
                                            }
                                        });
                                    }
                                });
                            } else {
                                // Отклоняем предложение
                                ACLMessage reject = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                                reject.addReceiver(msg.getSender());
                                String rejectionReason = "";

                                if (Math.abs(truck.getLoadPercentage() - idealLoadPercentage) <= 10) {
                                    rejectionReason = "У нас уже оптимальная загрузка (" + truck.getLoadPercentage() + "%)";
                                } else if (direction.equals("NEED_MORE") && otherLoadPercentage <= idealLoadPercentage) {
                                    rejectionReason = "Требуется БОЛЬШЕ груза, но другой грузовик имеет " + otherLoadPercentage +
                                            "% загрузки (не больше идеальной: " + idealLoadPercentage + "%)";
                                } else if (direction.equals("NEED_LESS") && otherLoadPercentage >= idealLoadPercentage) {
                                    rejectionReason = "Требуется МЕНЬШЕ груза, но другой грузовик имеет " + otherLoadPercentage +
                                            "% загрузки (не меньше идеальной: " + idealLoadPercentage + "%)";
                                }

                                reject.setContent("REJECT_REASON:" + rejectionReason);
                                send(reject);
                                System.out.println("[ОТКАЗ] Предложение не подходит. Причина: " + rejectionReason);
                            }
                        }
                    }
                } else {
                    block();
                }
            }
        });
        // Добавляем к циклическому поведению для запросов о типах грузов
        // Обработчик запросов о грузах и несовместимостях
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                // Правильно вложенные операторы OR для трех условий
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.or(
                                MessageTemplate.MatchContent("GET_CARGO_TYPES"),
                                MessageTemplate.or(
                                        MessageTemplate.MatchContent("GET_INCOMPATIBILITIES"),
                                        MessageTemplate.MatchContent("GET_FREE_CAPACITY")
                                )
                        )
                );

                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.INFORM);

                    if (msg.getContent().equals("GET_CARGO_TYPES")) {
                        // Формируем список типов грузов
                        StringBuilder typesList = new StringBuilder("CARGO_TYPES:");
                        boolean first = true;
                        for (Cargo cargo : truck.getLoadedCargos()) {
                            if (!first) {
                                typesList.append(",");
                            }
                            typesList.append(cargo.getType());
                            first = false;
                        }
                        reply.setContent(typesList.toString());
                        System.out.println("[ОТВЕТ] Отправляем типы грузов: " + typesList);
                    }
                    else if (msg.getContent().equals("GET_INCOMPATIBILITIES")) {
                        // Формируем информацию о несовместимостях
                        StringBuilder incompInfo = new StringBuilder("INCOMPATIBILITIES:");
                        boolean hasIncompatibilities = false;

                        for (Cargo cargo : truck.getLoadedCargos()) {
                            if (!cargo.getIncompatibleTypes().isEmpty()) {
                                incompInfo.append(cargo.getType()).append(":");

                                boolean first = true;
                                for (String incompType : cargo.getIncompatibleTypes()) {
                                    if (!first) {
                                        incompInfo.append(",");
                                    }
                                    incompInfo.append(incompType);
                                    first = false;
                                }
                                incompInfo.append(";");
                                hasIncompatibilities = true;
                            }
                        }

                        String content = incompInfo.toString();
                        reply.setContent(content);
                        System.out.println("[ОТВЕТ] Отправляем информацию о несовместимостях: " + content);
                    }
                    else if (msg.getContent().equals("GET_FREE_CAPACITY")) {
                        float freeCapacity = truck.getCapacity() - truck.getCurrentLoad();
                        reply.setContent("FREE_CAPACITY:" + freeCapacity);
                        System.out.println("[ОТВЕТ] Отправляем информацию о свободной вместимости: " + freeCapacity);
                    }

                    send(reply);
                } else {
                    block();
                }
            }
        });
        // Обработчик для запросов типов грузов
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchContent("REQUEST_CARGO_TYPES"));
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    // Формируем список типов грузов
                    StringBuilder typesList = new StringBuilder("CARGO_TYPES:");
                    boolean first = true;
                    for (Cargo cargo : truck.getLoadedCargos()) {
                        if (!first) {
                            typesList.append(",");
                        }
                        typesList.append(cargo.getType());
                        first = false;
                    }

                    // Отправляем ответ с типами грузов
                    ACLMessage reply = msg.createReply();
                    reply.setPerformative(ACLMessage.INFORM);
                    reply.setContent(typesList.toString());
                    send(reply);
                    System.out.println("[DEBUG] Отправлены типы грузов: " + typesList.toString());
                } else {
                    block();
                }
            }
        });
        // Добавьте обработчик FAILURE сообщений
        // Обработчик FAILURE сообщений
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.FAILURE);
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    String content = msg.getContent();
                    if (content != null) {
                        if (content.startsWith("CARGO_INCOMPATIBLE:")) {
                            String[] parts = content.split(":");
                            if (parts.length > 1) {
                                System.out.println("[ОШИБКА] Груз " + parts[1] +
                                        " несовместим с грузами в грузовике " +
                                        msg.getSender().getLocalName());

                                // Получаем груз для восстановления
                                Cargo cargoToRestore = pendingTransfers.remove(msg.getSender());
                                if (cargoToRestore != null) {
                                    // Проверяем совместимость перед восстановлением
                                    if (truck.canAddCargo(cargoToRestore)) {
                                        truck.addCargo(cargoToRestore);
                                        System.out.println("[ВОССТАНОВЛЕНИЕ] Груз " + cargoToRestore.getId() +
                                                " возвращен обратно из-за несовместимости");
                                    } else {
                                        System.out.println("[ОШИБКА] Невозможно восстановить груз " + cargoToRestore.getId() +
                                                " (тип: " + cargoToRestore.getType() + ") - стал несовместим с текущими грузами " +
                                                truck.getLoadedCargos());

                                        // Отправляем груз менеджеру для обработки
                                        sendCargoToManager(cargoToRestore);
                                    }
                                }
                            }
                        } else if (content.startsWith("TRANSFER_FAILED:")) {
                            System.out.println("[ОШИБКА] Передача груза не удалась: " + content);

                            // Аналогичная проверка совместимости перед восстановлением
                            Cargo cargoToRestore = pendingTransfers.remove(msg.getSender());
                            if (cargoToRestore != null) {
                                if (truck.canAddCargo(cargoToRestore)) {
                                    truck.addCargo(cargoToRestore);
                                    System.out.println("[ВОССТАНОВЛЕНИЕ] Груз " + cargoToRestore.getId() +
                                            " возвращен обратно из-за ошибки передачи");
                                } else {
                                    System.out.println("[ОШИБКА] Невозможно восстановить груз " + cargoToRestore.getId() +
                                            " (тип: " + cargoToRestore.getType() + ") - стал несовместим с текущими грузами " +
                                            truck.getLoadedCargos());

                                    // Отправляем груз менеджеру для обработки
                                    sendCargoToManager(cargoToRestore);
                                }
                            }
                        }
                    }
                    exchangeInProgress = false;
                    checkLoadingProgress();
                } else {
                    block();
                }
            }
        });
        addBehaviour(new WakerBehaviour(this, 180000) { // 30 second timeout
            protected void onWake() {
                if (!readyStatusSent) {
                    System.out.println(getAID().getName() + " завершает работу по таймауту");
                    notifyManager();
                }
            }
        });

        // Объединенный обработчик для REPORT_STATUS сообщений
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate reportTemplate = MessageTemplate.and(
                        MessageTemplate.or(
                                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                                MessageTemplate.MatchPerformative(ACLMessage.INFORM)
                        ),
                        MessageTemplate.MatchContent("REPORT_STATUS")
                );

                ACLMessage reportMsg = myAgent.receive(reportTemplate);
                if (reportMsg != null) {
                    System.out.println(getAID().getName() + " received REPORT_STATUS request");

                    ACLMessage reply = reportMsg.createReply();
                    reply.setPerformative(ACLMessage.INFORM);
                    try {
                        reply.setContentObject(truck);
                        myAgent.send(reply);
                        System.out.println(getAID().getName() + " sent final report to manager");
                    } catch (IOException e) {
                        System.err.println("Error sending truck data: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    block(100);
                }
            }
        });
    }
    private void processCargo(Cargo receivedCargo, AID sender) {
        if (truck.canAddCargo(receivedCargo)) {
            truck.addCargo(receivedCargo);
            System.out.println("[УСПЕХ] Получен груз " + receivedCargo.getId() +
                    " (тип: " + receivedCargo.getType() + ")" +
                    " от " + sender.getLocalName() +
                    ". Новая загрузка: " + truck.getLoadPercentage() + "%");

            // Отправляем подтверждение получения
            ACLMessage confirmMsg = new ACLMessage(ACLMessage.CONFIRM);
            confirmMsg.addReceiver(sender);
            confirmMsg.setContent("CARGO_RECEIVED:" + receivedCargo.getId());
            send(confirmMsg);

            checkLoadingProgress();
        } else {
            // Улучшенное сообщение об ошибке
            System.out.println("[ОШИБКА] Не удалось добавить полученный груз " +
                    receivedCargo.getId() + " (тип: " + receivedCargo.getType() + ")" +
                    " от " + sender.getLocalName() +
                    " - несовместим с текущими грузами " +
                    truck.getLoadedCargos() +
                    " или превышена вместимость " + truck.getCapacity());

            // Отправляем сообщение об ошибке
            ACLMessage errorMsg = new ACLMessage(ACLMessage.FAILURE);
            errorMsg.addReceiver(sender);
            errorMsg.setContent("CARGO_INCOMPATIBLE:" + receivedCargo.getId());
            send(errorMsg);
        }
    }
    private Cargo findCargoToTransfer(float targetWeight) {
        System.out.println("Поиск груза для передачи. Целевой вес: " + targetWeight);
        List<Cargo> loadedCargos = truck.getLoadedCargos();
        System.out.println("Доступные грузы: " + loadedCargos.size() + " шт.");

        if (loadedCargos.isEmpty()) {
            System.out.println("Нет доступных грузов для передачи!");
            return null;
        }

        // Сначала ищем груз с точным весом
        for (Cargo cargo : loadedCargos) {
            if (Math.abs(cargo.getWeight() - targetWeight) < 1.0 &&
                    canRemoveCargoSafely(cargo) &&
                    !recentlyTransferredCargoIds.contains(cargo.getId())) {
                System.out.println("Найден идеальный груз: " + cargo.getId() + " весом " + cargo.getWeight());
                recentlyTransferredCargoIds.add(cargo.getId());
                return cargo;
            }
        }

        // Если точного соответствия нет, ищем груз поменьше целевого веса
        Cargo bestSmallerMatch = null;
        float maxWeight = 0;
        for (Cargo cargo : loadedCargos) {
            if (cargo.getWeight() < targetWeight &&
                    cargo.getWeight() > maxWeight &&
                    canRemoveCargoSafely(cargo) &&
                    !recentlyTransferredCargoIds.contains(cargo.getId())) {
                maxWeight = cargo.getWeight();
                bestSmallerMatch = cargo;
            }
        }

        // Если нашли подходящий меньший груз, возвращаем его
        if (bestSmallerMatch != null) {
            System.out.println("Найден подходящий меньший груз: " + bestSmallerMatch.getId() +
                    " весом " + bestSmallerMatch.getWeight());
            recentlyTransferredCargoIds.add(bestSmallerMatch.getId());
            return bestSmallerMatch;
        }

        // Иначе ищем груз с минимальной разницей
        Cargo bestMatch = null;
        float minDiff = Float.MAX_VALUE;
        for (Cargo cargo : loadedCargos) {
            float diff = Math.abs(cargo.getWeight() - targetWeight);
            if (diff < minDiff &&
                    canRemoveCargoSafely(cargo) &&
                    !recentlyTransferredCargoIds.contains(cargo.getId())) {
                minDiff = diff;
                bestMatch = cargo;
            }
        }

        if (bestMatch != null) {
            recentlyTransferredCargoIds.add(bestMatch.getId());
            globalTransferredCargoIds.add(bestMatch.getId());
        } else {
            System.out.println("Не найдено ни одного подходящего груза!");
        }

        return bestMatch;
    }
    // Метод для отправки груза менеджеру
    private void sendCargoToManager(Cargo cargo) {
        ACLMessage managerMsg = new ACLMessage(ACLMessage.INFORM);
        managerMsg.addReceiver(new AID("manager", AID.ISLOCALNAME));
        try {
            managerMsg.setContentObject(cargo);
            managerMsg.addUserDefinedParameter("action", "UNDELIVERABLE_CARGO");
            send(managerMsg);
            System.out.println("[ВОССТАНОВЛЕНИЕ] Груз " + cargo.getId() +
                    " передан менеджеру, так как не может быть восстановлен в грузовике " +
                    getAID().getLocalName());
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("[КРИТИЧЕСКАЯ ОШИБКА] Не удалось отправить груз менеджеру!");
        }
    }
    private boolean canRemoveCargoSafely(Cargo cargo) {
        // Проверяем, не является ли этот груз единственным препятствием для несовместимости других грузов
        List<Cargo> otherCargos = new ArrayList<>(truck.getLoadedCargos());
        otherCargos.remove(cargo);

        // 1. Создаем набор всех типов грузов, кроме удаляемого
        Set<String> remainingTypes = new HashSet<>();
        for (Cargo remainingCargo : otherCargos) {
            remainingTypes.add(remainingCargo.getType());
        }

        // 2. Проверяем, нет ли несовместимости между оставшимися грузами
        for (Cargo remainingCargo : otherCargos) {
            for (String incompatibleType : remainingCargo.getIncompatibleTypes()) {
                if (remainingTypes.contains(incompatibleType)) {
                    System.out.println("[DEBUG] Удаление груза " + cargo.getId() +
                            " создаст несовместимость между типами " +
                            remainingCargo.getType() + " и " + incompatibleType);
                    return false;
                }
            }
        }

        System.out.println("[DEBUG] Груз " + cargo.getId() + " можно безопасно удалить");
        return true;
    }

    private void requestCargo() {
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(new AID("manager", AID.ISLOCALNAME));
        request.setContent("REQUEST_CARGO");
        send(request);
    }

    private void processCargos(List<Cargo> availableCargos) {
        boolean cargoFound = false;
        processedCargoIds.clear();

        for (Cargo cargo : availableCargos) {
            if (processedCargoIds.contains(cargo.getId())) continue;
            if (truck.canAddCargo(cargo)) {
                cargoFound = true;

                ACLMessage claim = new ACLMessage(ACLMessage.REQUEST);
                claim.addReceiver(new AID("manager", AID.ISLOCALNAME));
                claim.setContent("CLAIM_CARGO:" + cargo.getId());
                send(claim);
                processedCargoIds.add(cargo.getId());
                return;
            }
            processedCargoIds.add(cargo.getId());
        }

        if (!cargoFound) {
            if (retryCount < MAX_RETRIES) {
                retryCount++;
                addBehaviour(new WakerBehaviour(this, 2000) {
                    protected void onWake() {
                        requestCargo();
                    }
                });
            } else {
                notifyManager();
            }
        }
    }

    private void checkLoadingProgress() {
        float loadPercentage = truck.getLoadPercentage();
        float idealLoad = truck.getCapacity() * (idealLoadPercentage / 100);
        float currentLoad = truck.getCurrentLoad();
        float difference = Math.abs(loadPercentage - idealLoadPercentage);

        System.out.println("[DEBUG] " + getAID().getName() +
                " проверка загрузки: " + loadPercentage +
                "%, идеальная: " + idealLoadPercentage +
                "%, разница: " + difference +
                ", READY статус: " + readyStatusSent +
                ", попытки обмена: " + exchangeAttempts + "/" + MAX_EXCHANGE_ATTEMPTS);

        if (exchangeInProgress) {
            System.out.println("[DEBUG] Обмен в процессе, ждем завершения...");
            return; // Ждем ответа от других грузовиков
        }

        // Если загрузка уже идеальная (в пределах 10%) и статус не отправлен, отправляем
        if (difference <= 10) {
            System.out.println("[DEBUG] Загрузка в допустимых пределах (" + loadPercentage +
                    "%), завершаем.");
            notifyManager();
            return;
        }

        // Если статус уже отправлен, ничего не делаем
        if (readyStatusSent) {
            System.out.println("[DEBUG] Уже отправлен READY статус, пропускаем обмен.");
            return;
        }

        // Проверка на превышение времени обмена
        // In checkLoadingProgress method
        // Only check time limit if we've started exchanges
        if (exchangeStartTime > 0 && System.currentTimeMillis() - exchangeStartTime > MAX_EXCHANGE_TIME) {
            System.out.println("[ЗАВЕРШЕНИЕ] Превышено время обмена, завершаем с текущей загрузкой: "
                    + truck.getLoadPercentage() + "%");
            exchangeStartTime = -1; // Reset the timer
            notifyManager();
            return;
        }

        // Если еще есть попытки обмена, пробуем обмен
        if (exchangeAttempts < MAX_EXCHANGE_ATTEMPTS) {
            if (loadPercentage > idealLoadPercentage + 10) {
                // Задержка для перегруженных грузовиков, чтобы недогруженные начали обмен первыми
                addBehaviour(new WakerBehaviour(this, 1000) {
                    protected void onWake() {
                        if (!exchangeInProgress && !readyStatusSent) {
                            System.out.println("[DEBUG] Грузовик перегружен на " + (loadPercentage - idealLoadPercentage) +
                                    "%, инициируем обмен после задержки.");
                            requestExchange();
                        }
                    }
                });
                return;
            }
            else if (loadPercentage < idealLoadPercentage - 10) {
                // Недогруженные грузовики инициируют обмен немедленно
                System.out.println("[DEBUG] Грузовик недогружен на " + (idealLoadPercentage - loadPercentage) +
                        "%, инициируем обмен.");
                requestExchange();
                return;
            }
        } else {
            System.out.println("[ЗАВЕРШЕНИЕ] Достигнут лимит попыток обмена, завершаем с текущей загрузкой: "
                    + truck.getLoadPercentage() + "%");
        }

        // Если попытки обмена исчерпаны или разница небольшая, отправляем READY
        System.out.println("[DEBUG] Завершаем попытки обмена и отправляем READY статус.");
        notifyManager();
    }

    private void requestExchange() {
        float loadPercentage = truck.getLoadPercentage();
        if (Math.abs(loadPercentage - idealLoadPercentage) <= 10) {
            System.out.println("[ОБМЕН] " + getAID().getName() + " имеет оптимальную загрузку (" +
                    loadPercentage + "%), обмен не требуется.");
            notifyManager(); // Отправляем READY статус, так как загрузка оптимальна
            return;
        }
        if (readyStatusSent) {
            System.out.println("[ОБМЕН] " + getAID().getName() + " не может инициировать обмен - уже отправил READY статус.");
            return;
        }
        if (exchangeAttempts >= MAX_EXCHANGE_ATTEMPTS) {
            System.out.println("[ОБМЕН] " + getAID().getName() + " достиг лимита попыток обмена.");
            notifyManager();
            return;
        }
        if (exchangeInProgress) {
            System.out.println("[ОБМЕН] Обмен уже идет, ждем ответа...");
            return;
        }

        // Проверяем, какие грузовики еще активны (не в состоянии READY)
        List<AID> activeOtherTrucks = new ArrayList<>();
        for (AID truckAID : otherTruckAIDs) {
            if (!truckReadyStatus.containsKey(truckAID) || !truckReadyStatus.get(truckAID)) {
                activeOtherTrucks.add(truckAID);
            }
        }

        // Вычисляем отклонение от идеальной загрузки (позитивное значение = перегрузка)
        float loadDeviation = loadPercentage - idealLoadPercentage;

        // ---- Распределение перегруженных грузовиков по интервалам для сортировки ----
        // Определяем интервал загрузки для этого грузовика
        int loadBucket;
        if (loadDeviation > 40) loadBucket = 0;      // Экстремальная перегрузка (>40%)
        else if (loadDeviation > 30) loadBucket = 1; // Очень сильная перегрузка (30-40%)
        else if (loadDeviation > 20) loadBucket = 2; // Сильная перегрузка (20-30%)
        else if (loadDeviation > 10) loadBucket = 3; // Умеренная перегрузка (10-20%)
        else if (loadDeviation > 0) loadBucket = 4;  // Небольшая перегрузка (0-10%)
        else if (loadDeviation > -10) loadBucket = 5; // Небольшая недогрузка (0-10%)
        else if (loadDeviation > -20) loadBucket = 6; // Умеренная недогрузка (10-20%)
        else if (loadDeviation > -30) loadBucket = 7; // Сильная недогрузка (20-30%)
        else loadBucket = 8;                          // Очень сильная недогрузка (>30%)

        // Получаем ID грузовика (чтобы разрешить коллизии грузовиков в одном интервале)
        int truckId = truck.getId();

        // Базовое время задержки для каждого интервала (в миллисекундах)
        int[] baseDelays = {
                0,     // Экстремальная перегрузка: немедленно
                1000,  // Очень сильная перегрузка: 1 секунда
                2000,  // Сильная перегрузка: 2 секунды
                4000,  // Умеренная перегрузка: 4 секунды
                6000,  // Небольшая перегрузка: 6 секунд
                8000,  // Небольшая недогрузка: 8 секунд
                10000, // Умеренная недогрузка: 10 секунд
                12000, // Сильная недогрузка: 12 секунд
                15000  // Очень сильная недогрузка: 15 секунд
        };

        // Базовая задержка зависит от интервала загрузки
        int baseDelay = baseDelays[loadBucket];

        // Добавляем небольшую задержку, зависящую от ID грузовика
        // Предполагаем, что ID однозначные (1-9), поэтому умножаем на 100мс
        // Это разрешает коллизии между грузовиками в одном интервале загрузки
        int idDelay = truckId * 100;

        // Финальная задержка: базовая (по загрузке) + компонента по ID
        int delay = baseDelay + idDelay;

        // Логируем информацию о планировании обмена
        System.out.println("[ОБМЕН] Грузовик " + getAID().getName() +
                " запланировал обмен через " + (delay/1000.0) + " секунд (загрузка: " +
                loadPercentage + "%, отклонение: " + loadDeviation +
                "%, интервал: " + loadBucket + ", ID: " + truckId + ")");

        addBehaviour(new WakerBehaviour(this, delay) {
            protected void onWake() {
                // Проверяем, не изменилась ли ситуация за время ожидания
                if (readyStatusSent || exchangeInProgress) {
                    return;
                }

                // Расчет текущих параметров обмена (внутри таймера, чтобы данные были актуальными)
                float currentLoadPercentage = truck.getLoadPercentage();
                float idealLoad = truck.getCapacity() * (idealLoadPercentage / 100);
                float loadDiff = Math.abs(idealLoad - truck.getCurrentLoad());

                System.out.println("[DEBUG] idealLoadPercentage: " + idealLoadPercentage);
                System.out.println("[DEBUG] Идеальная загрузка: " + idealLoad);
                System.out.println("[DEBUG] Текущая загрузка: " + truck.getCurrentLoad());

                // Перепроверка необходимости обмена
                if (Math.abs(currentLoadPercentage - idealLoadPercentage) <= 10) {
                    System.out.println("[ОБМЕН] " + getAID().getName() + " теперь имеет оптимальную загрузку (" +
                            currentLoadPercentage + "%), обмен не требуется.");
                    notifyManager();
                    return;
                }

                // Расчёт параметров обмена
                desiredExchangeWeight = (loadDiff > truck.getCapacity() * 0.3f) ? loadDiff / 2 : loadDiff;
                if (desiredExchangeWeight < 50) { // Минимальный порог для обмена
                    System.out.println("[ОБМЕН] Слишком малый вес для обмена (" + desiredExchangeWeight + "), пропускаем.");
                    notifyManager();
                    return;
                }

                direction = (truck.getCurrentLoad() > idealLoad) ? "NEED_LESS" : "NEED_MORE";

                // Начало обмена
                exchangeInProgress = true;
                exchangeAttempts++;
                exchangeStartTime = System.currentTimeMillis();

                System.out.println("[ОБМЕН] Направление: " + direction +
                        " | Идеальная загрузка: " + idealLoad +
                        " | Текущая: " + truck.getCurrentLoad() +
                        " | Нужный вес обмена: " + desiredExchangeWeight);

                // Установка таймаута
                addBehaviour(new WakerBehaviour(myAgent, 40000) { // 40 seconds timeout
                    protected void onWake() {
                        if (exchangeInProgress) {
                            System.out.println("[ОБМЕН] Таймаут обмена для " + getAID().getName());
                            exchangeInProgress = false;
                            checkLoadingProgress();
                        }
                    }
                });

                // Отправка запросов только активным грузовикам
                ACLMessage request = new ACLMessage(ACLMessage.CFP);
                int receiverCount = 0;

                // Отправляем запросы только активным грузовикам
                for (AID truckAID : activeOtherTrucks) {
                    request.addReceiver(truckAID);
                    receiverCount++;
                    System.out.println("[ОБМЕН] Запрос отправлен активному грузовику: " + truckAID.getLocalName());
                }

                if (receiverCount == 0) {
                    System.out.println("[ОБМЕН] Нет доступных активных грузовиков для запроса обмена.");
                    exchangeInProgress = false;
                    checkLoadingProgress();
                    return;
                }

                // Добавляем текущую загрузку в запрос для контекста
                request.setContent("EXCHANGE_REQUEST:" + desiredExchangeWeight + ":" + direction + ":" +
                        truck.getLoadPercentage());
                send(request);
                System.out.println("[ОБМЕН] Запрос отправлен " + receiverCount + " активным грузовикам");
            }
        });
    }
    private void notifyManager() {
        if (!readyStatusSent) {
            readyStatusSent = true;
            System.out.println(getAID().getName() + " отправляет TRUCK_READY в менеджер.");

            ACLMessage ready = new ACLMessage(ACLMessage.REQUEST);
            ready.addReceiver(new AID("manager", AID.ISLOCALNAME));
            ready.setContent("TRUCK_READY");
            send(ready);

            ACLMessage statusUpdate = new ACLMessage(ACLMessage.INFORM);
            for (AID truckAID : otherTruckAIDs) {
                statusUpdate.addReceiver(truckAID);
            }
            statusUpdate.setContent("TRUCK_READY_STATUS:" + getAID().getLocalName());
            send(statusUpdate);
        } else {
            System.out.println(getAID().getName() + " уже отправил TRUCK_READY, повторять не нужно.");
        }
    }
    private boolean hasTransferableCargo() {
        for (Cargo cargo : truck.getLoadedCargos()) {
            if (canRemoveCargoSafely(cargo)) {
                return true;
            }
        }
        return false;
    }
    private void continueExchange(AID receiver) {
        // Отправляем принятие предложения
        ACLMessage accept = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
        accept.addReceiver(receiver);
        accept.setContent("ACCEPT_EXCHANGE");
        send(accept);
        System.out.println("[DEBUG] Предложение принято, отправлен ACCEPT_PROPOSAL");

        // Определяем действие в зависимости от направления обмена
        if (direction.equals("NEED_LESS")) {
            // Если мы хотим уменьшить груз, то отправляем наш груз
            sendCargoToReceiver(receiver);
        } else if (direction.equals("NEED_MORE")) {
            // Если мы хотим увеличить груз, то ждем пока другой грузовик отправит нам груз
            // Увеличиваем таймаут, так как теперь мы ждем, пока другой грузовик найдет и отправит груз
            System.out.println("[ОБМЕН] Ожидаем получения груза от " + receiver.getLocalName());

            // Таймер для завершения ожидания, если груз не придет
            addBehaviour(new WakerBehaviour(this, 30000) { // 30 секунд на ожидание
                protected void onWake() {
                    if (exchangeInProgress) {
                        System.out.println("[ОБМЕН] Истекло время ожидания груза от " + receiver.getLocalName());
                        exchangeInProgress = false;
                        checkLoadingProgress();
                    }
                }
            });
        } else {
            System.out.println("[ОШИБКА] Неизвестное направление обмена: " + direction);
            exchangeInProgress = false;
            checkLoadingProgress();
        }
    }
    private void sendCargoToReceiver(AID receiver) {
        // Находим груз для передачи
        Cargo cargoToTransfer = findCargoToTransfer(desiredExchangeWeight);
        if (cargoToTransfer != null) {
            boolean compatible = checkCompatibility(cargoToTransfer, receiver);

            if (!compatible) {
                System.out.println("[ОТКАЗ] Обмен отклонен: груз " + cargoToTransfer.getId() +
                        " (" + cargoToTransfer.getType() + ")" +
                        " несовместим с грузами в грузовике " + receiver.getLocalName());
                exchangeInProgress = false;
                checkLoadingProgress();
                return;
            }

            // Проверяем, улучшит ли обмен нашу ситуацию
            float currentDifference = Math.abs(truck.getLoadPercentage() - idealLoadPercentage);
            float newLoad = truck.getCurrentLoad() - cargoToTransfer.getWeight();
            float newPercentage = (newLoad / truck.getCapacity()) * 100;
            float newDifference = Math.abs(newPercentage - idealLoadPercentage);

            if (newDifference >= currentDifference) {
                System.out.println("[ОТКАЗ] Обмен отклонен: не улучшает загрузку (" +
                        newPercentage + "% vs текущие " + truck.getLoadPercentage() + "%)");
                exchangeInProgress = false;
                checkLoadingProgress();
                return;
            }

            // Проверяем, можно ли безопасно удалить груз
            if (canRemoveCargoSafely(cargoToTransfer)) {
                ACLMessage transferMsg = new ACLMessage(ACLMessage.INFORM);
                transferMsg.addReceiver(receiver);
                try {
                    // Сохраняем копию груза перед удалением
                    pendingTransfers.put(receiver, cargoToTransfer);

                    truck.removeCargo(cargoToTransfer);  // Удаляем груз
                    transferMsg.setContentObject(cargoToTransfer);
                    send(transferMsg);
                    System.out.println("[УСПЕХ] Передан груз " + cargoToTransfer.getId() +
                            " -> " + receiver.getLocalName());

                    // Установим таймер для автоматического восстановления груза
                    addBehaviour(new WakerBehaviour(this, 10000) { // 10 секунд на подтверждение
                        protected void onWake() {
                            if (pendingTransfers.containsKey(receiver)) {
                                Cargo cargoToRestore = pendingTransfers.remove(receiver);
                                // Проверка совместимости перед восстановлением
                                if (cargoToRestore != null) {
                                    if (truck.canAddCargo(cargoToRestore)) {
                                        truck.addCargo(cargoToRestore);
                                        System.out.println("[ВОССТАНОВЛЕНИЕ] Груз " + cargoToRestore.getId() +
                                                " автоматически возвращен - не получено подтверждение");
                                    } else {
                                        System.out.println("[ОШИБКА] Невозможно восстановить груз " + cargoToRestore.getId() +
                                                " (тип: " + cargoToRestore.getType() + ") - стал несовместим с текущими грузами " +
                                                truck.getLoadedCargos());
                                        sendCargoToManager(cargoToRestore);
                                    }
                                }
                                exchangeInProgress = false;
                                checkLoadingProgress();
                            }
                        }
                    });

                } catch (IOException e) {
                    // В случае ошибки возвращаем груз обратно
                    truck.addCargo(cargoToTransfer);
                    pendingTransfers.remove(receiver); // Убираем из ожидающих
                    System.out.println("[ОШИБКА] Не удалось сериализовать груз: " + e.getMessage());
                    e.printStackTrace();
                    exchangeInProgress = false;
                    checkLoadingProgress();
                }

            } else {
                System.out.println("[ОШИБКА] Груз " + cargoToTransfer.getId() +
                        " нельзя безопасно удалить - нарушится совместимость");
                // Отменяем обмен
                exchangeInProgress = false;
                checkLoadingProgress();
            }
        } else {
            System.out.println("[ОШИБКА] Не удалось найти подходящий груз для передачи");
            exchangeInProgress = false;
            checkLoadingProgress();
        }
    }
    // Улучшенная проверка совместимости
    // Улучшенная проверка совместимости
    private boolean checkCompatibility(Cargo cargo, AID receiverAID) {

        System.out.println("[ПРОВЕРКА] Начинаем проверку совместимости груза " + cargo.getId() +
                " (тип: " + cargo.getType() + ") с грузовиком " + receiverAID.getLocalName());

        // Проверяем наличие несовместимых типов для передаваемого груза
        if (cargo.getIncompatibleTypes().isEmpty()) {
            System.out.println("[ПРОВЕРКА] Груз " + cargo.getId() + " (" + cargo.getType() +
                    ") не имеет несовместимых типов, должен быть совместим со всеми грузами");
            return true;
        }

        // Запрос информации о текущих грузах в грузовике-получателе
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(receiverAID);
        request.setContent("GET_CARGO_TYPES");
        send(request);

        MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                MessageTemplate.MatchSender(receiverAID)
        );

        ACLMessage reply = blockingReceive(mt, 10000); // ждем ответа не более 5 секунд

        if (reply != null && reply.getContent() != null &&
                reply.getContent().startsWith("CARGO_TYPES:")) {

            String typesStr = reply.getContent().substring("CARGO_TYPES:".length());

            // Если нет типов в грузовике-получателе, значит совместимость обеспечена
            if (typesStr.isEmpty()) {
                System.out.println("[ПРОВЕРКА] Грузовик " + receiverAID.getLocalName() + " пуст, совместимость обеспечена");
                return true;
            }

            String[] types = typesStr.split(",");
            System.out.println("[ПРОВЕРКА] Типы в грузовике " + receiverAID.getLocalName() + ": " + Arrays.toString(types));
            System.out.println("[ПРОВЕРКА] Несовместимые типы для груза " + cargo.getId() + ": " + cargo.getIncompatibleTypes());

            // 1. Проверяем, есть ли в принимающем грузовике типы, которые несовместимы с передаваемым грузом
            for (String type : types) {
                if (type.isEmpty()) continue;

                if (cargo.getIncompatibleTypes().contains(type)) {
                    System.out.println("[ПРОВЕРКА] Груз " + cargo.getId() +
                            " несовместим с типом " + type + " в грузовике " + receiverAID.getLocalName());
                    return false;
                }
            }

            // 2. Проверяем, нет ли среди грузов принимающего грузовика таких, у которых есть несовместимость с нашим грузом
            // Запрашиваем дополнительную информацию о несовместимостях
            ACLMessage incompRequest = new ACLMessage(ACLMessage.REQUEST);
            incompRequest.addReceiver(receiverAID);
            incompRequest.setContent("GET_INCOMPATIBILITIES");
            send(incompRequest);

            MessageTemplate mtIncomp = MessageTemplate.and(
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                    MessageTemplate.MatchSender(receiverAID)
            );

            ACLMessage incompReply = blockingReceive(mtIncomp, 5000);

            if (incompReply != null && incompReply.getContent() != null) {
                String content = incompReply.getContent();
                System.out.println("[ПРОВЕРКА] Получена информация о несовместимостях: " + content);

                if (content.startsWith("INCOMPATIBILITIES:")) {
                    String incompStr = content.substring("INCOMPATIBILITIES:".length());

                    // Если нет несовместимостей, разрешаем обмен
                    if (incompStr.isEmpty()) {
                        System.out.println("[ПРОВЕРКА] Нет информации о несовместимостях в грузовике " +
                                receiverAID.getLocalName() + ", считаем груз совместимым");
                        return true;
                    }

                    String[] incompPairs = incompStr.split(";");

                    for (String pair : incompPairs) {
                        if (pair.isEmpty()) continue;

                        String[] parts = pair.split(":");
                        if (parts.length == 2) {
                            String cargoType = parts[0];
                            String[] incompTypes = parts[1].split(",");

                            for (String incompType : incompTypes) {
                                if (incompType.equals(cargo.getType())) {
                                    System.out.println("[ПРОВЕРКА] Тип " + cargo.getType() +
                                            " несовместим с типом " + cargoType + " в грузовике " + receiverAID.getLocalName());
                                    return false;
                                }
                            }
                        }
                    }
                }
            } else {
                System.out.println("[ПРОВЕРКА] Не получен ответ о несовместимостях, но типы грузов совместимы");
                return true;
            }

            System.out.println("[ПРОВЕРКА] Груз " + cargo.getId() + " (" + cargo.getType() +
                    ") совместим с грузами в грузовике " + receiverAID.getLocalName());
            return true;
        }

        // Если не получили ответ о типах грузов
        System.out.println("[ПРОВЕРКА] Не получен ответ о типах грузов от " + receiverAID.getLocalName() +
                ", считаем несовместимым для безопасности");
        return false;
    }

    // Добавьте метод для проверки вместимости грузовика-получателя
    private boolean checkCapacity(float cargoWeight, AID receiverAID) {
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.addReceiver(receiverAID);
        request.setContent("GET_FREE_CAPACITY");
        send(request);

        MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                MessageTemplate.MatchSender(receiverAID)
        );

        ACLMessage reply = blockingReceive(mt, 5000);

        if (reply != null && reply.getContent() != null &&
                reply.getContent().startsWith("FREE_CAPACITY:")) {

            float freeCapacity = Float.parseFloat(
                    reply.getContent().substring("FREE_CAPACITY:".length()));

            return cargoWeight <= freeCapacity;
        }

        // Если не получили ответ, считаем что нет места
        return false;
    }
}