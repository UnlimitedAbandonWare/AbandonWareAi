package com.example.lms.azure.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Represents the top‑level Azure free tier catalogue loaded from JSON.  The
 * {@code items} list contains all service entries.  Unknown fields are
 * ignored to provide forward‑compatibility with future schema changes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AzureFreeTierCatalog {
    private List<ServiceItem> items;

    public AzureFreeTierCatalog() {
    }

    public List<ServiceItem> getItems() {
        return items;
    }

    public void setItems(List<ServiceItem> items) {
        this.items = items;
    }
}