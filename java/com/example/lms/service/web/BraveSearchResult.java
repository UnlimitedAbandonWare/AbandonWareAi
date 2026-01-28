package com.example.lms.service.web;

import java.util.List;

/**
 * Brave 검색 결과 + 메타 정보.
 * 상위 오케스트레이터가 "0건" 원인을 구분할 수 있게 실패 사유를 포함한다.
 */
public record BraveSearchResult(
        List<String> snippets,
        Status status,
        Integer httpStatus,
        long cooldownMs,
        String message,
        long elapsedMs
) {

    public enum Status {
        OK,
        DISABLED,
        COOLDOWN,
        RATE_LIMIT_LOCAL,
        HTTP_429,
        HTTP_503,
        HTTP_ERROR,
        EXCEPTION
    }

    public static BraveSearchResult disabled(long elapsedMs) {
        return new BraveSearchResult(List.of(), Status.DISABLED, null, 0L, "brave disabled", elapsedMs);
    }

    public static BraveSearchResult cooldown(long cooldownMs, long elapsedMs) {
        return new BraveSearchResult(List.of(), Status.COOLDOWN, null, cooldownMs, "brave cooldown", elapsedMs);
    }

    public static BraveSearchResult ok(List<String> snippets, long elapsedMs) {
        return new BraveSearchResult(snippets == null ? List.of() : snippets, Status.OK, 200, 0L, null, elapsedMs);
    }
}
