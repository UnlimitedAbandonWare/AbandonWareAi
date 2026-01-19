package com.example.lms.search;

import com.example.lms.infra.resilience.FriendShieldPatternDetector;
import com.example.lms.infra.resilience.IrregularityProfiler;
import com.example.lms.infra.resilience.AuxDownTracker;
import com.example.lms.infra.resilience.AuxBlockTracker;
import com.example.lms.infra.resilience.AuxBlockedReason;
import com.example.lms.infra.resilience.NoiseRoutingGate;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.service.guard.GuardContextHolder;

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


        // UAW: If auxiliary LLM calls are already degraded for this request (or we are in STRIKE/COMPRESSION),
        // block additional keyword-selection calls and fall back to deterministic extraction.
        try {
            var ctx = GuardContextHolder.getOrDefault();
            boolean breakerOpen = false;
            boolean otherAuxOpen = false;
            boolean blockByOtherAuxOpen = false;
            if (nightmareBreaker != null) {
                // Stage-scoped breaker: keyword selection has its own key.
                breakerOpen = nightmareBreaker.isOpen(NightmareKeys.KEYWORD_SELECTION_SELECT);

                // Optional policy: when other aux breakers are OPEN (most commonly query-transformer timeouts),
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
                            System.getProperty("orch.keywordSelection.blockWhenOtherAuxBreakerOpen", "true"));
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
            if (breakerOpen || ctxSaysBlock || blockByOtherAuxOpen) {
                AuxBlockedReason reason = (blockByOtherAuxOpen && !breakerOpen && !ctxSaysBlock)
                        ? AuxBlockedReason.OTHER_AUX_BREAKER_OPEN
                        : AuxBlockTracker.resolveReason(breakerOpen, ctx);

                // NoiseGate: in COMPRESSION-only mode, allow a small fraction of requests to still run
                // keyword selection to avoid deterministic quality cliffs from false positives.
                boolean noiseEscaped = false;
                double noiseEscapeP = 0.0;
                double noiseRoll = 1.0;
                if (!breakerOpen && reason == AuxBlockedReason.COMPRESSION) {
                    try {
                        boolean stageNoiseEnabled = Boolean.parseBoolean(System.getProperty("orch.noiseGate.keywordSelection.compression.enabled", "true"));
                        if (stageNoiseEnabled) {
                            double irr = (ctx != null) ? ctx.getIrregularityScore() : 0.0;
                            double max = Double.parseDouble(System.getProperty("orch.noiseGate.keywordSelection.compression.escapeP.max", "0.14"));
                            double min = Double.parseDouble(System.getProperty("orch.noiseGate.keywordSelection.compression.escapeP.min", "0.02"));
                            double t = Math.min(1.0, Math.max(0.0, (irr - 0.35) / 0.45));
                            double escapeP = max + (min - max) * t;

                            NoiseRoutingGate.GateDecision gd = NoiseRoutingGate.decideEscape("keywordSelection.compression", escapeP, ctx);
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
                        boolean stageNoiseEnabled = Boolean.parseBoolean(System.getProperty("orch.noiseGate.keywordSelection.otherAuxOpen.enabled", "true"));
                        if (stageNoiseEnabled) {
                            double irr = (ctx != null) ? ctx.getIrregularityScore() : 0.0;
                            double max = Double.parseDouble(System.getProperty("orch.noiseGate.keywordSelection.otherAuxOpen.escapeP.max", "0.06"));
                            double min = Double.parseDouble(System.getProperty("orch.noiseGate.keywordSelection.otherAuxOpen.escapeP.min", "0.01"));
                            double t = Math.min(1.0, Math.max(0.0, (irr - 0.35) / 0.45));
                            double escapeP = max + (min - max) * t;

                            NoiseRoutingGate.GateDecision gd = NoiseRoutingGate.decideEscape("keywordSelection.otherAuxOpen", escapeP, ctx);
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
                        return Optional.of(ensureMinMust(deepCopy(cached), conversation, domainProfile, 2, "cache_rescue_blocked"));
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
                    return Optional.of(ensureMinMust(deepCopy(cached), conversation, domainProfile, 2, "cache_rescue_blank"));
                }
                SelectedTerms fb = ensureMinMust(
                        fallbackTerms(conversation, domainProfile, mustLim),
                        conversation,
                        domainProfile,
                        2,
                        "fallback_blank_seed");
                // Observability: even if ensureMinMust is a no-op (fallbackTerms already has >=2 MUST),
                // record the enforced minimum so dashboards can distinguish degraded-but-rescued cases.
                try {
                    TraceStore.put("aux.keywordSelection.forceMinMust", 2);
                    TraceStore.put("aux.keywordSelection.forceMinMust.reason", "fallback_blank_seed");
                    TraceStore.put("aux.keywordSelection.forceMinMust.applied", true);
                } catch (Throwable ignore) {
                }
                try {
                    // Seed cache even on fallback_blank so subsequent requests can use cache_rescue_blank
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
            if (terms.getMust() == null)
                terms.setMust(List.of());
            if (terms.getExact() == null)
                terms.setExact(List.of());
            if (terms.getShould() == null)
                terms.setShould(List.of());
            if (terms.getMaybe() == null)
                terms.setMaybe(List.of());
            if (terms.getNegative() == null)
                terms.setNegative(List.of());
            if (terms.getDomains() == null)
                terms.setDomains(List.of());
            if (terms.getAliases() == null)
                terms.setAliases(List.of());
            // Normalise domainProfile; default to provided domain or 'general'
            if (terms.getDomainProfile() == null || terms.getDomainProfile().isBlank()) {
                terms.setDomainProfile(Objects.toString(domainProfile, "general"));
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
                return Optional.of(ensureMinMust(deepCopy(cached), conversation, domainProfile, 2, "cache_rescue_parse"));
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
                return Optional.of(ensureMinMust(deepCopy(cached), conversation, domainProfile, 2, "cache_rescue_exception"));
            }
            return Optional.of(fallbackTerms(conversation, domainProfile, mustLim));
        }
    }

    
    private static String cacheKey(String conversation, String domainProfile, int mustLim) {
        String base = extractLikelyUserQuery(Objects.toString(conversation, ""));
        String dp = Objects.toString(domainProfile, "general");
        String b = (base == null) ? "" : base.trim().toLowerCase(Locale.ROOT);
        String d = dp.trim().toLowerCase(Locale.ROOT);
        return d + "|" + mustLim + "|" + b;
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
            return fb;
        }

        java.util.LinkedHashSet<String> must = new java.util.LinkedHashSet<>();
        for (String s : copyList(in.getMust())) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isBlank()) continue;
            must.add(t);
        }

        if (must.size() >= min) {
            return in;
        }

        // Query-aware fallback seeds (anchor + secondMust)
        SelectedTerms fb = fallbackTerms(conversation, domainProfile, Math.max(min, 2));
        for (String s : copyList(fb.getMust())) {
            if (must.size() >= min) break;
            if (s == null) continue;
            String t = s.trim();
            if (t.isBlank()) continue;
            must.add(t);
        }

        // Last resort: promote fallback SHOULD tokens into MUST
        if (must.size() < min) {
            for (String s : copyList(fb.getShould())) {
                if (must.size() >= min) break;
                if (s == null) continue;
                String t = s.trim();
                if (t.isBlank()) continue;
                must.add(t);
            }
        }

        if (must.size() < min) {
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
    // - Prefer query-aware boosters (official/info/review/price/location) in SHOULD tokens.
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

        // Query-aware fallback: add a second core token so we don't end up with a single-anchor MUST.
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
            if (t == null) continue;
            String tt = t.trim();
            if (tt.isBlank()) continue;
            should.add(tt);
            if (should.size() >= 4) break;
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
        st.setExact(List.of());
        st.setShould(should);
        st.setMaybe(List.of());
        st.setNegative(List.of());
        st.setDomains(List.of());
        st.setAliases(List.of());
        st.setDomainProfile(dp);

        try {
            TraceStore.put("keywordSelection.fallback.anchor", anchor);
            TraceStore.put("keywordSelection.fallback.intent", intent.name());
            TraceStore.put("keywordSelection.fallback.must", must);
            TraceStore.put("keywordSelection.fallback.should", should);
            if (secondMust != null && !secondMust.isBlank()) {
                TraceStore.put("keywordSelection.fallback.secondMust", secondMust);
            }
            TraceStore.put("keywordSelection.fallback.mustLimit", mustLimit);
        } catch (Throwable ignore) {
            // best-effort
        }

        return st;
    }

    private static String scanConversationForAnchor(String conversation, int minLen) {
        if (conversation == null || conversation.isBlank()) return "";
        String[] lines = conversation.strip().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = (lines[i] == null) ? "" : lines[i].trim();
            if (line.isBlank()) continue;
            line = line.replaceFirst("^(USER|사용자|Q|질문)\\s*[:：]\\s*", "").trim();
            if (line.length() < minLen) continue;
            if (line.length() > 140) line = line.substring(0, 140);
            return line;
        }
        return "";
    }

    private static String pickAnchor(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isBlank()) return "";

        // Normalize punctuation to spaces.
        String norm = t.replaceAll("[\\p{Punct}]+", " ").replaceAll("\\s+", " ").trim();
        if (norm.isBlank()) return "";

        String best = "";
        for (String tok : norm.split("\\s+")) {
            if (tok == null) continue;
            String w = tok.trim();
            if (w.isBlank()) continue;
            if (w.length() < 2) continue;
            if (FALLBACK_STOPWORDS.contains(w)) continue;
            if (w.length() > best.length()) best = w;
        }

        String out = best.isBlank() ? norm : best;
        if (out.length() > 64) out = out.substring(0, 64);
        return out;
    }

    /**
     * Pick a second core token from the query (different from the primary anchor).
     *
     * <p>
     * Used only in fallback mode when the keyword-selection LLM returns blank,
     * to avoid single-token MUST plans (which tend to be overly broad and unstable).
     * </p>
     */
    private static String pickSecondMust(String s, String primary) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isBlank()) return "";

        String p = (primary == null) ? "" : primary.trim();
        String pLower = p.isBlank() ? "" : p.toLowerCase(Locale.ROOT);

        // Normalize punctuation to spaces.
        String norm = t.replaceAll("[\\p{Punct}]+", " ").replaceAll("\\s+", " ").trim();
        if (norm.isBlank()) return "";

        String best = "";
        for (String tok : norm.split("\\s+")) {
            if (tok == null) continue;
            String w = tok.trim();
            if (w.isBlank()) continue;
            if (w.length() < 2) continue;

            String wl = w.toLowerCase(Locale.ROOT);
            if (!pLower.isBlank() && wl.equals(pLower)) continue;

            // stopwords (case-insensitive best-effort)
            if (FALLBACK_STOPWORDS.contains(w) || FALLBACK_STOPWORDS.contains(wl)) continue;

            // avoid numeric-only tokens
            if (w.matches("\\d+")) continue;

            if (w.length() > best.length()) best = w;
        }

        if (best.isBlank()) return "";
        if (best.length() > 64) best = best.substring(0, 64);
        return best;
    }


    private static boolean containsHangul(String s) {
        if (s == null || s.isBlank()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) return true;
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