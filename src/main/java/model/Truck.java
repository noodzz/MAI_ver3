package model;

import java.io.Serializable;
import java.util.*;

public class Truck implements Serializable {
    private int id;
    private float capacity;
    private double currentLoad;
    private List<Cargo> loadedCargos;
    private Set<String> loadedCargoTypes;
    private Set<String> incompatibleCargoTypes = new HashSet<>(); // Типы грузов, которые нельзя перевозить
    private Map<String, Set<String>> typeIncompatibilityMap = new HashMap<>();


    public Truck(int id, float capacity) {
        this.id = id;
        this.capacity = capacity;
        this.loadedCargos = new ArrayList<>();
        this.loadedCargoTypes = new HashSet<>();
    }

    public int getId() {
        return id;
    }
    public void addIncompatibleCargoType(String type) {
        incompatibleCargoTypes.add(type);
    }
    public float getCapacity() {
        return capacity;
    }

    public List<Cargo> getLoadedCargos() {
        return new ArrayList<>(loadedCargos);
    }
    public boolean canCarryCargo(Cargo cargo) {
        return !incompatibleCargoTypes.contains(cargo.getType());
    }
    public boolean canAddCargo(Cargo cargo) {

        // Check capacity
        if (getCurrentLoad() + cargo.getWeight() > capacity) {
            System.out.println("[DEBUG] Груз " + cargo.getId() + " не помещается в грузовик " + id);
            return false;
        }

        // Check compatibility
        for (String loadedType : loadedCargoTypes) {
            if (cargo.getIncompatibleTypes().contains(loadedType)) {
                System.out.println("[DEBUG] Груз " + cargo.getId() + " конфликтует с типом " + loadedType);
                return false;
            }
        }

        // Check if current cargo types are incompatible with new cargo
        for (String incompatibleType : cargo.getIncompatibleTypes()) {
            if (loadedCargoTypes.contains(incompatibleType)) {
                return false;
            }
        }

        return true;
    }

    public void addCargo(Cargo cargo) {
        if (canAddCargo(cargo)) {
            loadedCargos.add(cargo);
            loadedCargoTypes.add(cargo.getType());

            // Регистрируем несовместимые типы
            for (String incompatibleType : cargo.getIncompatibleTypes()) {
                registerTypeIncompatibility(cargo.getType(), incompatibleType);
            }

            System.out.println("[DEBUG] Груз " + cargo.getId() + " добавлен в грузовик " + getId());
        } else {
            System.out.println("[DEBUG] Груз " + cargo.getId() + " не добавлен в грузовик " + getId());
        }
    }

    public void registerTypeIncompatibility(String type, String incompatibleType) {
        typeIncompatibilityMap.computeIfAbsent(type, k -> new HashSet<>()).add(incompatibleType);
        typeIncompatibilityMap.computeIfAbsent(incompatibleType, k -> new HashSet<>()).add(type);
    }
    public float getCurrentLoad() {
        float total = 0;
        for (Cargo cargo : loadedCargos) {
            total += cargo.getWeight();
        }
        System.out.println("[DEBUG] Текущая загрузка грузовика " + id + ": " + total); // Логирование
        return total;
    }
    /**
     * Проверяет совместимость переданных типов грузов с текущими грузами в грузовике
     * @param cargoTypes массив строк с типами грузов для проверки
     * @return true, если все типы совместимы, false в противном случае
     */
    public boolean areCargoTypesCompatible(String[] cargoTypes) {
        // Если нет типов для проверки, считаем совместимыми
        if (cargoTypes == null || cargoTypes.length == 0) {
            return true;
        }

        // Если нет загруженных грузов, любые типы совместимы
        if (loadedCargoTypes.isEmpty()) {
            return true;
        }

        // Проверяем каждый переданный тип на совместимость
        for (String newType : cargoTypes) {
            if (newType == null || newType.isEmpty()) continue;

            // Проверяем совместимость с уже загруженными типами
            for (String loadedType : loadedCargoTypes) {
                // Этот простой подход проверяет только несовместимые типы грузовика
                if (incompatibleCargoTypes.contains(newType)) {
                    return false;
                }
            }
        }
        return true;
    }
    public float getLoadPercentage() {
        return (getCurrentLoad() / capacity) * 100;
    }
    public void removeCargo(Cargo cargo) {
        loadedCargos.remove(cargo);
        updateLoadedTypes();

    }
    private void updateLoadedTypes() {
        loadedCargoTypes.clear();
        for (Cargo c : loadedCargos) {
            loadedCargoTypes.add(c.getType());
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Truck ID: ").append(id).append("\n");
        sb.append("Capacity: ").append(capacity).append("\n");
        sb.append("Current Load: ").append(getCurrentLoad()).append(" (").append(getLoadPercentage()).append("%)\n");
        sb.append("Loaded Cargo Types: ").append(loadedCargoTypes).append("\n");
        sb.append("Cargos: \n");

        for (Cargo cargo : loadedCargos) {
            sb.append("  - ").append(cargo).append("\n");
        }

        return sb.toString();
    }

}
