package com.example.lms.search;

import com.example.lms.infra.resilience.FriendShieldPatternDetector;
import com.example.lms.infra.resilience.IrregularityProfiler;
import com.example.lms.infra.resilience.AuxDownTracker;
import com.example.lms.infra.resilience.AuxBlockTracker;
import com.example.lms.infra.resilience.AuxBlockedReason;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.service.guard.GuardContextHolder;

import com.example.lms.prompt.QueryKeywordPromptBuilder;
import com.example.lms.search.terms.SelectedTerms;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
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

        // UAW: If auxiliary LLM calls are already degraded for this request (or we are in STRIKE/COMPRESSION),
        // block additional keyword-selection calls and fall back to deterministic extraction.
        try {
            var ctx = GuardContextHolder.getOrDefault();
            boolean breakerOpen = false;
            if (nightmareBreaker != null) {
                breakerOpen = nightmareBreaker.isAnyOpen(
                        NightmareKeys.KEYWORD_SELECTION_SELECT,
                        NightmareKeys.DISAMBIGUATION_CLARIFY,
                        NightmareKeys.QUERY_TRANSFORMER_RUN_LLM,
                        NightmareKeys.FAST_LLM_COMPLETE);
            }
            boolean ctxSaysBlock = ctx != null && (ctx.isAuxHardDown() || ctx.isAuxDegraded() || ctx.isStrikeMode()
                    || ctx.isCompressionMode() || ctx.isBypassMode());
            if (breakerOpen || ctxSaysBlock) {
                AuxBlockedReason reason = AuxBlockTracker.resolveReason(breakerOpen, ctx);
                try {
                    TraceStore.put("keywordSelection.mode", "blocked");
                    TraceStore.put("keywordSelection.reason", reason.code());
                    TraceStore.putIfAbsent("aux.keywordSelection", "blocked:" + reason.code());
                    AuxBlockTracker.markStageBlocked("keywordSelection", reason, "KeywordSelectionService.select", NightmareKeys.KEYWORD_SELECTION_SELECT);
                } catch (Throwable ignore) {
                }
                traceRule("keywordSelection.mode=blocked (" + reason.code() + ")");
                return Optional.of(fallbackTerms(conversation, domainProfile, mustLim));
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
                // UAW: blank output = silent failure, mark aux as degraded for request-scoped routing.
                try {
                    AuxDownTracker.markDegraded("keyword-selection", "blank");
                } catch (Throwable ignore) {
                }
                try {
                    TraceStore.put("keywordSelection.mode", "fallback_blank");
                } catch (Throwable ignore) {
                }
                traceRule("keywordSelection.mode=fallback_blank (blank llm output)");
                if (nightmareBreaker != null) {
                    nightmareBreaker.recordBlank(NightmareKeys.KEYWORD_SELECTION_SELECT, prompt);
                }
                if (irregularityProfiler != null) {
                    irregularityProfiler.bump(GuardContextHolder.getOrDefault(), 0.15, "keyword_blank");
                }
                return Optional.of(fallbackTerms(conversation, domainProfile, mustLim));
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
            return Optional.of(terms);
        } catch (IOException parseEx) {
            log.warn("[KeywordSelection] JSON parse failed, fall back to heuristics. error={}", parseEx.getMessage());
            try {
                AuxDownTracker.markDegraded("keyword-selection", "parse-fail", parseEx);
            } catch (Throwable ignore) {
            }
            try {
                TraceStore.put("keywordSelection.mode", "fallback_parse");
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
            return Optional.of(fallbackTerms(conversation, domainProfile, mustLim));
        } catch (Exception ex) {
            log.error("[KeywordSelection] unexpected failure in keyword selection, fall back to heuristics", ex);
            try {
                AuxDownTracker.markDegraded("keyword-selection", "exception", ex);
            } catch (Throwable ignore) {
            }
            try {
                TraceStore.put("keywordSelection.mode", "fallback_exception");
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
            return Optional.of(fallbackTerms(conversation, domainProfile, mustLim));
        }
    }

    /**
     * Fail-soft fallback: never return an empty selection.
     * This keeps web/query planning alive even when the keyword LLM returns
     * blank/invalid JSON.
     */
    private static SelectedTerms fallbackTerms(String conversation, String domainProfile, int maxMust) {
        int limit = Math.max(1, maxMust);
        String conv = Objects.toString(conversation, "");
        String base = extractLikelyUserQuery(conv);

        Set<String> out = new LinkedHashSet<>();
        if (!base.isBlank())
            out.add(base);

        String dp = Objects.toString(domainProfile, "general");
        String dpu = dp.toUpperCase(Locale.ROOT);
        if (dpu.contains("GENSHIN") || dpu.contains("GAME") || dpu.contains("GAMES")) {
            out.add("공략");
            out.add("빌드");
        } else if (dpu.contains("TECH") || dpu.contains("IT") || dpu.contains("DEV") || dpu.contains("CODE")) {
            out.add("방법");
            out.add("해결");
        }

        List<String> must = new ArrayList<>();
        for (String s : out) {
            if (s == null)
                continue;
            String t = s.trim();
            if (t.isBlank())
                continue;
            must.add(t);
            if (must.size() >= limit)
                break;
        }
        if (must.isEmpty()) {
            must = List.of("검색");
        }

        SelectedTerms st = new SelectedTerms();
        st.setMust(must);
        st.setExact(List.of());
        st.setShould(List.of());
        st.setMaybe(List.of());
        st.setNegative(List.of());
        st.setDomains(List.of());
        st.setAliases(List.of());
        st.setDomainProfile(Objects.toString(domainProfile, "general"));
        return st;
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
            line = line.replaceFirst("^(USER|사용자|Q|질문)\s*[:：]\\s*", "").trim();
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