package com.abandonware.ai.agent.integrations;

import java.net.URI;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * CanonicalUrlNormalizer
 *  - 추적용 쿼리파라미터(utm_*, fbclid) 제거
 *  - fragment 제거
 */
public final class CanonicalUrlNormalizer {
  private CanonicalUrlNormalizer() {}

  public static String canonical(String url) {
    if (url == null || url.isBlank()) return url;
    try {
      URI u = URI.create(url);
      String filteredQuery = null;
      if (u.getQuery() != null && !u.getQuery().isBlank()) {
        filteredQuery = Arrays.stream(u.getQuery().split("&"))
          .filter(p -> !p.startsWith("utm_") && !p.startsWith("fbclid"))
          .collect(Collectors.joining("&"));
        if (filteredQuery.isBlank()) filteredQuery = null;
      }
      return new URI(u.getScheme(), u.getAuthority(), u.getPath(), filteredQuery, null).toString();
    } catch (Exception e) {
      return url; // 실패 시 원본 유지 (Fail-soft)
    }
  }
}