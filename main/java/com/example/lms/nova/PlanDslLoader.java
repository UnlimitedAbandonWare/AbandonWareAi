package com.example.lms.nova;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Component("novaPlanDslLoader")
public class PlanDslLoader {
    public BravePlan load(String id) {
        if (id == null || id.isBlank()) {
            return disabled();
        }
        String path = "plans/" + id + ".yaml";
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            Map<?, ?> y = map(new Yaml().load(in));
            if (y.isEmpty()) {
                return disabled();
            }
            Map<?, ?> retrieval = map(y.get("retrieval"));
            Map<?, ?> k = map(retrieval.get("k"));
            Map<?, ?> burst = map(y.get("burst"));
            Map<?, ?> gates = map(y.get("gates"));
            Map<?, ?> properties = map(nested(y, "plan", "overrides", "properties"));
            Map<?, ?> knobs = map(nested(y, "plan", "overrides", "knobs"));

            int web = intValue(k.get("web"), intValue(properties.get("naver.search.web-top-k"), 12));
            int vector = intValue(k.get("vector"), 8);
            int kg = intValue(k.get("kg"), 4);
            int queryBurstCount = intValue(knobs.get("expand.queryBurst.count"), 0);
            boolean burstEnabled = boolValue(burst.get("enabled"), false)
                    || boolValue(knobs.get("extremeZ.enabled"), false)
                    || queryBurstCount > 0;
            int bmin = 0, bmax = 0;
            if (burst != null && burst.containsKey("subqueries")) {
                Map<?, ?> sub = map(burst.get("subqueries"));
                bmin = intValue(sub.get("min"), 0);
                bmax = intValue(sub.get("max"), 0);
            }
            if (bmax <= 0 && queryBurstCount > 0) {
                bmax = queryBurstCount;
                bmin = Math.min(3, queryBurstCount);
            }
            int minCitations = intValue(gates.get("minCitations"),
                    intValue(gates.get("citationMin"), intValue(properties.get("gate.citation.min"), 2)));
            String order = orderValue(retrieval.get("order"));
            String tier = stringValue(gates.get("authorityTier"), "trusted");

            return new BravePlan(true, web, vector, kg, burstEnabled, bmin, bmax, minCitations, order, tier);
        } catch (IOException | YAMLException e) {
            String type = SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown");
            TraceStore.put("nova.planDsl.suppressed.load", true);
            TraceStore.put("nova.planDsl.suppressed.load.errorType", type);
            TraceStore.put("nova.planDsl.suppressed.stage", "load");
            TraceStore.put("nova.planDsl.suppressed.errorType", type);
            return disabled();
        }
    }

    private static BravePlan disabled() {
        return new BravePlan(false, 0, 0, 0, false, 0, 0, 0, "", "");
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> m ? m : Map.of();
    }

    private static Object nested(Map<?, ?> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            Map<?, ?> currentMap = map(current);
            if (currentMap.isEmpty()) {
                return null;
            }
            current = currentMap.get(key);
        }
        return current;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            TraceStore.put("nova.planDsl.suppressed.intValue", true);
            TraceStore.put("nova.planDsl.suppressed.intValue.errorType", errorType(e));
            TraceStore.put("nova.planDsl.suppressed.stage", "intValue");
            TraceStore.put("nova.planDsl.suppressed.errorType", errorType(e));
            return fallback;
        }
    }

    private static String errorType(Throwable e) {
        if (e instanceof NumberFormatException) {
            return "invalid_number";
        }
        return SafeRedactor.traceLabelOrFallback(e == null ? null : e.getClass().getSimpleName(), "unknown");
    }

    private static boolean boolValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : Boolean.parseBoolean(text);
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static String orderValue(Object value) {
        if (value instanceof Map<?, ?> order) {
            Object stages = order.get("stages");
            if (stages instanceof List<?> list && !list.isEmpty()) {
                return String.join("_then_", list.stream().map(String::valueOf).toList());
            }
        }
        return stringValue(value, "web_then_vector_then_kg");
    }
}
