package model;

import java.io.Serializable;
import java.util.List;

public class Cargo implements Serializable {
    private int id;
    private String type;
    private float weight;
    private List<String> incompatibleTypes;

    public Cargo(int id, String type, float weight, List<String> incompatibleTypes) {
        this.id = id;
        this.type = type;
        this.weight = weight;
        this.incompatibleTypes = incompatibleTypes;
    }

    public int getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public float getWeight() {
        return weight;
    }

    public List<String> getIncompatibleTypes() {
        return incompatibleTypes;
    }

    @Override
    public String toString() {
        return "Cargo " + id + " (Type: " + type + ", Weight: " + weight + ")";
    }
}
