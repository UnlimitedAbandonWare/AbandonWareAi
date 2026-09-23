package com.nova.protocol.plan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Minimal classpath plan loader that avoids a hard YAML parser dependency.
 *
 * <p>The plan files in this module use a small subset of YAML: nested maps,
 * scalar values, and inline integer maps such as {@code k: { web: 8 }}. This
 * loader intentionally extracts only the fields represented by {@link Plan}.
 */
public class PlanLoader {
    private static final String PLAN_ROOT = "plans";
    private static final System.Logger LOG = System.getLogger(PlanLoader.class.getName());

    public PlanLoader() {}

    public Map<String,Object> loadAll(){
        Map<String, Object> out = new LinkedHashMap<>();
        for (String id : discoverPlanIds()) {
            Plan plan = loadFromClasspath(id);
            if (plan != null) {
                out.put(plan.getId(), toMap(plan));
            }
        }
        return out;
    }

    public Map<String,Object> get(String id){
        Plan plan = loadFromClasspath(id);
        return plan == null ? new LinkedHashMap<>() : toMap(plan);
    }

    public Plan loadFromClasspath(String id){
        List<String> candidates = resourceCandidates(id);
        if (candidates.isEmpty()) {
            return null;
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = PlanLoader.class.getClassLoader();
        }
        for (String resource : candidates) {
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) {
                    continue;
                }
                return parsePlan(stripPlanSuffix(Path.of(resource).getFileName().toString()), readLines(in));
            } catch (IOException ignored) {
                logSuppressed("classpathRead", ignored, resource.length());
                return null;
            }
        }
        return null;
    }

    private static List<String> readLines(InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static Plan parsePlan(String requestedId, List<String> lines) {
        Plan plan = new Plan();
        plan.setId(requestedId == null || requestedId.isBlank() ? "default" : requestedId);
        Map<String, Integer> kAllocation = new LinkedHashMap<>();
        Map<String, Integer> timeouts = new LinkedHashMap<>();
        Map<String, Object> burst = new LinkedHashMap<>();
        List<String> stack = new ArrayList<>();

        for (String rawLine : lines) {
            String withoutComment = stripComment(rawLine);
            if (withoutComment.isBlank()) {
                continue;
            }
            int indent = leadingSpaces(withoutComment) / 2;
            String line = withoutComment.trim();
            if (line.startsWith("- ")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String rawValue = line.substring(colon + 1).trim();
            if (key.isEmpty()) {
                continue;
            }

            while (stack.size() > indent) {
                stack.remove(stack.size() - 1);
            }
            if (rawValue.isEmpty()) {
                while (stack.size() > indent) {
                    stack.remove(stack.size() - 1);
                }
                stack.add(key);
                continue;
            }

            List<String> fullPath = new ArrayList<>(stack);
            fullPath.add(key);
            String path = String.join(".", fullPath);
            Object value = parseScalar(rawValue);

            if ("id".equals(path) || "plan.id".equals(path)) {
                plan.setId(String.valueOf(value));
            } else if ("gates.citationMin".equals(path)
                    || "gate.citation.min".equals(key)
                    || path.endsWith(".gate.citation.min")) {
                plan.setCitationMin(asInt(value, plan.getCitationMin()));
            } else if ("retrieval.k".equals(path) && rawValue.startsWith("{")) {
                kAllocation.putAll(parseInlineIntMap(rawValue));
            } else if (path.startsWith("retrieval.k.")) {
                kAllocation.put(key, asInt(value, 0));
            } else if (key.toLowerCase(java.util.Locale.ROOT).contains("timeout")) {
                timeouts.put(key, asInt(value, 0));
            }

            if (isBurstPath(path)) {
                burst.put(key, value);
            }
            if ("overdrive.enabled".equals(key) || path.endsWith(".overdrive.enabled")) {
                plan.setEnableOverdrive(asBoolean(value));
            }
        }

        if (!kAllocation.isEmpty()) {
            plan.setkAllocation(kAllocation);
        }
        if (!timeouts.isEmpty()) {
            plan.setTimeouts(timeouts);
        }
        if (!burst.isEmpty()) {
            plan.setBurst(burst);
        }
        return plan;
    }

    private static boolean isBurstPath(String path) {
        return path.startsWith("params.")
                || path.startsWith("plan.overrides.knobs.")
                || path.startsWith("burst.");
    }

    private static Map<String, Integer> parseInlineIntMap(String value) {
        Map<String, Integer> out = new LinkedHashMap<>();
        String body = value.trim();
        if (body.startsWith("{")) {
            body = body.substring(1);
        }
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1);
        }
        for (String part : body.split(",")) {
            int colon = part.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = part.substring(0, colon).trim();
            Object scalar = parseScalar(part.substring(colon + 1).trim());
            if (!key.isEmpty()) {
                out.put(key, asInt(scalar, 0));
            }
        }
        return out;
    }

    private static Object parseScalar(String value) {
        String v = value == null ? "" : value.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            return Boolean.parseBoolean(v);
        }
        try {
            if (!v.contains(".") && !v.contains(":")) {
                return Integer.parseInt(v);
            }
            if (!v.contains("${") && !v.contains(":")) {
                double parsed = Double.parseDouble(v);
                if (!Double.isFinite(parsed)) {
                    throw new NumberFormatException("non-finite");
                }
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            logSuppressed("scalarParse", ignored, v.length());
            return v;
        }
        return v;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) {
                logSuppressed("intParse", new NumberFormatException("non-finite"), String.valueOf(value).length());
                return fallback;
            }
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            logSuppressed("intParse", ignored, String.valueOf(value).length());
            return fallback;
        }
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static String stripComment(String line) {
        if (line == null) {
            return "";
        }
        int idx = line.indexOf('#');
        return idx < 0 ? line : line.substring(0, idx);
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static List<String> resourceCandidates(String id) {
        if (id == null || id.isBlank()) {
            return List.of();
        }
        String normalized = id.trim().replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith(PLAN_ROOT + "/")) {
            return List.of(normalized);
        }
        if (normalized.endsWith(".yaml") || normalized.endsWith(".yml")) {
            return List.of(PLAN_ROOT + "/" + normalized);
        }
        return List.of(PLAN_ROOT + "/" + normalized + ".yaml", PLAN_ROOT + "/" + normalized + ".yml");
    }

    private static String stripPlanSuffix(String fileName) {
        if (fileName == null) {
            return null;
        }
        if (fileName.endsWith(".yaml")) {
            return fileName.substring(0, fileName.length() - ".yaml".length());
        }
        if (fileName.endsWith(".yml")) {
            return fileName.substring(0, fileName.length() - ".yml".length());
        }
        return fileName;
    }

    private static TreeSet<String> discoverPlanIds() {
        TreeSet<String> ids = new TreeSet<>();
        ClassLoader loader = PlanLoader.class.getClassLoader();
        URL url = loader.getResource(PLAN_ROOT);
        if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) {
            return ids;
        }
        try (var stream = Files.list(Path.of(url.toURI()))) {
            stream.map(Path::getFileName)
                    .filter(Objects::nonNull)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(".yaml") || name.endsWith(".yml"))
                    .map(PlanLoader::stripPlanSuffix)
                    .forEach(ids::add);
        } catch (IOException | java.net.URISyntaxException | IllegalArgumentException | SecurityException ignored) {
            logSuppressed("discoverPlanIds", ignored, PLAN_ROOT.length());
            return ids;
        }
        return ids;
    }

    private static void logSuppressed(String stage, Exception failure, int valueLength) {
        LOG.log(System.Logger.Level.DEBUG, "Plan loader fallback stage={0} valueLength={1} errorType={2}",
                stage,
                Math.max(0, valueLength),
                errorType(failure));
    }

    private static String errorType(Exception failure) {
        if (failure == null) {
            return "unknown";
        }
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure.getClass().getSimpleName();
    }

    private static Map<String, Object> toMap(Plan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", plan.getId());
        out.put("citationMin", plan.getCitationMin());
        if (plan.getkAllocation() != null) {
            out.put("kAllocation", plan.getkAllocation());
        }
        if (plan.getTimeouts() != null) {
            out.put("timeouts", plan.getTimeouts());
        }
        if (plan.getBurst() != null) {
            out.put("burst", plan.getBurst());
        }
        out.put("enableOverdrive", plan.isEnableOverdrive());
        return out;
    }

}
