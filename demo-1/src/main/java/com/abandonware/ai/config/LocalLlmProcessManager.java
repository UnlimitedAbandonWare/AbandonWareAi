package com.abandonware.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    public void run(ApplicationArguments args) throws Exception {
        if (!autostart) {
            // 자동 기동 비활성화
            return;
        }

        System.out.println("[LocalLlmProcessManager] 로컬 LLM 자동 기동 시작...");
        if (isServiceRunning()) {
            System.out.println("[LocalLlmProcessManager] 로컬 LLM 서버가 이미 실행 중입니다. (health 체크 성공)");
            return;
        }

        if (startCommand == null || startCommand.trim().isEmpty()) {
            System.err.println("[LocalLlmProcessManager] start-command가 비어 있습니다. 로컬 LLM을 시작할 수 없습니다.");
            return;
        }

        Process process = startLocalLlmProcess();
        System.out.println("[LocalLlmProcessManager] vLLM 프로세스 실행 명령 완료: " + startCommand);

        boolean up = waitForHealthCheck();
        if (!up) {
            System.err.println("[LocalLlmProcessManager] 지정된 시간 내에 로컬 LLM 서버의 응답을 받지 못했습니다. (자동 기동 실패)");
            // 필요 시 종료/예외 처리 추가 가능
        } else {
            System.out.println("[LocalLlmProcessManager] 로컬 LLM 서버가 정상 기동되었습니다!");
        }
    }

    /** health-check URL에 접속하여 서버 응답 여부 확인 */
    private boolean isServiceRunning() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(healthCheckUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.connect();
            int code = conn.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** start-command 설정을 기반으로 프로세스 실행 */
    private Process startLocalLlmProcess() throws IOException {
        List<String> parts = splitCommandRespectingQuotes(startCommand);
        ProcessBuilder pb = new ProcessBuilder(parts);
        pb.redirectErrorStream(true); // 표준에러를 표준출력으로 병합
        return pb.start();
    }

    /** health-check URL에 반복적으로 요청 보내어 서버 준비 완료 대기 */
    private boolean waitForHealthCheck() throws InterruptedException {
        long totalWait = healthCheckTimeout.toMillis();
        long interval = 1000L;
        long waited = 0L;
        while (waited < totalWait) {
            if (isServiceRunning()) {
                return true;
            }
            Thread.sleep(interval);
            waited += interval;
        }
        return false;
    }

    /** 공백과 따옴표를 고려하여 명령어를 분리 */
    static List<String> splitCommandRespectingQuotes(String cmd) {
        List<String> out = new ArrayList<>();
        if (cmd == null) return out;
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
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
