package com.example.lms.util;

import dev.langchain4j.data.document.Metadata;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

public final class MetadataUtils {

    private MetadataUtils() {}       // util class‑방지용 private 생성자

    /* 메타데이터 → Map 변환 (LangChain4j v0.27 ‑ v0.30 호환) */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object meta) {
        try {
            Method m = meta.getClass().getMethod("asMap");
            return (Map<String, Object>) m.invoke(meta);
        } catch (NoSuchMethodException e) {
            try {
                Method m = meta.getClass().getMethod("map");
                return (Map<String, Object>) m.invoke(meta);
            } catch (Exception ignored) { }
        } catch (Exception ignored) { }
        return Map.of();
    }

    /* 타입‑안전한 put */
    public static void putTyped(Metadata md, String key, Object val) {
        if (md == null || key == null || val == null) return;
        if (val instanceof String s)        md.put(key, s);
        else if (val instanceof Integer i)  md.put(key, i);
        else if (val instanceof Long l)     md.put(key, l);
        else if (val instanceof Double d)   md.put(key, d);
        else if (val instanceof Float f)    md.put(key, f);
        else if (val instanceof UUID u)     md.put(key, u);
        else if (val instanceof Boolean b)  md.put(key, b.toString());
        else if (val instanceof Number n)   md.put(key, n.doubleValue());
        else                                md.put(key, String.valueOf(val));
    }

    /* 메타데이터 복사 */
    public static void copyMetadata(Metadata src, Metadata dst) {
        if (src == null || dst == null) return;
        Map<String, Object> m = toMap(src);
        if (m.isEmpty()) return;
        m.forEach((k, v) -> putTyped(dst, k, v));
    }
}
