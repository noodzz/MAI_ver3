package util;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class NetworkHelper {
    /**
     * Получает список всех IP-адресов машины
     */
    public static List<String> getLocalIPs() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                // Фильтруем отключенные интерфейсы
                if (iface.isUp() && !iface.isLoopback()) {
                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return ips;
    }

    /**
     * Выводит информацию о доступных сетевых интерфейсах
     */
    public static void printNetworkInfo() {
        System.out.println("=== Доступные сетевые интерфейсы ===");
        List<String> ips = getLocalIPs();
        for (int i = 0; i < ips.size(); i++) {
            System.out.println((i+1) + ". " + ips.get(i));
        }
        System.out.println("===================================");
    }
}
