package ai.abandonware.nova.orch.compress;

import ai.abandonware.nova.orch.anchor.AnchorNarrower;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fast, deterministic "keep anchor + tail" condenser for very large user
 * inputs.
 *
 * <p>
 * Goal: prevent large pasted documents/logs from exploding downstream costs
 * (LLM prompts,
 * tokenization, morphology-like analysis) while preserving the user's intent
 * (request line)
 * and a stable anchor term.
 * </p>
 */
public final class AnchorTailQueryCompressor {

    /** Rough heuristic: lines that look like user requests/questions. */
    private static final Pattern REQUEST_HINT = Pattern.compile(
            "(\\?|해줘|해주세요|부탁|분석|요약|정리|설명|출력|원인|에러|오류|문제|왜|어떻게|뭐야|뭔가|뭔지|알려|찾아)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CODE_FENCE = Pattern.compile("^```.*$");

    /** Fallback token pattern (Korean/letters/digits). */
    private static final Pattern TOKEN_PAT = Pattern.compile("[\\p{IsHangul}\\p{L}\\p{Nd}]{2,}");

    /**
     * Minimal stopwords for fallback anchor picking (AnchorNarrower has its own
     * richer list).
     */
    private static final Set<String> FALLBACK_STOPWORDS = Set.of(
            "그런데", "근데", "아래", "내용", "이거", "저거", "그거", "무엇", "뭐야", "뭔가", "뭔지",
            "해주세요", "해줘", "부탁", "설명", "요약", "정리", "분석", "출력");

    private static final int DEFAULT_MAX_SCAN_CHARS = 8_000;
    private static final int DEFAULT_MAX_SCAN_LINES = 96;

    private AnchorTailQueryCompressor() {
    }

    public enum PickStrategy {
        /** Preferred: found a request-like line near the end. */
        TAIL_REQUEST_LINE,
        /** Fallback: first request-like line near the beginning. */
        HEAD_REQUEST_LINE,
        /** Fallback: last non-empty line (non-fence) in the scan window. */
        LAST_NON_EMPTY_LINE,
        /** Fallback: raw tail slice. */
        RAW_TAIL
    }

    public record Result(
            String condensed,
            String anchor,
            String pickedLine,
            PickStrategy strategy,
            int originalLen,
            int maxLen) {
    }

    /**
     * Condense to {@code maxLen} while keeping a stable anchor + the request
     * line/tail.
     *
     * <p>
     * This method never throws. It returns an empty string for null/blank input.
     * </p>
     */
    public static Result condenseKeepAnchorAndTail(String original, int maxLen, AnchorNarrower anchorNarrower) {
        String s = safeTrim(original);
        if (s.isBlank() || maxLen <= 0) {
            return new Result("", "", "", PickStrategy.RAW_TAIL, original == null ? 0 : original.length(), maxLen);
        }
        if (s.length() <= maxLen) {
            return new Result(s, pickAnchor(anchorNarrower, s, maxLen), "", PickStrategy.RAW_TAIL, s.length(), maxLen);
        }

        try {
            int scanChars = Math.min(DEFAULT_MAX_SCAN_CHARS, s.length());
            LineHit tail = scanFromEnd(s, scanChars, DEFAULT_MAX_SCAN_LINES);
            LineHit head = scanFromStart(s, Math.min(scanChars / 2, s.length()), DEFAULT_MAX_SCAN_LINES);

            LineHit chosen;
            PickStrategy strategy;

            if (tail != null && tail.requestLike) {
                chosen = tail;
                strategy = PickStrategy.TAIL_REQUEST_LINE;
            } else if (head != null && head.requestLike) {
                chosen = head;
                strategy = PickStrategy.HEAD_REQUEST_LINE;
            } else if (tail != null) {
                chosen = tail;
                strategy = PickStrategy.LAST_NON_EMPTY_LINE;
            } else {
                chosen = null;
                strategy = PickStrategy.RAW_TAIL;
            }

            String pickedLine = chosen == null ? "" : chosen.line;

            // Anchor sampling: head+tail window to avoid scanning massive strings.
            String anchorSample = buildAnchorSample(s, 2400);
            String anchor = pickAnchor(anchorNarrower, anchorSample, maxLen);

            String tailText;
            if (!pickedLine.isBlank()) {
                tailText = pickedLine;
            } else {
                tailText = tailSlice(s, maxLen);
                strategy = PickStrategy.RAW_TAIL;
            }

            String condensed = assemble(anchor, tailText, maxLen);
            return new Result(condensed, anchor, pickedLine, strategy, s.length(), maxLen);
        } catch (Exception e) {
            // Fail-soft: never break the caller.
            String anchor = pickAnchor(anchorNarrower, s, maxLen);
            String condensed = assemble(anchor, tailSlice(s, maxLen), maxLen);
            return new Result(condensed, anchor, "", PickStrategy.RAW_TAIL, s.length(), maxLen);
        }
    }

    /**
     * Condense specifically for "analysis" prompts: keep anchor + request line + a
     * small tail excerpt.
     */
    public static Result condenseForQueryAnalysis(String original, int maxLen, AnchorNarrower anchorNarrower) {
        String s = safeTrim(original);
        if (s.isBlank() || maxLen <= 0) {
            return new Result("", "", "", PickStrategy.RAW_TAIL, original == null ? 0 : original.length(), maxLen);
        }
        if (s.length() <= maxLen) {
            return new Result(s, pickAnchor(anchorNarrower, s, maxLen), "", PickStrategy.RAW_TAIL, s.length(), maxLen);
        }

        try {
            int scanChars = Math.min(DEFAULT_MAX_SCAN_CHARS, s.length());
            LineHit tail = scanFromEnd(s, scanChars, DEFAULT_MAX_SCAN_LINES);
            LineHit head = scanFromStart(s, Math.min(scanChars / 2, s.length()), DEFAULT_MAX_SCAN_LINES);

            LineHit chosen;
            PickStrategy strategy;

            if (tail != null && tail.requestLike) {
                chosen = tail;
                strategy = PickStrategy.TAIL_REQUEST_LINE;
            } else if (head != null && head.requestLike) {
                chosen = head;
                strategy = PickStrategy.HEAD_REQUEST_LINE;
            } else if (tail != null) {
                chosen = tail;
                strategy = PickStrategy.LAST_NON_EMPTY_LINE;
            } else {
                chosen = null;
                strategy = PickStrategy.RAW_TAIL;
            }

            String pickedLine = chosen == null ? "" : chosen.line;

            String anchorSample = buildAnchorSample(s, 3200);
            String anchor = pickAnchor(anchorNarrower, anchorSample, Math.min(maxLen, 80));

            // Tail excerpt keeps extra entity/error tokens that might not be on the request
            // line.
            int tailBudget = Math.min(700, Math.max(200, maxLen / 2));
            String tailExcerpt = tailSlice(s, tailBudget);

            String condensed = assembleMulti(anchor, pickedLine, tailExcerpt, maxLen);
            return new Result(condensed, anchor, pickedLine, strategy, s.length(), maxLen);
        } catch (Exception e) {
            String anchor = pickAnchor(anchorNarrower, s, Math.min(maxLen, 80));
            String condensed = assemble(anchor, tailSlice(s, maxLen), maxLen);
            return new Result(condensed, anchor, "", PickStrategy.RAW_TAIL, s.length(), maxLen);
        }
    }

    // ─────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String buildAnchorSample(String s, int window) {
        if (s.length() <= window * 2) {
            return s;
        }
        String head = s.substring(0, Math.min(window, s.length()));
        String tail = s.substring(Math.max(0, s.length() - window));
        return head + "\n" + tail;
    }

    private static String pickAnchor(AnchorNarrower anchorNarrower, String text, int maxLen) {
        String s = safeTrim(text);
        if (s.isBlank()) {
            return "";
        }

        // Prefer AnchorNarrower when available.
        if (anchorNarrower != null) {
            try {
                AnchorNarrower.Anchor pickedAnchor = anchorNarrower.pick(s, List.of(), List.of());
                String term = safeTrim(pickedAnchor.term());
                if (!term.isBlank()) {
                    // If the anchor term is too long, prefer a shorter cheap-variant.
                    int maxAnchorLen = Math.min(48, Math.max(18, maxLen / 2));
                    if (term.length() > maxAnchorLen) {
                        String best = chooseBestVariant(anchorNarrower.cheapVariants(term, pickedAnchor), maxAnchorLen);
                        if (!best.isBlank()) {
                            return best;
                        }
                        return term.substring(0, maxAnchorLen);
                    }
                    return term;
                }
            } catch (Exception ignored) {
                // fallthrough to regex fallback
            }
        }

        // Regex fallback (cheap): pick the longest non-stopword token.
        String best = "";
        Matcher m = TOKEN_PAT.matcher(s);
        while (m.find()) {
            String tok = m.group();
            if (tok == null)
                continue;
            String t = tok.trim();
            if (t.length() < 2)
                continue;
            if (FALLBACK_STOPWORDS.contains(t))
                continue;
            if (t.length() > best.length()) {
                best = t;
            }
        }
        if (best.length() > 48) {
            return best.substring(0, 48);
        }
        return best;
    }

    private static String chooseBestVariant(List<String> variants, int maxLen) {
        if (variants == null || variants.isEmpty()) {
            return "";
        }
        return variants.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isBlank())
                // Prefer variants that fit within maxLen; among them choose the longest (more
                // info).
                .filter(v -> v.length() <= maxLen)
                .max(Comparator.comparingInt(String::length))
                .orElseGet(() -> variants.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(v -> !v.isBlank())
                        .min(Comparator.comparingInt(String::length))
                        .orElse(""));
    }

    private static boolean isCodeFence(String line) {
        if (line == null)
            return false;
        return CODE_FENCE.matcher(line.trim()).matches();
    }

    private static boolean looksLikeRequestLine(String line) {
        if (line == null)
            return false;
        String s = line.trim();
        if (s.isBlank())
            return false;
        // Avoid treating very long lines (likely raw logs) as requests.
        if (s.length() > 600)
            return false;
        return REQUEST_HINT.matcher(s).find();
    }

    private static String tailSlice(String s, int maxChars) {
        if (s == null)
            return "";
        if (maxChars <= 0)
            return "";
        if (s.length() <= maxChars)
            return s.trim();
        return s.substring(Math.max(0, s.length() - maxChars)).trim();
    }

    private static String assemble(String anchor, String tail, int maxLen) {
        String a = safeTrim(anchor);
        String t = safeTrim(tail);
        if (t.isBlank()) {
            return truncateKeepEnd(a, maxLen);
        }
        if (a.isBlank()) {
            return truncateKeepEnd(t, maxLen);
        }

        // If tail already contains anchor (case-insensitive), don't duplicate.
        if (containsIgnoreCase(t, a)) {
            return truncateKeepEnd(t, maxLen);
        }

        String sep = " | ";
        int budget = maxLen - a.length() - sep.length();
        if (budget <= 0) {
            return truncateKeepEnd(a, maxLen);
        }
        String tailPart = truncateKeepEnd(t, budget);
        return (a + sep + tailPart).trim();
    }

    private static String assembleMulti(String anchor, String pickedLine, String tailExcerpt, int maxLen) {
        String a = safeTrim(anchor);
        String p = safeTrim(pickedLine);
        String t = safeTrim(tailExcerpt);

        StringBuilder sb = new StringBuilder();
        if (!a.isBlank()) {
            sb.append(a);
        }
        if (!p.isBlank() && !containsIgnoreCase(p, a)) {
            if (!sb.isEmpty())
                sb.append("\n");
            sb.append(p);
        }
        if (!t.isBlank()) {
            // If the picked line is already contained in the tail excerpt, keep only the
            // tail excerpt.
            if (!p.isBlank() && containsIgnoreCase(t, p)) {
                // no-op
            } else {
                if (!sb.isEmpty())
                    sb.append("\n");
                sb.append(t);
            }
        }

        String out = sb.toString().trim();
        if (out.length() <= maxLen) {
            return out;
        }
        // Keep end (tail) to preserve the user's latest question details.
        return truncateKeepEnd(out, maxLen);
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null)
            return false;
        String h = haystack.toLowerCase(Locale.ROOT);
        String n = needle.toLowerCase(Locale.ROOT);
        return h.contains(n);
    }

    private static String truncateKeepEnd(String s, int maxLen) {
        String x = safeTrim(s);
        if (x.isBlank() || maxLen <= 0)
            return "";
        if (x.length() <= maxLen)
            return x;
        if (maxLen <= 1) {
            return x.substring(x.length() - maxLen);
        }
        // Prefix ellipsis, keep end.
        int keep = maxLen - 1;
        return "…" + x.substring(x.length() - keep);
    }

    private static LineHit scanFromEnd(String s, int maxScanChars, int maxLines) {
        if (s == null || s.isBlank())
            return null;
        int scanStart = Math.max(0, s.length() - Math.max(1, maxScanChars));
        int idx = s.length();
        int lines = 0;
        LineHit firstNonEmpty = null;

        while (idx > scanStart && lines < maxLines) {
            int prevNl = s.lastIndexOf('\n', idx - 1);
            int lineStart = Math.max(scanStart, prevNl + 1);
            String line = s.substring(lineStart, idx).trim();
            idx = prevNl;
            lines++;

            if (line.isBlank())
                continue;
            if (isCodeFence(line))
                continue;

            boolean requestLike = looksLikeRequestLine(line);
            LineHit hit = new LineHit(line, lineStart, requestLike);
            if (firstNonEmpty == null) {
                firstNonEmpty = hit;
            }
            if (requestLike) {
                return hit;
            }
        }
        return firstNonEmpty;
    }

    private static LineHit scanFromStart(String s, int maxScanChars, int maxLines) {
        if (s == null || s.isBlank())
            return null;
        int scanEnd = Math.min(s.length(), Math.max(1, maxScanChars));
        int idx = 0;
        int lines = 0;
        LineHit firstNonEmpty = null;

        while (idx < scanEnd && lines < maxLines) {
            int nextNl = s.indexOf('\n', idx);
            if (nextNl < 0 || nextNl > scanEnd) {
                nextNl = scanEnd;
            }
            String line = s.substring(idx, nextNl).trim();
            idx = nextNl + 1;
            lines++;

            if (line.isBlank())
                continue;
            if (isCodeFence(line))
                continue;

            boolean requestLike = looksLikeRequestLine(line);
            LineHit hit = new LineHit(line, Math.max(0, idx - line.length()), requestLike);
            if (firstNonEmpty == null) {
                firstNonEmpty = hit;
            }
            if (requestLike) {
                return hit;
            }
        }
        return firstNonEmpty;
    }

    private static final class LineHit {
        final String line;
        final int startIdx;
        final boolean requestLike;

        private LineHit(String line, int startIdx, boolean requestLike) {
            this.line = line;
            this.startIdx = startIdx;
            this.requestLike = requestLike;
        }
    }
}
