package com.example.lms.assist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Reads an explicit fixed origin or the owned tunnel receipt; never trusts a URL from a web request. */
@Component
public final class InterviewDemoPublicAddress {
    @Value("${demo.public-url-file:data/agent-handoff/interview-tunnel/state.json}") private String stateFile;
    @Value("${demo.interview.fixed-public-origin:}") private String fixedPublicOrigin = "";
    private final ObjectMapper json = new ObjectMapper();
    public String currentOrigin() {
        try {
            if (fixedPublicOrigin != null && !fixedPublicOrigin.isBlank()) {
                URI fixed=URI.create(fixedPublicOrigin);
                if (!"https".equals(fixed.getScheme()) || fixed.getHost()==null
                        || (fixed.getPort()!=-1 && fixed.getPort()!=443) || fixed.getRawUserInfo()!=null
                        || !fixed.getRawPath().isEmpty() || fixed.getRawQuery()!=null || fixed.getRawFragment()!=null) return "";
                return "https://"+fixed.getHost().toLowerCase(java.util.Locale.ROOT);
            }
            if (stateFile == null) return "";
            Path path=Path.of(stateFile);
            if (!Files.isRegularFile(path) || Files.size(path)>4096) return "";
            var state=json.readTree(Files.readAllBytes(path));
            if (!"active".equals(state.path("status").asText()) || state.path("expiresAt").asLong()<=System.currentTimeMillis()) return "";
            var process=ProcessHandle.of(state.path("pid").asLong()).filter(ProcessHandle::isAlive);
            if (process.isEmpty()) return "";
            var started=process.get().info().startInstant();
            if (started.isEmpty() || Math.abs(started.get().toEpochMilli()-Instant.parse(state.path("startedAt").asText()).toEpochMilli())>1000) return "";
            URI uri=URI.create(state.path("publicUrl").asText());
            if (!"https".equals(uri.getScheme()) || uri.getHost()==null || !uri.getHost().endsWith(".trycloudflare.com")
                    || uri.getPort()!=-1 || uri.getRawUserInfo()!=null || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery()!=null || uri.getRawFragment()!=null) return "";
            return uri.toString();
        } catch (Exception unavailable) { return ""; }
    }
}
