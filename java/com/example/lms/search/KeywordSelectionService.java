package com.example.lms.search;

import com.example.lms.prompt.QueryKeywordPromptBuilder;
import com.example.lms.search.terms.SelectedTerms;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * Service that delegates to an LLM via LangChain4j to select search
 * vocabulary from a conversation.  It builds a prompt using the
 * centralised {@link QueryKeywordPromptBuilder}, invokes the injected
 * {@link ChatModel} and attempts to parse the returned JSON into a
 * {@link SelectedTerms} object.  Failures during the call or parsing
 * will result in an empty {@link Optional} allowing callers to fall
 * back to rule based extraction.
 */
@Service
@RequiredArgsConstructor
public class KeywordSelectionService {
    private static final Logger log = LoggerFactory.getLogger(KeywordSelectionService.class);
    // Always use the mini/low-tier model for keyword selection to control costs
    // and avoid failures when high-tier models are unavailable.  The qualifier
    // ensures the "mini" ChatModel bean is injected.
    @Qualifier("localChatModel")
    private final ChatModel chatModel;
    private final QueryKeywordPromptBuilder prompts;
    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Strip enclosing triple backtick code fences from a JSON string.  LLMs
     * sometimes wrap JSON responses in markdown code fences (``` or ```json),
     * causing Jackson to fail during parsing.  This helper removes the
     * leading and trailing fences and trims whitespace.  When the input
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

    /**
     * Request term selection from the LLM.  When the call succeeds and
     * valid JSON is returned, the parsed {@link SelectedTerms} is
     * returned.  Otherwise an empty {@link Optional} is provided and
     * callers may revert to a rule based extractor.
     *
     * @param conversation full conversation history (never null)
     * @param domainProfile inferred domain profile or "general" when unknown
     * @param maxMust maximum number of must keywords to include; values ≤0 are coerced to 1
     * @return an {@link Optional} containing the selected terms or empty when parsing fails
     */
    public Optional<SelectedTerms> select(String conversation, String domainProfile, int maxMust) {
        try {
            String prompt = prompts.buildSelectedTermsJsonPrompt(
                    Objects.toString(conversation, ""),
                    Objects.toString(domainProfile, "general"),
                    Math.max(1, maxMust)
            );
            // Invoke the chat model; expecting raw JSON as the sole response
            // Invoke the chat model with a list of messages rather than the
            // deprecated String overload.  This conforms to the LangChain4j
            // 1.0.1 API and avoids implicit conversions.
            String json = chatModel
                    .chat(java.util.List.of(UserMessage.from(prompt)))
                    .aiMessage()
                    .text();
            // Strip markdown fences before JSON parsing
            json = sanitizeJson(json);
            SelectedTerms terms = om.readValue(json, SelectedTerms.class);
            if (terms.getMust() == null) terms.setMust(List.of());
            if (terms.getExact() == null) terms.setExact(List.of());
            if (terms.getShould() == null) terms.setShould(List.of());
            if (terms.getMaybe() == null) terms.setMaybe(List.of());
            if (terms.getNegative() == null) terms.setNegative(List.of());
            if (terms.getDomains() == null) terms.setDomains(List.of());
            if (terms.getAliases() == null) terms.setAliases(List.of());
            // Normalise domainProfile; default to provided domain or 'general'
            if (terms.getDomainProfile() == null || terms.getDomainProfile().isBlank()) {
                terms.setDomainProfile(Objects.toString(domainProfile, "general"));
            }
            return Optional.of(terms);
        } catch (IOException parseEx) {
            log.warn("[KeywordSelection] JSON parse failed, fall back to heuristics. error={}", parseEx.getMessage());
            return Optional.empty();
        } catch (Exception ex) {
            log.error("[KeywordSelection] unexpected failure in keyword selection, fall back to heuristics", ex);
            return Optional.empty();
        }
    }
}