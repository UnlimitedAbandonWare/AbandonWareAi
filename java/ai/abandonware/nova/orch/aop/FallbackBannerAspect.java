package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.router.LlmRouterBandit;
import ai.abandonware.nova.orch.router.LlmRouterContext;
import com.example.lms.service.ChatResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;

import java.util.Locale;

/**
 * UAW 관점에서 "silent failure"를 줄이기 위한 최소 패치:
 * - LLM 경로가 실패해서 fallback:evidence로 나간 경우,
 *   사용자에게 "현재 LLM-OFF(우회 경로)"임을 명시
 *
 * <p>추가 오케스트레이션:
 * - llmrouter.* alias가 사용된 경우, 성공/실패를 router에 피드백하여
 *   health/cooldown/bandit 선택이 동작할 수 있도록 한다.
 */
@Aspect
public class FallbackBannerAspect {

    private final Environment env;
    private final LlmRouterBandit router;

    public FallbackBannerAspect(Environment env) {
        this(env, null);
    }

    public FallbackBannerAspect(Environment env, LlmRouterBandit router) {
        this.env = env;
        this.router = router;
    }

    @Around("execution(* com.example.lms.service.ChatWorkflow.continueChat(..))")
    public Object aroundContinueChat(ProceedingJoinPoint pjp) throws Throwable {
        long startedAt = System.currentTimeMillis();
        try {
            Object out = pjp.proceed();
            if (!(out instanceof ChatResult r)) {
                recordRouterOutcome(true, startedAt);
                return out;
            }

            String modelUsed = r.modelUsed();
            boolean degraded = modelUsed != null && modelUsed.toLowerCase(Locale.ROOT).contains("fallback:evidence");
            boolean internalUaw = isInternalUawThread();

            recordRouterOutcome(!degraded, startedAt);

            // Avoid contaminating internal UAW samples with the degraded-mode banner.
            if (!degraded || internalUaw) {
                return out;
            }

            String content = (r.content() == null) ? "" : r.content();
            // 중복 삽입 방지
            if (content.startsWith("※ [DEGRADED MODE]")) {
                return out;
            }

            String hint = buildHint(modelUsed);
            String patched =
                    "※ [DEGRADED MODE] LLM 호출이 실패/차단되어 'Evidence-only(LLM-OFF)' 경로로 답변했습니다.\n"
                            + hint
                            + "\n\n"
                            + content;
            return ChatResult.of(patched, r.modelUsed(), r.ragUsed(), r.evidence());
        } catch (Throwable t) {
            recordRouterOutcome(false, startedAt);
            throw t;
        } finally {
            // Avoid ThreadLocal leaks.
            LlmRouterContext.clear();
        }
    }

    private void recordRouterOutcome(boolean success, long startedAtMillis) {
        if (router == null) {
            return;
        }
        LlmRouterContext.Route route = LlmRouterContext.get();
        if (route == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long latency = Math.max(0L, now - route.startedAtMs());
        if (route.startedAtMs() <= 0L) {
            latency = Math.max(0L, now - startedAtMillis);
        }

        try {
            router.recordOutcome(route.key(), success, latency);
        } catch (Exception ignore) {
            // fail-soft
        }
    }

    private String buildHint(String modelUsed) {
        // 가능한 모든 키 이름을 탐색해 정확한 원인 안내
        String baseUrl = firstNonBlank(
                get("llm.base-url-openai", ""),
                get("llm.openai.base-url", ""),
                get("openai.api.url", ""),
                get("openai.base-url", ""),
                get("spring.ai.openai.base-url", ""),
                get("OPENAI_BASE_URL", ""),
                "https://api.openai.com/v1"
        );
        String key = firstNonBlank(
                get("llm.api-key-openai", ""),
                get("llm.openai.api-key", ""),
                get("openai.api.key", ""),
                get("openai.api-key", ""),
                get("spring.ai.openai.api-key", ""),
                get("OPENAI_API_KEY", "")
        );
        boolean baseLooksOpenAi = baseUrl.toLowerCase(Locale.ROOT).contains("api.openai.com");
        boolean keyMissing = key.isBlank();
        boolean keyLooksGroq = key.startsWith("gsk_");

        StringBuilder sb = new StringBuilder();
        sb.append("- modelUsed: ").append(modelUsed).append('\n');
        sb.append("- (원인 가이드) 띵킹 셋업/자세한 설명은 '정상 LLM 경로'에서만 적용됩니다.\n");
        if (baseLooksOpenAi && keyLooksGroq) {
            sb.append("- 의심: OpenAI base-url에 Groq 키(gsk_)가 매핑된 상태일 수 있습니다.\n");
            sb.append("  → 해결: OPENAI_API_KEY(sk-/sk-proj-)를 설정하세요.\n");
        } else if (keyMissing) {
            sb.append("- 의심: llm.api-key-openai / llm.openai.api-key / openai.api.key / OPENAI_API_KEY 미설정.\n");
            sb.append("  → 해결: env에 OPENAI_API_KEY를 설정하세요(권장). 또는 llm.api-key-openai로 바인딩하세요.\n");
        } else {
            sb.append("- 의심: 인증/네트워크/timeout으로 LLM 호출 실패. 서버 로그를 확인하세요.\n");
        }
        return sb.toString();
    }

    private boolean isInternalUawThread() {
        // Most stable signal: dedicated scheduler thread name prefix.
        // (see: UawAutolearnSchedulerConfig threadNamePrefix="uaw-autolearn-")
        String tn = Thread.currentThread().getName();
        if (tn == null || tn.isBlank()) {
            return false;
        }
        return tn.toLowerCase(Locale.ROOT).contains("uaw");
    }


    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private String get(String k, String def) {
        try {
            String v = (env == null) ? null : env.getProperty(k);
            return (v == null) ? def : v;
        } catch (Exception ignore) {
            return def;
        }
    }
}
