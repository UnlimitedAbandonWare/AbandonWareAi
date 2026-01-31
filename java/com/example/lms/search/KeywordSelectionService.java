package com.example.lms.search;

import com.example.lms.infra.resilience.FriendShieldPatternDetector;
import com.example.lms.infra.resilience.IrregularityProfiler;
import com.example.lms.infra.resilience.AuxDownTracker;
import com.example.lms.infra.resilience.AuxBlockTracker;
import com.example.lms.infra.resilience.AuxBlockedReason;
import com.example.lms.infra.resilience.NoiseRoutingGate;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.transform.QueryTransformer;
import com.example.lms.service.guard.GuardContextHolder;
import com.abandonware.ai.agent.integrations.TextUtils;
import ai.abandonware.nova.orch.trace.OrchTrace;

import com.example.lms.prompt.QueryKeywordPromptBuilder;
import com.example.lms.search.terms.SelectedTerms;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

/**
 * Service that delegates to an LLM via LangChain4j to select search
 * vocabulary from a conversation. It builds a prompt using the
 * centralised {@link QueryKeywordPromptBuilder}, invokes the injected
 * {@link ChatModel} and attempts to parse the returned JSON into a
 * {@link SelectedTerms} object. Failures during the call or parsing
 * will result in an empty {@link Optional} allowing callers to fall
 * back to rule based extraction.
 */
@Service
public class KeywordSelectionService {
    private static final Logger log = LoggerFactory.getLogger(KeywordSelectionService.class);
    // Always use the mini/low-tier model for keyword selection to control costs
    // and avoid failures when high-tier models are unavailable. The qualifier
    // ensures the "mini" ChatModel bean is injected.
    private final ChatModel chatModel;
    private final QueryKeywordPromptBuilder prompts;
    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // Cache the last successful selection per (domainProfile + base query).
    // This enables rescue on breaker-open / blank output without calling the LLM.
    private final Cache<String, SelectedTerms> selectionCache = Caffeine.newBuilder()
            .maximumSize(512)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    public KeywordSelectionService(
            @Qualifier("fastChatModel") ChatModel chatModel,
            QueryKeywordPromptBuilder prompts) {
        this.chatModel = chatModel;
        this.prompts = prompts;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IrregularityProfiler irregularityProfiler;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private QueryTransformer queryTransformer;

    /**
     * Strip enclosing triple backtick code fences from a JSON string. LLMs
     * sometimes wrap JSON responses in markdown code fences (``` or ```json),
     * causing Jackson to fail during parsing. This helper removes the
     * leading and trailing fences and trims whitespace. When the input
     * does not contain fences it is returned unchanged.
     *
     * @param s the raw LLM response
     * @return a cleaned JSON string without code fences
     */

    private static String sanitizeJson(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        if (t.startsWith("```")) {
            // Remove opening fence with optional language specifier
            t = t.replaceFirst("^```(?:json)?\\s*", "");
            // Remove closing fence
            t = t.replaceFirst("\\s*```\\s*$", "");
        }
        return t;
    }

    private static void traceRule(String msg) {
        if (msg == null)
            return;
        String m = msg.trim();
        if (m.isEmpty())
            return;
        if (m.length() > 220)
            m = m.substring(0, 220) + "…";
        try {
            TraceStore.append("web.selectedTerms.rules", m);
        } catch (Throwable ignore) {
            // ignore
        }
        try {
            TraceStore.append("keywordSelection.rules", m);
        } catch (Throwable ignore) {
            // ignore
        }
    }

    /**
     * Request term selection from the LLM. When the call succeeds and
     * valid JSON is returned, the parsed {@link SelectedTerms} is
     * returned. Otherwise an empty {@link Optional} is provided and
     * callers may revert to a rule based extractor.
     *
     * @param conversation  full conversation history (never null)
     * @param domainProfile inferred domain profile or "general" when unknown
     * @param maxMust       maximum number of must keywords to include; values ≤0
     *                      are coerced to 1
     * @return an {@link Optional} containing the selected terms or empty when
     *         parsing fails
     */
    public Optional<SelectedTerms> select(String conversation, String domainProfile, int maxMust) {
        int mustLim = Math.max(1, maxMust); // Moved outside try for catch-block access

        String cacheKey = cacheKey(conversation, domainProfile, mustLim);
        SelectedTerms cached = selectionCache.getIfPresent(cacheKey);

        // UAW: If auxiliary LLM calls are already degraded for this request (or we are
        // in STRIKE/COMPRESSION),
        // block additional keyword-selection calls and fall back to deterministic
        // extraction.
        try {
            var ctx = GuardContextHolder.getOrDefault();
            boolean breakerOpen = false;
            boolean otherAuxOpen = false;
            boolean blockByOtherAuxOpen = false;
            if (nightmareBreaker != null) {
                // Stage-scoped breaker: keyword selection has its own key.
                breakerOpen = nightmareBreaker.isOpen(NightmareKeys.KEYWORD_SELECTION_SELECT);

                // Optional policy: when other aux breakers are OPEN (most commonly
                // query-transformer timeouts),
                // block keyword selection as well to avoid cascading timeouts/blank output.
                otherAuxOpen = nightmareBreaker.isAnyOpen(
                        NightmareKeys.QUERY_TRANSFORMER_RUN_LLM,
                        NightmareKeys.DISAMBIGUATION_CLARIFY,
                        NightmareKeys.FAST_LLM_COMPLETE);
                if (otherAuxOpen && !breakerOpen) {
                    try {
                        TraceStore.put("aux.keywordSelection.breaker.scope", "isolated");
                        TraceStore.put("aux.keywordSelection.breaker.otherAuxOpen", true);
                    } catch (Throwable ignore) {
                        // best-effort
                    }

                    boolean blockWhenOtherAuxBreakerOpen = Boolean.parseBoolean(
                            System.getProperty("orch.keywordSelection.blockWhenOtherAuxBreakerOpen", "false"));
                    if (blockWhenOtherAuxBreakerOpen) {
                        blockByOtherAuxOpen = true;
                        try {
                            TraceStore.put("aux.keywordSelection.breaker.otherAuxOpen.blocked", true);
                        } catch (Throwable ignore) {
                            // best-effort
                        }
                    }
                }
            }
            boolean ctxSaysBlock = ctx != null && (ctx.isAuxHardDown() || ctx.isAuxDegraded() || ctx.isStrikeMode()
                    || ctx.isCompressionMode() || ctx.isBypassMode());

            // QTX soft-cooldown: when QueryTransformer already throttles aux LLM calls,
            // avoid calling the fast LLM here (prevents blank/low-quality seeds looping).
            boolean qtxFailureCooldown = false;
            String qtxSignal = null;
            try {
                // Global QTX soft-cooldown signal (cross-thread / cross-stage):
                // If QueryTransformer is already in a soft cooldown window due to TIMEOUT,
                // avoid calling keyword-selection LLM (prevents repeated blank responses).
                if (queryTransformer != null) {
                    try {
                        long rem = queryTransformer.getSoftCooldownRemainingMs();
                        if (rem > 0) {
                            qtxFailureCooldown = true;
                            qtxSignal = "qtx.softCooldown.remainingMs";
                            TraceStore.put("qtx.softCooldown.active", true);
                            TraceStore.put("qtx.softCooldown.remainingMs", rem);
                            TraceStore.putIfAbsent("aux.keywordSelection.blocked.qtx.remainingMs", rem);
                        }
                    } catch (Throwable ignore) {
                        // best-effort
                    }
                }

                Object v = TraceStore.get("qtx.softCooldown.active");
                if (v != null) {
                    String s = String.valueOf(v).trim();
                    if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
                        qtxFailureCooldown = true;
                        qtxSignal = "qtx.softCooldown.active";
                    }
                }

                if (!qtxFailureCooldown) {
                    Object v2 = TraceStore.get("aux.queryTransformer.degraded");
                    if (v2 != null) {
                        String s2 = String.valueOf(v2).trim();
                        if ("true".equalsIgnoreCase(s2) || "1".equals(s2)) {
                            qtxFailureCooldown = true;
                            qtxSignal = "aux.queryTransformer.degraded";
                        }
                    }
                }

                if (!qtxFailureCooldown) {
                    String rr = String.valueOf(TraceStore.get("aux.queryTransformer.degraded.reason"));
                    if (rr != null && !rr.isBlank()) {
                        String lr = rr.toLowerCase(java.util.Locale.ROOT);
                        if (lr.contains("cooldown") || lr.contains("degraded") || lr.contains("soft")) {
                            qtxFailureCooldown = true;
                            qtxSignal = "aux.queryTransformer.degraded.reason";
                        }
                    }
                }

                if (qtxFailureCooldown) {
                    TraceStore.putIfAbsent("aux.keywordSelection.blocked.qtx", true);
                }
            } catch (Throwable ignore) {
                // best-effort
            }

            if (!breakerOpen && !ctxSaysBlock && !blockByOtherAuxOpen && qtxFailureCooldown) {
                try {
                    TraceStore.put("keywordSelection.qtxGate", true);
                    TraceStore.put("keywordSelection.qtxGate.reason", AuxBlockedReason.FAILURE_COOLDOWN.code());
                    if (qtxSignal != null) {
                        TraceStore.put("keywordSelection.qtxGate.qtx", qtxSignal);
                    }
                    TraceStore.put("keywordSelection.mode", "blocked_qtxGate");
                    TraceStore.put("keywordSelection.reason", AuxBlockedReason.FAILURE_COOLDOWN.code());
                    TraceStore.putIfAbsent("aux.keywordSelection", "blocked:qtx_gate");
                    AuxBlockTracker.markStageBlocked(
                            "keywordSelection",
                            AuxBlockedReason.FAILURE_COOLDOWN,
                            "KeywordSelectionService.select(qtxGate)",
                            (String) null);
                } catch (Throwable ignore) {
                }
                traceRule("keywordSelection.mode=blocked_qtxGate (failure-cooldown)");
                if (cached != null) {
                    try {
                        TraceStore.put("keywordSelection.mode", "cache_rescue_qtxGate");
                    } catch (Throwable ignore) {
                    }
                    traceRule("keywordSelection.mode=cache_rescue_qtxGate");
                    return Optional.of(ensureMinMust(deepCopy(cached), conversation, domainProfile, 2,
                            "cache_rescue_qtxGate"));
                }
                SelectedTerms fb = fallbackTerms(conversation, domainProfile, mustLim);
                return Optional.of(ensureMinMust(fb, conversation, domainProfile, 2, "fallback_qtxGate"));
            }

            if (breakerOpen || ctxSaysBlock || blockByOtherAuxOpen) {
                AuxBlockedReason reason = (blockByOtherAuxOpen && !breakerOpen && !ctxSaysBlock)
                        ? AuxBlockedReason.OTHER_AUX_BREAKER_OPEN
                        : AuxBlockTracker.resolveReason(breakerOpen, ctx);

                // NoiseGate: in COMPRESSION-only mode, allow a small fraction of requests to
                // still run
                // keyword selection to avoid deterministic quality cliffs from false positives.
                boolean noiseEscaped = false;
                double noiseEscapeP = 0.0;
                double noiseRoll = 1.0;
                if (!breakerOpen && reason == AuxBlockedReason.COMPRESSION) {
                    try {
                        boolean stageNoiseEnabled = Boolean.parseBoolean(
                                System.getProperty("orch.noiseGate.keywordSelection.compression.enabled", "true"));
                        if (stageNoiseEnabled) {
                            double irr = (ctx != null) ? ctx.getIrregularityScore() : 0.0;
                            double max = Double.parseDouble(System
                                    .getProperty("orch.noiseGate.keywordSelection.compression.escapeP.max", "0.14"));
                            double min = Double.parseDouble(System
                                    .getProperty("orch.noiseGate.keywordSelection.compression.escapeP.min", "0.02"));
                            double t = Math.min(1.0, Math.max(0.0, (irr - 0.35) / 0.45));
                            double escapeP = max + (min - max) * t;

                            NoiseRoutingGate.GateDecision gd = NoiseRoutingGate
                                    .decideEscape("keywordSelection.compression", escapeP, ctx);
                            noiseEscaped = gd.escape();
                            noiseEscapeP = gd.escapeP();
                            noiseRoll = gd.roll();
                        }
                    } catch (Throwable ignore) {
                        // fail-soft
                    }
                }

                // NoiseGate: when other aux breakers are OPEN, this is a *soft block*.
                // Allow a tiny escape probability so we don't over-block keyword selection.
                if (!breakerOpen && reason == AuxBlockedReason.OTHER_AUX_BREAKER_OPEN) {
                    try {
                        boolean stageNoiseEnabled = Boolean.parseBoolean(
                                System.getProperty("orch.noiseGate.keywordSelection.otherAuxOpen.enabled", "true"));
                        if (stageNoiseEnabled) {
                            double irr = (ctx != null) ? ctx.getIrregularityScore() : 0.0;
                            double max = Double.parseDouble(System
                                    .getProperty("orch.noiseGate.keywordSelection.otherAuxOpen.escapeP.max", "0.06"));
                            double min = Double.parseDouble(System
                                    .getProperty("orch.noiseGate.keywordSelection.otherAuxOpen.escapeP.min", "0.01"));
                            double t = Math.min(1.0, Math.max(0.0, (irr - 0.35) / 0.45));
                            double escapeP = max + (min - max) * t;

                            NoiseRoutingGate.GateDecision gd = NoiseRoutingGate
                                    .decideEscape("keywordSelection.otherAuxOpen", escapeP, ctx);
                            noiseEscaped = gd.escape();
                            noiseEscapeP = gd.escapeP();
                            noiseRoll = gd.roll();
                        }
                    } catch (Throwable ignore) {
                        // fail-soft
                    }
                }

                if (!noiseEscaped) {
                    try {
                        TraceStore.put("keywordSelection.mode", "blocked");
                        TraceStore.put("keywordSelection.reason", reason.code());
                        TraceStore.putIfAbsent("aux.keywordSelection", "blocked:" + reason.code());
                        String breakerKey = (reason == AuxBlockedReason.OTHER_AUX_BREAKER_OPEN)
                                ? null
                                : NightmareKeys.KEYWORD_SELECTION_SELECT;
                        String note = (reason == AuxBlockedReason.OTHER_AUX_BREAKER_OPEN)
                                ? "KeywordSelectionService.select(otherAuxOpen)"
                                : "KeywordSelectionService.select";
                        AuxBlockTracker.markStageBlocked("keywordSelection", reason, note, breakerKey);
                    } catch (Throwable ignore) {
                    }
                    traceRule("keywordSelection.mode=blocked (" + reason.code() + ")");
                    if (cached != null) {
                        try {
                            TraceStore.put("keywordSelection.mode", "cache_rescue_blocked");
                        } catch (Throwable ignore) {
                        }
                        traceRule("keywordSelection.mode=cache_rescue_blocked");
                        return Optional.of(ensureMinMust(deepCopy(cached), conversation, domainProfile, 2,
                                "cache_rescue_blocked"));
                    }
                    return Optional.of(fallbackTerms(conversation, domainProfile, mustLim));
                }

                // Noise escape breadcrumbs (best-effort)
                try {
                    TraceStore.put("keywordSelection.noiseEscape", true);
                    TraceStore.put("keywordSelection.noiseEscape.escapeP", noiseEscapeP);
                    TraceStore.put("keywordSelection.noiseEscape.roll", noiseRoll);
                } catch (Throwable ignore) {
                }
                try {
                    java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
                    meta.put("reason", reason.code());
                    meta.put("breakerKey", NightmareKeys.KEYWORD_SELECTION_SELECT);
                    meta.put("escapeP", noiseEscapeP);
                    meta.put("roll", noiseRoll);
                    AuxBlockTracker.markStageNoiseOverride(
                            "keywordSelection",
                            "KeywordSelectionService.noiseEscape(" + reason.code() + ")",
                            noiseEscapeP,
                            meta);
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable ignore) {
            // fall through to normal LLM path
        }
        try {
            int maxConvChars = Integer.parseInt(System.getProperty("keywordSelection.maxConversationChars", "2400"));
            int maxConvLines = Integer.parseInt(System.getProperty("keywordSelection.maxConversationLines", "80"));
            String convForPrompt = trimConversationForPrompt(Objects.toString(conversation, ""), maxConvChars,
                    maxConvLines);

            String dp = Objects.toString(domainProfile, "general");
            try {
                TraceStore.put("keywordSelection.domainProfile", dp);
            } catch (Throwable ignore) {
            }
            try {
                TraceStore.put("keywordSelection.maxMust", mustLim);
            } catch (Throwable ignore) {
            }
            traceRule("keywordSelection.start domainProfile=" + dp + " maxMust=" + mustLim);

            String prompt = prompts.buildSelectedTermsJsonPrompt(
                    convForPrompt,
                    dp,
                    mustLim);
            // Invoke the chat model; expecting raw JSON as the sole response
            // Invoke the chat model with a list of messages rather than the
            // deprecated String overload. This conforms to the LangChain4j
            // 1.0.1 API and avoids implicit conversions.
            String json;
            if (nightmareBreaker != null) {
                json = nightmareBreaker.execute(
                        NightmareKeys.KEYWORD_SELECTION_SELECT,
                        prompt,
                        () -> chatModel.chat(java.util.List.of(UserMessage.from(prompt))).aiMessage().text(),
                        FriendShieldPatternDetector::looksLikeSilentFailure,
                        () -> "");
            } else {
                json = chatModel
                        .chat(java.util.List.of(UserMessage.from(prompt)))
                        .aiMessage()
                        .text();
            }
            // Strip markdown fences before JSON parsing
            json = sanitizeJson(json);
            if (json == null || json.isBlank()) {
                // MERGE_HOOK:PROJ_AGENT::KEYWORD_FALLBACK_BLANK_DEGRADED_V1
                // Blank output is a *cheap* degradation signal (not a hard block).
                // Record degraded KPI so dashboards can distinguish "blocked" vs "degraded".
                try {
                    AuxDownTracker.markSoft("keyword-selection", "blank");
                } catch (Throwable ignore) {
                }
                try {
                    TraceStore.put("keywordSelection.mode", "fallback_blank");
                    TraceStore.putIfAbsent("aux.keywordSelection", "degraded:blank");
                    TraceStore.put("aux.keywordSelection.degraded", Boolean.TRUE);
                    TraceStore.put("aux.keywordSelection.degraded.reason", "blank");
                    TraceStore.inc("aux.keywordSelection.degraded.count");
                } catch (Throwable ignore) {
                }
                traceRule("keywordSelection.mode=fallback_blank degraded=true reason=blank");
                if (nightmareBreaker != null) {
                    nightmareBreaker.recordBlank(NightmareKeys.KEYWORD_SELECTION_SELECT, prompt);
                }
                if (irregularityProfiler != null) {
                    irregularityProfiler.bump(GuardContextHolder.getOrDefault(), 0.15, "keyword_blank");
                }
                if (cached != null) {
                    try {
                        TraceStore.put("keywordSelection.mode", "cache_rescue_blank");
                    } catch (Throwable ignore) {
                    }
                    traceRule("keywordSelection.mode=cache_rescue_blank");
                    return Optional
                            .of(ensureMinMust(deepCopy(cached), conversation, domainProfile, 2, "cache_rescue_blank"));
                }
                SelectedTerms fb = ensureMinMust(
                        fallbackTerms(conversation, domainProfile, mustLim),
                        conversation,
                        domainProfile,
                        2,
                        "fallback_blank_seed");
                // Observability: even if ensureMinMust is a no-op (fallbackTerms already has
                // >=2 MUST),
                // record the enforced minimum so dashboards can distinguish
                // degraded-but-rescued cases.
                try {
                    TraceStore.put("aux.keywordSelection.forceMinMust", 2);
                    TraceStore.put("aux.keywordSelection.forceMinMust.reason", "fallback_blank_seed");
                    TraceStore.put("aux.keywordSelection.forceMinMust.applied", true);
                } catch (Throwable ignore) {
                }
                try {
                    // Seed cache even on fallback_blank so subsequent requests can use
                    // cache_rescue_blank
                    // without re-invoking the LLM.
                    selectionCache.put(cacheKey, deepCopy(fb));
                    TraceStore.put("keywordSelection.cacheSeeded", true);
                    TraceStore.put("keywordSelection.cacheSeeded.reason", "fallback_blank");
                } catch (Throwable ignore) {
                    // best-effort
                }
                return Optional.of(fb);
            }
            SelectedTerms terms = om.readValue(json, SelectedTerms.class);
            if (terms == null) {
                AuxDownTracker.markSoft("keyword-selection", "json-null");
                try {
                    TraceStore.put("keywordSelection.mode", "fallback_json_null");
                    TraceStore.putIfAbsent("aux.keywordSelection", "degraded:json-null");
                    TraceStore.put("aux.keywordSelection.degraded", Boolean.TRUE);
                    TraceStore.put("aux.keywordSelection.degraded.reason", "json-null");
                    TraceStore.inc("aux.keywordSelection.degraded.count");
                } catch (Throwable ignore) {
                }
                SelectedTerms fb = ensureMinMust(fallbackTerms(conversation, domainProfile, mustLim), conversation,
                        domainProfile,
                        2,
                        "fallback_json_null");
                try {
                    selectionCache.put(cacheKey, deepCopy(fb));
                } catch (Throwable ignore) {
                }
                return Optional.of(fb);
            }

            // Normalise + trim + cap list sizes
            // Cap list sizes using mustLim for MUST, default 5 for other term types
            terms.setMust(copyList(terms.getMust(), mustLim));
            terms.setExact(copyList(terms.getExact(), 5));
            terms.setShould(copyList(terms.getShould(), 5));
            terms.setMaybe(copyList(terms.getMaybe(), 5));
            terms.setNegative(copyList(terms.getNegative(), 5));
            terms.setDomains(copyList(terms.getDomains(), 5));
            terms.setAliases(copyList(terms.getAliases(), 5));

            // Normalise domainProfile; default to provided domain or 'general'
            if (terms.getDomainProfile() == null || terms.getDomainProfile().isBlank()) {
                terms.setDomainProfile(Objects.toString(domainProfile, "general"));
            }

            // Empty-Guard: JSON may parse but still yield empty seeds.
            // Ensure at least one meaningful must-term so downstream query planning doesn't
            // collapse.
            if (terms.getMust() == null || terms.getMust().isEmpty()) {
                AuxDownTracker.markSoft("keyword-selection", "empty_must");
                try {
                    TraceStore.putIfAbsent("aux.keywordSelection", "degraded:empty_must");
                    TraceStore.put("aux.keywordSelection.degraded", Boolean.TRUE);
                    TraceStore.put("aux.keywordSelection.degraded.reason", "empty_must");
                    TraceStore.inc("aux.keywordSelection.degraded.count");
                } catch (Throwable ignore) {
                    // fail-soft
                }

                SelectedTerms cachedForEmpty = selectionCache.getIfPresent(cacheKey);
                if (cachedForEmpty != null && cachedForEmpty.getMust() != null && !cachedForEmpty.getMust().isEmpty()) {
                    terms = ensureMinMust(deepCopy(cachedForEmpty), conversation, domainProfile, 2,
                            "cache_rescue_empty_must");
                } else {
                    terms = ensureMinMust(terms, conversation, domainProfile, 2, "llm_json_empty_must");
                }
            }

            try {
                TraceStore.put("keywordSelection.mode", "llm_json");
            } catch (Throwable ignore) {
            }
            traceRule("keywordSelection.mode=llm_json");
            selectionCache.put(cacheKey, deepCopy(terms));
            return Optional.of(terms);
        } catch (IOException parseEx) {
            log.warn("[KeywordSelection] JSON parse failed, fall back to heuristics. error={}", parseEx.getMessage());
            try {
                AuxDownTracker.markSoft("keyword-selection", "parse-fail");
            } catch (Throwable ignore) {
            }
            try {
                TraceStore.put("keywordSelection.mode", "fallback_parse");
                TraceStore.putIfAbsent("aux.keywordSelection", "degraded:parse-fail");
                TraceStore.put("aux.keywordSelection.degraded", Boolean.TRUE);
                TraceStore.put("aux.keywordSelection.degraded.reason", "parse-fail");
                TraceStore.inc("aux.keywordSelection.degraded.count");
            } catch (Throwable ignore) {
            }
            traceRule("keywordSelection.mode=fallback_parse (invalid json)");
            if (nightmareBreaker != null) {
                nightmareBreaker.recordSilentFailure(NightmareKeys.KEYWORD_SELECTION_SELECT, "invalid_json",
                        "parse_failed");
            }
            if (irregularityProfiler != null) {
                irregularityProfiler.bump(GuardContextHolder.getOrDefault(), 0.10, "keyword_parse_failed");
            }
            if (cached != null) {
                try {
                    TraceStore.put("keywordSelection.mode", "cache_rescue_parse");
                } catch (Throwable ignore) {
                }
                traceRule("keywordSelection.mode=cache_rescue_parse");
                return Optional
                        .of(ensureMinMust(deepCopy(cached), conversation, domainProfile, 2, "cache_rescue_parse"));
            }
            return Optional.of(fallbackTerms(conversation, domainProfile, mustLim));
        } catch (Exception ex) {
            log.error("[KeywordSelection] unexpected failure in keyword selection, fall back to heuristics", ex);
            try {
                AuxDownTracker.markDegraded("keyword-selection", "exception", ex);
            } catch (Throwable ignore) {
            }
            try {
                TraceStore.put("keywordSelection.mode", "fallback_exception");
                TraceStore.putIfAbsent("aux.keywordSelection", "degraded:exception");
                TraceStore.put("aux.keywordSelection.degraded", Boolean.TRUE);
                TraceStore.put("aux.keywordSelection.degraded.reason", "exception");
                TraceStore.inc("aux.keywordSelection.degraded.count");
            } catch (Throwable ignore) {
            }
            traceRule("keywordSelection.mode=fallback_exception");
            if (nightmareBreaker != null) {
                NightmareBreaker.FailureKind kind = NightmareBreaker.classify(ex);
                nightmareBreaker.recordFailure(NightmareKeys.KEYWORD_SELECTION_SELECT, kind, ex, "keyword_selection");
            }
            if (irregularityProfiler != null) {
                irregularityProfiler.markHighRisk(GuardContextHolder.getOrDefault(), "keyword_failed");
            }
            if (cached != null) {
                try {
                    TraceStore.put("keywordSelection.mode", "cache_rescue_exception");
                } catch (Throwable ignore) {
                }
                traceRule("keywordSelection.mode=cache_rescue_exception");
                return Optional
                        .of(ensureMinMust(deepCopy(cached), conversation, domainProfile, 2, "cache_rescue_exception"));
            }
            return Optional.of(fallbackTerms(conversation, domainProfile, mustLim));
        }
    }

    private static String cacheKey(String conversation, String domainProfile, int mustLim) {
        String dp = Objects.toString(domainProfile, "general");
        String d = dp.trim().toLowerCase(Locale.ROOT);

        String conv = Objects.toString(conversation, "");
        String base = extractLikelyUserQuery(conv);

        // Prefer request-scoped userQuery when available: it's a cleaner, less
        // collision-prone seed than raw conversation tail.
        String userQuery = null;
        try {
            var gctx = GuardContextHolder.getOrDefault();
            userQuery = (gctx == null) ? null : gctx.getUserQuery();
        } catch (Throwable ignore) {
            userQuery = null;
        }

        String seed = (userQuery != null && !userQuery.isBlank()) ? userQuery : base;
        String norm = TextUtils.normalizeQueryKey(Objects.toString(seed, ""));
        if (norm.isBlank()) {
            norm = Objects.toString(seed, "").trim().toLowerCase(Locale.ROOT);
        }
        if (norm.length() > 128) {
            norm = norm.substring(0, 128);
        }

        // Add a short fingerprint of the conversation tail to reduce cache collisions
        // (and cross-session contamination),
        // while keeping the key short and non-PII (hash only).
        String tail = conv;
        if (tail.length() > 240) {
            tail = tail.substring(tail.length() - 240);
        }
        String tailNorm = TextUtils.normalizeQueryKey(tail);
        String h8 = TextUtils.sha1(d + "|" + mustLim + "|" + norm + "|" + tailNorm);
        if (h8.length() > 8) {
            h8 = h8.substring(0, 8);
        }

        try {
            TraceStore.put("aux.keywordSelection.cacheKey.hash8", h8);
            TraceStore.put("aux.keywordSelection.cacheKey.seedSource",
                    (userQuery != null && !userQuery.isBlank()) ? "userQuery" : "conversation");
        } catch (Throwable ignore) {
        }

        return d + "|" + mustLim + "|" + norm + "|" + h8;
    }

    private static SelectedTerms deepCopy(SelectedTerms in) {
        if (in == null) {
            return null;
        }
        SelectedTerms out = new SelectedTerms();
        out.setExact(copyList(in.getExact()));
        out.setMust(copyList(in.getMust()));
        out.setShould(copyList(in.getShould()));
        out.setMaybe(copyList(in.getMaybe()));
        out.setNegative(copyList(in.getNegative()));
        out.setDomains(copyList(in.getDomains()));
        out.setAliases(copyList(in.getAliases()));
        out.setDomainProfile(in.getDomainProfile());
        return out;
    }

    private static List<String> copyList(List<String> in) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            if (s == null)
                continue;
            String t = s.trim();
            if (t.isBlank())
                continue;
            out.add(t);
        }
        return out;
    }

    /**
     * Overloaded copyList with size limit: returns at most {@code limit} non-blank
     * items.
     */
    private static List<String> copyList(List<String> in, int limit) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        int cap = Math.max(0, limit);
        List<String> out = new ArrayList<>(Math.min(in.size(), cap));
        for (String s : in) {
            if (out.size() >= cap)
                break;
            if (s == null)
                continue;
            String t = s.trim();
            if (t.isBlank())
                continue;
            out.add(t);
        }
        return out;
    }

    private static SelectedTerms ensureMinMust(
            SelectedTerms in,
            String conversation,
            String domainProfile,
            int minMust,
            String reason) {
        int min = Math.max(0, minMust);
        if (min <= 0) {
            return in;
        }

        if (in == null) {
            SelectedTerms fb = fallbackTerms(conversation, domainProfile, Math.max(min, 2));
            try {
                TraceStore.put("aux.keywordSelection.forceMinMust", Math.max(min, 2));
                TraceStore.put("aux.keywordSelection.forceMinMust.reason", reason);
                TraceStore.put("aux.keywordSelection.forceMinMust.applied", true);
                TraceStore.inc("aux.keywordSelection.forceMinMust.count");
            } catch (Throwable ignore) {
            }
            // Observability: record non-empty MUST seeds for downstream verification
            try {
                int mc = (fb.getMust() == null) ? 0 : copyList(fb.getMust()).size();
                TraceStore.put("aux.keywordSelection.must.count", mc);
                TraceStore.put("aux.keywordSelection.must.nonBlank.count", mc);
                TraceStore.put("aux.keywordSelection.must.atLeastOne", mc > 0);
            } catch (Throwable ignore) {
            }
            return fb;
        }

        java.util.LinkedHashSet<String> must = new java.util.LinkedHashSet<>();
        for (String s : copyList(in.getMust())) {
            if (s == null)
                continue;
            String t = s.trim();
            if (t.isBlank())
                continue;
            must.add(t);
        }

        if (must.size() >= min) {
            // Normalise the MUST list (trim + de-dup) and record seed count for soak
            // verification.
            try {
                in.setMust(new java.util.ArrayList<>(must));
            } catch (Throwable ignore) {
                // ignore
            }
            try {
                TraceStore.put("aux.keywordSelection.must.count", must.size());
                TraceStore.put("aux.keywordSelection.must.nonBlank.count", must.size());
                TraceStore.put("aux.keywordSelection.must.atLeastOne", must.size() > 0);
            } catch (Throwable ignore) {
            }
            return in;
        }

        // Query-aware fallback seeds (anchor + secondMust)
        SelectedTerms fb = fallbackTerms(conversation, domainProfile, Math.max(min, 2));
        for (String s : copyList(fb.getMust())) {
            if (must.size() >= min)
                break;
            if (s == null)
                continue;
            String t = s.trim();
            if (t.isBlank())
                continue;
            must.add(t);
        }

        // Last resort: promote fallback SHOULD tokens into MUST
        if (must.size() < min) {
            for (String s : copyList(fb.getShould())) {
                if (must.size() >= min)
                    break;
                if (s == null)
                    continue;
                String t = s.trim();
                if (t.isBlank())
                    continue;
                must.add(t);
            }
        }

        if (must.size() < min) {
            // Emergency non-empty fallback: even if we can't satisfy the configured
            // minimum,
            // keep at least ONE MUST seed so downstream query planning doesn't collapse.
            if (must.size() >= 1) {
                try {
                    in.setMust(new java.util.ArrayList<>(must));
                } catch (Throwable ignore) {
                    // ignore
                }
                try {
                    TraceStore.put("aux.keywordSelection.forceMinMust", min);
                    TraceStore.put("aux.keywordSelection.forceMinMust.reason", reason);
                    TraceStore.put("aux.keywordSelection.forceMinMust.applied", true);
                    TraceStore.put("aux.keywordSelection.forceMinMust.partial", true);
                    TraceStore.inc("aux.keywordSelection.forceMinMust.count");
                } catch (Throwable ignore) {
                }
            }
            try {
                TraceStore.put("aux.keywordSelection.must.count", must.size());
                TraceStore.put("aux.keywordSelection.must.nonBlank.count", must.size());
                TraceStore.put("aux.keywordSelection.must.atLeastOne", must.size() > 0);
            } catch (Throwable ignore) {
            }
            return in;
        }

        in.setMust(new java.util.ArrayList<>(must));
        try {
            TraceStore.put("aux.keywordSelection.forceMinMust", min);
            TraceStore.put("aux.keywordSelection.forceMinMust.reason", reason);
            TraceStore.put("aux.keywordSelection.forceMinMust.applied", true);
            TraceStore.inc("aux.keywordSelection.forceMinMust.count");
        } catch (Throwable ignore) {
        }
        try {
            TraceStore.put("aux.keywordSelection.must.count", must.size());
            TraceStore.put("aux.keywordSelection.must.nonBlank.count", must.size());
            TraceStore.put("aux.keywordSelection.must.atLeastOne", must.size() > 0);
        } catch (Throwable ignore) {
        }
        return in;
    }

    /**
     * Fail-soft fallback: never return an empty selection.
     * This keeps web/query planning alive even when the keyword LLM returns
     * blank/invalid JSON.
     */
    // MERGE_HOOK:PROJ_AGENT::KEYWORD_FALLBACK_QUERY_AWARE_V2
    // Fail-soft fallback: never return an empty selection.
    // - Avoid single-token queries ("곧", "더그림" 등) that collapse recall.
    // - Prefer query-aware boosters (official/info/review/price/location) in SHOULD
    // tokens.
    private enum FallbackIntent {
        LOCATION,
        PRODUCT,
        TECH,
        PERSON,
        GENERAL
    }

    private static final Set<String> FALLBACK_STOPWORDS = Set.of(
            "추천", "알려줘", "알려주세요", "알려", "뭐야", "뭔가", "무엇", "어떻게", "방법", "정리",
            "설명", "해줘", "해주세요", "좀", "그리고", "근데", "가능", "가능해", "가능한", "있어",
            "없어", "찾아줘", "찾아줘요");

    private static SelectedTerms fallbackTerms(String conversation, String domainProfile, int maxMust) {
        int mustLimit = Math.max(2, maxMust);
        if (maxMust < 2) {
            try {
                TraceStore.put("aux.keywordSelection.forceMinMust", 2);
                TraceStore.put("aux.keywordSelection.forceMinMust.reason", "fallbackTerms");
                TraceStore.inc("aux.keywordSelection.forceMinMust.count");
            } catch (Throwable ignore) {
            }
        }
        String conv = Objects.toString(conversation, "");
        String dp = Objects.toString(domainProfile, "general");

        String base = extractLikelyUserQuery(conv);
        String seedSource = "conversation.tail";

        // [PATCH src111_merge15/merge15] When keyword-selection degrades
        // (blank/blocked),
        // the conversation tail can be noisy or trimmed. Prefer GuardContext.userQuery
        // as
        // a stable fallback seed to sharpen MUST anchors and reduce citation
        // starvation.
        try {
            var gctx = GuardContextHolder.getOrDefault();
            String uq = (gctx == null) ? null : gctx.getUserQuery();
            if (uq != null && !uq.isBlank()) {
                int baseScore = fallbackSeedSpecificityScore(base);
                int uqScore = fallbackSeedSpecificityScore(uq);

                // If the conversation tail is too generic, prefer the stable userQuery
                // to avoid low-quality MUST anchors (e.g., "다음 단계", "검색").
                boolean baseTooGeneric = (base == null || base.isBlank() || base.trim().length() < 3)
                        || (baseScore <= 1 && uqScore > baseScore);

                if (baseTooGeneric) {
                    base = uq;
                    seedSource = "guardContext.userQuery";
                    try {
                        TraceStore.put("keywordSelection.fallback.seed", "guardContext.userQuery");
                        TraceStore.put("keywordSelection.fallback.seed.baseScore", baseScore);
                        TraceStore.put("keywordSelection.fallback.seed.uqScore", uqScore);
                    } catch (Throwable ignore) {
                        // best-effort
                    }
                }
            }
        } catch (Throwable ignore) {
            // best-effort
        }

        String anchorCandidate = (base == null) ? "" : base.trim();

        // If the last line is too short, scan earlier lines for a better anchor.
        if (anchorCandidate.length() < 3) {
            String alt = scanConversationForAnchor(conv, 3);
            if (alt != null && !alt.isBlank()) {
                anchorCandidate = alt;
            }
        }

        String anchor = pickAnchor(anchorCandidate);
        if (anchor.isBlank()) {
            anchor = anchorCandidate;
        }

        // Query-aware fallback: add a second core token so we don't end up with a
        // single-anchor MUST.
        String secondMust = pickSecondMust(anchorCandidate, anchor);

        FallbackIntent intent = detectIntent(anchorCandidate, dp);
        boolean ko = containsHangul(anchorCandidate) || containsHangul(dp);

        LinkedHashSet<String> extra = new LinkedHashSet<>();

        if (ko) {
            switch (intent) {
                case LOCATION -> {
                    extra.add("위치");
                    extra.add("근처");
                    extra.add("주소");
                    extra.add("후기");
                    extra.add("가격");
                }
                case PRODUCT -> {
                    extra.add("가격");
                    extra.add("스펙");
                    extra.add("리뷰");
                    extra.add("후기");
                    extra.add("비교");
                }
                case TECH -> {
                    extra.add("공식 문서");
                    extra.add("설정");
                    extra.add("에러");
                    extra.add("해결");
                    extra.add("방법");
                }
                case PERSON -> {
                    extra.add("프로필");
                    extra.add("약력");
                    extra.add("경력");
                    extra.add("소속");
                }
                default -> {
                    extra.add("공식 홈페이지");
                    extra.add("소개");
                    extra.add("후기");
                }
            }
            // Always keep at least one official/info hint to support strict-domain plans.
            extra.add("공식 홈페이지");
        } else {
            switch (intent) {
                case LOCATION -> {
                    extra.add("near me");
                    extra.add("location");
                    extra.add("reviews");
                    extra.add("price");
                }
                case PRODUCT -> {
                    extra.add("price");
                    extra.add("specs");
                    extra.add("review");
                    extra.add("comparison");
                }
                case TECH -> {
                    extra.add("official docs");
                    extra.add("setup");
                    extra.add("error");
                    extra.add("fix");
                }
                case PERSON -> {
                    extra.add("profile");
                    extra.add("bio");
                    extra.add("career");
                }
                default -> {
                    extra.add("official site");
                    extra.add("overview");
                    extra.add("review");
                }
            }
            extra.add("official");
        }

        // Remove boosters already present in anchor (best-effort).
        String anchorLower = (anchor + " " + secondMust).toLowerCase(Locale.ROOT);
        extra.removeIf(t -> t == null || t.isBlank() || anchorLower.contains(t.toLowerCase(Locale.ROOT)));

        // derive an exact phrase (preferably the entity name) so SmartQueryPlanner can
        // wrap it in quotes
        // and anchor web recall even under degraded MUST selection.
        List<String> exact = new ArrayList<>();
        try {
            String exactPhrase = anchorCandidate;
            if (exactPhrase != null) {
                // strip common definitional suffixes/prefixes (ko/en) and trailing punctuation
                exactPhrase = exactPhrase
                        .replaceAll("(?i)\\b(누구야|누구냐|뭐야|뭐냐|무엇|정의|뜻|meaning|definition|who\\s+is|what\\s+is)\\b", " ")
                        .replaceAll("[?？!！]+$", "")
                        .replaceAll("\\s+", " ")
                        .trim();
            }

            String source = "full_query";
            // Prefer a short leading entity phrase (e.g., "아이리 칸나" from "아이리 칸나 공식 홈페이지").
            String entityPhrase = extractLeadingEntityPhrase(exactPhrase);
            if (entityPhrase != null && !entityPhrase.isBlank()) {
                exactPhrase = entityPhrase;
                source = "entity_phrase";
                try {
                    TraceStore.put("keywordSelection.fallback.entityPhrase", entityPhrase);
                } catch (Throwable ignore) {
                    // best-effort
                }
            }

            // Keep only reasonably short phrases; single tokens are already covered by
            // MUST.
            if (exactPhrase != null && exactPhrase.length() >= 2 && exactPhrase.length() <= 80
                    && exactPhrase.contains(" ")) {
                exact.add(exactPhrase);
                try {
                    TraceStore.put("keywordSelection.fallback.exact", exactPhrase);
                    TraceStore.put("keywordSelection.fallback.exact.source", source);
                } catch (Throwable ignore) {
                    // best-effort
                }
            }
        } catch (Throwable ignore) {
            // best-effort
        }

        List<String> must = new ArrayList<>();
        if (!anchor.isBlank()) {
            must.add(anchor);
        }
        if (secondMust != null && !secondMust.isBlank()
                && must.stream().noneMatch(x -> x != null && x.equalsIgnoreCase(secondMust))) {
            must.add(secondMust);
        }
        if (must.isEmpty()) {
            must.add(ko ? "검색" : "search");
        }

        List<String> should = new ArrayList<>();
        for (String t : extra) {
            if (t == null)
                continue;
            String tt = t.trim();
            if (tt.isBlank())
                continue;
            should.add(tt);
            if (should.size() >= 4)
                break;
        }

        // Ensure we have at least 2 tokens in total when possible.
        if (should.isEmpty()) {
            should.add(ko ? "정보" : "info");
        }

        // If we have room, promote one booster into MUST.
        while (must.size() < mustLimit && !should.isEmpty()) {
            must.add(should.remove(0));
        }

        SelectedTerms st = new SelectedTerms();
        st.setMust(must);
        st.setExact(exact);
        st.setShould(should);
        st.setMaybe(List.of());
        st.setNegative(List.of());
        st.setDomains(List.of());
        st.setAliases(List.of());
        st.setDomainProfile(dp);

        try {
            TraceStore.put("keywordSelection.fallback.anchor", anchor);
            TraceStore.put("keywordSelection.fallback.intent", intent.name());
            TraceStore.put("keywordSelection.fallback.seedSource", seedSource);
            TraceStore.put("keywordSelection.fallback.must", must);
            TraceStore.put("keywordSelection.fallback.should", should);
            if (secondMust != null && !secondMust.isBlank()) {
                TraceStore.put("keywordSelection.fallback.secondMust", secondMust);
            }
            TraceStore.put("keywordSelection.fallback.mustLimit", mustLimit);
        } catch (Throwable ignore) {
            // best-effort
        }

        // [DEBUG] Structured event (visible in EvidenceListTraceInjectionAspect) for
        // degraded keyword selection.
        try {
            String seedPreview = anchorCandidate;
            if (seedPreview != null && seedPreview.length() > 120)
                seedPreview = seedPreview.substring(0, 120);
            OrchTrace.appendEvent(OrchTrace.newEvent(
                    "aux",
                    "keywordSelection",
                    "fallbackTerms",
                    java.util.Map.of(
                            "seedSource", seedSource,
                            "seed", seedPreview == null ? "" : seedPreview,
                            "intent", intent.name(),
                            "anchor", anchor == null ? "" : anchor,
                            "secondMust", secondMust == null ? "" : secondMust,
                            "exact", exact.isEmpty() ? "" : exact.get(0),
                            "mustSize", must.size(),
                            "shouldSize", should.size())));
        } catch (Throwable ignore) {
            // best-effort
        }

        return st;
    }

    private static String extractLeadingEntityPhrase(String s) {
        if (s == null)
            return "";
        String t = s.trim();
        if (t.isBlank())
            return "";

        String norm = t.replaceAll("[\\p{Punct}]+", " ").replaceAll("\\s+", " ").trim();
        if (norm.isBlank())
            return "";

        String[] toks = norm.split("\\s+");
        if (toks.length < 2)
            return "";

        String t0 = toks[0] == null ? "" : toks[0].trim();
        String t1 = toks[1] == null ? "" : toks[1].trim();
        if (t0.isBlank() || t1.isBlank())
            return "";

        if (!looksLikeEntityToken(t0) || !looksLikeEntityToken(t1))
            return "";

        String phrase = (t0 + " " + t1).trim();
        if (phrase.length() < 2 || phrase.length() > 80)
            return "";
        return phrase;
    }

    private static boolean looksLikeEntityToken(String w) {
        if (w == null)
            return false;
        String t = w.trim();
        if (t.isBlank())
            return false;
        if (t.length() < 2 || t.length() > 32)
            return false;

        String tl = t.toLowerCase(Locale.ROOT);
        if (FALLBACK_STOPWORDS.contains(t) || FALLBACK_STOPWORDS.contains(tl))
            return false;

        // year/number-ish tokens
        if (t.matches("\\d{4}.*") || (t.matches(".*\\d.*") && !t.matches(".*[a-zA-Z].*")))
            return false;

        boolean hasLatin = t.matches(".*[a-zA-Z].*");
        boolean hasHangul = containsHangul(t);
        return hasLatin || hasHangul;
    }

    private static String scanConversationForAnchor(String conversation, int minLen) {
        if (conversation == null || conversation.isBlank())
            return "";
        String[] lines = conversation.strip().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = (lines[i] == null) ? "" : lines[i].trim();
            if (line.isBlank())
                continue;
            line = line.replaceFirst("^(USER|사용자|Q|질문)\\s*[:：]\\s*", "").trim();
            if (line.length() < minLen)
                continue;
            if (line.length() > 140)
                line = line.substring(0, 140);
            return line;
        }
        return "";
    }

    private static String pickAnchor(String s) {
        if (s == null)
            return "";
        String t = s.trim();
        if (t.isBlank())
            return "";

        // Normalize punctuation to spaces.
        String norm = t.replaceAll("[\\p{Punct}]+", " ").replaceAll("\\s+", " ").trim();
        if (norm.isBlank())
            return "";

        String best = "";
        for (String tok : norm.split("\\s+")) {
            if (tok == null)
                continue;
            String w = tok.trim();
            if (w.isBlank())
                continue;
            if (w.length() < 2)
                continue;
            // Avoid anchoring on year/number-ish tokens (e.g., "2026년에").
            // Keep tokens that include latin letters (e.g., "RTX4090") as they can be real
            // entities.
            if (w.matches("\\d{4}.*") || (w.matches(".*\\d.*") && !w.matches(".*[a-zA-Z].*")))
                continue;
            if (FALLBACK_STOPWORDS.contains(w))
                continue;
            if (w.length() > best.length())
                best = w;
        }

        String out = best.isBlank() ? norm : best;
        if (out.length() > 64)
            out = out.substring(0, 64);
        return out;
    }

    /**
     * Pick a second core token from the query (different from the primary anchor).
     *
     * <p>
     * Used only in fallback mode when the keyword-selection LLM returns blank,
     * to avoid single-token MUST plans (which tend to be overly broad and
     * unstable).
     * </p>
     */
    private static String pickSecondMust(String s, String primary) {
        if (s == null)
            return "";
        String t = s.trim();
        if (t.isBlank())
            return "";

        String p = (primary == null) ? "" : primary.trim();
        String pLower = p.isBlank() ? "" : p.toLowerCase(Locale.ROOT);

        // Normalize punctuation to spaces.
        String norm = t.replaceAll("[\\p{Punct}]+", " ").replaceAll("\\s+", " ").trim();
        if (norm.isBlank())
            return "";

        String best = "";
        for (String tok : norm.split("\\s+")) {
            if (tok == null)
                continue;
            String w = tok.trim();
            if (w.isBlank())
                continue;
            if (w.length() < 2)
                continue;

            String wl = w.toLowerCase(Locale.ROOT);
            if (!pLower.isBlank() && wl.equals(pLower))
                continue;

            // stopwords (case-insensitive best-effort)
            if (FALLBACK_STOPWORDS.contains(w) || FALLBACK_STOPWORDS.contains(wl))
                continue;

            // avoid numeric-only tokens
            if (w.matches("\\d+"))
                continue;

            // Avoid year/number-ish tokens (e.g., "2026년에").
            // Keep tokens that include latin letters (e.g., "RTX4090") as they can be real
            // entities.
            if (w.matches("\\d{4}.*") || (w.matches(".*\\d.*") && !w.matches(".*[a-zA-Z].*")))
                continue;

            if (w.length() > best.length())
                best = w;
        }

        if (best.isBlank())
            return "";
        if (best.length() > 64)
            best = best.substring(0, 64);
        return best;
    }

    private static boolean containsHangul(String s) {
        if (s == null || s.isBlank())
            return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3)
                return true;
        }
        return false;
    }

    private static FallbackIntent detectIntent(String query, String domainProfile) {
        String q = Objects.toString(query, "").toLowerCase(Locale.ROOT);
        String dp = Objects.toString(domainProfile, "").toLowerCase(Locale.ROOT);
        String s = q + " " + dp;

        if (s.matches(".*(근처|가까운|위치|주소|지도|거리|역|맛집|숙소|주변).*")) {
            return FallbackIntent.LOCATION;
        }
        if (s.matches(".*(가격|스펙|사양|비교|구매|리뷰|후기|추천).*")) {
            return FallbackIntent.PRODUCT;
        }
        if (s.matches(".*(에러|오류|exception|stacktrace|설치|설정|how to|docs|documentation|api).*")) {
            return FallbackIntent.TECH;
        }
        if (s.matches(".*(프로필|약력|경력|이력|인물|bio|profile).*")) {
            return FallbackIntent.PERSON;
        }
        return FallbackIntent.GENERAL;
    }



    /**
     * Heuristic "specificity" score for deterministic fallback seeds.
     *
     * <p>Used to decide whether the conversation tail is too generic (e.g., "다음 단계")
     * and we should prefer {@code GuardContext.userQuery} as a stable anchor.</p>
     */
    private static int fallbackSeedSpecificityScore(String text) {
        if (text == null) {
            return 0;
        }
        String s = text.trim();
        if (s.isBlank()) {
            return 0;
        }

        String[] parts = s.split("\\s+");
        int score = 0;
        java.util.HashSet<String> seen = new java.util.HashSet<>();

        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String tok = p.trim();
            if (tok.isEmpty()) {
                continue;
            }
            tok = tok.replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "");
            if (tok.isEmpty()) {
                continue;
            }

            String low = tok.toLowerCase(Locale.ROOT);
            if (FALLBACK_STOPWORDS.contains(low)) {
                continue;
            }
            // Additional Korean command-ish tokens that often appear in short follow-ups.
            if (low.equals("다음") || low.equals("단계") || low.equals("패치")
                    || low.equals("디버깅") || low.equals("수정")
                    || low.equals("해줘") || low.equals("해주세요")
                    || low.equals("부탁해") || low.equals("부탁드립니다")) {
                continue;
            }

            boolean informative = false;

            // Strong signals for technical queries / ids.
            if (tok.indexOf('_') >= 0) {
                informative = true;
            } else {
                for (int i = 0; i < tok.length(); i++) {
                    char c = tok.charAt(i);
                    if (Character.isDigit(c)) {
                        informative = true;
                        break;
                    }
                }
            }

            if (!informative) {
                // If it has letters, require a bit of length to avoid "다음" / "단계" style tokens.
                boolean hasLetter = false;
                for (int i = 0; i < tok.length(); i++) {
                    char c = tok.charAt(i);
                    if (Character.isLetter(c)) {
                        hasLetter = true;
                        break;
                    }
                }
                if (hasLetter && tok.length() >= 3) {
                    informative = true;
                }
            }

            if (!informative) {
                continue;
            }

            if (seen.add(low)) {
                score++;
            }
            if (score >= 6) {
                break;
            }
        }

        return score;
    }

    private static String extractLikelyUserQuery(String conversation) {
        if (conversation == null)
            return "";
        String s = conversation.strip();
        if (s.isEmpty())
            return "";
        // Try to pick the last non-empty line (often the most recent user utterance)
        String[] lines = s.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.isEmpty())
                continue;
            // Strip common role prefixes
            line = line.replaceFirst("^(USER|사용자|Q|질문)\\s*[:：]\\s*", "").trim();
            if (line.length() > 96)
                line = line.substring(0, 96);
            return line;
        }
        return "";
    }

    /**
     * Trim conversation to fit within character/line limits for the prompt.
     */
    private String trimConversationForPrompt(String conversation, int maxChars, int maxLines) {
        if (conversation == null)
            return "";
        String trimmed = conversation;
        if (trimmed.length() > maxChars) {
            trimmed = trimmed.substring(trimmed.length() - maxChars);
        }
        String[] lines = trimmed.split("\\R");
        if (lines.length > maxLines) {
            java.util.StringJoiner sj = new java.util.StringJoiner("\n");
            for (int i = lines.length - maxLines; i < lines.length; i++) {
                sj.add(lines[i]);
            }
            trimmed = sj.toString();
        }
        return trimmed;
    }
}