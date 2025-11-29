package com.example.lms.probe;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "probe")
public class ProbeProperties {
    private Search search = new Search();
    private String adminToken;

    public Search getSearch() { return search; }
    public void setSearch(Search search) { this.search = search; }

    public String getAdminToken() { return adminToken; }
    public void setAdminToken(String adminToken) { this.adminToken = adminToken; }

    public static class Search {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}