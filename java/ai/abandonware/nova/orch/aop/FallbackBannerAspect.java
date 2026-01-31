package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.router.LlmRouterBandit;
import ai.abandonware.nova.orch.router.LlmRouterContext;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.service.ChatResult;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * UAW 관점에서 "silent failure"를 줄이기 위한 패치:
 * <ul>
 *     <li>LLM 경로가 실패해서 fallback:evidence로 나간 경우, 사용자에게 '현재 LLM-OFF(우회 경로)'임을 명시</li>
 *     <li>(추가) fallback:evidence 감지 시 보조 모델로 복구 시도 (preferred + candidates failover)</li>
 * </ul>
 *
 * <p>추가 오케스트레이션:
 * - llmrouter.* alias가 사용된 경우, 성공/실패를 router에 피드백하여
 *   다음 라우팅 정책이 개선되도록 한다.
 */
@Aspect
public class FallbackBannerAspect {

    private final Environment env;
    private final LlmRouterBandit router;
    private final DynamicChatModelFactory chatModelFactory;

    /** First-success cache for evidence-aux recovery model. */
    private final AtomicReference<String> stickyAuxModel = new AtomicReference<>();

    public FallbackBannerAspect(Environment env) {
        this(env, null, null);
    }

    public FallbackBannerAspect(Environment env, LlmRouterBandit router) {
        this(env, router, null);
    }

    public FallbackBannerAspect(Environment env,
                               LlmRouterBandit router,
                               DynamicChatModelFactory chatModelFactory) {
        this.env = env;
        this.router = router;
        this.chatModelFactory = chatModelFactory;
    }

    @Around("execution(* com.example.lms.service.ChatWorkflow.continueChat(..))")
    public Object aroundContinueChat(ProceedingJoinPoint pjp) throws Throwable {
        long startedAt = System.currentTimeMillis();
        try {
            ChatRequestDto req = extractRequest(pjp);

            Object out = pjp.proceed();
            if (!(out instanceof ChatResult r)) {
                recordRouterOutcome(true, startedAt);
                return out;
            }

            String modelUsed = r.modelUsed();
            boolean degraded = modelUsed != null
                    && modelUsed.toLowerCase(Locale.ROOT).contains("fallback:evidence");
            boolean internalUaw = isInternalUawThread();

            // (TRACE) Surface degraded-mode routing to the UI without polluting the answer body.
            // This allows clients to render a fallback badge based on answer.mode.
            try {
                if (degraded) {
                    TraceStore.putIfAbsent("answer.mode", "FALLBACK_EVIDENCE");
                }
            } catch (Exception ignoreMode) {
                // fail-soft
            }

            // Router feedback: if the primary route degraded, mark it as failure (even if we later rescue).
            recordRouterOutcome(!degraded, startedAt);

            // If we hit evidence-only fallback, attempt a second-chance generation using an auxiliary model.
            // Fail-soft: if aux is unavailable, keep the original evidence-only response.
            if (degraded && !internalUaw && isEvidenceAuxEnabled() && chatModelFactory != null) {
                ChatResult recovered = tryAuxRecovery(req, r);
                if (recovered != null) {
                    return recovered;
                }
            }

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

    private boolean isEvidenceAuxEnabled() {
        String v = get("nova.orch.evidence-aux.enabled", "true");
        return v != null && "true".equalsIgnoreCase(v.trim());
    }

    private ChatResult tryAuxRecovery(ChatRequestDto req, ChatResult evidenceOnly) {
        if (req == null || evidenceOnly == null) return null;
        if (chatModelFactory == null) return null;

        String query = req.getMessage();
        String evidence = evidenceOnly.content();
        if (query == null || query.isBlank() || evidence == null || evidence.isBlank()) {
            return null;
        }

        int timeoutSeconds = Math.max(5, getInt("nova.orch.evidence-aux.timeout-seconds", 15));
        int maxTokens = Math.max(64, getInt("nova.orch.evidence-aux.max-tokens", 512));
        int maxChars = Math.max(2000, getInt("nova.orch.evidence-aux.max-chars", 15000));

        // How many actual aux LLM calls to attempt (serveable candidates only).
        int maxAttempts = Math.max(1, getInt("nova.orch.evidence-aux.max-attempts", 2));

        List<String> candidates = getAuxCandidates();
        int attemptedCalls = 0;
        for (String auxModel : candidates) {
            if (auxModel == null || auxModel.isBlank()) {
                continue;
            }

            boolean canServe;
            try {
                canServe = chatModelFactory.canServe(auxModel);
            } catch (Exception e) {
                canServe = false;
            }
            if (!canServe) {
                // Skip quickly and try the next candidate.
                continue;
            }

            attemptedCalls++;
            if (attemptedCalls > maxAttempts) {
                break;
            }

            try {
                // Recovery path: low temperature to reduce hallucination.
                ChatModel model = chatModelFactory.lcWithTimeout(auxModel, 0.2, null, maxTokens, timeoutSeconds);

                String recovered = runRecoveryPrompt(model, query, clip(evidence, maxChars));
                if (recovered != null && !recovered.isBlank()) {
                    stickyAuxModel.compareAndSet(null, auxModel);
                    return ChatResult.of(recovered,
                            auxModel + ":fallback:aux",
                            evidenceOnly.ragUsed(),
                            evidenceOnly.evidence());
                }
            } catch (Exception ignore) {
                // fail-soft: try next candidate
            }
        }

        return null;
    }

    private String runRecoveryPrompt(ChatModel model, String query, String evidence) {
        String sys = "근거(Evidence) 기반 답변 생성기. Evidence에 있는 내용만 사용하여 답변하라. "
                + "Evidence에 없는 내용은 추측하지 말고, 필요한 추가 확인 항목을 제안하라. "
                + "사용자 질문의 언어로 답하라.";
        String user = "[질문]\n" + query
                + "\n\n[Evidence(LLM-OFF draft)]\n" + evidence
                + "\n\n최종 답변:";

        List<ChatMessage> msgs = List.of(SystemMessage.from(sys), UserMessage.from(user));
        return model.chat(msgs).aiMessage().text();
    }

    private List<String> getAuxCandidates() {
        // Keep order stable and remove duplicates.
        Set<String> out = new LinkedHashSet<>();

        // 0) Sticky winner (first success)
        addCandidate(out, stickyAuxModel.get());

        // 1) Preferred aux model
        addCandidate(out, get("nova.orch.evidence-aux.preferred-model", ""));

        // 2) Optional CSV list: if preferred cannot be served or fails, try the next candidate.
        for (String m : csv(get("nova.orch.evidence-aux.candidates", ""))) {
            addCandidate(out, m);
        }

        // 3) Backend base model as an extra option
        addCandidate(out, get("app.ai.default-model", ""));

        // 4) Hard fallback
        addCandidate(out, "gpt-5.2-chat-latest");

        return new ArrayList<>(out);
    }

    private static void addCandidate(Set<String> out, String candidate) {
        if (out == null || candidate == null) return;
        String s = candidate.trim();
        if (s.isBlank()) return;
        out.add(s);
    }

    private static List<String> csv(String raw) {
        if (raw == null) return List.of();
        String t = raw.trim();
        if (t.isBlank()) return List.of();
        String[] parts = t.split(",");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            String s = p.trim();
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private ChatRequestDto extractRequest(ProceedingJoinPoint pjp) {
        try {
            Object[] args = pjp.getArgs();
            if (args != null && args.length > 0 && args[0] instanceof ChatRequestDto req) {
                return req;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        if (max <= 0) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n[...truncated...]";
    }

    private void recordRouterOutcome(boolean ok, long startedAt) {
        if (router == null) return;

        LlmRouterContext.Route route = LlmRouterContext.get();
        if (route == null) return;

        String key = route.key();
        if (key == null || key.isBlank()) return;

        long now = System.currentTimeMillis();
        long base = (route.startedAtMs() > 0L) ? route.startedAtMs() : startedAt;
        long latencyMs = Math.max(0L, now - base);

        // recordOutcome(String key, boolean success, long latencyMs)
        router.recordOutcome(key, ok, latencyMs);
    }

    private boolean isInternalUawThread() {
        String name = Thread.currentThread().getName();
        return name != null && name.contains("uaw-autolearn");
    }

    private String buildHint(String modelUsed) {
        String hintUrl = get("nova.orch.failure.hintUrl", "");
        StringBuilder sb = new StringBuilder();
        if (modelUsed != null && !modelUsed.isBlank()) sb.append(" - modelUsed: ").append(modelUsed).append("\n");
        if (hintUrl != null && !hintUrl.isBlank()) sb.append(" - hint: ").append(hintUrl).append("\n");
        return sb.toString();
    }

    private int getInt(String key, int def) {
        String v = get(key, null);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception ignore) {
            return def;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
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
