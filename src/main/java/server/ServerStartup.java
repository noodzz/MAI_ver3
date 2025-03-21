package server;

import util.NetworkHelper;

public class ServerStartup {
    public static void main(String[] args) {
        System.out.println("Запуск JADE сервера для системы загрузки грузовиков");
        NetworkHelper.printNetworkInfo();
        System.out.println("Используйте один из этих IP-адресов при запуске клиента");
        System.out.println("Запуск сервера...");
        ServerMain.main(args);
    }
}
