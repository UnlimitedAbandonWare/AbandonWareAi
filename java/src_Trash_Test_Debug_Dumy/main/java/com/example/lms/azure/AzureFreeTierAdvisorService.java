package com.example.lms.azure;

import com.example.lms.azure.model.AzureFreeTierCatalog;
import com.example.lms.azure.model.ServiceItem;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Optional;

/**
 * Advisor service for querying the Azure free tier catalogue.  On
 * startup this service loads the JSON catalogue from the classpath.  The
 * {@link #checkVmEligibility(String, String)} method checks whether a
 * particular OS and VM size combination is eligible for 12‑month free
 * usage.  {@link #getFreeTierInfo(String)} returns detailed information
 * for a given service name.  This component is self‑contained and does
 * not affect RAG functionality.
 */
@Service
public class AzureFreeTierAdvisorService {
    private static final Logger log = LoggerFactory.getLogger(AzureFreeTierAdvisorService.class);
    private AzureFreeTierCatalog catalog;

    @PostConstruct
    public void init() {
        try (InputStream is = AzureFreeTierAdvisorService.class
                .getResourceAsStream("/knowledge/azure/free_12m_ko.json")) {
            if (is != null) {
                ObjectMapper mapper = new ObjectMapper();
                this.catalog = mapper.readValue(is, AzureFreeTierCatalog.class);
            } else {
                log.warn("[AzureFreeTier] free_12m_ko.json not found");
            }
        } catch (Exception e) {
            log.warn("[AzureFreeTier] failed to load catalog: {}", e.toString());
            this.catalog = null;
        }
    }

    /**
     * Check if a VM of the given OS type and size is covered by the Azure
     * 12‑month free tier.  The OS type should be "Linux" or "Windows" and the
     * size should match one of the tiers in the catalogue.
     *
     * @param osType the operating system type (case‑insensitive)
     * @param vmSize the VM size (case‑sensitive)
     * @return an optional containing the matching service or empty if none match
     */
    public Optional<ServiceItem> checkVmEligibility(String osType, String vmSize) {
        if (catalog == null || osType == null || vmSize == null) {
            return Optional.empty();
        }
        String serviceName;
        String t = osType.trim().toLowerCase();
        if ("linux".equals(t)) {
            serviceName = "Linux 가상 머신";
        } else if ("windows".equals(t)) {
            serviceName = "Windows 가상 머신";
        } else {
            return Optional.empty();
        }
        return catalog.getItems().stream()
                .filter(item -> item.getServiceName() != null && item.getServiceName().equalsIgnoreCase(serviceName))
                .filter(item -> item.getTiers() != null && item.getTiers().contains(vmSize))
                .findFirst();
    }

    /**
     * Retrieve the free tier information for the specified service name.  The
     * name match is case‑insensitive.
     *
     * @param serviceName the name of the service
     * @return an optional containing the service item or empty if not found
     */
    public Optional<ServiceItem> getFreeTierInfo(String serviceName) {
        if (catalog == null || serviceName == null) {
            return Optional.empty();
        }
        return catalog.getItems().stream()
                .filter(item -> item.getServiceName() != null && item.getServiceName().equalsIgnoreCase(serviceName))
                .findFirst();
    }
}