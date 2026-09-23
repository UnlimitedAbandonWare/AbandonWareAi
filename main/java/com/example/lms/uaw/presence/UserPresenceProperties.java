package com.example.lms.uaw.presence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Defines how to classify "user traffic" and how long the server must be quiet
 * before we consider the user absent.
 */
@ConfigurationProperties(prefix = "uaw.presence")
public class UserPresenceProperties {

    /** Quiet time (seconds) after the last user request finished. */
    private int quietSeconds = 120;

    /** Include path patterns (Ant style). Exclude patterns win over includes. */
    private List<String> includePaths = List.of("/api/**");

    /** Exclude path patterns (health checks, docs, static files, etc.). */
    private List<String> excludePaths = List.of(
            "/actuator/**",
            "/health/**",
            "/metrics/**",
            "/prometheus/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/favicon.ico"
    );

    /**
     * 어떤 HTTP 메서드를 "사용자 트래픽"으로 판단할지.
     *
     * 기본값은 "상태 변경" 메서드(POST/PUT/PATCH/DELETE)만 포함합니다.
     * GET/HEAD 같은 조회 요청(폴링, SSE, 헬스체크 대체 호출 등)이
     * 사용자 활동으로 잡혀 autolearn이 영원히 열리지 않는 문제를 완화합니다.
     */
    private List<String> includeMethods = List.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * includeMethods로 포함되어 있더라도, 여기 나열된 메서드는 사용자 트래픽에서 제외합니다.
     * (기본은 빈 목록)
     */
    private List<String> excludeMethods = List.of();

    public int getQuietSeconds() {
        return quietSeconds;
    }

    public void setQuietSeconds(int quietSeconds) {
        this.quietSeconds = quietSeconds;
    }

    public List<String> getIncludePaths() {
        return includePaths;
    }

    public void setIncludePaths(List<String> includePaths) {
        this.includePaths = includePaths;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }

    public List<String> getIncludeMethods() {
        return includeMethods;
    }

    public void setIncludeMethods(List<String> includeMethods) {
        this.includeMethods = includeMethods;
    }

    public List<String> getExcludeMethods() {
        return excludeMethods;
    }

    public void setExcludeMethods(List<String> excludeMethods) {
        this.excludeMethods = excludeMethods;
    }
}
