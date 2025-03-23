package server;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import model.Cargo;
import model.CargoPool;
import model.LoadingConfiguration;
import model.Truck;

import java.io.*;
import java.util.*;

public class ServerMain {
    public static void main(String[] args) {
        try {
            // Чтение конфигурации из файла
            LoadingConfiguration config = readConfiguration("config.txt");
            if (config == null || config.getTrucks() == null || config.getCargos() == null) {
                System.out.println("Ошибка: Конфигурация не загружена!");
                return;
            }

            if (config.getTrucks().isEmpty()) {
                System.out.println("Ошибка: Нет грузовиков в конфигурации!");
                return;
            }

            if (config.getCargos().isEmpty()) {
                System.out.println("Ошибка: Нет грузов в конфигурации!");
                return;
            }
            // Запуск JADE Runtime
            Runtime rt = Runtime.instance();

            // Создание профиля для главного контейнера (сервера)
            Profile profile = new ProfileImpl();
            profile.setParameter(Profile.MAIN_HOST, "localhost"); // Изменить на IP-адрес сервера для удаленного доступа
            profile.setParameter(Profile.MAIN_PORT, "1099");
            profile.setParameter(Profile.GUI, "true");

            // Создание главного контейнера
            AgentContainer mainContainer = rt.createMainContainer(profile);

            // Создание агента-менеджера
            AgentController managerAgent = mainContainer.createNewAgent(
                    "manager",
                    "agents.LoadingManagerAgent",
                    new Object[]{config});
            managerAgent.start();
            CargoPool cargoPool = CargoPool.getInstance();
            cargoPool.initializePool(config.getCargos());
            System.out.println("Пул грузов инициализирован с " + config.getCargos().size() + " грузами");
            // Создание первой группы агентов-грузовиков на сервере
            // (предполагается, что половину грузовиков запускаем на сервере)
            int trucksOnServer = 0;


            for (int i = 0; i < trucksOnServer; i++) {
                Truck truck = config.getTrucks().get(i);
                AgentController truckAgent = mainContainer.createNewAgent(
                        "truck-" + truck.getId(),
                        "agents.TruckAgent",
                        new Object[]{truck, config.getIdealLoadPercentage()});
                truckAgent.start();
            }

            // Сохраняем конфигурацию для клиентов
            saveClientConfiguration(config, trucksOnServer);

            System.out.println("Сервер МАС запущен успешно на порту 1099.");
            System.out.println("Запустите клиентов для добавления агентов-грузовиков.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Сохранение конфигурации для клиентов
    private static void saveClientConfiguration(LoadingConfiguration config, int startIndex) throws IOException {
        List<Truck> clientTrucks = new ArrayList<>();
        for (int i = startIndex; i < config.getTrucks().size(); i++) {
            clientTrucks.add(config.getTrucks().get(i));
        }

        // Сохраняем только нужные клиенту грузовики
        LoadingConfiguration clientConfig = new LoadingConfiguration();
        clientConfig.setTrucks(clientTrucks);
        clientConfig.setCargos(config.getCargos());
        clientConfig.setIdealLoadPercentage(config.getIdealLoadPercentage());

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("client_config.dat"))) {
            oos.writeObject(clientConfig);
        }
    }

    private static LoadingConfiguration readConfiguration(String filename) {
        LoadingConfiguration config = new LoadingConfiguration();
        List<Truck> trucks = new ArrayList<>();
        List<Cargo> cargos = new ArrayList<>();
        float idealLoadPercentage = 50.0f;

        try (InputStream inputStream = ServerMain.class.getClassLoader().getResourceAsStream(filename);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            String section = "";

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1);
                    continue;
                }

                if (section.equals("TRUCKS")) {
                    String[] parts = line.split(",");
                    if (parts.length >= 2) {
                        int id = Integer.parseInt(parts[0].trim());
                        float capacity = Float.parseFloat(parts[1].trim());
                        trucks.add(new Truck(id, capacity));
                    }
                } else if (section.equals("CARGOS")) {
                    String[] parts = line.split(",");
                    if (parts.length >= 3) {
                        int id = Integer.parseInt(parts[0].trim());
                        String type = parts[1].trim();
                        float weight = Float.parseFloat(parts[2].trim());

                        List<String> incompatibleTypes = new ArrayList<>();
                        if (parts.length > 3) {
                            String[] incompatible = parts[3].trim().split(";");
                            incompatibleTypes.addAll(Arrays.asList(incompatible));
                        }

                        cargos.add(new Cargo(id, type, weight, incompatibleTypes));
                    }
                } else if (section.equals("SETTINGS")) {
                    String[] parts = line.split("=");
                    if (parts.length == 2 && parts[0].trim().equals("idealLoadPercentage")) {
                        idealLoadPercentage = Float.parseFloat(parts[1].trim());
                    }
                }
            }

            config.setTrucks(trucks);
            config.setCargos(cargos);
            config.setIdealLoadPercentage(idealLoadPercentage);

        } catch (IOException e) {
            e.printStackTrace();
        }

        return config;
    }
}