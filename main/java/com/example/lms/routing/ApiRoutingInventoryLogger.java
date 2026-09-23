package com.example.lms.routing;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Boots a secret-safe inventory of api-routing.yaml env names (present/absent only).
 */
@Component
public class ApiRoutingInventoryLogger {

    private static final System.Logger LOG = System.getLogger(ApiRoutingInventoryLogger.class.getName());

    private final Environment env;
    private final ResourceLoader resourceLoader;

    public ApiRoutingInventoryLogger(Environment env, ResourceLoader resourceLoader) {
        this.env = env;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void logInventory() {
        Set<String> names = loadEnvNames();
        if (names.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "[AWX][api-route] inventory skipped (no env names parsed from api-routing.yaml)");
            return;
        }
        List<String> present = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        for (String name : names) {
            String value = env.getProperty(name);
            if (ApiRoutingDebug.keyPresent(value)) {
                present.add(name + "(len=" + value.trim().length() + ")");
            } else {
                absent.add(name);
            }
        }
        LOG.log(
                System.Logger.Level.INFO,
                "[AWX][api-route] inventory present={0} absent={1}",
                present,
                absent);
        ApiRoutingDebug.decision(
                "boot",
                "inventory",
                "n/a",
                "classpath:configs/api-routing.yaml",
                !present.isEmpty(),
                "api-routing.yaml");
    }

    private Set<String> loadEnvNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String location : List.of(
                "classpath:configs/api-routing.yaml",
                "file:configs/api-routing.yaml",
                "file:./configs/api-routing.yaml")) {
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("- ") && trimmed.length() > 2) {
                        String token = trimmed.substring(2).trim();
                        if (isEnvName(token)) {
                            names.add(token);
                        }
                    }
                }
            } catch (Exception ex) {
                LOG.log(System.Logger.Level.WARNING,
                        "[AWX][api-route] inventory parse failed location={0} errorType={1}",
                        location,
                        ex.getClass().getSimpleName());
            }
            if (!names.isEmpty()) {
                break;
            }
        }
        return names;
    }

    private static boolean isEnvName(String token) {
        if (token == null || token.length() < 3) {
            return false;
        }
        if (token.contains(":") || token.contains("/") || token.contains(" ")) {
            return false;
        }
        String upper = token.toUpperCase(Locale.ROOT);
        return token.equals(upper) && token.chars().allMatch(c -> c == '_' || Character.isLetterOrDigit(c));
    }
}
