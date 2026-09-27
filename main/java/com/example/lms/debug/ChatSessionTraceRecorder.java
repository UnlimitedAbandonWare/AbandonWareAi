package com.example.lms.debug;

import com.example.lms.agent.context.AgentDbContextProperties;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 채팅/디스플레이 세션 종료 시 정제된 디버그 증거 한 건을 날짜별 파일에 append 한다.
 *
 * <p>목적: Grok/Devin/Codex/Cline이 같은 {@code var/debug/chat-session-traces/} 경로만 보고
 * 해당 세션의 모델·파이프라인 경고·Agent DB 상태를 재현할 수 있게 한다.</p>
 *
 * <p>안전 규칙:
 * <ul>
 *   <li>프롬프트/응답 본문, 토큰, API 키 값은 절대 기록하지 않는다.</li>
 *   <li>sessionId/runId는 {@code hash:<sha256-12>} 형태로만 기록한다
 *       (runId는 attach/cancel 권한을 가진 clientToken이므로 원문 저장 금지).</li>
 *   <li>trace 메타는 키 이름만 수집하고 값은 복사하지 않는다.</li>
 *   <li>파일 쓰기 실패는 호출자에게 전파하지 않는다(fail-soft).</li>
 * </ul>
 * </p>
 */
@Component
public class ChatSessionTraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionTraceRecorder.class);
    private static final String SCHEMA = "awx.chat-session-trace.v1";
    private static final int MAX_TRACE_KEYS = 256;
    private static final long MAX_DEDUP_SCAN_BYTES = 512L * 1024L;
    private static final DateTimeFormatter DAY_DIR =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Object writeMutex = new Object();

    @Value("${abandonware.debug.chat-session-traces.dir:var/debug/chat-session-traces}")
    private String traceDir = "var/debug/chat-session-traces";

    @Value("${abandonware.debug.chat-session-traces.enabled:true}")
    private boolean enabled = true;

    /**
     * AgentDbContextAutoConfiguration은 enabled=true일 때만 로드되므로 빈이 없으면
     * 곧 disabled로 간주한다(UI Debug FX의 Agent DB 상태와 동일 근거).
     */
    @Autowired(required = false)
    private AgentDbContextProperties agentDbContextProperties;

    public ChatSessionTraceRecorder() {
    }

    /** 단위 테스트용: 디렉터리/플래그를 직접 주입한다. */
    ChatSessionTraceRecorder(Path dir, boolean enabled, AgentDbContextProperties props) {
        this.traceDir = Objects.requireNonNull(dir, "dir").toString();
        this.enabled = enabled;
        this.agentDbContextProperties = props;
    }

    /**
     * 세션 종료 레코드 한 건을 append 한다. 동일 recordId가 이미 파일에 있으면
     * 다시 쓰지 않는다(멱등). 절대 예외를 던지지 않는다.
     */
    public void recordTerminal(String surface, Object sessionId, String runToken,
            String requestedModel, String effectiveModel, Boolean ragEnabled,
            String outcome, Map<String, Object> traceMeta) {
        if (!enabled) {
            return;
        }
        try {
            Map<String, Object> meta = traceMeta == null ? Map.of() : traceMeta;
            String sessionHash = sessionId == null ? null
                    : SafeRedactor.hash12(String.valueOf(sessionId));
            String runHash = SafeRedactor.hash12(runToken);
            String recordId = firstNonNull(runHash, sessionHash,
                    SafeRedactor.hash12(UUID.randomUUID().toString()));
            if (recordId == null) {
                return;
            }

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("schema", SCHEMA);
            record.put("ts", Instant.now().toString());
            record.put("sessionId", sessionHash == null ? null : "hash:" + sessionHash);
            record.put("runId", runHash == null ? null : "hash:" + runHash);
            record.put("recordId", recordId);
            boolean isDisplay = "display".equals(surface);
            record.put("surface", isDisplay ? "display" : "chat");
            if (isDisplay) {
                // Display 대화 DB는 별도 read-only 레인 — 라이브 H2 JDBC 금지, 경로 참조만 남긴다.
                record.put("related", Map.of(
                        "metaDisplayDbExport", "var/meta-display-db/export/",
                        "metaDisplayDbExportCli", "scripts/meta_display_db_export.py"));
            }
            record.put("requestedModel", modelLabel(requestedModel));
            record.put("effectiveModel", modelLabel(effectiveModel));
            record.put("baseUrlClass", classifyBaseUrl(
                    meta.get("llm.factory.baseUrlHost"), meta.get("llm.client.modelClass")));
            record.put("ragEnabled", ragEnabled);
            record.put("agentDbContextEnabled",
                    agentDbContextProperties != null && agentDbContextProperties.isEnabled());
            record.put("harmonyWarn", truthy(meta.get("chat.harmony.postprocess.degraded")));
            record.put("harmonyDecision",
                    SafeRedactor.traceLabel(meta.get("chat.harmony.postprocess.decision")));
            record.put("cfvmQueued",
                    truthy(meta.get("traceMemory.cfvm.offered"))
                            || truthy(meta.get("prompt.agentDebugEvidence.traceMemory.cfvmOffered")));
            String outcomeLabel = SafeRedactor.traceLabelOrFallback(outcome, "unknown");
            record.put("outcome", outcomeLabel);
            record.put("errorClass", errorClass(outcomeLabel));
            record.put("fallbackCount", asInt(meta.get("llm.gateway.fallback.count"), 0));
            record.put("traceKeys", sanitizedTraceKeys(meta));

            append(recordId, sessionHash, record);
        } catch (Throwable t) {
            log.debug("chat session trace skipped: errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(t)), String.valueOf(t).length());
        }
    }

    /** recordId 기준 멱등 append. 파일이 이미 해당 recordId를 포함하면 쓰지 않는다. */
    private void append(String recordId, String sessionHash, Map<String, Object> record)
            throws Exception {
        String stem = (sessionHash != null ? "s-" + sessionHash : "r-" + recordId);
        Path file = Path.of(traceDir, DAY_DIR.format(Instant.now()), stem + ".json");
        String line = mapper.writeValueAsString(record);
        synchronized (writeMutex) {
            if (Files.isRegularFile(file) && Files.size(file) <= MAX_DEDUP_SCAN_BYTES
                    && Files.readString(file, StandardCharsets.UTF_8)
                            .contains("\"recordId\":\"" + recordId + "\"")) {
                return;
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private static String errorClass(String outcome) {
        if (outcome == null) {
            return "error";
        }
        String o = outcome.toLowerCase(Locale.ROOT);
        if (o.contains("cancel")) {
            return "cancelled";
        }
        if (o.contains("error") || o.contains("fail") || o.contains("exception")) {
            return "error";
        }
        return "none";
    }

    private static String classifyBaseUrl(Object hostValue, Object modelClassValue) {
        if (hostValue == null) {
            return classifyModelClass(modelClassValue);
        }
        String host = String.valueOf(hostValue).trim().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || !host.matches("[a-z0-9.\\[\\]\\-:]{1,120}")) {
            return "unknown";
        }
        if (host.equals("localhost") || host.endsWith(".localhost")
                || host.startsWith("127.") || host.equals("::1") || host.equals("[::1]")
                || host.equals("0.0.0.0")) {
            return "local";
        }
        if (host.startsWith("10.") || host.startsWith("192.168.")
                || host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*")
                || host.startsWith("169.254.") || host.startsWith("fe80")
                || host.endsWith(".local") || host.endsWith(".lan")) {
            return "private-lan";
        }
        return "remote";
    }

    /** 호스트 키가 없을 때 모델 클래스명으로 로컬/원격을 추정한다. */
    private static String classifyModelClass(Object modelClassValue) {
        if (modelClassValue == null) {
            return "unknown";
        }
        String cls = String.valueOf(modelClassValue).trim().toLowerCase(Locale.ROOT);
        if (cls.isEmpty() || !cls.matches("[a-z0-9_.:\\-]{1,160}")) {
            return "unknown";
        }
        if (cls.contains("ollama") || cls.contains("local")) {
            return "local";
        }
        if (cls.contains("openai")) {
            return "remote";
        }
        return "unknown";
    }

    /**
     * 모델명은 비밀이 아니며 UI Debug FX가 원문을 보여주므로 {@code provider/model}의
     * {@code /}를 구분자로 유지한다. 세그먼트마다 SafeRedactor 규칙을 적용해
     * 비밀 패턴/비정상 입력은 여전히 해시된다.
     */
    private static String modelLabel(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim().replaceAll("[\\r\\n\\t]+", " ");
        if (text.isBlank()) {
            return "";
        }
        String[] parts = text.split("/", -1);
        if (parts.length > 4) {
            return SafeRedactor.traceLabel(text);
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            String seg = SafeRedactor.traceLabel(parts[i]);
            if (seg != null) {
                sb.append(seg);
            }
        }
        String out = sb.toString();
        return out.length() > 160 ? out.substring(0, 160) : out;
    }

    private static List<String> sanitizedTraceKeys(Map<String, Object> meta) {
        if (meta.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(meta.size());
        for (Object key : meta.keySet()) {
            if (key == null) {
                continue;
            }
            String name = String.valueOf(key);
            // 키 이름은 코드 상수이지만 방어적으로 라벨 패턴을 강제한다.
            keys.add(name.matches("[A-Za-z0-9_.:\\-]{1,128}")
                    ? name
                    : SafeRedactor.traceLabelOrFallback(name, "field"));
        }
        Collections.sort(keys);
        return keys.size() > MAX_TRACE_KEYS ? keys.subList(0, MAX_TRACE_KEYS) : keys;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignore) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
