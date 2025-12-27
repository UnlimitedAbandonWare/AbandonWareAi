package ai.abandonware.nova.orch.aop;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatWorkflow;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;

/**
 * 내부 자동학습(UAW)에서 생성되는 seed 프롬프트를 "초엄격" 모드로 강제합니다.
 *
 * <p>
 * 기본적으로 UAW는 {@code ChatService.ask("내부 자동학습: ...")} 형태로 호출되며,
 * 이는 결국 {@link ChatWorkflow#ask(String)} 경로를 탑니다.
 * 이 Aspect는 해당 호출을 가로채어, 웹 검색/검증/RAG/공식 출처 제한 등을 활성화한
 * {@link ChatWorkflow#continueChat(ChatRequestDto)} 경로로 우회시킵니다.
 * </p>
 */
@Aspect
@Slf4j
public class UawAutolearnStrictRequestAspect {

    private final Environment env;

    public UawAutolearnStrictRequestAspect(Environment env) {
        this.env = env;
    }

    @Around("execution(* com.example.lms.service.ChatWorkflow.ask(String)) && args(question)")
    public Object aroundAsk(ProceedingJoinPoint pjp, String question) throws Throwable {
        if (question == null) {
            return pjp.proceed();
        }

        final String prefix = env.getProperty("uaw.autolearn.strict.prefix", "내부 자동학습:");
        if (prefix == null || prefix.isBlank() || !question.startsWith(prefix)) {
            return pjp.proceed();
        }

        final String stripped = question.substring(prefix.length()).trim();
        if (stripped.isBlank()) {
            return pjp.proceed();
        }

        final int searchQueries = env.getProperty("uaw.autolearn.strict.search-queries", Integer.class, 12);
        final int maxSources = env.getProperty("uaw.autolearn.strict.max-sources", Integer.class, 12);

        // Optional overrides
        final String forcedModel = trimToNull(env.getProperty("uaw.autolearn.strict.model"));
        final Integer maxTokens = env.getProperty("uaw.autolearn.strict.max-tokens", Integer.class, 1024);
        final String memoryMode = env.getProperty("uaw.autolearn.strict.memory-mode", "ephemeral");
        final SearchMode searchMode = resolveSearchMode(env.getProperty("uaw.autolearn.strict.search-mode"), searchQueries);
        final Double temperature = resolveTemperature(env.getProperty("uaw.autolearn.strict.temperature"));

        ChatRequestDto req = ChatRequestDto.builder()
                .message(stripped)
                .model(forcedModel)
                .maxTokens(maxTokens)
                .memoryMode(memoryMode)
                // 강제 모드
                .useWebSearch(true)
                .useRag(true)
                .useVerification(true)
                .officialSourcesOnly(true)
                .searchMode(searchMode)
                .searchQueries(searchQueries)
                .webTopK(maxSources)
                .temperature(temperature)
                .build();

        try {
            // IMPORTANT: call via proxy to keep other AOP layers active.
            ChatWorkflow wf = (ChatWorkflow) pjp.getThis();
            ChatResult result = wf.continueChat(req);
            log.debug("[UAWStrict] forced strict mode for autolearn seed. queries={}, sources={}", searchQueries,
                    maxSources);
            return result;
        } catch (Throwable t) {
            // For autolearn, avoid polluting dataset with degraded banners or noisy failures.
            if (isHardLlmFailure(t)) {
                log.error("[UAWStrict] hard LLM failure in strict path; returning empty result (skip). root={}",
                        summarize(rootCause(t)));
                return ChatResult.of("", "fallback:evidence:uaw-llm-unavailable", false, Set.of());
            }

            // Non-hard failures: fall back to original ask(), but pass the stripped question to avoid
            // sending the autolearn prefix into the normal user pipeline.
            log.warn("[UAWStrict] strict path failed; falling back to ask(stripped)", t);
            return pjp.proceed(new Object[] { stripped });
        }
    }

    private static SearchMode resolveSearchMode(String raw, int searchQueries) {
        if (raw != null && !raw.isBlank()) {
            try {
                return SearchMode.valueOf(raw.trim());
            } catch (Exception ignore) {
                // fallthrough
            }
        }
        return (searchQueries >= 2) ? SearchMode.FORCE_DEEP : SearchMode.FORCE_LIGHT;
    }

    private static Double resolveTemperature(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        return s.isBlank() ? null : s;
    }

    private static boolean isHardLlmFailure(Throwable t) {
        Throwable root = rootCause(t);
        String cn = root.getClass().getName();
        if (cn.endsWith("ModelNotFoundException")) {
            return true;
        }
        if (cn.endsWith("UnknownHostException") || cn.endsWith("ConnectException") || cn.endsWith("HttpTimeoutException")) {
            return true;
        }

        String msg = root.getMessage();
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase(Locale.ROOT);
        if (m.contains("model") && m.contains("not found")) {
            return true;
        }
        if (m.contains("connection refused") || m.contains("connect timed out") || m.contains("unknown host")) {
            return true;
        }
        return false;
    }

    private static Throwable rootCause(Throwable t) {
        if (t == null) {
            return new RuntimeException("null");
        }
        Throwable cur = t;
        for (int i = 0; i < 12; i++) {
            Throwable next = cur.getCause();
            if (next == null || next == cur) {
                break;
            }
            cur = next;
        }
        return cur;
    }

    private static String summarize(Throwable t) {
        if (t == null) {
            return "<null>";
        }
        String msg = (t.getMessage() == null) ? "" : t.getMessage();
        msg = msg.replaceAll("\\s+", " ").trim();
        if (msg.length() > 180) {
            msg = msg.substring(0, 177) + "...";
        }
        String cn = t.getClass().getSimpleName();
        return msg.isBlank() ? cn : (cn + ": " + msg);
    }
}
