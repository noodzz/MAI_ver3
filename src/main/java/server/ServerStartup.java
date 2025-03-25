package server;

import util.NetworkHelper;

public class ServerStartup {
    public static void main(String[] args) {
        System.out.println("Запуск JADE сервера для системы загрузки грузовиков");
        NetworkHelper.printNetworkInfo();
        System.out.println("Используйте один из этих IP-адресов при запуске клиента");

        // Показываем информацию о том, как указать файл конфигурации
        System.out.println();
        System.out.println("Для использования своего файла конфигурации, запустите сервер с параметром --config:");
        System.out.println("  Windows: run_server.bat путь/к/config.txt");
        System.out.println("  Linux/Mac: ./run_server.sh путь/к/config.txt");
        System.out.println();

        System.out.println("Запуск сервера...");
        ServerMain.main(args);
    }
}