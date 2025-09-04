package com.example.lms.query;

import com.example.lms.query.config.AiQueryProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Provides basic query normalization and expansion logic.  Queries are lowercased,
 * whitespace‑normalized and stripped of leading/trailing punctuation.  Simple
 * rewrite rules defined in {@code alias.yml} are applied via regex replacement.
 * The {@link #expandTerms(String)} method applies append rules to generate
 * alternative search terms for recall improvement.
 */
@Component
public class QueryHygiene {

    private final AiQueryProperties props;

    public QueryHygiene(AiQueryProperties props) {
        this.props = props;
    }

    /**
     * Normalize a raw query string by lowercasing, trimming punctuation and
     * collapsing whitespace.  Simple regex replace rules defined in
     * {@link AiQueryProperties.Rewrite#getReplace()} are applied in order.
     *
     * @param input the raw user query
     * @return a normalized query
     */
    public String normalize(String input) {
        String q = (input == null) ? "" : input;
        if (props.getNorm().isLowerCase()) {
            q = q.toLowerCase(Locale.ROOT);
        }
        if (props.getNorm().isNormalizeSpace()) {
            q = q.replaceAll("\\s+", " ").trim();
        }
        if (props.getNorm().isTrimPunct()) {
            q = q.replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "");
        }
        // apply regex replacement rules
        for (var rule : props.getRewrite().getReplace()) {
            String pattern = rule.get("pattern");
            String replacement = rule.getOrDefault("replacement", "");
            if (pattern != null) {
                q = q.replaceAll(pattern, replacement);
            }
        }
        return q;
    }

    /**
     * Generate a list of expanded search terms based on append rules.  The
     * normalized query itself is always included at index 0.  Each append rule
     * specifies a set of substrings that must all be present in the query; if
     * the condition is satisfied the rule’s additions are appended to the result.
     *
     * @param normalized a normalized query
     * @return a list of search terms including expansions
     */
    public List<String> expandTerms(String normalized) {
        List<String> out = new ArrayList<>();
        out.add(normalized);
        props.getRewrite().getAppendTerms().forEach(rule -> {
            boolean allHit = rule.getIfContains().stream().allMatch(normalized::contains);
            if (allHit) {
                out.addAll(rule.getAdd());
            }
        });
        return out;
    }
}