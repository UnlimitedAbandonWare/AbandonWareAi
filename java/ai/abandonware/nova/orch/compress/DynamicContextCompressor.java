package ai.abandonware.nova.orch.compress;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * RAG context compressor used in strike/compression modes to reduce prompt size
 * and
 * mitigate LLM timeouts.
 *
 * <p>
 * Design goals:
 * <ul>
 * <li>Fail-soft: if anything goes wrong, return the original list.</li>
 * <li>Keep metadata: url/title/provider etc should be preserved.</li>
 * <li>Keep at least 1 doc when input is non-empty.</li>
 * </ul>
 */
public class DynamicContextCompressor {

    private final NovaOrchestrationProperties props;

    public DynamicContextCompressor(NovaOrchestrationProperties props) {
        this.props = props;
    }

    public List<Content> compress(String anchor, List<Content> docs) {
        NovaOrchestrationProperties.RagCompressorProps cfg = props != null ? props.getRagCompressor() : null;
        int maxContents = (cfg != null ? cfg.getMaxContents() : 8);
        int maxCharsPerContent = (cfg != null ? cfg.getMaxCharsPerContent() : 800);
        int anchorWindowChars = (cfg != null ? cfg.getAnchorWindowChars() : 220);
        return compress(anchor, docs, maxContents, maxCharsPerContent, anchorWindowChars);
    }

    public List<Content> compress(
            String anchor,
            List<Content> docs,
            int maxContents,
            int maxCharsPerContent,
            int anchorWindowChars) {
        if (docs == null || docs.isEmpty()) {
            return docs;
        }
        try {
            String a = anchor == null ? "" : anchor.trim();
            List<Content> sorted = new ArrayList<>(docs);
            sorted.sort((x, y) -> {
                boolean xHas = containsAnchor(textOf(x), a);
                boolean yHas = containsAnchor(textOf(y), a);
                return Boolean.compare(yHas, xHas);
            });

            int keepN = Math.max(1, maxContents);
            int perDocChars = Math.max(80, maxCharsPerContent);
            int windowChars = Math.max(0, anchorWindowChars);

            List<Content> out = new ArrayList<>(Math.min(keepN, sorted.size()));
            Map<String, Integer> perHostCount = new HashMap<>();
            Set<String> seenText = new HashSet<>();

            for (Content c : sorted) {
                if (c == null) {
                    continue;
                }
                if (out.size() >= keepN) {
                    break;
                }

                String host = hostOf(c);
                if (!host.isBlank()) {
                    int n = perHostCount.getOrDefault(host, 0);
                    if (n >= 2) {
                        continue;
                    }
                }

                String text = textOf(c);
                if (text.isBlank()) {
                    continue;
                }
                String normKey = normalizeForDedupe(text);
                if (!seenText.add(normKey)) {
                    continue;
                }

                String trimmed = trimText(text, a, perDocChars, windowChars);
                Content rebuilt = rebuild(c, trimmed);

                out.add(rebuilt);
                if (!host.isBlank()) {
                    perHostCount.put(host, perHostCount.getOrDefault(host, 0) + 1);
                }
            }
            return out.isEmpty() ? docs : out;
        } catch (Exception ignore) {
            return docs;
        }
    }

    private static boolean containsAnchor(String text, String anchor) {
        if (text == null || text.isBlank() || anchor == null || anchor.isBlank()) {
            return false;
        }
        if (text.contains(anchor)) {
            return true;
        }
        // basic case-insensitive check for ASCII-ish queries
        String lt = text.toLowerCase();
        String la = anchor.toLowerCase();
        return lt.contains(la);
    }

    private static String textOf(Content c) {
        if (c == null) {
            return "";
        }
        if (c.textSegment() != null && c.textSegment().text() != null) {
            return c.textSegment().text();
        }
        return String.valueOf(c);
    }

    private static Content rebuild(Content original, String newText) {
        Map<String, Object> meta = safeMetadata(original != null ? original.metadata() : null);
        if (meta.isEmpty()) {
            return Content.from(TextSegment.from(newText));
        }
        return Content.from(TextSegment.from(newText, Metadata.from(meta)));
    }

    private static Map<String, Object> safeMetadata(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return new HashMap<>();
        }
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static String hostOf(Content c) {
        Map<String, Object> meta = safeMetadata(c != null ? c.metadata() : null);
        Object u = firstNonBlank(meta, "url", "URL", "sourceUrl", "source_url", "link", "href", "canonical",
                "permalink");
        if (u == null) {
            return "";
        }
        String s = String.valueOf(u);
        if (s.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(s);
            String host = uri.getHost();
            return host == null ? "" : host;
        } catch (Exception ignore) {
            return s;
        }
    }

    private static Object firstNonBlank(Map<String, Object> meta, String... keys) {
        if (meta == null || keys == null) {
            return null;
        }
        for (String k : keys) {
            if (k == null) {
                continue;
            }
            Object v = meta.get(k);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v);
            if (!s.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String trimText(String text, String anchor, int maxChars, int anchorWindowChars) {
        if (text == null) {
            return "";
        }
        String t = text;
        if (t.length() <= maxChars) {
            return t;
        }

        String a = anchor == null ? "" : anchor.trim();
        if (!a.isBlank() && anchorWindowChars > 0) {
            int idx = indexOfIgnoreCase(t, a);
            if (idx >= 0) {
                int half = anchorWindowChars / 2;
                int start = Math.max(0, idx - half);
                int end = Math.min(t.length(), idx + a.length() + half);
                String sub = t.substring(start, end);
                String prefix = start > 0 ? "…" : "";
                String suffix = end < t.length() ? "…" : "";
                t = prefix + sub + suffix;
            }
        }

        if (t.length() > maxChars) {
            t = t.substring(0, maxChars) + "…";
        }
        return t;
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        if (text == null || needle == null || needle.isBlank()) {
            return -1;
        }
        int idx = text.indexOf(needle);
        if (idx >= 0) {
            return idx;
        }
        return text.toLowerCase().indexOf(needle.toLowerCase());
    }

    private static String normalizeForDedupe(String text) {
        if (text == null) {
            return "";
        }
        String s = text
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();
        if (s.length() > 240) {
            s = s.substring(0, 240);
        }
        return s;
    }
}
