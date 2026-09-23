package com.example.lms.service.answer;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.debug.ai.ChatUsageLedger;
import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.TimedChatModelCaller;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.verbosity.VerbosityProfile;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;



@Service
public class AnswerExpanderService {
    private static final Logger log = LoggerFactory.getLogger(AnswerExpanderService.class);
    private static final Pattern RESTRUCTURED_HEADING =
            Pattern.compile("(?im)^\\s*#{1,6}\\s*RESTRUCTURED\\s*$");
    private static final Pattern LEADING_DRAFT_HEADING =
            Pattern.compile("(?i)\\A\\s*#{1,6}\\s*DRAFT\\s*(?:\\R|$)");
    private static final Pattern NUMBER_TOKEN = Pattern.compile(
            "[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

    private final PromptBuilder promptBuilder;

    @Autowired(required = false)
    private ChatUsageLedger chatUsageLedger;

    public AnswerExpanderService(PromptBuilder promptBuilder) {
        this.promptBuilder = java.util.Objects.requireNonNull(promptBuilder, "promptBuilder");
    }

    /**
     * Backward-compatible overload used by legacy call sites that do not provide
     * explicit evidence snippets. Delegates to the full variant with an empty list.
     */
    public String expandWithLc(String draft, VerbosityProfile vp, ChatModel model) {
        return expandWithLc(draft, vp, model, java.util.Collections.emptyList());
    }



    private static String buildExpandPrompt(String draft, VerbosityProfile vp, List<String> evidenceSnippets) {
    String sections = (vp.sections() == null || vp.sections().isEmpty())
            ? ""
            : "- Use these section headers (Korean): " + String.join(", ", vp.sections()) + "\n";

    String evidenceBlock = "";
    List<String> safeEvidenceSnippets = safeEvidenceSnippets(evidenceSnippets);
    if (!safeEvidenceSnippets.isEmpty()) {
        evidenceBlock = "\n[검색 결과 기반 정보]\n"
                + String.join("\n", safeEvidenceSnippets)
                + "\n\n위 정보만을 신뢰 가능한 외부 근거로 사용해.\n"
                + "검색 결과에 없는 사실을 '추측해서' 만들지 마.\n\n";
    }

    String noEvidenceRule = safeEvidenceSnippets.isEmpty()
            ? """
           - No EVIDENCE was provided. Restructure or lightly polish the DRAFT only.
           - Prefer keeping a non-empty DRAFT over silence. Do NOT reply with [NO_EVIDENCE] or any placeholder that blanks the answer.
           - If the DRAFT is already usable, return a lightly cleaned version of the DRAFT.
           """
            : """
           - Prefer EVIDENCE when present; do not invent facts beyond DRAFT/EVIDENCE.
           """;

    return """
           You are a Korean technical editor that ONLY restructures existing content.

           HARD RULES:
           - DO NOT add any new facts, entities, character names, places, dates, or numbers not present in the DRAFT or EVIDENCE.
           - DO NOT guess or invent background information to reach the target length.
           %s
           ALLOWED:
           - Reorder sentences for better flow
           - Add transition phrases using only info already in DRAFT or EVIDENCE
           - Split into paragraphs or sections
           %s

           Target minimum length: %d Korean words (but quality over quantity - never fabricate).

           ## DRAFT
           %s
           """.formatted(noEvidenceRule, evidenceBlock, Math.max(1, vp.minWordCount()), draft);
}

    private static List<String> safeEvidenceSnippets(List<String> evidenceSnippets) {
        if (evidenceSnippets == null || evidenceSnippets.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        java.util.ArrayList<String> safe = new java.util.ArrayList<>(evidenceSnippets.size());
        for (String snippet : evidenceSnippets) {
            String redacted = SafeRedactor.safeMessage(snippet, 1_200);
            if (redacted != null && !redacted.isBlank()) {
                safe.add(redacted);
            }
        }
        return safe;
    }

    public String expandWithLc(String draft, VerbosityProfile vp, ChatModel model, List<String> evidenceSnippets) {
        ChatUsageLedger.ExpansionAttempt expansionAttempt = chatUsageLedger == null
                ? null
                : chatUsageLedger.beginExpansion();
        ChatUsageLedger.ModelAttempt modelAttempt = null;
        try {
            TimeBudget requestBudget = TimeBudgetContext.get();
            if (requestBudget != null && requestBudget.expired()) {
                TraceStore.put("answer.expansion.skipped", "request_budget_exhausted");
                if (expansionAttempt != null) {
                    expansionAttempt.skippedBeforeModel();
                }
                return null;
            }
            PromptContext ctx = PromptContext.builder()
                    .systemInstruction("You are a cautious Korean editor. Restructure only; never invent.")
                    .userQuery(buildExpandPrompt(draft, vp, evidenceSnippets))
                    .build();
            List<ChatMessage> msgs = java.util.List.of(UserMessage.from(promptBuilder.build(ctx)));
            String result;
            ChatUsageLedger.ConfiguredCap configuredCap = DynamicChatModelFactory
                    .configuredTokenBudget(model)
                    .withRequestContext(
                            vp == null ? null : vp.targetTokenBudgetOut(),
                            null,
                            ChatUsageLedger.CapSource.PROFILE_TARGET);
            if (requestBudget == null) {
                if (expansionAttempt != null) {
                    modelAttempt = expansionAttempt.beginModelInvocation(configuredCap);
                }
                ChatResponse response = model.chat(msgs);
                if (modelAttempt != null) {
                    modelAttempt.responseReceived(response == null ? null : response.tokenUsage());
                }
                result = response == null || response.aiMessage() == null
                        ? null
                        : response.aiMessage().text();
                if (TimedChatModelCaller.isExpectedFailureResponse(result)) {
                    throw new IllegalStateException("Answer expansion returned an expected-failure route marker");
                }
                if (modelAttempt != null && result != null && !result.isBlank()) {
                    modelAttempt.markSuccessful();
                }
            } else {
                long remainingMs = requestBudget.remainingMillis();
                if (remainingMs <= 0L) {
                    TraceStore.put("answer.expansion.skipped", "request_budget_exhausted");
                    if (expansionAttempt != null) {
                        expansionAttempt.skippedBeforeModel();
                    }
                    return null;
                }
                if (expansionAttempt != null) {
                    modelAttempt = expansionAttempt.beginModelInvocation(configuredCap);
                }
                var ai = TimedChatModelCaller.chat(
                        model,
                        msgs,
                        Duration.ofMillis(remainingMs),
                        "answer_expansion",
                        model.getClass().getName(),
                        modelAttempt);
                result = ai.text();
            }

            result = userFacingExpansion(result);

            if (result == null || result.isBlank()) {
                if (expansionAttempt != null) {
                    expansionAttempt.rejectedEmpty();
                }
                return null;
            }

            // The editor prompt forbids inventing numbers. Enforce that invariant so a
            // second model call cannot turn a correct draft value into a different one.
            if (introducesUnsupportedNumber(draft, result, evidenceSnippets)) {
                TraceStore.put("answer.expansion.rejected", "numeric_invariant_mismatch");
                if (expansionAttempt != null) {
                    expansionAttempt.rejectedNumeric();
                }
                return null;
            }

            // [NO_EVIDENCE] / soft-empty markers: treat as null expansion so callers KEEP the original draft.
            if (result != null) {
                String trimmed = result.trim();
                String compact = trimmed.replaceAll("\\s+", "");
                if ("[NO_EVIDENCE]".equals(trimmed)
                        || "NO_EVIDENCE".equalsIgnoreCase(trimmed)
                        || compact.contains("근거없다")
                        || compact.contains("근거없음")) {
                    if (expansionAttempt != null) {
                        expansionAttempt.rejectedNoEvidence();
                    }
                    TraceStore.put("answer.expansion.rejected", "no_evidence_marker_keep_draft");
                    return null; // 확장하지 않고 원문 그대로 사용
                }
            }

            // 너무 짧게 요약한 경우도 원문 사용
            if (result != null && result.length() < draft.length() * 0.8) {
                if (expansionAttempt != null) {
                    expansionAttempt.rejectedTooShort();
                }
                return null;
            }

            if (expansionAttempt != null) {
                expansionAttempt.accepted();
            }
            return result;
        } catch (Exception e) {
            if (modelAttempt != null) {
                if (TimedChatModelCaller.isHardTimeout(e)) {
                    modelAttempt.timedOut();
                } else if (e instanceof CancellationException || e instanceof InterruptedException) {
                    modelAttempt.cancelled();
                } else {
                    modelAttempt.failedBeforeResponse();
                }
            }
            if (expansionAttempt != null) {
                if (modelAttempt == null) {
                    expansionAttempt.failedBeforeModel();
                } else if (TimedChatModelCaller.isHardTimeout(e)) {
                    expansionAttempt.timedOut();
                } else if (e instanceof CancellationException || e instanceof InterruptedException) {
                    expansionAttempt.cancelled();
                } else {
                    expansionAttempt.failedAfterModel();
                }
            }
            log.debug("[AnswerExpander] expansion failed errorHash={} errorLength={}",
                    SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            return null; // 확장 실패 시 원문 사용
        }
    }

    private static String userFacingExpansion(String result) {
        if (result == null || result.isBlank()) {
            return result;
        }
        Matcher marker = RESTRUCTURED_HEADING.matcher(result);
        int lastMarkerEnd = -1;
        while (marker.find()) {
            lastMarkerEnd = marker.end();
        }
        String cleaned = lastMarkerEnd >= 0 ? result.substring(lastMarkerEnd) : result;
        cleaned = LEADING_DRAFT_HEADING.matcher(cleaned).replaceFirst("");
        return cleaned.strip();
    }

    private static boolean introducesUnsupportedNumber(
            String draft,
            String result,
            List<String> evidenceSnippets) {
        if (result == null || result.isBlank()) {
            return false;
        }
        Set<String> allowed = new HashSet<>();
        collectNumberTokens(allowed, draft);
        for (String evidence : safeEvidenceSnippets(evidenceSnippets)) {
            collectNumberTokens(allowed, evidence);
        }
        Matcher resultNumbers = NUMBER_TOKEN.matcher(result);
        while (resultNumbers.find()) {
            if (structuralListOrdinal(result, resultNumbers)) {
                continue;
            }
            if (!allowed.contains(canonicalNumber(resultNumbers.group()))) {
                return true;
            }
        }
        return false;
    }

    private static void collectNumberTokens(Set<String> target, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = NUMBER_TOKEN.matcher(text);
        while (matcher.find()) {
            target.add(canonicalNumber(matcher.group()));
        }
    }

    private static String canonicalNumber(String token) {
        try {
            return new BigDecimal(token.replace(",", ""))
                    .stripTrailingZeros()
                    .toPlainString();
        } catch (NumberFormatException ignored) {
            return token;
        }
    }

    private static boolean structuralListOrdinal(String text, Matcher number) {
        int lineStart = text.lastIndexOf('\n', Math.max(0, number.start() - 1)) + 1;
        if (!text.substring(lineStart, number.start()).isBlank() || number.end() >= text.length()) {
            return false;
        }
        char delimiter = text.charAt(number.end());
        if (delimiter != '.' && delimiter != ')') {
            return false;
        }
        int afterDelimiter = number.end() + 1;
        return afterDelimiter < text.length() && Character.isWhitespace(text.charAt(afterDelimiter));
    }
}
