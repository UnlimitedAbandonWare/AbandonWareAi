package com.example.lms.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.util.Locale;

/**
 * 로컬 전용 디버그 스튜디오: 면접/디스플레이 정적 화면을 /debug/* 아래로
 * forward해서 로컬 테스트 브라우저가 전체 사이트를 덮지 않고 쓸 수 있게 한다.
 * 비-loopback(직접 피어나 Host 기준)에는 404 — 공개 표면이 아니다.
 */
@Controller
@ConditionalOnProperty(name = "debug.studio.enabled", havingValue = "true")
public class DebugStudioController {

    @GetMapping("/debug/studio")
    public String studio(HttpServletRequest request) {
        requireLoopback(request);
        return "forward:/assets/interview/index.html";
    }

    @GetMapping("/debug/display")
    public String display(HttpServletRequest request) {
        requireLoopback(request);
        return "forward:/assets/display/index.html";
    }

    private static void requireLoopback(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr()) || !isLoopback(request.getServerName())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "debug_studio_local_only");
        }
    }

    public static boolean isLoopback(String value) {
        if (value == null || value.isBlank()) return false;
        String host = value.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if ("localhost".equals(host) || host.endsWith(".localhost")) return true;
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
