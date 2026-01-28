package ai.abandonware.nova.orch.aop;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatWorkflow;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;

import java.util.Locale;
import java.util.Set;

/**
 * 내부 자동학습(UAW) seed 프롬프트를 "증거/검증 우선" 경로로 강제합니다.
 *
 * <p>
 * Breaker 기반으로:
 * <ul>
 * <li>chat:draft 브레이커 오픈 시: fail-closed(빈 결과 반환)로 데이터셋 오염 방지</li>
 * <li>websearch / retrieval 브레이커 오픈 시: degrade(해당 기능 OFF) 후 계속 실행</li>
 * </ul>
 * </p>
 */
@Aspect
public class UawAutolearnStrictRequestAspect {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(UawAutolearnStrictRequestAspect.class);

    private final Environment env;
    private final NightmareBreaker nightmareBreaker;

    public UawAutolearnStrictRequestAspect(Environment env, NightmareBreaker nightmareBreaker) {
        this.env = env;
        this.nightmareBreaker = nightmareBreaker;
    }

    @Around("execution(* com.example.lms.service.ChatWorkflow.ask(..))")
    public Object aroundAsk(ProceedingJoinPoint pjp) throws Throwable {
        final Object[] args0 = pjp.getArgs();
        final String question;
        if (args0 != null && args0.length > 0 && args0[0] instanceof String s) {
            question = s;
        } else {
            question = null;
        }
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

        GuardContext prev = null;
        try {
            prev = GuardContextHolder.get();
        } catch (Throwable ignore) {
        }
        GuardContext gctx = (prev != null) ? prev : GuardContext.defaultContext();

        try {
            // Mark UAW run for downstream aspects (survives TraceStore.clear()).
            gctx.putPlanOverride("uaw.autolearn", true);
            gctx.putPlanOverride("uaw.autolearn.pipeline", "legacy-strict");
            gctx.setUserQuery(stripped);
            GuardContextHolder.set(gctx);

            try {
                TraceStore.put("uaw.autolearn", true);
                TraceStore.put("uaw.autolearn.pipeline", "legacy-strict");
            } catch (Throwable ignore) {
            }

            final int searchQueries = env.getProperty("uaw.autolearn.strict.search-queries", Integer.class, 12);
            final int maxSources = env.getProperty("uaw.autolearn.strict.max-sources", Integer.class, 12);

            // Breaker-aware gating (DROP)
            boolean degradeWeb = false;
            boolean degradeRag = false;
            try {
                if (nightmareBreaker != null) {
                    if (nightmareBreaker.isAnyOpenPrefix(NightmareKeys.CHAT_DRAFT)) {
                        TraceStore.put("uaw.strict.failClosed", true);
                        TraceStore.put("uaw.strict.failClosed.reason", "breaker-open:" + NightmareKeys.CHAT_DRAFT);
                        return ChatResult.of("", "fallback:evidence:uaw-breaker-open", false, Set.of());
                    }
                    degradeWeb = nightmareBreaker.isAnyOpenPrefix("websearch:");
                    degradeRag = nightmareBreaker.isAnyOpenPrefix("retrieval:")
                            || nightmareBreaker.isOpenOrHalfOpen(NightmareKeys.RETRIEVAL_VECTOR);
                }
            } catch (Throwable ignore) {
            }

            try {
                gctx.putPlanOverride("uaw.degradeWeb", degradeWeb);
                gctx.putPlanOverride("uaw.degradeRag", degradeRag);
            } catch (Throwable ignore) {
            }

            // Optional overrides
            final String forcedModel = trimToNull(env.getProperty("uaw.autolearn.strict.model"));
            final Integer maxTokens = env.getProperty("uaw.autolearn.strict.max-tokens", Integer.class, 1024);
            final String memoryMode = env.getProperty("uaw.autolearn.strict.memory-mode", "ephemeral");
            final SearchMode searchMode = resolveSearchMode(env.getProperty("uaw.autolearn.strict.search-mode"),
                    searchQueries);
            final Double temperature = resolveTemperature(env.getProperty("uaw.autolearn.strict.temperature"));

            ChatRequestDto req = ChatRequestDto.builder()
                    .message(stripped)
                    .model(forcedModel)
                    .maxTokens(maxTokens)
                    .memoryMode(memoryMode)
                    // strict knobs (breaker-aware)
                    .useWebSearch(!degradeWeb)
                    .useRag(!degradeRag)
                    .useVerification(!degradeWeb)
                    .officialSourcesOnly(true)
                    .searchMode(searchMode)
                    .searchQueries(searchQueries)
                    .webTopK(maxSources)
                    .temperature(temperature)
                    .build();

            try {
                // These keys are mostly for outer logging; they may be cleared by ChatWorkflow.
                TraceStore.put("uaw.strict.degradeWeb", degradeWeb);
                TraceStore.put("uaw.strict.degradeRag", degradeRag);
            } catch (Throwable ignore) {
            }

            try {
                ChatWorkflow wf = (ChatWorkflow) pjp.getThis();
                ChatResult result = wf.continueChat(req);
                log.debug("[UAWStrict] forced strict mode for autolearn seed. queries={}, sources={}", searchQueries,
                        maxSources);
                return result;
            } catch (Throwable t) {
                if (isHardLlmFailure(t)) {
                    log.error("[UAWStrict] hard LLM failure in strict path; returning empty result (skip). root={}",
                            summarize(rootCause(t)));
                    return ChatResult.of("", "fallback:evidence:uaw-llm-unavailable", false, Set.of());
                }
                log.warn("[UAWStrict] strict path failed; falling back to ask(stripped)", t);
                // SoT snapshot: clone args once, then proceed(args) exactly once.
                if (args0 == null || args0.length < 1) {
                    return pjp.proceed();
                }
                final Object[] args = args0.clone();
                args[0] = stripped;
                return pjp.proceed(args);
            }
        } finally {
            // Restore prior guard context
            try {
                if (prev != null) {
                    GuardContextHolder.set(prev);
                } else {
                    GuardContextHolder.clear();
                }
            } catch (Throwable ignore) {
            }
        }
    }

    private static SearchMode resolveSearchMode(String raw, int searchQueries) {
        if (raw != null && !raw.isBlank()) {
            try {
                return SearchMode.valueOf(raw.trim());
            } catch (Exception ignore) {
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
        if (cn.endsWith("UnknownHostException") || cn.endsWith("ConnectException")
                || cn.endsWith("HttpTimeoutException")) {
            return true;
        }

        String msg = root.getMessage();
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase(Locale.ROOT);
        return (m.contains("model") && m.contains("not found"))
                || m.contains("connection refused")
                || m.contains("connect timed out")
                || m.contains("unknown host");
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
