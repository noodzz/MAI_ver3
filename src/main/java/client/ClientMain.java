package client;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import model.CargoPool;
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
            AgentController consoleAgent = container.createNewAgent(
                    "console-" + System.currentTimeMillis(),
                    "agents.ConsoleAgent",
                    null);
            consoleAgent.start();

            System.out.println("Клиент подключился к серверу " + serverIP);
            System.out.println("Запрос на начало процесса распределения отправлен");

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

