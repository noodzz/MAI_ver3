package agents;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

public class ConsoleAgent extends Agent {

    @Override
    protected void setup() {
        System.out.println("Консольный агент " + getAID().getName() + " запущен.");

        // Отправить запрос на запуск распределения
        addBehaviour(new OneShotBehaviour(this) {
            public void action() {
                ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
                msg.addReceiver(new AID("manager", AID.ISLOCALNAME));
                msg.setContent("START_DISTRIBUTION");
                myAgent.send(msg);
                System.out.println("Запрос на начало распределения отправлен менеджеру");
            }
        });

        // Подписаться на консольные сообщения
        addBehaviour(new OneShotBehaviour(this) {
            public void action() {
                ACLMessage subscription = new ACLMessage(ACLMessage.SUBSCRIBE);
                subscription.addReceiver(new AID("manager", AID.ISLOCALNAME));
                subscription.setContent("CONSOLE_SUBSCRIBE");
                myAgent.send(subscription);
                System.out.println("Подписка на консольный вывод отправлена");
            }
        });

        // Обработка входящих консольных сообщений
        addBehaviour(new CyclicBehaviour(this) {
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
                ACLMessage msg = myAgent.receive(mt);

                if (msg != null) {
                    String content = msg.getContent();
                    if (content != null && content.startsWith("CONSOLE_OUTPUT:")) {
                        String output = content.substring("CONSOLE_OUTPUT:".length());
                        System.out.println(output);
                    }
                } else {
                    block();
                }
            }
        });
    }
}