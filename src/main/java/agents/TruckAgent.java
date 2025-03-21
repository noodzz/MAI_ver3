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
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchContent("TRUCK_READY_STATUS");
                ACLMessage msg = myAgent.receive(mt);
                if (msg != null) {
                    String content = msg.getContent();
                    String truckName = content.split(":")[1];
                    AID truckAID = new AID(truckName, AID.ISLOCALNAME);
                    truckReadyStatus.put(truckAID, true);
                    System.out.println("Грузовик " + truckName + " теперь в статусе READY");
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
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.CFP);
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null && !readyStatusSent) {
                    AID sender = msg.getSender();
                    String content = msg.getContent();
                    System.out.println(getAID().getName() + " получил запрос обмена: " + content);

                    if (content.startsWith("EXCHANGE_REQUEST")) {
                        String[] parts = content.split(":", 3);
                        if (parts.length >= 3) {
                            try {
                                desiredExchangeWeight = Float.parseFloat(parts[1]);
                                direction = parts[2]; // "NEED_MORE" или "NEED_LESS"

                                boolean canHelp = false;
                                if (direction.equals("NEED_MORE") && truck.getLoadPercentage() > idealLoadPercentage) {
                                    canHelp = true; // Отдаем груз
                                } else if (direction.equals("NEED_LESS") && truck.getLoadPercentage() < idealLoadPercentage) {
                                    canHelp = true; // Принимаем груз
                                }

                                if (canHelp && !readyStatusSent) {
                                    ACLMessage reply = new ACLMessage(ACLMessage.PROPOSE);
                                    reply.addReceiver(msg.getSender());
                                    reply.setContent("EXCHANGE_POSSIBLE:" + truck.getLoadPercentage());
                                    System.out.println(getAID().getName() + " может помочь с обменом, отправка предложения: " + truck.getLoadPercentage());
                                    myAgent.send(reply);
                                } else {
                                    String reason = "";
                                    if (readyStatusSent) {
                                        reason = "уже отправил READY статус";
                                    } else if (direction.equals("NEED_MORE") && truck.getLoadPercentage() <= idealLoadPercentage) {
                                        reason = "недостаточно груза для передачи (загрузка: " + truck.getLoadPercentage() +
                                                "%, идеальная: " + idealLoadPercentage + "%)";
                                    } else if (direction.equals("NEED_LESS") && truck.getLoadPercentage() >= idealLoadPercentage) {
                                        reason = "недостаточно места для приема (загрузка: " + truck.getLoadPercentage() +
                                                "%, идеальная: " + idealLoadPercentage + "%)";
                                    }

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
                            if ((direction.equals("NEED_MORE") && otherLoadPercentage > idealLoadPercentage) ||
                                    (direction.equals("NEED_LESS") && otherLoadPercentage < idealLoadPercentage)) {

                                // Принимаем предложение
                                ACLMessage accept = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
                                accept.addReceiver(msg.getSender());
                                accept.setContent("ACCEPT_EXCHANGE");
                                send(accept);
                                System.out.println("[DEBUG] Предложение принято, отправлен ACCEPT_PROPOSAL");

                                // Находим груз для передачи
                                Cargo cargoToTransfer = findCargoToTransfer(desiredExchangeWeight);
                                if (cargoToTransfer != null) {
                                    System.out.println("[DEBUG] Найден груз для передачи: " + cargoToTransfer.getId());
                                    truck.removeCargo(cargoToTransfer);
                                    ACLMessage transferMsg = new ACLMessage(ACLMessage.INFORM);
                                    transferMsg.addReceiver(msg.getSender());
                                    try {
                                        transferMsg.setContentObject(cargoToTransfer);
                                        send(transferMsg);
                                        System.out.println("[УСПЕХ] Передан груз " + cargoToTransfer.getId());
                                        exchangeInProgress = false;
                                        checkLoadingProgress(); // Проверяем загрузку после обмена
                                    } catch (IOException e) {
                                        truck.addCargo(cargoToTransfer);
                                        e.printStackTrace();
                                    }
                                } else {
                                    System.out.println("[ОШИБКА] Не удалось найти подходящий груз для передачи");
                                    exchangeInProgress = false;
                                    checkLoadingProgress();
                                }
                            } else {
                                // Отклоняем предложение
                                ACLMessage reject = new ACLMessage(ACLMessage.REJECT_PROPOSAL);
                                reject.addReceiver(msg.getSender());
                                String rejectionReason = "";
                                if (direction.equals("NEED_MORE") && otherLoadPercentage <= idealLoadPercentage) {
                                    rejectionReason = "Truck needs MORE cargo but other truck has " + otherLoadPercentage +
                                            "% load (ideal: " + idealLoadPercentage + "%)";
                                } else if (direction.equals("NEED_LESS") && otherLoadPercentage >= idealLoadPercentage) {
                                    rejectionReason = "Truck needs LESS cargo but other truck has " + otherLoadPercentage +
                                            "% load (ideal: " + idealLoadPercentage + "%)";
                                }

// Include reason in the message content
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
                                System.out.println("[УСПЕХ] Получен груз " + receivedCargo.getId());
                                checkLoadingProgress();
                            }
                        } else if (content instanceof Truck) {
                            // Обработка сообщений с объектом Truck, если такие есть
                            System.out.println("Получен объект Truck");
                        } else {
                            System.out.println("Получен объект с классом: " + content.getClass().getName());
                        }
                    } catch (UnreadableException e) {
                        // Проверяем, возможно это текстовое сообщение INFORM
                        String textContent = msg.getContent();
                        if (textContent != null && textContent.equals("REPORT_STATUS")) {
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
                            System.out.println("Ошибка чтения объекта: " + e.getMessage());
                            e.printStackTrace();
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
        System.out.println("Доступные грузы: " + truck.getLoadedCargos());

        List<Cargo> loadedCargos = truck.getLoadedCargos();
        for (Cargo cargo : loadedCargos) {

            if (cargo.getWeight() == targetWeight && canRemoveCargoSafely(cargo)) {
                return cargo;
            }
        }

        Cargo bestMatch = null;
        float minDiff = Float.MAX_VALUE;
        for (Cargo cargo : loadedCargos) {
            float diff = Math.abs(cargo.getWeight() - targetWeight);
            if (diff < minDiff && canRemoveCargoSafely(cargo)) {
                minDiff = diff;
                bestMatch = cargo;
            }
        }
        return bestMatch;
    }

    private boolean canRemoveCargoSafely(Cargo cargo) {
        truck.removeCargo(cargo);
        boolean isSafe = true;
        for (Cargo remainingCargo : truck.getLoadedCargos()) {
            if (!truck.canAddCargo(remainingCargo)) {
                isSafe = false;
                break;
            }
        }
        truck.addCargo(cargo);
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
        System.out.println("[DEBUG] " + getAID().getName() +
                " проверка загрузки: " + loadPercentage +
                "%, идеальная: " + idealLoadPercentage +
                "%, разница: " + Math.abs(loadPercentage - idealLoadPercentage));

        if (exchangeInProgress) {
            System.out.println("[DEBUG] Обмен в процессе, ждем завершения...");
            return; // Ждем ответа от других грузовиков
        }

        if (Math.abs(loadPercentage - idealLoadPercentage) <= 10) {
            System.out.println("[DEBUG] Загрузка в допустимых пределах, завершаем.");
            notifyManager();
            return;
        }

        if (exchangeAttempts < MAX_EXCHANGE_ATTEMPTS) {
            System.out.println(getAID().getName() + " инициирует обмен грузами.");
            requestExchange();
        } else {
            System.out.println("Достигнут лимит попыток обмена, но грузовик НЕ отправляет TRUCK_READY сразу.");
            addBehaviour(new WakerBehaviour(this, 10000) { // Даем дополнительное время
                protected void onWake() {
                    if (!exchangeInProgress) { // Если обмен не идет, завершаем загрузку
                        System.out.println("Финальная проверка обменов. Завершаем.");
                        notifyManager();
                    } else {
                        System.out.println("[ОБМЕН] Ждем завершения обмена, не отправляем TRUCK_READY.");
                    }
                }
            });
        }
    }


    private void requestExchange() {
        if (exchangeAttempts >= MAX_EXCHANGE_ATTEMPTS || readyStatusSent) {
            System.out.println("[ОБМЕН] " + getAID().getName() + " не может инициировать обмен.");
            return;
        }
        if (exchangeInProgress) {
            System.out.println("[ОБМЕН] Обмен уже идет, ждем ответа...");
            return;
        }
        exchangeInProgress = true;
        exchangeAttempts++;
        addBehaviour(new WakerBehaviour(this, 20000) { // 10 секунд на обмен
            protected void onWake() {
                if (exchangeInProgress) {
                    System.out.println("[ОБМЕН] Таймаут обмена для " + getAID().getName());
                    exchangeInProgress = false;
                    checkLoadingProgress(); // Попытка продолжить работу
                }
            }
        });
        // Логирование параметров
        System.out.println("[DEBUG] idealLoadPercentage: " + idealLoadPercentage);
        float idealLoad = truck.getCapacity() * (idealLoadPercentage / 100);
        System.out.println("[DEBUG] Идеальная загрузка: " + idealLoad);
        System.out.println("[DEBUG] Текущая загрузка: " + truck.getCurrentLoad());


        // Рассылка запроса другим грузовикам
        ACLMessage request = new ACLMessage(ACLMessage.CFP);
        for (AID truckAID : otherTruckAIDs) {
            if (!truckAID.equals(getAID())) { // Исключаем себя
                request.addReceiver(truckAID);
                System.out.println("[ОБМЕН] Запрос отправлен: " + truckAID.getLocalName());
            }
        }

        // Расчёт параметров обмена
        desiredExchangeWeight = Math.abs(idealLoad - truck.getCurrentLoad()) / 2;
        if (desiredExchangeWeight <= 0) {
            System.out.println("[ОБМЕН] Нет необходимости в обмене.");
            return;
        }

        direction = (truck.getCurrentLoad() > idealLoad) ? "NEED_LESS" : "NEED_MORE";
        System.out.println("[ОБМЕН] Направление: " + direction);
        System.out.println("[ОБМЕН] Направление: " + direction + " | Идеальная загрузка: " + idealLoad + " | Текущая: " + truck.getCurrentLoad());

        request.setContent("EXCHANGE_REQUEST:" + desiredExchangeWeight + ":" + direction);
        send(request);
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
}