package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.lang.acl.UnreadableException;
import model.Cargo;
import model.Truck;
import util.LogHelper;
import model.CargoPool;


import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

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
    private List<AID> otherTruckAIDs;
    private Map<AID, Boolean> truckReadyStatus = new HashMap<>();
    private Map<AID, Cargo> pendingTransfers = new HashMap<>();
    private static Set<Integer> recentlyTransferredCargoIds = Collections.synchronizedSet(new HashSet<>());    private long exchangeStartTime = -1;
    private static final long MAX_EXCHANGE_TIME = 60000;
    private static Set<Integer> globalTransferredCargoIds = new HashSet<>();
    private Set<Integer> impossibleCargoIds = new HashSet<>();


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
        }

        LogHelper.info(truck.getId(), "Агент запущен. Вместимость: " + truck.getCapacity());


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
                            LogHelper.info(truck.getId(), "Грузовик " + truckName + " теперь в статусе READY");
                        }
                    }
                    else if (content != null && content.startsWith("CARGO_TYPES:")) {
                        // Обрабатываем тут сообщения о типах грузов
                        LogHelper.info(truck.getId(), "Получены типы грузов: " + content);
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
                                LogHelper.debug(truck.getId(), "Получен объект Truck");
                            } else {
                                LogHelper.debug(truck.getId(), "Получен объект с классом: " + contentObj.getClass().getName());
                            }
                        } catch (UnreadableException e) {
                            LogHelper.debug(truck.getId(), "Получено неизвестное текстовое сообщение INFORM: " + content);
                        }
                    }
                } else {
                    block();
                }
            }
        });
        // Behavior to handle cargo response REFUSE
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
                                LogHelper.exchange(truck.getId(),"Груз " + cargoId +
                                        " успешно получен");

                                // Удаляем груз из списка ожидающих подтверждения
                                pendingTransfers.remove(msg.getSender());
                            }
                        } else {
                            // Это подтверждение от LoadingManagerAgent с объектом Cargo
                            try {
                                Cargo receivedCargo = (Cargo) msg.getContentObject();
                                truck.addCargo(receivedCargo);
                                LogHelper.info(truck.getId(), " загрузил груз " + receivedCargo.getId());

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
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    AID sender = msg.getSender();
                    String content = msg.getContent();
                    LogHelper.info(truck.getId(), " получил запрос обмена: " + content +
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
                                /*else if (direction.equals("NEED_MORE") &&
                                        truck.getLoadPercentage() > idealLoadPercentage) {
                                    // Мы перегружены, а другой грузовик хочет больше - хорошее совпадение
                                    if (!truck.getLoadedCargos().isEmpty() &&
                                            hasTransferableCargo()) {
                                        canHelp = true;
                                    } else {
                                        reason = "нет грузов для передачи или их нельзя безопасно удалить";
                                    }
                                }*/
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
                                    LogHelper.exchange(truck.getId(), " может помочь с обменом, отправка предложения: " + truck.getLoadPercentage());
                                    myAgent.send(reply);
                                } else {
                                    // Добавлен явный отказ вместо просто логирования
                                    ACLMessage decline = new ACLMessage(ACLMessage.REFUSE);
                                    decline.addReceiver(msg.getSender());
                                    decline.setContent("EXCHANGE_REFUSED:" + reason);
                                    myAgent.send(decline);
                                    LogHelper.failure(truck.getId()," не может помочь с обменом. Причина: " + reason);
                                }
                            } catch (NumberFormatException e) {
                                LogHelper.error(truck.getId(),"Неверный формат данных в сообщении обмена: " + content);
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
                        LogHelper.failure(truck.getId(),"Получен отказ от " + msg.getSender().getLocalName() +
                                ": " + reason);
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
                    LogHelper.info(truck.getId(), " получил предложение: " + content);

                    if (content.startsWith("EXCHANGE_POSSIBLE")) {
                        // Убираем проверку exchangeInProgress, обрабатываем все предложения
                        String[] parts = content.split(":");
                        if (parts.length >= 2) {
                            float otherLoadPercentage = Float.parseFloat(parts[1]);
                            LogHelper.debug(truck.getId(),"Направление обмена: " + direction +
                                    ", загрузка другого грузовика: " + otherLoadPercentage);
                            // Проверяем, подходит ли предложение
                            boolean acceptProposal = false;

                            // Если наша загрузка уже оптимальная, не принимаем предложения
                            if (Math.abs(truck.getLoadPercentage() - idealLoadPercentage) <= 10) {
                                acceptProposal = false;
                                LogHelper.failure(truck.getId(),"Предложение отклонено - у нас уже оптимальная загрузка.");
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
                                                                    LogHelper.failure(truck.getId(),"Тип " + cargoType +
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
                                                        LogHelper.failure(truck.getId()," Предложение отклонено из-за несовместимости типов грузов");
                                                        exchangeInProgress = false;
                                                        checkLoadingProgress();
                                                    }
                                                } else {
                                                    // Если не получили информацию о типах, продолжаем обмен (рискованно)
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
                                LogHelper.failure(truck.getId()," Предложение не подходит. Причина: " + rejectionReason);
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
                        LogHelper.info(truck.getId()," Отправляем типы грузов: " + typesList);
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
                        LogHelper.info(truck.getId()," Отправляем информацию о несовместимостях: " + content);
                    }
                    else if (msg.getContent().equals("GET_FREE_CAPACITY")) {
                        float freeCapacity = truck.getCapacity() - truck.getCurrentLoad();
                        reply.setContent("FREE_CAPACITY:" + freeCapacity);
                        LogHelper.info(truck.getId()," Отправляем информацию о свободной вместимости: " + freeCapacity);
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
                    LogHelper.debug(truck.getId()," Отправлены типы грузов: " + typesList.toString());
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
                                LogHelper.failure(truck.getId()," Груз " + parts[1] +
                                        " несовместим с грузами в грузовике ");

                                // Получаем груз для восстановления
                                Cargo cargoToRestore = pendingTransfers.remove(msg.getSender());
                                if (cargoToRestore != null) {
                                    // Проверяем совместимость перед восстановлением
                                    if (truck.canAddCargo(cargoToRestore)) {
                                        truck.addCargo(cargoToRestore);
                                        LogHelper.warning(truck.getId()," Груз " + cargoToRestore.getId() +
                                                " возвращен обратно из-за несовместимости");
                                    } else {
                                        LogHelper.failure(truck.getId()," Невозможно восстановить груз " + cargoToRestore.getId() +
                                                " (тип: " + cargoToRestore.getType() + ") - стал несовместим с текущими грузами " +
                                                truck.getLoadedCargos());

                                        // Отправляем груз менеджеру для обработки
                                        returnCargoToPool(cargoToRestore);
                                    }
                                }
                            }
                        } else if (content.startsWith("TRANSFER_FAILED:")) {
                            LogHelper.failure(truck.getId()," Передача груза не удалась: " + content);

                            // Аналогичная проверка совместимости перед восстановлением
                            Cargo cargoToRestore = pendingTransfers.remove(msg.getSender());
                            if (cargoToRestore != null) {
                                if (truck.canAddCargo(cargoToRestore)) {
                                    truck.addCargo(cargoToRestore);
                                    LogHelper.warning(truck.getId()," Груз " + cargoToRestore.getId() +
                                            " возвращен обратно из-за ошибки передачи");
                                } else {
                                    LogHelper.failure(truck.getId()," Невозможно восстановить груз " + cargoToRestore.getId() +
                                            " (тип: " + cargoToRestore.getType() + ") - стал несовместим с текущими грузами " +
                                            truck.getLoadedCargos());

                                    // Отправляем груз менеджеру для обработки
                                    returnCargoToPool(cargoToRestore);
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
                    LogHelper.info(truck.getId(), "Завершение работы по таймауту");
                    notifyManager();
                }
            }
        });

        // Объединенный обработчик для REPORT_STATUS сообщений
        // В метод setup() класса TruckAgent добавьте следующий код
// Заменяет предыдущий обработчик REPORT_STATUS
        // Around line 583 (the REPORT_STATUS handler)
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate reportTemplate = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchContent("REPORT_STATUS")
                );

                ACLMessage reportMsg = myAgent.receive(reportTemplate);
                if (reportMsg != null) {
                    // Add small random delay to prevent network congestion
                    try {
                        Thread.sleep(300 + (truck.getId() * 100));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    LogHelper.info(truck.getId(), "Получен запрос REPORT_STATUS от " + reportMsg.getSender().getLocalName());

                    // Create a reply directly to the message
                    ACLMessage reply = reportMsg.createReply();
                    reply.setPerformative(ACLMessage.INFORM);

                    try {
                        // Send string representation
                        String truckData = serializeTruckToString();
                        reply.setContent(truckData);
                        myAgent.send(reply);

                        LogHelper.success(truck.getId(), "Отправлен финальный отчет менеджеру (текстовый формат)");
                    } catch (Exception e) {
                        LogHelper.failure(truck.getId(), "Ошибка при отправке данных грузовика: " + e.getMessage());
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
            LogHelper.success(truck.getId(), "Получен груз " + receivedCargo.getId() +
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
            LogHelper.failure(truck.getId(), "Не удалось добавить полученный груз " +
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
        LogHelper.debug(truck.getId(), "Поиск груза для передачи. Целевой вес: " + targetWeight);
        List<Cargo> loadedCargos = truck.getLoadedCargos();
        LogHelper.debug(truck.getId(), "Доступные грузы: " + loadedCargos.size() + " шт.");
        if (loadedCargos.isEmpty()) {
            LogHelper.warning(truck.getId(), "Нет доступных грузов для передачи!");
            return null;
        }

        // Сначала ищем груз с точным весом
        for (Cargo cargo : loadedCargos) {
            if (Math.abs(cargo.getWeight() - targetWeight) < 1.0 &&
                    canRemoveCargoSafely(cargo) &&
                    !recentlyTransferredCargoIds.contains(cargo.getId())) {
                LogHelper.debug(truck.getId(), "Найден идеальный груз: " + cargo.getId() + " весом " + cargo.getWeight());
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
            LogHelper.debug(truck.getId(), "Найден подходящий меньший груз: " + bestSmallerMatch.getId() +
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
            LogHelper.warning(truck.getId(), "Не найдено ни одного подходящего груза!");        }

        return bestMatch;
    }

    private void returnCargoToPool(Cargo cargo) {
        CargoPool cargoPool = CargoPool.getInstance();
        cargoPool.addCargo(cargo); // Добавляем груз обратно в пул
        LogHelper.info(truck.getId(), "Груз " + cargo.getId() +
                " возвращен в пул, так как не может быть восстановлен");
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
                    LogHelper.debug(truck.getId(), "Удаление груза " + cargo.getId() +
                            " создаст несовместимость между типами " +
                            remainingCargo.getType() + " и " + incompatibleType);
                    return false;
                }
            }
        }

        LogHelper.debug(truck.getId(), "Груз " + cargo.getId() + " можно безопасно удалить");
        return true;
    }

    private void requestCargo() {
        // Получаем доступные грузы напрямую из пула
        CargoPool cargoPool = CargoPool.getInstance();
        List<Cargo> availableCargos = cargoPool.getAvailableCargos();

        if (availableCargos.isEmpty()) {
            LogHelper.info(truck.getId(), "Нет доступных грузов в пуле, завершаем загрузку");
            loadingComplete = true;
            checkLoadingProgress();
            return;
        }

        // Ищем подходящий груз
        processCargos(availableCargos);
    }


    private void processCargos(List<Cargo> availableCargos) {

        boolean cargoFound = false;
        processedCargoIds.clear();
        CargoPool cargoPool = CargoPool.getInstance();

        for (Cargo cargo : availableCargos) {
            if (processedCargoIds.contains(cargo.getId())) continue;

            if (truck.canAddCargo(cargo)) {
                cargoFound = true;
                int cargoId = cargo.getId();

                // Пытаемся зарезервировать груз
                if (cargoPool.reserveCargo(cargoId)) {
                    // Если успешно зарезервировали, берем его из пула
                    Cargo reservedCargo = cargoPool.takeCargo(cargoId);

                    if (reservedCargo != null) {
                        // Добавляем груз в грузовик
                        truck.addCargo(reservedCargo);
                        LogHelper.success(truck.getId(), "Загрузил груз " + reservedCargo.getId() +
                                " (тип: " + reservedCargo.getType() + ")");

                        float loadPercentage = truck.getLoadPercentage();
                        if (Math.abs(loadPercentage - idealLoadPercentage) <= 10) {
                            notifyManager();
                        } else {
                            // Просим следующий груз
                            addBehaviour(new WakerBehaviour(this, 500) {
                                protected void onWake() {
                                    requestCargo();
                                }
                            });
                        }
                        return;
                    } else {
                        // Если не смогли взять груз (кто-то опередил), отменяем резервирование
                        cargoPool.cancelReservation(cargoId);
                    }
                }
            } else {
                LogHelper.warning(truck.getId(), "Не могу загрузить груз " + cargo.getId() +
                        " (тип: " + cargo.getType() + ") - несовместим с текущими грузами или превышена вместимость");
                impossibleCargoIds.add(cargo.getId());
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

        LogHelper.debug(truck.getId(), "Проверка загрузки: " + loadPercentage +
                "%, идеальная: " + idealLoadPercentage +
                "%, разница: " + difference +
                ", READY статус: " + readyStatusSent +
                ", попытки обмена: " + exchangeAttempts + "/" + MAX_EXCHANGE_ATTEMPTS);
        if (exchangeInProgress) {
            LogHelper.debug(truck.getId(), "Обмен в процессе, ждем завершения...");
            return; // Ждем ответа от других грузовиков
        }

        // Если загрузка уже идеальная (в пределах 10%) и статус не отправлен, отправляем
        if (difference <= 10) {
            LogHelper.info(truck.getId(), "Загрузка в допустимых пределах (" + loadPercentage +
                    "%), завершаем.");
            notifyManager();
            return;
        }

        // Если статус уже отправлен, ничего не делаем
        if (readyStatusSent) {
            LogHelper.debug(truck.getId(), "Уже отправлен READY статус, пропускаем обмен.");
            return;
        }

        // Проверка на превышение времени обмена
        if (exchangeStartTime > 0 && System.currentTimeMillis() - exchangeStartTime > MAX_EXCHANGE_TIME) {
            LogHelper.warning(truck.getId(), "Превышено время обмена, завершаем с текущей загрузкой: "
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
                            LogHelper.exchange(truck.getId(), "Грузовик перегружен на " + (loadPercentage - idealLoadPercentage) +
                                    "%, инициируем обмен после задержки.");
                            requestExchange();
                        }
                    }
                });
                return;
            }
            else if (loadPercentage < idealLoadPercentage - 10) {
                // Недогруженные грузовики инициируют обмен немедленно
                LogHelper.exchange(truck.getId(), "Грузовик недогружен на " + (idealLoadPercentage - loadPercentage) +
                        "%, ожидаем получения грузов...");
                addBehaviour(new WakerBehaviour(this, 60000) { // 30 секунд ожидания
                    protected void onWake() {
                        if (!readyStatusSent) {
                            LogHelper.warning(truck.getId(), "Истекло время ожидания грузов, отправляем READY с текущей загрузкой: " +
                                    truck.getLoadPercentage() + "%");
                            notifyManager();
                        }
                    }
                });
                return;
            }
        } else {
            LogHelper.info(truck.getId(), "Достигнут лимит попыток обмена, завершаем с текущей загрузкой: "
                    + truck.getLoadPercentage() + "%");
        }

        // Если попытки обмена исчерпаны или разница небольшая, отправляем READY
        LogHelper.debug(truck.getId(), "Завершаем попытки обмена и отправляем READY статус.");        notifyManager();
    }

    private void requestExchange() {
        float loadPercentage = truck.getLoadPercentage();
        if (Math.abs(loadPercentage - idealLoadPercentage) <= 10) {
            LogHelper.exchange(truck.getId(), "Имеет оптимальную загрузку (" +
                    loadPercentage + "%), обмен не требуется.");
            notifyManager(); // Отправляем READY статус, так как загрузка оптимальна
            return;
        }
        if (readyStatusSent) {
            LogHelper.exchange(truck.getId(), "Не может инициировать обмен - уже отправил READY статус.");
            return;
        }
        if (exchangeAttempts >= MAX_EXCHANGE_ATTEMPTS) {
            LogHelper.exchange(truck.getId(), "Достиг лимита попыток обмена.");
            notifyManager();
            return;
        }
        if (exchangeInProgress) {
            LogHelper.exchange(truck.getId(), "Обмен уже идет, ждем ответа...");
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
                15000,  // Небольшая недогрузка: 8 секунд
                20000, // Умеренная недогрузка: 10 секунд
                25000, // Сильная недогрузка: 12 секунд
                30000  // Очень сильная недогрузка: 15 секунд
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
        LogHelper.exchange(truck.getId(), "Запланирован обмен через " + (delay/1000.0) +
                " секунд (загрузка: " + loadPercentage + "%, отклонение: " + loadDeviation +
                "%, интервал: " + loadBucket + ")");

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

                LogHelper.debug(truck.getId(), "idealLoadPercentage: " + idealLoadPercentage);
                LogHelper.debug(truck.getId(), "Идеальная загрузка: " + idealLoad);
                LogHelper.debug(truck.getId(), "Текущая загрузка: " + truck.getCurrentLoad());

                // Перепроверка необходимости обмена
                if (Math.abs(currentLoadPercentage - idealLoadPercentage) <= 10) {
                    LogHelper.exchange(truck.getId(), "Теперь имеет оптимальную загрузку (" +
                            currentLoadPercentage + "%), обмен не требуется.");
                    notifyManager();
                    return;
                }

                // Расчёт параметров обмена
                desiredExchangeWeight = (loadDiff > truck.getCapacity() * 0.3f) ? loadDiff / 2 : loadDiff;
                if (desiredExchangeWeight < 50) { // Минимальный порог для обмена
                    LogHelper.exchange(truck.getId(), "Слишком малый вес для обмена (" + desiredExchangeWeight + "), пропускаем.");
                    notifyManager();
                    return;
                }

                if (truck.getCurrentLoad() > idealLoad) {
                    direction = "NEED_LESS";
                } else {
                    // Если нужно больше груза, пропускаем обмен
                    exchangeInProgress = false;
                    notifyManager();
                    return;
                }

                // Начало обмена
                exchangeInProgress = true;
                exchangeAttempts++;
                exchangeStartTime = System.currentTimeMillis();

                LogHelper.exchange(truck.getId(), "Направление: " + direction +
                        " | Идеальная загрузка: " + idealLoad +
                        " | Текущая: " + truck.getCurrentLoad() +
                        " | Нужный вес обмена: " + desiredExchangeWeight);

                // Установка таймаута
                addBehaviour(new WakerBehaviour(myAgent, 40000) { // 40 seconds timeout
                    protected void onWake() {
                        if (exchangeInProgress) {
                            LogHelper.warning(truck.getId(), "Таймаут обмена!");
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
                    LogHelper.debug(truck.getId(), "Запрос отправлен активному грузовику: " + truckAID.getLocalName());
                }

                if (receiverCount == 0) {
                    LogHelper.warning(truck.getId(), "Нет доступных активных грузовиков для запроса обмена.");
                    exchangeInProgress = false;
                    checkLoadingProgress();
                    return;
                }

                // Добавляем текущую загрузку в запрос для контекста
                request.setContent("EXCHANGE_REQUEST:" + desiredExchangeWeight + ":" + direction + ":" +
                        truck.getLoadPercentage());
                send(request);
                LogHelper.exchange(truck.getId(), "Запрос отправлен " + receiverCount + " активным грузовикам");
            }
        });
    }
    private void notifyManager() {
        if (!readyStatusSent) {
            readyStatusSent = true;
            LogHelper.info(truck.getId(), "Отправка TRUCK_READY в менеджер.");
            try {
                Thread.sleep(truck.getId() * 2000); // По 0.5 сек на каждый ID
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

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
            LogHelper.debug(truck.getId(), "Уже отправил TRUCK_READY, повторять не нужно.");
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
        LogHelper.debug(truck.getId(), "Предложение принято, отправлен ACCEPT_PROPOSAL");

        // Определяем действие в зависимости от направления обмена
        if (direction.equals("NEED_LESS")) {
            // Если мы хотим уменьшить груз, то отправляем наш груз
            sendCargoToReceiver(receiver);
        } else if (direction.equals("NEED_MORE")) {
            // Если мы хотим увеличить груз, то ждем пока другой грузовик отправит нам груз
            // Увеличиваем таймаут, так как теперь мы ждем, пока другой грузовик найдет и отправит груз
            /*LogHelper.exchange(truck.getId(), "Ожидание получения груза от " + receiver.getLocalName());

            // Таймер для завершения ожидания, если груз не придет
            addBehaviour(new WakerBehaviour(this, 30000) { // 30 секунд на ожидание
                protected void onWake() {
                    if (exchangeInProgress) {
                        LogHelper.warning(truck.getId(), "Истекло время ожидания груза от " + receiver.getLocalName());
                        exchangeInProgress = false;
                        checkLoadingProgress();
                    }
                }
            });*/
        } else {
            LogHelper.error(truck.getId(), "Неизвестное направление обмена: " + direction);            exchangeInProgress = false;
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
                LogHelper.warning(truck.getId(), "Обмен отклонен: груз " + cargoToTransfer.getId() +
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
                LogHelper.warning(truck.getId(), "Обмен отклонен: не улучшает загрузку (" +
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
                    LogHelper.success(truck.getId(), "Передан груз " + cargoToTransfer.getId() +
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
                                        LogHelper.warning(truck.getId(), "Груз " + cargoToRestore.getId() +
                                                " автоматически возвращен - не получено подтверждение");
                                    } else {
                                        LogHelper.error(truck.getId(), "Невозможно восстановить груз " + cargoToRestore.getId() +
                                                " (тип: " + cargoToRestore.getType() + ") - стал несовместим с текущими грузами");
                                        returnCargoToPool(cargoToRestore);
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
                    LogHelper.error(truck.getId(), "Не удалось сериализовать груз: " + e.getMessage());
                    e.printStackTrace();
                    exchangeInProgress = false;
                    checkLoadingProgress();
                }

            } else {
                LogHelper.error(truck.getId(), "Груз " + cargoToTransfer.getId() +
                        " нельзя безопасно удалить - нарушится совместимость");
                // Отменяем обмен
                exchangeInProgress = false;
                checkLoadingProgress();
            }
        } else {
            LogHelper.error(truck.getId(), "Не удалось найти подходящий груз для передачи");
            exchangeInProgress = false;
            checkLoadingProgress();
        }
    }
    // Улучшенная проверка совместимости
    private boolean checkCompatibility(Cargo cargo, AID receiverAID) {

        LogHelper.debug(truck.getId(), "Проверка совместимости груза " + cargo.getId() +
                " (тип: " + cargo.getType() + ") с грузовиком " + receiverAID.getLocalName());
        // Проверяем наличие несовместимых типов для передаваемого груза
        if (cargo.getIncompatibleTypes().isEmpty()) {
            LogHelper.debug(truck.getId(), "Груз " + cargo.getId() + " (" + cargo.getType() +
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
                LogHelper.debug(truck.getId(), "Грузовик " + receiverAID.getLocalName() + " пуст, совместимость обеспечена");
                return true;
            }

            String[] types = typesStr.split(",");
            LogHelper.debug(truck.getId(), "Типы в грузовике " + receiverAID.getLocalName() + ": " + Arrays.toString(types));
            LogHelper.debug(truck.getId(), "Несовместимые типы для груза " + cargo.getId() + ": " + cargo.getIncompatibleTypes());

            // 1. Проверяем, есть ли в принимающем грузовике типы, которые несовместимы с передаваемым грузом
            for (String type : types) {
                if (type.isEmpty()) continue;

                if (cargo.getIncompatibleTypes().contains(type)) {
                    LogHelper.warning(truck.getId(), "Груз " + cargo.getId() +
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
                LogHelper.debug(truck.getId(), "Получена информация о несовместимостях: " + content);

                if (content.startsWith("INCOMPATIBILITIES:")) {
                    String incompStr = content.substring("INCOMPATIBILITIES:".length());

                    // Если нет несовместимостей, разрешаем обмен
                    if (incompStr.isEmpty()) {
                        LogHelper.debug(truck.getId(), "Нет информации о несовместимостях в грузовике " +
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
                                    LogHelper.warning(truck.getId(), "Тип " + cargo.getType() +
                                            " несовместим с типом " + cargoType + " в грузовике " + receiverAID.getLocalName());
                                    return false;
                                }
                            }
                        }
                    }
                }
            } else {
                LogHelper.debug(truck.getId(), "Не получен ответ о несовместимостях, но типы грузов совместимы");
                return true;
            }

            LogHelper.debug(truck.getId(), "Груз " + cargo.getId() + " (" + cargo.getType() +
                    ") совместим с грузами в грузовике " + receiverAID.getLocalName());
            return true;
        }

        // Если не получили ответ о типах грузов
        LogHelper.warning(truck.getId(), "Не получен ответ о типах грузов от " + receiverAID.getLocalName() +
                ", считаем несовместимым для безопасности");
        return false;
    }
    private String serializeTruckToString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TRUCK_DATA_START\n");
        sb.append("ID:").append(truck.getId()).append("\n");
        sb.append("CAPACITY:").append(truck.getCapacity()).append("\n");
        sb.append("CURRENT_LOAD:").append(truck.getCurrentLoad()).append("\n");
        sb.append("LOAD_PERCENTAGE:").append(truck.getLoadPercentage()).append("\n");
        sb.append("PROCESSED_CARGO_IDS:").append(String.join(",",
                processedCargoIds.stream().map(String::valueOf).collect(Collectors.toList()))).append("\n");
        sb.append("IMPOSSIBLE_CARGO_IDS:").append(String.join(",",
                impossibleCargoIds.stream().map(String::valueOf).collect(Collectors.toList()))).append("\n");

        // List of loaded cargo types
        sb.append("CARGO_TYPES:");
        Set<String> loadedTypes = new HashSet<>();
        for (Cargo c : truck.getLoadedCargos()) {
            loadedTypes.add(c.getType());
        }
        sb.append(String.join(",", loadedTypes)).append("\n");

        // List of cargos with clear delimiters
        sb.append("CARGOS_START\n");
        for (Cargo cargo : truck.getLoadedCargos()) {
            sb.append(cargo.getId()).append(":").append(cargo.getType()).append(":").append(cargo.getWeight()).append("\n");
        }
        sb.append("CARGOS_END\n");
        sb.append("TRUCK_DATA_END");

        return sb.toString();
    }

}