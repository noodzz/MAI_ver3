package client;

public class ClientStartup {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Необходимо указать IP-адрес сервера в качестве параметра.");
            System.out.println("Пример: java ClientStartup 192.168.1.10");
            return;
        }

        System.out.println("Запуск JADE клиента для системы загрузки грузовиков");
        System.out.println("Подключение к серверу: " + args[0]);
        ClientMain.main(args);
    }
}
