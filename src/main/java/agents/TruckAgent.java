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
                                if (truck.canAddCargo(receivedCargo)) {
                                    truck.addCargo(receivedCargo);
                                    System.out.println("[УСПЕХ] Получен груз " + receivedCargo.getId() +
                                            " (тип: " + receivedCargo.getType() + ")" +
                                            " от " + msg.getSender().getLocalName() +
                                            ". Новая загрузка: " + truck.getLoadPercentage() + "%");
                                    checkLoadingProgress();
                                } else {
                                    System.out.println("[ОШИБКА] Не удалось добавить полученный груз " +
                                            receivedCargo.getId() + " (тип: " + receivedCargo.getType() + ")" +
                                            " от " + msg.getSender().getLocalName() +
                                            " - несовместим с текущими грузами");
                                    ACLMessage errorMsg = new ACLMessage(ACLMessage.FAILURE);
                                    errorMsg.addReceiver(msg.getSender());
                                    errorMsg.setContent("CARGO_INCOMPATIBLE:" + receivedCargo.getId());
                                    send(errorMsg);
                                }
                            } else if (contentObj instanceof Truck) {
                                System.out.println("Получен объект Truck");
                            } else {
                                System.out.println("Получен объект с классом: " + contentObj.getClass().getName());
                            }
                        } catch (UnreadableException e) {
                            if (content == null) {
                                System.out.println("Ошибка чтения объекта от " + msg.getSender().getLocalName() +
                                        ": " + e.getMessage());
                            } else {
                                System.out.println("Получено неизвестное текстовое сообщение INFORM: " + content);
                            }
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
                                                        compatible = truck.areCargoTypesCompatible(cargoTypes);
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
                            }
                        } else if (content.startsWith("TRANSFER_FAILED:")) {
                            System.out.println("[ОШИБКА] Передача груза не удалась: " + content);
                        }
                    }
                    exchangeInProgress = false;
                    checkLoadingProgress();
                } else {
                    block();
                }
            }
        });
        addBehaviour(new WakerBehaviour(this, 60000) { // 30 second timeout
            protected void onWake() {
                if (!readyStatusSent) {
                    System.out.println(getAID().getName() + " завершает работу по таймауту");
                    notifyManager();
                }
            }
        });
        // Behavior to handle cargo transfers (INFORM)
        // Замените оба обработчика INFORM на один более интеллектуальный
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    try {
                        Object content = msg.getContentObject();
                        if (content instanceof List) {
                            List<Cargo> availableCargos = (List<Cargo>) content;
                            processCargos(availableCargos);
                        } else if (content instanceof Cargo) {
                            Cargo receivedCargo = (Cargo) content;
                            if (truck.canAddCargo(receivedCargo)) {
                                truck.addCargo(receivedCargo);
                                System.out.println("[УСПЕХ] Получен груз " + receivedCargo.getId() +
                                        " от " + msg.getSender().getLocalName() +
                                        ". Новая загрузка: " + truck.getLoadPercentage() + "%");
                                checkLoadingProgress();
                            } else {
                                System.out.println("[ОШИБКА] Не удалось добавить полученный груз " +
                                        receivedCargo.getId() + " от " + msg.getSender().getLocalName());
                            }
                        } else if (content instanceof Truck) {
                            System.out.println("Получен объект Truck");
                        } else {
                            System.out.println("Получен объект с классом: " + content.getClass().getName());
                        }
                    } catch (UnreadableException e) {
                        // Проверяем, возможно это текстовое сообщение INFORM
                        String textContent = msg.getContent();
                        if (textContent != null) {
                            if (textContent.equals("REPORT_STATUS")) {
                                System.out.println(getAID().getName() + " sending final report to manager");
                                ACLMessage reply = msg.createReply();
                                reply.setPerformative(ACLMessage.INFORM);
                                try {
                                    reply.setContentObject(truck);
                                    myAgent.send(reply);
                                } catch (IOException ioe) {
                                    ioe.printStackTrace();
                                }
                            } else if (textContent.startsWith("TRUCK_READY_STATUS")) {
                                // Handle truck ready status correctly
                                String[] parts = textContent.split(":");
                                if (parts.length > 1) {
                                    String truckName = parts[1];
                                    System.out.println("Грузовик " + truckName + " теперь в статусе READY");
                                }
                            } else {
                                System.out.println("Получено текстовое сообщение INFORM: " + textContent);
                            }
                        } else {
                            System.out.println("Ошибка чтения объекта от " + msg.getSender().getLocalName() +
                                    ": " + e.getMessage());
                        }
                    }
                } else {
                    block();
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
            if (Math.abs(cargo.getWeight() - targetWeight) < 1.0 && canRemoveCargoSafely(cargo)) {
                System.out.println("Найден идеальный груз: " + cargo.getId() + " весом " + cargo.getWeight());
                return cargo;
            }
        }

        // Если точного соответствия нет, ищем груз поменьше целевого веса
        Cargo bestSmallerMatch = null;
        float maxWeight = 0;
        for (Cargo cargo : loadedCargos) {
            if (cargo.getWeight() < targetWeight && cargo.getWeight() > maxWeight && canRemoveCargoSafely(cargo)) {
                maxWeight = cargo.getWeight();
                bestSmallerMatch = cargo;
            }
        }

        // Если нашли подходящий меньший груз, возвращаем его
        if (bestSmallerMatch != null) {
            System.out.println("Найден подходящий меньший груз: " + bestSmallerMatch.getId() +
                    " весом " + bestSmallerMatch.getWeight());
            return bestSmallerMatch;
        }

        // Иначе ищем груз с минимальной разницей
        Cargo bestMatch = null;
        float minDiff = Float.MAX_VALUE;
        for (Cargo cargo : loadedCargos) {
            float diff = Math.abs(cargo.getWeight() - targetWeight);
            if (diff < minDiff && canRemoveCargoSafely(cargo)) {
                minDiff = diff;
                bestMatch = cargo;
            }
        }

        if (bestMatch != null) {
            System.out.println("Найден ближайший груз: " + bestMatch.getId() +
                    " весом " + bestMatch.getWeight() +
                    " (разница с целевым: " + minDiff + ")");
        } else {
            System.out.println("Не найдено ни одного подходящего груза!");
        }

        return bestMatch;
    }

    private boolean canRemoveCargoSafely(Cargo cargo) {
        // Временно удаляем груз
        truck.removeCargo(cargo);

        // Проверяем, остаются ли все оставшиеся грузы совместимыми
        boolean isSafe = true;
        List<Cargo> remainingCargos = truck.getLoadedCargos();

        // Проверяем каждый оставшийся груз на совместимость с другими
        for (Cargo remainingCargo : remainingCargos) {
            if (!truck.canAddCargo(remainingCargo)) {
                System.out.println("[DEBUG] Удаление груза " + cargo.getId() +
                        " нарушает совместимость для груза " + remainingCargo.getId());
                isSafe = false;
                break;
            }
        }

        // Возвращаем груз обратно
        truck.addCargo(cargo);

        if (isSafe) {
            System.out.println("[DEBUG] Груз " + cargo.getId() + " можно безопасно удалить");
        } else {
            System.out.println("[DEBUG] Груз " + cargo.getId() + " НЕЛЬЗЯ безопасно удалить - нарушится совместимость");
        }

        return isSafe;
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
            return;
        }
        if (exchangeInProgress) {
            System.out.println("[ОБМЕН] Обмен уже идет, ждем ответа...");
            return;
        }

        // Логирование параметров
        System.out.println("[DEBUG] idealLoadPercentage: " + idealLoadPercentage);
        float idealLoad = truck.getCapacity() * (idealLoadPercentage / 100);
        System.out.println("[DEBUG] Идеальная загрузка: " + idealLoad);
        System.out.println("[DEBUG] Текущая загрузка: " + truck.getCurrentLoad());
        float loadDiff = Math.abs(idealLoad - truck.getCurrentLoad());

        // Расчёт параметров обмена
        desiredExchangeWeight = (loadDiff > truck.getCapacity() * 0.3f) ? loadDiff / 2 : loadDiff;
        if (desiredExchangeWeight < 50) { // Минимальный порог для обмена
            System.out.println("[ОБМЕН] Слишком малый вес для обмена (" + desiredExchangeWeight + "), пропускаем.");
            exchangeInProgress = false;
            notifyManager();
            return;
        }

        direction = (truck.getCurrentLoad() > idealLoad) ? "NEED_LESS" : "NEED_MORE";
        System.out.println("[ОБМЕН] Направление: " + direction +
                " | Идеальная загрузка: " + idealLoad +
                " | Текущая: " + truck.getCurrentLoad() +
                " | Нужный вес обмена: " + desiredExchangeWeight);

        exchangeInProgress = true;
        exchangeAttempts++;

        // Set timeout for exchange attempt
        addBehaviour(new WakerBehaviour(this, 20000) { // 20 seconds timeout
            protected void onWake() {
                if (exchangeInProgress) {
                    System.out.println("[ОБМЕН] Таймаут обмена для " + getAID().getName());
                    exchangeInProgress = false;
                    checkLoadingProgress(); // Попытка продолжить работу
                }
            }
        });

        // Prepare and send request to other trucks
        ACLMessage request = new ACLMessage(ACLMessage.CFP);
        int receiverCount = 0;

        for (AID truckAID : otherTruckAIDs) {
            if (!truckAID.equals(getAID())) { // Исключаем себя
                request.addReceiver(truckAID);
                receiverCount++;
                System.out.println("[ОБМЕН] Запрос отправлен: " + truckAID.getLocalName() +
                        " (полный AID: " + truckAID + ")");
            }
        }

        if (receiverCount == 0) {
            System.out.println("[ОБМЕН] Нет доступных грузовиков для запроса обмена.");
            exchangeInProgress = false;
            notifyManager();
            return;
        }

        // Добавляем текущую загрузку в запрос для контекста
        request.setContent("EXCHANGE_REQUEST:" + desiredExchangeWeight + ":" + direction + ":" +
                truck.getLoadPercentage());
        send(request);
        System.out.println("[ОБМЕН] Запрос отправлен " + receiverCount + " грузовикам");
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

        // Находим груз для передачи
        Cargo cargoToTransfer = findCargoToTransfer(desiredExchangeWeight);
        if (cargoToTransfer != null) {
            System.out.println("[DEBUG] Найден груз для передачи: " + cargoToTransfer.getId() +
                    " весом " + cargoToTransfer.getWeight());
            // Проверяем, можно ли безопасно удалить груз
            if (canRemoveCargoSafely(cargoToTransfer)) {
                ACLMessage transferMsg = new ACLMessage(ACLMessage.INFORM);
                transferMsg.addReceiver(receiver);
                try {
                    truck.removeCargo(cargoToTransfer);  // Удаляем груз
                    transferMsg.setContentObject(cargoToTransfer);
                    send(transferMsg);
                    System.out.println("[УСПЕХ] Передан груз " + cargoToTransfer.getId() +
                            " -> " + receiver.getLocalName());
                    exchangeInProgress = false;
                    checkLoadingProgress(); // Проверяем загрузку после обмена
                } catch (IOException e) {
                    // В случае ошибки возвращаем груз обратно
                    truck.addCargo(cargoToTransfer);
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
}