package com.abandonware.ai.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalLlmProcessManager implements ApplicationRunner {
    @Value("${local-llm.autostart:false}")
    private boolean autostart;

    @Value("${local-llm.health-check-url:http://localhost:11434/health}")
    private String healthCheckUrl;

    @Value("${local-llm.health-check-timeout:30s}")
    private Duration healthCheckTimeout;

    @Value("${local-llm.start-command:}")
    private String startCommand;

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!autostart) {
                return;
            }
            System.out.println("[LocalLlmProcessManager] autostart=true");

            if (isServiceRunning()) {
                System.out.println("[LocalLlmProcessManager] Local LLM already up (health OK): " + healthCheckUrl);
                return;
            }

            if (startCommand == null || startCommand.isBlank()) {
                System.err.println("[LocalLlmProcessManager] start-command is empty; cannot start local LLM.");
                return;
            }

            System.out.println("[LocalLlmProcessManager] starting Local LLM via: " + startCommand);
            try {
                startLocalLlmProcess();
            } catch (Exception ex) {
                System.err.println("[LocalLlmProcessManager] Failed to start command: " + ex.getMessage());
                return;
            }

            if (!waitForHealthCheck()) {
                System.err.println("[LocalLlmProcessManager] Local LLM did not become healthy within " + healthCheckTimeout);
            } else {
                System.out.println("[LocalLlmProcessManager] Local LLM is healthy.");
            }
        } catch (Exception e) {
            System.err.println("[LocalLlmProcessManager] Unexpected error: " + e.getMessage());
        }
    }

    private boolean isServiceRunning() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(healthCheckUrl).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.connect();
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private void startLocalLlmProcess() throws Exception {
        java.util.List<String> tokens = splitCommand(startCommand);
        ProcessBuilder pb = new ProcessBuilder(tokens);
        pb.redirectErrorStream(true);
        pb.inheritIO();
        pb.start();
    }

    private boolean waitForHealthCheck() throws InterruptedException {
        long total = healthCheckTimeout.toMillis();
        long waited = 0L;
        final long step = 1000L;
        while (waited < total) {
            if (isServiceRunning()) return true;
            Thread.sleep(step);
            waited += step;
        }
        return false;
    }

    static java.util.List<String> splitCommand(String cmd) {
        java.util.List<String> out = new java.util.ArrayList<>();
        boolean inQuote = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (Character.isWhitespace(c) && !inQuote) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
