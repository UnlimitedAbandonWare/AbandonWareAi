package com.example.lms.azure.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A single entry in the Azure free tier catalogue.  Each service item
 * describes the category (e.g. 컴퓨팅), the service name, the eligible VM
 * tiers, the quota and any additional notes.  Keywords are preserved for
 * future filtering or indexing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ServiceItem {
    private String category;
    private String serviceName;
    private List<String> tiers;
    private Quota quota;
    private String notes;
    private List<String> keywords;

    public ServiceItem() {
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public List<String> getTiers() {
        return tiers;
    }

    public void setTiers(List<String> tiers) {
        this.tiers = tiers;
    }

    public Quota getQuota() {
        return quota;
    }

    public void setQuota(Quota quota) {
        this.quota = quota;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }
}