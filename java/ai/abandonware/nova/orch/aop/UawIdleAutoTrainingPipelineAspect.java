package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.web.RuleBasedQueryAugmenter;
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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * DROP: UAW idle auto-training pipeline.
 *
 * <p>
 * Intercepts "내부 자동학습:" seed prompts and runs them through a strict
 * evidence-first path, with breaker-aware fail-closed/degrade policies to avoid
 * poisoning the dataset.
 * </p>
 */
@Aspect
public class UawIdleAutoTrainingPipelineAspect {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(UawIdleAutoTrainingPipelineAspect.class);

    private final Environment env;
    private final RuleBasedQueryAugmenter augmenter;

    @Nullable
    private final NightmareBreaker nightmareBreaker;

    public UawIdleAutoTrainingPipelineAspect(Environment env,
            RuleBasedQueryAugmenter augmenter,
            @Nullable NightmareBreaker nightmareBreaker) {
        this.env = env;
        this.augmenter = augmenter;
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

        final String afterPrefix = question.substring(prefix.length()).trim();
        if (afterPrefix.isBlank()) {
            return pjp.proceed();
        }

        Seed seed = extractSeedTag(afterPrefix);
        final String stripped = seed.stripped();
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
            gctx.putPlanOverride("uaw.autolearn", true);
            gctx.putPlanOverride("uaw.autolearn.pipeline", "idle-pipeline");
            gctx.setUserQuery(stripped);
            GuardContextHolder.set(gctx);
        } catch (Throwable ignore) {
        }

        try {
            TraceStore.put("uaw.autolearn", true);
            TraceStore.put("uaw.autolearn.pipeline", "idle-pipeline");
            if (seed.tag() != null) {
                TraceStore.put("uaw.pipeline.seedTag", seed.tag());
            }
        } catch (Throwable ignore) {
        }

        try {
            // Fail-closed when chat draft is breaker-open.
            if (nightmareBreaker != null && nightmareBreaker.isAnyOpenPrefix(NightmareKeys.CHAT_DRAFT)) {
                TraceStore.put("uaw.pipeline.failClosed", true);
                TraceStore.put("uaw.pipeline.failClosed.reason", "breaker-open:" + NightmareKeys.CHAT_DRAFT);
                return ChatResult.of("", "fallback:evidence:uaw-breaker-open", false, Set.of());
            }

            // Deterministic alias/canonical rewrite (no LLM)
            String q = stripped;
            RuleBasedQueryAugmenter.Augment aug = null;
            try {
                aug = augmenter.augment(stripped);
                if (aug != null && aug.canonical() != null && !aug.canonical().isBlank()) {
                    q = aug.canonical();
                    TraceStore.put("uaw.pipeline.canonicalQuery", q);
                    TraceStore.put("uaw.pipeline.intent", aug.intent() == null ? "UNKNOWN" : aug.intent().name());
                }
            } catch (Throwable ignore) {
            }

            // DomainProfile selection: TECH_API -> docs, FINANCE -> official, GENERAL ->
            // jul14 (company directory friendly)
            String domainProfile = resolveDomainProfile(aug, q);
            try {
                TraceStore.put("uaw.pipeline.domainProfile", domainProfile);
            } catch (Throwable ignore) {
            }

            // Provider-aware web degrade: filter out only the providers that are
            // breaker-open.
            List<String> requestedProviders = parseWebProviders(
                    env.getProperty("uaw.autolearn.strict.web-providers", "NAVER,BRAVE"));
            List<String> providers = new ArrayList<>(requestedProviders);

            boolean degradeWeb = false;
            if (nightmareBreaker != null) {
                if (isOpenPrefix(NightmareKeys.WEBSEARCH_NAVER)) {
                    degradeWeb = providers.removeIf(p -> "NAVER".equalsIgnoreCase(p)) || degradeWeb;
                }
                if (isOpenPrefix(NightmareKeys.WEBSEARCH_BRAVE)) {
                    degradeWeb = providers.removeIf(p -> "BRAVE".equalsIgnoreCase(p)) || degradeWeb;
                }
                // If some websearch breaker is open but not provider-specific, conservatively
                // mark as degraded.
                try {
                    if (!degradeWeb && nightmareBreaker.isAnyOpenPrefix("websearch:")) {
                        degradeWeb = true;
                    }
                } catch (Throwable ignore) {
                }
            }

            try {
                TraceStore.put("uaw.pipeline.webProviders.requested", String.join(",", requestedProviders));
                TraceStore.put("uaw.pipeline.webProviders.effective", String.join(",", providers));
                TraceStore.put("uaw.pipeline.degradeWeb", degradeWeb);
            } catch (Throwable ignore) {
            }

            if (providers.isEmpty()) {
                // Fail-closed: evidence-first requires at least one web provider.
                TraceStore.put("uaw.pipeline.failClosed", true);
                TraceStore.put("uaw.pipeline.failClosed.reason", "breaker-open:websearch-all");
                return ChatResult.of("", "fallback:evidence:uaw-websearch-unavailable", false, Set.of());
            }

            // Breaker-aware RAG degrade knobs
            boolean degradeRag = false;
            try {
                if (nightmareBreaker != null) {
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

            try {
                TraceStore.put("uaw.pipeline.degradeRag", degradeRag);
            } catch (Throwable ignore) {
            }

            // -------- Plan selection (scenario / variant) --------
            // Goal: keep UAW deterministic-ish but still explore a few stable variants.
            final int baseSearchQueries = env.getProperty("uaw.autolearn.strict.search-queries", Integer.class, 12);
            final int baseMaxSources = env.getProperty("uaw.autolearn.strict.max-sources", Integer.class, 12);

            final long planSeq = TraceStore.nextSequence("uaw.pipeline.plan.seq");
            final String scenario = (aug != null && aug.intent() != null) ? aug.intent().name() : "UNKNOWN";
            final int variant = planVariant(seed.tag(), scenario, q, planSeq);
            final int searchQueries = planSearchQueries(baseSearchQueries, scenario, variant);
            final int maxSources = planMaxSources(baseMaxSources, scenario, variant);
            final String planId = "uaw-" + scenario.toLowerCase(Locale.ROOT) + "-v" + variant;

            try {
                TraceStore.put("uaw.pipeline.plan.id", planId);
                TraceStore.put("uaw.pipeline.plan.scenario", scenario);
                TraceStore.put("uaw.pipeline.plan.variant", variant);
                TraceStore.put("uaw.pipeline.plan.seq", planSeq);
                TraceStore.put("uaw.pipeline.plan.searchQueries", searchQueries);
                TraceStore.put("uaw.pipeline.plan.maxSources", maxSources);
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
                    .message(q)
                    .model(forcedModel)
                    .maxTokens(maxTokens)
                    .memoryMode(memoryMode)
                    .domainProfile(domainProfile)
                    .webProviders(providers)
                    // strict evidence knobs (breaker-aware)
                    .useWebSearch(true)
                    .useRag(!degradeRag)
                    .useVerification(true)
                    .officialSourcesOnly(true)
                    .precisionSearch(true)
                    .accumulation(false)
                    .searchMode(searchMode)
                    .searchQueries(searchQueries)
                    .webTopK(maxSources)
                    .temperature(temperature)
                    .build();

            // Trace: high-level plan steps (debuggability / ablation bridge)
            try {
                List<String> steps = new ArrayList<>();
                steps.add("plan=" + planId);
                steps.add("scenario=" + scenario);
                steps.add("variant=" + variant);
                steps.add("domainProfile=" + domainProfile);
                steps.add("providers=" + providers);
                steps.add("searchMode=" + searchMode.name());
                steps.add("searchQueries=" + searchQueries);
                steps.add("maxSources=" + maxSources);
                steps.add("degradeWeb=" + degradeWeb);
                steps.add("degradeRag=" + degradeRag);
                steps.add("verify=true");
                steps.add("officialOnly=true");
                TraceStore.put("uaw.pipeline.plan.steps", String.join("|", steps));
            } catch (Throwable ignore) {
            }

            try {
                ChatWorkflow wf = (ChatWorkflow) pjp.getThis();
                ChatResult result = wf.continueChat(req);
                log.debug("[UAWPipeline] strict pipeline ran. providers={} degradeWeb={} degradeRag={}", providers,
                        degradeWeb, degradeRag);
                return result;
            } catch (Throwable t) {
                if (isHardLlmFailure(t)) {
                    log.error("[UAWPipeline] hard LLM failure; skip. root={}", summarize(rootCause(t)));
                    return ChatResult.of("", "fallback:evidence:uaw-llm-unavailable", false, Set.of());
                }
                log.warn("[UAWPipeline] pipeline failed; falling back to ask(stripped)", t);
                // SoT snapshot: clone args once, then proceed(args) exactly once.
                if (args0 == null || args0.length < 1) {
                    return pjp.proceed();
                }
                final Object[] args = args0.clone();
                args[0] = q;
                return pjp.proceed(args);
            }
        } finally {
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

    private boolean isOpenPrefix(String keyPrefix) {
        if (nightmareBreaker == null)
            return false;
        if (keyPrefix == null || keyPrefix.isBlank())
            return false;
        try {
            return nightmareBreaker.isAnyOpenPrefix(keyPrefix);
        } catch (Throwable ignore) {
            return false;
        }
    }

    private String resolveDomainProfile(@Nullable RuleBasedQueryAugmenter.Augment aug, String canonical) {
        RuleBasedQueryAugmenter.Intent intent = (aug == null) ? null : aug.intent();

        if (intent == RuleBasedQueryAugmenter.Intent.TECH_API) {
            return env.getProperty("uaw.autolearn.strict.domainProfile.tech", "docs");
        }
        if (intent == RuleBasedQueryAugmenter.Intent.FINANCE) {
            return env.getProperty("uaw.autolearn.strict.domainProfile.finance", "official");
        }
        // Default: GENERAL (incl. company/entity lookup)
        return env.getProperty("uaw.autolearn.strict.domainProfile.general", "jul14");
    }

    private static List<String> parseWebProviders(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split(",");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null)
                continue;
            String s = p.trim();
            if (s.isBlank())
                continue;
            out.add(s);
        }
        return out;
    }

    private static SearchMode resolveSearchMode(String raw, int searchQueries) {
        if (raw != null && !raw.isBlank()) {
            try {
                return SearchMode.valueOf(raw.trim());
            } catch (Exception ignore) {
            }
        }
        // Conservative default: deep only when explicitly configured or when query
        // budget is large.
        return (searchQueries >= 10) ? SearchMode.FORCE_DEEP : SearchMode.FORCE_LIGHT;
    }

    private static Double resolveTemperature(String raw) {
        if (raw == null || raw.isBlank())
            return null;
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    // ---- Plan variant helpers ----
    private static int planVariant(@Nullable String seedTag, String scenario, String canonicalQuery, long planSeq) {
        int h = Objects.hash(seedTag, scenario, canonicalQuery, planSeq);
        return Math.floorMod(h, 3);
    }

    private static int planSearchQueries(int base, String scenario, int variant) {
        int q = Math.max(2, base);
        if ("TECH_API".equals(scenario)) {
            q = Math.min(q, 8);
        } else if ("FINANCE".equals(scenario)) {
            q = Math.min(q, 10);
        }

        if (variant == 1) {
            q += 2;
        } else if (variant == 2) {
            q -= 2;
        }

        // Safety clamps
        q = Math.max(2, Math.min(q, 20));
        return q;
    }

    private static int planMaxSources(int base, String scenario, int variant) {
        int k = Math.max(4, base);
        if ("TECH_API".equals(scenario)) {
            k = Math.min(k, 10);
        } else if ("FINANCE".equals(scenario)) {
            k = Math.min(k, 12);
        }

        if (variant == 1) {
            k += 2;
        } else if (variant == 2) {
            k -= 2;
        }

        // Safety clamps
        k = Math.max(4, Math.min(k, 20));
        return k;
    }

    private static String trimToNull(String v) {
        if (v == null)
            return null;
        String s = v.trim();
        return s.isBlank() ? null : s;
    }

    private static boolean isHardLlmFailure(Throwable t) {
        Throwable root = rootCause(t);
        String cn = root.getClass().getName();
        if (cn.endsWith("ModelNotFoundException"))
            return true;
        if (cn.endsWith("UnknownHostException") || cn.endsWith("ConnectException")
                || cn.endsWith("HttpTimeoutException")) {
            return true;
        }
        String msg = root.getMessage();
        if (msg == null)
            return false;
        String m = msg.toLowerCase(Locale.ROOT);
        return (m.contains("model") && m.contains("not found"))
                || m.contains("connection refused")
                || m.contains("connect timed out")
                || m.contains("unknown host");
    }

    private static Throwable rootCause(Throwable t) {
        if (t == null)
            return new RuntimeException("null");
        Throwable cur = t;
        for (int i = 0; i < 12; i++) {
            Throwable next = cur.getCause();
            if (next == null || next == cur)
                break;
            cur = next;
        }
        return cur;
    }

    private static String summarize(Throwable t) {
        if (t == null)
            return "<null>";
        String msg = (t.getMessage() == null) ? "" : t.getMessage();
        msg = msg.replaceAll("\\s+", " ").trim();
        if (msg.length() > 180)
            msg = msg.substring(0, 177) + "...";
        String cn = t.getClass().getSimpleName();
        return msg.isBlank() ? cn : (cn + ": " + msg);
    }

    /**
     * (curiosity) / [gap] 같은 내부 태그를 seed prompt 앞에서 떼어내기.
     */
    private static Seed extractSeedTag(String raw) {
        if (raw == null)
            return new Seed("", null);
        String s = raw.trim();
        if (s.isBlank())
            return new Seed("", null);

        // (tag) prefix
        if (s.startsWith("(")) {
            int idx = s.indexOf(')');
            if (idx > 1 && idx <= 32) {
                String tag = s.substring(1, idx).trim();
                String rest = s.substring(idx + 1).trim();
                if (!tag.isBlank() && !rest.isBlank()) {
                    return new Seed(rest, tag);
                }
            }
        }

        // [tag] prefix
        if (s.startsWith("[")) {
            int idx = s.indexOf(']');
            if (idx > 1 && idx <= 32) {
                String tag = s.substring(1, idx).trim();
                String rest = s.substring(idx + 1).trim();
                if (!tag.isBlank() && !rest.isBlank()) {
                    return new Seed(rest, tag);
                }
            }
        }

        return new Seed(s, null);
    }

    private record Seed(String stripped, @Nullable String tag) {
    }
}
