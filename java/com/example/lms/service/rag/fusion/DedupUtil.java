package com.example.lms.service.rag.fusion;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 검색 결과 중복 제거 유틸리티.
 *
 * <p>
 * - 신규 코드는 {@link Dedupable} 인터페이스와 {@link #dedup(List)} 를 사용하는 것을 권장합니다.<br>
 * - 기존 URL 기반 API {@link #dedupByCanonicalUrl(List)} 도 유지하되,
 *   리플렉션 의존성을 완화하고 타입 안전성을 보완했습니다.
 * </p>
 */
public final class DedupUtil {

    private DedupUtil() {
    }

    /**
     * 타입 안전한 내용 기반 Dedup.
     *
     * <p>각 항목의 {@link Dedupable#getDeduplicationKey()} 값이 동일하면
     * 중복으로 간주하고 최초 항목만 유지합니다.</p>
     */
    public static <T extends Dedupable> List<T> dedup(List<T> items) {
        if (items == null || items.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        Map<String, T> map = new LinkedHashMap<>();
        for (T item : items) {
            if (item == null) {
                continue;
            }
            String key = item.getDeduplicationKey();
            if (key == null || key.isBlank()) {
                // null 또는 빈 키는 항상 고유 항목으로 취급
                key = "__NULL__#" + System.identityHashCode(item);
            }
            map.putIfAbsent(key, item);
        }
        return new ArrayList<>(map.values());
    }

    /**
     * 레거시 API - URL 기반 중복 제거.
     *
     * <p>가능하다면 {@link #dedup(List)} 를 사용하는 것을 권장합니다.
     * 이 메서드는 여전히 일부 코드에서 사용될 수 있어 남겨 두되,
     * {@link Dedupable} 을 우선 사용하고, 마지막 수단으로만 리플렉션을 사용합니다.</p>
     */
    public static <T> List<T> dedupByCanonicalUrl(List<T> in) {
        if (in == null || in.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        Map<String, T> m = new LinkedHashMap<>();
        for (T d : in) {
            if (d == null) continue;

            String key = null;

            // 1) Dedupable 구현체라면 그 키를 우선 사용
            if (d instanceof Dedupable dedupable) {
                key = dedupable.getDeduplicationKey();
            }

            // 2) URL accessor를 가진 DTO인 경우에만 best-effort 리플렉션 사용
            if (key == null || key.isBlank()) {
                try {
                    var mtd = d.getClass().getMethod("getUrl");
                    Object o = mtd.invoke(d);
                    if (o instanceof String s && !s.isBlank()) {
                        key = canonicalKey(s);
                    }
                } catch (Exception ignored) {
                    // 실패하면 아래 identity 기반 키로 폴백
                }
            }

            // 3) 여전히 키가 없다면 객체 identity 기반으로 중복 제거
            if (key == null || key.isBlank()) {
                key = "__OBJ__#" + System.identityHashCode(d);
            }

            m.putIfAbsent(key, d);
        }
        return new ArrayList<>(m.values());
    }

    /**
     * URL을 도메인/경로 수준까지 정규화하여 Canonical Key를 생성합니다.
     * 쿼리 파라미터와 프래그먼트(#)는 제거합니다.
     */
    static String canonicalKey(String u) {
        if (u == null || u.isBlank()) return "";
        try {
            URI uri = URI.create(u);
            String scheme = uri.getScheme() != null
                    ? uri.getScheme().toLowerCase(java.util.Locale.ROOT)
                    : "";
            String host = uri.getHost() != null
                    ? uri.getHost().toLowerCase(java.util.Locale.ROOT)
                    : "";
            String path = uri.getPath() != null ? uri.getPath() : "";
            // 경로 끝의 슬래시는 통일성을 위해 제거 (루트 "/" 제외)
            if (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            return scheme + "://" + host + path;
        } catch (Exception e) {
            // 파싱 실패 시 원본 URL을 그대로 키로 사용
            return u;
        }
    }
}
