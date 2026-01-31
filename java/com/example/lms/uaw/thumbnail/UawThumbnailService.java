package com.example.lms.uaw.thumbnail;

import com.example.lms.llm.ChatModel;
import com.example.lms.prompt.QueryKeywordPromptBuilder;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UAW Thumbnail(1-line) 생성 + KnowledgeBase 적재.
 */
@Service
public class UawThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(UawThumbnailService.class);

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\\\"\\)]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_PATTERN = Pattern.compile("href=\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE);

    private final UawThumbnailProperties props;
    private final UawThumbnailPlanLoader planLoader;
    private final WebSearchProvider webSearchProvider;
    private final QueryKeywordPromptBuilder keywordPromptBuilder;
    private final ChatModel chatModel;
    private final KnowledgeBaseService knowledgeBase;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public UawThumbnailService(
            UawThumbnailProperties props,
            UawThumbnailPlanLoader planLoader,
            WebSearchProvider webSearchProvider,
            QueryKeywordPromptBuilder keywordPromptBuilder,
            ChatModel chatModel,
            KnowledgeBaseService knowledgeBase
    ) {
        this.props = props;
        this.planLoader = planLoader;
        this.webSearchProvider = webSearchProvider;
        this.keywordPromptBuilder = keywordPromptBuilder;
        this.chatModel = chatModel;
        this.knowledgeBase = knowledgeBase;
    }

    public record EvidenceItem(String anchor, String title, String url, String quote) {
        public String domain() {
            if (url == null) return "";
            try {
                java.net.URI uri = java.net.URI.create(url);
                String host = uri.getHost();
                return host == null ? "" : host.toLowerCase(Locale.ROOT);
            } catch (Exception e) {
                return "";
            }
        }
    }

    public record ThumbnailResult(
            String planId,
            String topic,
            String entityName,
            String caption,
            List<String> anchors,
            List<EvidenceItem> evidence,
            double confidenceScore
    ) {
    }

    public Optional<ThumbnailResult> generateAndPersist(String rawTopic) {
        if (!props.isEnabled()) {
            return Optional.empty();
        }

        String topic = normalizeTopic(rawTopic);
        if (topic.isBlank()) return Optional.empty();

        UawThumbnailPlanSpec plan = planLoader.loadOrDefault(props.getPlanId(), props);

        // entityName unique protection (some schemas may be unique by entityName only)
        String entityName = props.getKnowledgeDomain() + "::" + topic;

        try {
            if (knowledgeBase.find(props.getKnowledgeDomain(), entityName).isPresent()) {
                return Optional.empty();
            }
        } catch (Exception ignore) {
        }

        List<String> anchors = generateAnchors(topic, plan);
        List<EvidenceItem> evidence = collectEvidence(anchors, plan);

        if (evidence.size() < 2) {
            return Optional.empty();
        }

        Rendered rendered = render(topic, entityName, anchors, evidence, plan);
        if (rendered == null) return Optional.empty();

        double conf = clamp01(rendered.confidence);
        double minConf = effectiveMinConfidence(plan);
        if (conf < minConf) {
            log.info("[UAW_THUMB] low confidence -> skip. conf={} min={} topic={}", conf, minConf, topic);
            return Optional.empty();
        }

        List<String> sources = evidence.stream()
                .map(EvidenceItem::url)
                .filter(u -> u != null && !u.isBlank())
                .distinct()
                .toList();

        knowledgeBase.integrateVerifiedKnowledge(
                props.getKnowledgeDomain(),
                entityName,
                rendered.knowledgeJson,
                sources,
                conf
        );

        return Optional.of(new ThumbnailResult(
                plan != null ? plan.id() : props.getPlanId(),
                topic,
                entityName,
                rendered.caption,
                anchors,
                evidence,
                conf
        ));
    }

    private double effectiveMinConfidence(UawThumbnailPlanSpec plan) {
        try {
            if (plan != null && plan.persist() != null && plan.persist().minConfidence() != null) {
                return plan.persist().minConfidence();
            }
        } catch (Exception ignore) {
        }
        return props.getMinConfidence();
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    private String normalizeTopic(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.length() > props.getMaxTopicChars()) {
            t = t.substring(0, props.getMaxTopicChars()).trim();
        }
        return t.replaceAll("\\s+", " ").trim();
    }

    private List<String> generateAnchors(String topic, UawThumbnailPlanSpec plan) {
        int n = 12;
        try {
            if (plan != null && plan.anchors() != null && plan.anchors().count() != null) {
                n = plan.anchors().count();
            }
        } catch (Exception ignore) {
        }
        n = Math.max(3, Math.min(20, n));

        try {
            String prompt = keywordPromptBuilder.buildKeywordVariantsPrompt(topic, topic, n);
            String out = chatModel.generate(prompt, 0.0, 256);
            List<String> parsed = parseLineList(out, n);
            if (!parsed.isEmpty()) return parsed;
        } catch (Exception e) {
            log.warn("[UAW_THUMB] anchors llm failed -> fallback. err={}", e.toString());
        }

        Set<String> set = new LinkedHashSet<>();
        set.add(topic);
        for (String tok : topic.split("\\s+")) {
            if (tok.length() >= 2) set.add(tok);
        }
        List<String> out = new ArrayList<>(set);
        if (out.size() > n) return out.subList(0, n);
        return out;
    }

    private List<String> parseLineList(String raw, int max) {
        if (raw == null) return List.of();
        String s = raw.strip();
        if (s.startsWith("```")) {
            int idx = s.indexOf('\n');
            if (idx > 0) s = s.substring(idx + 1);
            int end = s.lastIndexOf("```");
            if (end > 0) s = s.substring(0, end);
        }

        String[] lines = s.split("\\r?\\n");
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String t = line.strip();
            t = t.replaceFirst("^[\\-*\\d\\.\\)]+\\s*", "").strip();
            if (t.isBlank()) continue;
            if (t.length() > 120) t = t.substring(0, 120).trim();
            if (!out.contains(t)) out.add(t);
            if (out.size() >= max) break;
        }
        return out;
    }

    private List<EvidenceItem> collectEvidence(List<String> anchors, UawThumbnailPlanSpec plan) {
        int webTopK = 4;
        int perAnchorK = 2;
        int finalK = 6;
        boolean uniqDomains = true;
        try {
            if (plan != null && plan.evidence() != null) {
                if (plan.evidence().webTopKPerAnchor() != null) webTopK = plan.evidence().webTopKPerAnchor();
                if (plan.evidence().evidenceTopKPerAnchor() != null) perAnchorK = plan.evidence().evidenceTopKPerAnchor();
                if (plan.evidence().finalK() != null) finalK = plan.evidence().finalK();
                if (plan.evidence().requireUniqueDomains() != null) uniqDomains = plan.evidence().requireUniqueDomains();
            }
        } catch (Exception ignore) {
        }

        webTopK = Math.max(1, Math.min(10, webTopK));
        perAnchorK = Math.max(1, Math.min(6, perAnchorK));
        finalK = Math.max(2, Math.min(12, finalK));

        List<EvidenceItem> pool = new ArrayList<>();
        Set<String> seenUrl = new LinkedHashSet<>();

        for (String anchor : anchors) {
            if (anchor == null || anchor.isBlank()) continue;

            List<String> snippets;
            try {
                snippets = webSearchProvider.search(anchor, webTopK);
            } catch (Exception e) {
                log.warn("[UAW_THUMB] web search failed anchor={} err={}", anchor, e.toString());
                continue;
            }

            int kept = 0;
            for (String snip : snippets) {
                EvidenceItem item = parseEvidence(anchor, snip);
                if (item == null) continue;
                if (item.url() == null || item.url().isBlank()) continue;

                String norm = item.url().trim();
                if (seenUrl.contains(norm)) continue;
                seenUrl.add(norm);

                pool.add(item);
                kept++;
                if (kept >= perAnchorK) break;
            }
        }

        // final selection: domain diversity first
        List<EvidenceItem> selected = new ArrayList<>();
        Set<String> usedDomains = new LinkedHashSet<>();

        for (EvidenceItem it : pool) {
            if (selected.size() >= finalK) break;
            if (uniqDomains) {
                String d = it.domain();
                if (!d.isBlank() && usedDomains.contains(d)) continue;
                if (!d.isBlank()) usedDomains.add(d);
            }
            selected.add(it);
        }

        if (selected.size() < finalK) {
            for (EvidenceItem it : pool) {
                if (selected.size() >= finalK) break;
                if (!selected.contains(it)) selected.add(it);
            }
        }

        return selected;
    }

    private EvidenceItem parseEvidence(String anchor, String snippet) {
        if (snippet == null || snippet.isBlank()) return null;

        String url = extractUrl(snippet);
        String title = extractTitle(snippet);
        String quote = extractQuote(snippet);

        if (title == null || title.isBlank()) title = anchor;
        if (quote == null) quote = "";
        if (quote.length() > 280) quote = quote.substring(0, 280).trim();

        return new EvidenceItem(anchor, title.trim(), url == null ? "" : url.trim(), quote.trim());
    }

    private String extractUrl(String snippet) {
        Matcher m = HREF_PATTERN.matcher(snippet);
        if (m.find()) {
            return m.group(1);
        }

        int idx = snippet.indexOf("URL:");
        if (idx >= 0) {
            String tail = snippet.substring(idx + 4).trim();
            int nl = tail.indexOf('\n');
            if (nl > 0) tail = tail.substring(0, nl).trim();
            return tail;
        }

        Matcher u = URL_PATTERN.matcher(snippet);
        if (u.find()) {
            return u.group(0);
        }

        return "";
    }

    private String extractTitle(String snippet) {
        int a1 = snippet.indexOf('>');
        int a2 = snippet.toLowerCase(Locale.ROOT).indexOf("</a>");
        if (a1 >= 0 && a2 > a1) {
            String t = snippet.substring(a1 + 1, a2);
            return stripHtml(t);
        }

        String[] lines = snippet.split("\\r?\\n");
        if (lines.length > 0) {
            String t = lines[0].trim();
            if (!t.isBlank()) return stripHtml(t);
        }
        return "";
    }

    private String extractQuote(String snippet) {
        String[] lines = snippet.split("\\r?\\n");
        if (lines.length >= 2) {
            String t = lines[1].trim();
            if (!t.isBlank()) return stripHtml(t);
        }
        int idx = snippet.indexOf(":");
        if (idx > 0 && idx < snippet.length() - 1) {
            return stripHtml(snippet.substring(idx + 1).trim());
        }
        return stripHtml(snippet.trim());
    }

    private String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    private record Rendered(String caption, double confidence, String knowledgeJson) {
    }

    private Rendered render(String topic, String entityName, List<String> anchors, List<EvidenceItem> evidence, UawThumbnailPlanSpec plan) {
        String evidenceText = formatEvidence(evidence);
        String anchorsJson = safeJsonArrayString(anchors);

        int maxTokens = 480;
        double temperature = 0.2;
        try {
            if (plan != null && plan.render() != null) {
                if (plan.render().maxTokens() != null) maxTokens = plan.render().maxTokens();
                if (plan.render().temperature() != null) temperature = plan.render().temperature();
            }
        } catch (Exception ignore) {
        }

        String prompt = ("""
                You are a strict summarizer.
                Create a single-line Korean caption for the TOPIC, grounded in EVIDENCE.
                Output MUST be valid JSON only (no markdown).

                JSON schema:
                {
                  \"domain\": \"%s\",
                  \"entity\": \"%s\",
                  \"entityType\": \"%s\",
                  \"attributes\": {
                    \"topic\": \"%s\",
                    \"caption\": \"(<=120 chars)\",
                    \"anchors\": %s,
                    \"facts\": [
                      {\"claim\": \"...\", \"cite\": [0,1]}
                    ]
                  },
                  \"sources\": [\"url...\"],
                  \"confidenceScore\": 0.0
                }

                Rules:
                - Use only the given EVIDENCE; if unsure, lower confidenceScore.
                - facts: 3~5 items. cite indexes refer to EVIDENCE list.
                - sources: URLs in the same order as EVIDENCE.

                TOPIC:
                %s

                EVIDENCE (indexed):
                %s
                """).formatted(
                escapeJson(props.getKnowledgeDomain()),
                escapeJson(entityName),
                escapeJson(props.getEntityType()),
                escapeJson(topic),
                anchorsJson,
                topic,
                evidenceText
        );

        try {
            String out = chatModel.generate(prompt, temperature, maxTokens);
            String jsonText = sanitizeJson(out);
            JsonNode node = json.readTree(jsonText);

            String caption = node.path("attributes").path("caption").asText("").trim();
            if (caption.length() > 120) {
                caption = caption.substring(0, 120).trim();
            }

            double conf = node.path("confidenceScore").asDouble(0.0);

            // Force sources to the extracted evidence URLs (avoid hallucinated links)
            if (node instanceof ObjectNode on) {
                on.putArray("sources").removeAll();
                var arr = on.putArray("sources");
                evidence.stream().map(EvidenceItem::url).forEach(arr::add);
            }

            String normalizedJson = json.writerWithDefaultPrettyPrinter().writeValueAsString(node);

            return new Rendered(caption, conf, normalizedJson);
        } catch (Exception e) {
            log.warn("[UAW_THUMB] render failed topic={} err={}", topic, e.toString());
            return null;
        }
    }

    private String formatEvidence(List<EvidenceItem> evidence) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < evidence.size(); i++) {
            EvidenceItem it = evidence.get(i);
            sb.append("[").append(i).append("] ")
                    .append(it.title()).append("\n")
                    .append(it.quote()).append("\n")
                    .append(it.url()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String sanitizeJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) s = s.substring(firstNl + 1);
            int end = s.lastIndexOf("```");
            if (end > 0) s = s.substring(0, end);
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String safeJsonArrayString(List<String> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(items.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
