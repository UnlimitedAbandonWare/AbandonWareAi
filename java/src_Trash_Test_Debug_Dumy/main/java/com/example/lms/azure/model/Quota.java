package com.example.lms.azure.model;

/**
 * Represents a service quota for the Azure free tier catalogue.  A quota
 * consists of a unit (e.g. hours/month) and a numeric value.  Additional
 * fields may be added in the future when the catalogue schema evolves.
 */
public class Quota {
    private String unit;
    private double value;

    public Quota() {
    }

    public Quota(String unit, double value) {
        this.unit = unit;
        this.value = value;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }
}