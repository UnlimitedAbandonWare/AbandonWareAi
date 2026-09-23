package com.example.lms.service.rag.kg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.net.URI;
import java.util.Locale;

@ConfigurationProperties(prefix = "retrieval.kg.neo4j")
public class Neo4jKnowledgeGraphProperties {

    private static final Logger log = LoggerFactory.getLogger(Neo4jKnowledgeGraphProperties.class);

    static final String REASON_DISABLED = "disabled";
    static final String REASON_MISSING_URI = "missing_uri";
    static final String REASON_MISSING_USER = "missing_user";
    static final String REASON_MISSING_PASSWORD = "missing_password";
    static final String REASON_UNSAFE_DEFAULT_CREDENTIALS = "unsafe_default_credentials";
    static final String REASON_MISSING_PROPERTIES = "missing_properties";

    private boolean enabled = false;
    private String uri = "";
    private String user = "";
    private String password = "";
    private String database = "neo4j";
    private long timeoutMs = 1200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean hasPassword() {
        return !isUnsafeValue(password)
                && !("neo4j".equals(normalize(user)) && "neo4j".equals(normalize(password)));
    }

    public String endpointHost() {
        if (isUnsafeValue(uri)) {
            return "";
        }
        try {
            URI parsed = URI.create(uri.trim());
            String host = parsed.getHost();
            return host == null ? "" : host;
        } catch (Exception ignore) {
            TraceStore.put("retrieval.kg.neo4j.endpointHost.suppressed", true);
            TraceStore.put("retrieval.kg.neo4j.endpointHost.reason", "parse-failure");
            TraceStore.put("retrieval.kg.neo4j.endpointHost.errorType",
                    SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
            log.debug("[AWX][rag][kg] neo4j endpoint parse skipped stage=endpoint_host err=parse-failure"); return "";
        }
    }

    public String disabledReason() {
        if (!enabled) {
            return REASON_DISABLED;
        }
        if (isUnsafeValue(uri)) {
            return REASON_MISSING_URI;
        }
        if (isUnsafeValue(user)) {
            return REASON_MISSING_USER;
        }
        if (isUnsafeValue(password)) {
            return REASON_MISSING_PASSWORD;
        }
        if ("neo4j".equals(normalize(user)) && "neo4j".equals(normalize(password))) {
            return REASON_UNSAFE_DEFAULT_CREDENTIALS;
        }
        return null;
    }

    private static boolean isUnsafeValue(String value) {
        String normalized = normalize(value);
        return normalized.isEmpty()
                || normalized.startsWith("${")
                || normalized.equals("dummy")
                || normalized.equals("test")
                || normalized.equals("changeme")
                || normalized.equals("change-me")
                || normalized.equals("null")
                || normalized.equals("none")
                || normalized.equals("password")
                || normalized.equals("sk-local");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
