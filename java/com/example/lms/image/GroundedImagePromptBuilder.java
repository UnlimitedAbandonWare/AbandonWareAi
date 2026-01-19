package com.example.lms.image;

import com.example.lms.dto.ImageTask;
import com.example.lms.prompt.PromptContext;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;




/**
 * Build a grounded image prompt and wrap it into an {@link ImageTask}.
 *
 * <p>Image generation requires a prompt that is both expressive and
 * grounded in the current conversation state.  This builder collects
 * relevant context such as the user’s query, the last assistant
 * response, uploaded file contents and recent web snippets and
 * extracts a concise list of keywords.  These keywords and context
 * buckets are assembled into a prompt that instructs the downstream
 * image generation model to focus only on the provided information.
 * The resulting prompt emphasises style hints and explicitly
 * discourages inclusion of trademarked text or logos.  When the
 * extracted context yields no keywords the KEYWORDS section is set to
 * “(none)” to avoid misleading the model.</p>
 */
@Component
@lombok.RequiredArgsConstructor
public class GroundedImagePromptBuilder {

    /**
     * Store for resolving franchise style cards.  When null no franchise
     * grounding is applied.
     */
    private final com.example.lms.image.style.ImageFranchiseStore franchiseStore;

    /**
     * Stop words removed from the keyword extraction.  Both English and
     * Korean articles and conjunctions are excluded to maximise
     * informative keywords.  The list may be extended as needed but
     * should remain small to avoid unnecessary overhead during
     * extraction.
     */
    private static final Set<String> STOP = Set.of(
            "the", "a", "an", "and", "or", "of", "to", "for", "with", "on", "in", "at", "is", "are", "was", "were",
            "이", "그", "저", "그리고", "또는", "에서", "의", "을", "를", "에", "은", "는", "가", "과", "로", "로서", "으로"
    );

    /**
     * Construct an {@link ImageTask} suitable for submission to the
     * underlying image generation service.  If {@code rawQuery} or
     * {@code ctx} are null the resulting prompt will still be
     * well-formed but may lack relevant grounding information.
     *
     * @param rawQuery the original user query requesting an image
     * @param ctx the current prompt context gathered from the chat
     * @return a new {@link ImageTask} configured for image generation
     */
    public ImageTask buildTask(String rawQuery, PromptContext ctx) {
        String grounded = buildGroundedPrompt(rawQuery, ctx);
        return ImageTask.builder()
                .mode("GENERATE")
                .prompt(grounded)
                .size(1024)
                .build();
    }
    /**
     * Convenience wrapper used by services that only need the grounded prompt string.
     * Exposes a stable API so callers can avoid depending on {@link ImageTask}.
     */
    public String build(String rawQuery, PromptContext ctx) {
        return buildGroundedPrompt(rawQuery, ctx);
    }
    /**
     * Assemble a grounded prompt from the user query and context.
     *
     * <p>The prompt includes three top level sections: TASK
     * instructions describing how to ground the image, STYLE_HINT
     * providing general stylistic guidance, KEYWORDS listing up to
     * thirty extracted tokens and CONTEXT enumerating the raw
     * information buckets.  When the subject is unclear the model is
     * instructed to prefer the most recent topic in the CONTEXT.
     * Trademark text and logos are explicitly discouraged.</p>
     *
     * @param query the user’s raw query, may be null
     * @param ctx contextual data from the conversation
     * @return a formatted prompt string
     */
    private String buildGroundedPrompt(String query, PromptContext ctx) {
        // Collect context buckets.  The query and conversation state guide both
        // keyword extraction and style grounding.
        List<String> buckets = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            buckets.add("USER_QUERY: " + query.strip());
        }
        if (ctx != null) {
            if (ctx.lastAssistantAnswer() != null && !ctx.lastAssistantAnswer().isBlank()) {
                buckets.add("LAST_ANSWER: " + ctx.lastAssistantAnswer());
            }
            if (ctx.fileContext() != null && !ctx.fileContext().isBlank()) {
                buckets.add("FILE_CONTEXT: " + truncate(ctx.fileContext(), 1200));
            }
            if (ctx.web() != null && !ctx.web().isEmpty()) {
                String web = ctx.web().stream()
                        .map(c -> {
                            try {
                                var seg = c.textSegment();
                                return seg != null ? seg.text() : c.toString();
                            } catch (Exception ignore) {
                                return c.toString();
                            }
                        })
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .limit(3)
                        .collect(Collectors.joining("\n- "));
                if (!web.isBlank()) {
                    buckets.add("WEB: " + truncate(web, 800));
                }
            }
        }
        // Extract up to 30 keywords from all buckets
        Set<String> keywords = extractKeywords(String.join("\n", buckets));
        String kw = keywords.isEmpty() ? "(none)" : String.join(", ", keywords);

        // Resolve franchise style card if available
        com.example.lms.image.style.FranchiseProfile fp = null;
        if (franchiseStore != null) {
            try {
                fp = franchiseStore.resolve(query, ctx != null ? ctx.memory() : null).orElse(null);
            } catch (Exception ignore) {
                // ignore errors in store resolution
            }
        }

        // Build style hint and negative cue lines
        StringBuilder styleHint = new StringBuilder();
        List<String> negatives = new ArrayList<>();
        if (fp != null) {
            // Copyright sensitive prompts should be phrased as inspired by the franchise
            if (fp.copyrightSensitive() && fp.rewriteToInspired()) {
                styleHint.append("- inspired by ").append(fp.franchise()).append(" style\n");
            } else {
                styleHint.append("- ").append(fp.franchise()).append(" style\n");
            }
            if (fp.visualMotifs() != null && !fp.visualMotifs().isEmpty()) {
                styleHint.append("- visual motifs: ").append(String.join(", ", fp.visualMotifs())).append("\n");
            }
            if (fp.palette() != null && !fp.palette().isEmpty()) {
                styleHint.append("- palette: ").append(String.join(", ", fp.palette())).append("\n");
            }
            if (fp.wardrobe() != null && !fp.wardrobe().isEmpty()) {
                styleHint.append("- wardrobe: ").append(String.join(", ", fp.wardrobe())).append("\n");
            }
            if (fp.camera() != null && !fp.camera().isEmpty()) {
                styleHint.append("- camera: ").append(String.join(", ", fp.camera())).append("\n");
            }
            if (fp.avoid() != null) {
                negatives.addAll(fp.avoid());
            }
            if (fp.negativeSoft() != null) {
                negatives.addAll(fp.negativeSoft());
            }
        } else {
            // Default style guidance when no franchise is matched
            styleHint.append("- modern, clean composition, coherent layout, balanced colors\n");
        }
        // Always discourage random wildlife and generic landscapes
        negatives.add("random wildlife");
        negatives.add("generic landscape");
        // If the query mentions wolves we must not exclude animals
        if (query != null) {
            String lowerQ = query.toLowerCase(Locale.ROOT);
            if (lowerQ.contains("wolf") || lowerQ.contains("늑대")) {
                negatives.removeIf(n -> n.toLowerCase(Locale.ROOT).contains("wildlife") || n.toLowerCase(Locale.ROOT).contains("animal"));
            }
        }
        // Assemble final string
        StringBuilder sb = new StringBuilder();
        sb.append("You are an image generation model.\n");
        sb.append("### TASK\n");
        sb.append("- Create an image grounded ONLY in the CONTEXT and KEYWORDS below.\n");
        sb.append("- If the subject is unclear, pick the most recent topic from CONTEXT.\n");
        sb.append("- Avoid brand/trademark text. Use generic typography when needed.\n");
        sb.append("### STYLE_HINT\n");
        sb.append(styleHint);
        if (!negatives.isEmpty()) {
            sb.append("- avoid: ").append(String.join(", ", new LinkedHashSet<>(negatives))).append("\n");
        }
        sb.append("### KEYWORDS\n");
        sb.append(kw).append("\n");
        sb.append("### CONTEXT\n");
        sb.append(String.join("\n", buckets)).append("\n");
        sb.append("### OUTPUT\n");
        sb.append("- One square image. If text is requested, keep it minimal.\n");
        return sb.toString();
    }

    /**
     * Extract a set of keywords from the supplied text.  The method
     * lowercases and splits on non-letter/digit/han characters, removes
     * stop words and keeps the original insertion order.  The result
     * size is capped at 30 distinct terms.
     *
     * @param text input string
     * @return ordered set of keywords
     */
    private static Set<String> extractKeywords(String text) {
        if (text == null) {
            return Collections.emptySet();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT)
                        .split("[^\\p{IsAlphabetic}\\p{IsDigit}가-힣]+"))
                .filter(t -> t.length() >= 2 && !STOP.contains(t))
                .limit(30)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Truncate a string to the specified length.  When the input is
     * longer than {@code n} characters the returned string ends with
     * an ellipsis to indicate truncation.  If the input is shorter no
     * suffix is appended.
     *
     * @param s the string to truncate
     * @param n maximum length
     * @return the truncated string or the original when shorter
     */
    private static String truncate(String s, int n) {
        if (s == null || s.length() <= n) {
            return s;
        }
        return s.substring(0, n) + "/* ... *&#47;";
    }
}