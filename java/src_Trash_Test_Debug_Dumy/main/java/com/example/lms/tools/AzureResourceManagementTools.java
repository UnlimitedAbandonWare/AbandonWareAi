package com.example.lms.tools;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;

/**
 * A minimal Azure Resource Manager tool exposed as a LangChain4j agent tool.
 *
 * <p>This component wraps potential Azure Resource Manager API calls but
 * deliberately returns static stub responses in the current offline mode.
 * It reads configuration values from application properties using optional
 * values and will return "unconfigured" when required values are missing.
 * When configured, the methods return a placeholder string indicating that
 * ARM integration is disabled in this offline environment.</p>
 */
@Component
public class AzureResourceManagementTools {

    @Value("${azure.subscription-id:}")
    String subscriptionId;

    @Value("${azure.arm.token:}")
    String bearerToken;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * List resource groups in a subscription. Returns a stub response offline.
     *
     * @return resource group list or "unconfigured"
     */
    @Tool("구독의 리소스 그룹 목록 조회")
    public String listResourceGroups() {
        if (bearerToken == null || bearerToken.isBlank()
                || subscriptionId == null || subscriptionId.isBlank()) {
            return "unconfigured";
        }
        // Offline mode: return disabled placeholder.
        return "disabled_offline";
    }

    /**
     * List virtual machines in the given resource group. Returns a stub response offline.
     *
     * @param rg resource group name
     * @return VM list or "unconfigured"
     */
    @Tool("리소스 그룹의 VM 목록 조회")
    public String listVirtualMachines(String rg) {
        if (bearerToken == null || bearerToken.isBlank()
                || subscriptionId == null || subscriptionId.isBlank()) {
            return "unconfigured";
        }
        return "disabled_offline";
    }
}
