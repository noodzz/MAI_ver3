package client;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import model.LoadingConfiguration;
import model.Truck;
import jade.core.AID;
import java.util.ArrayList;


import java.io.*;
import java.util.List;

public class ClientMain {
    public static void main(String[] args) {
        try {
            // Чтение IP-адреса сервера
            String serverIP = "192.168.1.43"; // По умолчанию - локальный хост
            if (args.length > 0) {
                serverIP = args[0];
            }
            System.out.println("Подключение к серверу: " + serverIP);

            // Загрузка конфигурации клиента
            LoadingConfiguration config = loadClientConfiguration();
            System.out.println("[DEBUG] Загружен idealLoadPercentage: " + config.getIdealLoadPercentage()); // Добавьте эту строку

            if (config == null || config.getTrucks().isEmpty()) {
                System.out.println("Ошибка: Не удалось загрузить конфигурацию клиента или нет грузовиков для запуска.");
                return;
            }

            System.out.println("Загружено " + config.getTrucks().size() + " грузовиков из конфигурации.");


            // Запуск JADE Runtime
            Runtime rt = Runtime.instance();

            // Создание профиля для подключения к главному контейнеру
            Profile profile = new ProfileImpl();
            profile.setParameter(Profile.MAIN_HOST, serverIP);
            profile.setParameter(Profile.MAIN_PORT, "1099");
            profile.setParameter(Profile.CONTAINER_NAME, "ClientContainer");  // Явное указание контейнера


            // Создание периферийного контейнера
            System.out.println("Создание периферийного контейнера...");
            AgentContainer container = rt.createAgentContainer(profile);

            // Создание агентов-грузовиков на клиенте
            List<AID> allTruckAIDs = new ArrayList<>();
            for (Truck truck : config.getTrucks()) {
                String agentName = "truck-" + truck.getId();
                allTruckAIDs.add(new AID(agentName, AID.ISGUID));
            }

            // Создание агентов-грузовиков на клиенте
            System.out.println("Запуск агентов-грузовиков на клиенте...");
            for (Truck truck : config.getTrucks()) {
                if (truck == null) {
                    System.out.println("Ошибка: найден пустой грузовик.");
                    continue;
                }
                String agentName = "truck-" + truck.getId();
                System.out.println("Создание агента для грузовика: " + truck.getId());

                // Передаем три параметра: грузовик, процент загрузки и список AID
                AgentController truckAgent = container.createNewAgent(
                        agentName,
                        "agents.TruckAgent",
                        new Object[]{
                                truck,
                                config.getIdealLoadPercentage(),
                                new ArrayList<>(allTruckAIDs) // Копируем список
                        });
                truckAgent.start();
                System.out.println("Запущен агент грузовика: truck-" + truck.getId());
            }
        } catch (Exception e) {
            System.out.println("Ошибка при запуске клиента:");
            e.printStackTrace();
        }
    }

    private static LoadingConfiguration loadClientConfiguration() {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream("client_config.dat"))) {
            return (LoadingConfiguration) ois.readObject();
        } catch (Exception e) {
            System.out.println("Ошибка при загрузке конфигурации клиента:");
            e.printStackTrace();
            return null;
        }
    }
}

