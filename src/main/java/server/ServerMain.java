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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ServerMain {
    private static final String DEFAULT_CONFIG_PATH = "config.txt";

    public static void main(String[] args) {
        try {
            String configPath = DEFAULT_CONFIG_PATH;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--config") && i + 1 < args.length) {
                    configPath = args[i + 1];
                    i++; // Пропускаем следующий аргумент, так как он является значением
                }
            }
            System.out.println("Использую файл конфигурации: " + configPath);

            // Чтение конфигурации из файла
            LoadingConfiguration config = readConfiguration(configPath);
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

    private static LoadingConfiguration readConfiguration(String filePath) {
        LoadingConfiguration config = new LoadingConfiguration();
        List<Truck> trucks = new ArrayList<>();
        List<Cargo> cargos = new ArrayList<>();
        float idealLoadPercentage = 50.0f;
        boolean useDynamicIdealLoad = false;

        Path path = Paths.get(filePath);

        try {
            if (Files.exists(path)) {
                System.out.println("Чтение конфигурации из файла: " + path.toAbsolutePath());
                try (BufferedReader reader = Files.newBufferedReader(path)) {
                    parseConfigFile(reader, trucks, cargos, config);
                }
            } else {
                // Если файл не найден в файловой системе, пробуем ресурсы класса (для обратной совместимости)
                System.out.println("Файл не найден в файловой системе. Попытка загрузки из ресурсов: " + filePath);
                try (InputStream inputStream = ServerMain.class.getClassLoader().getResourceAsStream(filePath)) {
                    if (inputStream == null) {
                        System.err.println("Ошибка: Файл конфигурации не найден ни в файловой системе, ни в ресурсах: " + filePath);
                        return null;
                    }
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                        parseConfigFile(reader, trucks, cargos, config);
                    }
                }
            }

            config.setTrucks(trucks);
            config.setCargos(cargos);

        } catch (IOException e) {
            System.err.println("Ошибка при чтении файла конфигурации: " + e.getMessage());
            e.printStackTrace();
            return null;
        }

        return config;
    }
    private static void parseConfigFile(BufferedReader reader, List<Truck> trucks, List<Cargo> cargos, LoadingConfiguration config) throws IOException {
        String line;
        String section = "";
        float idealLoadPercentage = 50.0f;
        boolean useDynamicIdealLoad = false;

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
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();

                    if (key.equals("idealLoadPercentage")) {
                        idealLoadPercentage = Float.parseFloat(value);
                        config.setIdealLoadPercentage(idealLoadPercentage);
                    } else if (key.equals("useDynamicIdealLoad")) {
                        useDynamicIdealLoad = Boolean.parseBoolean(value);
                        config.setUseDynamicIdealLoad(useDynamicIdealLoad);
                    }
                }
            }
        }
    }
}