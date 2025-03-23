package util;

import jade.core.AID;

/**
 * Helper class for improved logging in the truck loading system
 */
public class LogHelper {
    // ANSI color codes for terminal output
    public static final String RESET = "\u001B[0m";
    public static final String BLACK = "\u001B[30m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String PURPLE = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";
    public static final String WHITE = "\u001B[37m";

    // Background colors
    public static final String BG_BLACK = "\u001B[40m";
    public static final String BG_RED = "\u001B[41m";
    public static final String BG_GREEN = "\u001B[42m";
    public static final String BG_YELLOW = "\u001B[43m";
    public static final String BG_BLUE = "\u001B[44m";
    public static final String BG_PURPLE = "\u001B[45m";
    public static final String BG_CYAN = "\u001B[46m";
    public static final String BG_WHITE = "\u001B[47m";

    // Styles
    public static final String BOLD = "\u001B[1m";
    public static final String UNDERLINE = "\u001B[4m";

    // Log levels and their colors
    public static final String DEBUG = WHITE;
    public static final String INFO = WHITE;
    public static final String WARNING = YELLOW;
    public static final String ERROR = RED;
    public static final String EXCHANGE = WHITE;
    public static final String SUCCESS = GREEN + BOLD;
    public static final String FAILURE = RED + BOLD;

    /**
     * Gets a color based on truck ID (to ensure consistent coloring)
     * @param truckId The ID of the truck
     * @return ANSI color code
     */
    public static String getTruckColor(int truckId) {
        String[] colors = {
                CYAN, YELLOW, GREEN, PURPLE, BLUE, RED
        };

        return colors[Math.abs(truckId) % colors.length];
    }

    /**
     * Formats a truck identifier for logging
     * @param id Truck ID or name
     * @return Formatted truck identifier string
     */
    public static String formatTruckId(int id) {
        String color = getTruckColor(id);
        return color + "[TRUCK-" + id + "]" + RESET + " ";
    }

    /**
     * Formats a truck identifier from AID for logging
     * @param aid Agent ID
     * @return Formatted truck identifier string
     */
    public static String formatTruckId(AID aid) {
        if (aid == null) return "[UNKNOWN]";

        String localName = aid.getLocalName();
        if (localName.startsWith("truck-")) {
            try {
                int id = Integer.parseInt(localName.substring(6));
                return formatTruckId(id);
            } catch (NumberFormatException e) {
                return "[" + localName + "] ";
            }
        }

        return "[" + localName + "] ";
    }

    /**
     * Logs a debug message
     * @param truckId Truck ID
     * @param message Message to log
     */
    public static void debug(int truckId, String message) {
        System.out.println(formatTruckId(truckId) + DEBUG + "[DEBUG] " + message + RESET);
    }

    /**
     * Logs an info message
     * @param truckId Truck ID
     * @param message Message to log
     */
    public static void info(int truckId, String message) {
        System.out.println(formatTruckId(truckId) + INFO + "[INFO] " + message + RESET);
    }

    /**
     * Logs a warning message
     * @param truckId Truck ID
     * @param message Message to log
     */
    public static void warning(int truckId, String message) {
        System.out.println(formatTruckId(truckId) + WARNING + "[WARN] " + message + RESET);
    }

    /**
     * Logs an error message
     * @param truckId Truck ID
     * @param message Message to log
     */
    public static void error(int truckId, String message) {
        System.out.println(formatTruckId(truckId) + ERROR + "[ERROR] " + message + RESET);
    }

    /**
     * Logs an exchange-related message
     * @param truckId Truck ID
     * @param message Message to log
     */
    public static void exchange(int truckId, String message) {
        System.out.println(formatTruckId(truckId) + EXCHANGE + "[EXCHANGE] " + message + RESET);
    }

    /**
     * Logs a success message
     * @param truckId Truck ID
     * @param message Message to log
     */
    public static void success(int truckId, String message) {
        System.out.println(formatTruckId(truckId) + SUCCESS + "[SUCCESS] " + message + RESET);
    }

    /**
     * Logs a failure message
     * @param truckId Truck ID
     * @param message Message to log
     */
    public static void failure(int truckId, String message) {
        System.out.println(formatTruckId(truckId) + FAILURE + "[FAILURE] " + message + RESET);
    }

    /**
     * Logs a message with specified level
     * @param truckId Truck ID
     * @param level Log level (DEBUG, INFO, etc.)
     * @param tag Log tag (e.g., "DEBUG", "ERROR")
     * @param message Message to log
     */
    public static void log(int truckId, String level, String tag, String message) {
        System.out.println(formatTruckId(truckId) + level + "[" + tag + "] " + message + RESET);
    }

    /**
     * Used by managers and other non-truck agents
     * @param sender Agent name
     * @param level Log level color
     * @param tag Log tag
     * @param message Message to log
     */
    public static void systemLog(String sender, String level, String tag, String message) {
        System.out.println(BOLD + "[" + sender + "] " + RESET +
                level + "[" + tag + "] " + message + RESET);
    }
}