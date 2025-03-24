package model;

import java.io.Serializable;
import java.util.List;

public class LoadingConfiguration implements Serializable {
    private List<Truck> trucks;
    private List<Cargo> cargos;
    private float idealLoadPercentage;
    private boolean useDynamicIdealLoad;

    public List<Truck> getTrucks() {
        return trucks;
    }

    public void setTrucks(List<Truck> trucks) {
        this.trucks = trucks;
    }
    public boolean isUseDynamicIdealLoad() {
        return useDynamicIdealLoad;
    }

    public void setUseDynamicIdealLoad(boolean useDynamicIdealLoad) {
        this.useDynamicIdealLoad = useDynamicIdealLoad;
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