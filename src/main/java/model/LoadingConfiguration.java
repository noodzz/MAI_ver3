package model;

import java.io.Serializable;
import java.util.List;

public class LoadingConfiguration implements Serializable {
    private List<Truck> trucks;
    private List<Cargo> cargos;
    private float idealLoadPercentage;

    public List<Truck> getTrucks() {
        return trucks;
    }

    public void setTrucks(List<Truck> trucks) {
        this.trucks = trucks;
    }

    public List<Cargo> getCargos() {
        return cargos;
    }

    public void setCargos(List<Cargo> cargos) {
        this.cargos = cargos;
    }

    public float getIdealLoadPercentage() {
        return idealLoadPercentage;
    }

    public void setIdealLoadPercentage(float idealLoadPercentage) {
        this.idealLoadPercentage = idealLoadPercentage;
    }
}