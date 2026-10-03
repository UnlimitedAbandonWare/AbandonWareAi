package com.example.lms.routing;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Boots a secret-safe inventory of api-routing.yaml env names (present/absent only).
 */
@Component
public class ApiRoutingInventoryLogger {

    private static final System.Logger LOG = System.getLogger(ApiRoutingInventoryLogger.class.getName());

    private final Environment env;
    private final ResourceLoader resourceLoader;
    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;

    public ApiRoutingInventoryLogger(Environment env, ResourceLoader resourceLoader) {
        this(env, resourceLoader, null);
    }

    @Autowired
    public ApiRoutingInventoryLogger(Environment env, ResourceLoader resourceLoader,
            ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        this.env = env;
        this.resourceLoader = resourceLoader;
        this.debugEventStoreProvider = debugEventStoreProvider;
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
                present.add(name);
            } else {
                absent.add(name);
            }
        }
        LOG.log(
                System.Logger.Level.INFO,
                "[AWX][api-route] inventory present={0} absent={1}",
                present,
                absent);
        emitCredentialResolved(!present.isEmpty(), names.size(), present.size(), absent.size());
    }

    /**
     * The boot inventory is a credential-presence fact, not a route decision.
     */
    private void emitCredentialResolved(boolean anyPresent, int parsedCount, int presentCount, int absentCount) {
        ApiRoutingDebug.credential(
                "boot",
                "inventory",
                "classpath:configs/api-routing.yaml",
                anyPresent,
                "api-routing.yaml");
        try {
            DebugEventStore store = debugEventStoreProvider == null ? null : debugEventStoreProvider.getIfAvailable();
            if (store == null) {
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("purpose", "boot");
            data.put("provider", "inventory");
            data.put("endpointClass", "classpath:configs/api-routing.yaml");
            data.put("keyPresent", anyPresent);
            data.put("keySource", "api-routing.yaml");
            data.put("parsedCount", parsedCount);
            data.put("presentCount", presentCount);
            data.put("absentCount", absentCount);
            store.emit(
                    DebugProbeType.MODEL_GUARD,
                    DebugEventLevel.INFO,
                    "api.credential.resolved.inventory",
                    "[AWX][api-route] inventory resolved (credential presence only)",
                    "api.credential.resolved",
                    data,
                    null);
        } catch (RuntimeException ex) {
            TraceStore.put("apiRoutingInventory.debugEvent.suppressed.stage", "credential.emit");
        }
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
                parseEnvNames(reader, names);
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

    /**
     * Collects names declared in {@code env:} arrays, both inline
     * ({@code env: [A, B]}) and block ({@code env:} followed by indented
     * {@code - NAME} items) YAML forms. Only env names are kept; neighbouring
     * route keys and list entries are ignored.
     */
    static void parseEnvNames(BufferedReader reader, Set<String> names) throws java.io.IOException {
        String line;
        int envBlockIndent = -1;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = leadingWhitespace(line);
            if (envBlockIndent >= 0) {
                if (trimmed.startsWith("-") && indent > envBlockIndent) {
                    String token = trimmed.substring(1).trim();
                    if (isEnvName(token)) {
                        names.add(token);
                    }
                    continue;
                }
                envBlockIndent = -1;
            }
            String key = trimmed.startsWith("- ") ? trimmed.substring(2).trim() : trimmed;
            if (!key.startsWith("env:")) {
                continue;
            }
            String after = stripComment(key.substring(4)).trim();
            if (after.startsWith("[") && after.endsWith("]")) {
                for (String token : after.substring(1, after.length() - 1).split(",")) {
                    String t = unquote(token.trim());
                    if (isEnvName(t)) {
                        names.add(t);
                    }
                }
            } else if (after.isEmpty()) {
                envBlockIndent = indent;
            }
        }
    }

    private static int leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static String stripComment(String value) {
        int hash = value.indexOf('#');
        return hash < 0 ? value : value.substring(0, hash);
    }

    private static String unquote(String token) {
        if (token.length() >= 2 && ((token.startsWith("\"") && token.endsWith("\""))
                || (token.startsWith("'") && token.endsWith("'")))) {
            return token.substring(1, token.length() - 1);
        }
        return token;
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
