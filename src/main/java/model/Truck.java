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
            System.out.println("[DEBUG] Груз " + cargo.getId() + " добавлен в грузовик " + getId());
        } else {
            System.out.println("[DEBUG] Груз " + cargo.getId() + " не добавлен в грузовик " + getId());
        }
    }


    public float getCurrentLoad() {
        float total = 0;
        for (Cargo cargo : loadedCargos) {
            total += cargo.getWeight();
        }
        System.out.println("[DEBUG] Текущая загрузка грузовика " + id + ": " + total); // Логирование
        return total;
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
