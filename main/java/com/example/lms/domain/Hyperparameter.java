package com.example.lms.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Hyperparameter {
    @Id
    private String paramKey;

    private double paramValue;

    private String description;

    public Hyperparameter() {
    }

    public Hyperparameter(String paramKey, double paramValue, String description) {
        this.paramKey = paramKey;
        this.paramValue = paramValue;
        this.description = description;
    }

    public String getParamKey() { return paramKey; }
    public void setParamKey(String paramKey) { this.paramKey = paramKey; }

    public double getParamValue() { return paramValue; }
    public void setParamValue(double paramValue) { this.paramValue = paramValue; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
