package model;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Класс, представляющий общий пул доступных грузов
 */
public class CargoPool implements Serializable {
    private static CargoPool instance;
    private final Map<Integer, Cargo> availableCargos = new ConcurrentHashMap<>();
    private final Set<Integer> reservedCargos = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Integer> takenCargos = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private CargoPool() {
        // Приватный конструктор для синглтона
    }

    /**
     * Получить инстанс пула грузов (синглтон)
     */
    public static synchronized CargoPool getInstance() {
        if (instance == null) {
            instance = new CargoPool();
        }
        return instance;
    }

    /**
     * Инициализировать пул грузов начальным списком
     */
    public void initializePool(List<Cargo> cargos) {
        for (Cargo cargo : cargos) {
            availableCargos.put(cargo.getId(), cargo);
        }
        System.out.println("Пул грузов инициализирован с " + cargos.size() + " грузами");
    }

    /**
     * Получить список доступных грузов
     */
    public List<Cargo> getAvailableCargos() {
        List<Cargo> result = new ArrayList<>();
        for (Map.Entry<Integer, Cargo> entry : availableCargos.entrySet()) {
            if (!reservedCargos.contains(entry.getKey()) && !takenCargos.contains(entry.getKey())) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    /**
     * Попытаться зарезервировать груз
     * @return true, если успешно, false, если груз недоступен
     */
    public synchronized boolean reserveCargo(int cargoId) {
        if (availableCargos.containsKey(cargoId) &&
                !reservedCargos.contains(cargoId) &&
                !takenCargos.contains(cargoId)) {

            reservedCargos.add(cargoId);
            System.out.println("Груз " + cargoId + " зарезервирован");
            return true;
        }
        return false;
    }

    /**
     * Получить груз по ID (после успешного резервирования)
     */
    public synchronized Cargo takeCargo(int cargoId) {
        if (reservedCargos.contains(cargoId)) {
            reservedCargos.remove(cargoId);
            takenCargos.add(cargoId);
            Cargo cargo = availableCargos.get(cargoId);
            System.out.println("Груз " + cargoId + " взят из пула");
            return cargo;
        }
        return null;
    }

    /**
     * Отменить резервирование груза
     */
    public synchronized void cancelReservation(int cargoId) {
        reservedCargos.remove(cargoId);
        System.out.println("Резервирование груза " + cargoId + " отменено");
    }

    /**
     * Вернуть груз в пул (если он был взят)
     */
    public synchronized void returnCargo(Cargo cargo) {
        int cargoId = cargo.getId();
        if (takenCargos.contains(cargoId)) {
            takenCargos.remove(cargoId);
            System.out.println("Груз " + cargoId + " возвращен в пул");
        }
    }

    /**
     * Добавить новый груз в пул
     */
    public synchronized void addCargo(Cargo cargo) {
        availableCargos.put(cargo.getId(), cargo);
        System.out.println("Груз " + cargo.getId() + " добавлен в пул");
    }

    /**
     * Получить количество доступных грузов
     */
    public int getAvailableCargoCount() {
        int count = 0;
        for (Integer id : availableCargos.keySet()) {
            if (!reservedCargos.contains(id) && !takenCargos.contains(id)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Получить общее количество грузов в пуле (включая зарезервированные и взятые)
     */
    public int getTotalCargoCount() {
        return availableCargos.size();
    }
}