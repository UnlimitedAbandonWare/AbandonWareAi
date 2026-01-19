package com.example.lms.service.routing.plan;

import com.abandonware.ai.agent.integrations.TextUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default evidence slice policy:
 * <ul>
 * <li>{@code stage + normalized input}</li>
 * <li>sorted {@code key=value} pairs of caller-provided attributes</li>
 * </ul>
 */
@Component
public class DefaultEvidenceSlicePolicy implements EvidenceSlicePolicy {

    @Override
    public String fingerprint(String stage, String input, Map<String, Object> attributes) {
        String st = Objects.toString(stage, "").trim();
        String in = Objects.toString(input, "").trim();

        List<Map.Entry<String, Object>> entries = new ArrayList<>(
                attributes == null ? Map.<String, Object>of().entrySet() : attributes.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));

        StringBuilder sb = new StringBuilder(256);
        sb.append("stage=").append(st).append('\n');
        sb.append("input=").append(in).append('\n');

        for (Map.Entry<String, Object> e : entries) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k == null || k.isBlank()) {
                continue;
            }
            if (v == null) {
                continue;
            }
            sb.append(k).append('=').append(stringify(v)).append('\n');
        }

        return TextUtils.sha1(sb.toString());
    }

    private static String stringify(Object v) {
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                if (o == null) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append(',');
                }
                sb.append(o);
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }
}
