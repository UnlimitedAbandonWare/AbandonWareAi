package com.example.lms.service.rag.graph;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class QueryTimeAnchorMap {

    private static final Set<String> QUERY_ANCHOR_CUES = Set.of(
            "place", "location", "near", "nearby", "around", "landmark", "anchor",
            "route", "trail", "road", "bike", "bicycle", "cycling", "where", "name", "called",
            "\uc7a5\uc18c", "\uc8fc\ubcc0", "\uadfc\ucc98", "\uae30\uc900\uc810",
            "\uc790\uc804\uac70", "\uae38", "\ucf54\uc2a4", "\uc774\ub984", "\uc5b4\ub514");
    private static final Set<String> QUERY_ANCHOR_STOP_TOKENS = Set.of(
            "the", "and", "for", "with", "this", "that", "what", "where", "called",
            "about", "near", "around", "place", "location", "name");

    private final AnchorFrequencyIndex anchorFrequencyIndex;

    @Value("${rag.brain-state.anchor-map.enabled:${kg.anchor-map.enabled:false}}")
    private boolean enabled = false;

    @Value("${rag.brain-state.anchor-map.top-k:${kg.anchor-map.top-k:5}}")
    private int topK = 5;

    @Autowired
    public QueryTimeAnchorMap(AnchorFrequencyIndex anchorFrequencyIndex) {
        this.anchorFrequencyIndex = anchorFrequencyIndex;
    }

    QueryTimeAnchorMap(AnchorFrequencyIndex anchorFrequencyIndex, boolean enabled, int topK) {
        this.anchorFrequencyIndex = anchorFrequencyIndex;
        this.enabled = enabled;
        this.topK = topK;
    }

    public AnchorSlice slice(String query, String domain, List<String> exactMatched) {
        return slice(query, domain, exactMatched, topK);
    }

    public AnchorSlice slice(String query, String domain, List<String> exactMatched, int requestedTopK) {
        String requestedDomain = AnchorFrequencyIndex.normalizeOptionalDomain(domain);
        List<String> exact = exactMatched == null ? List.of() : List.copyOf(exactMatched);
        if (!exact.isEmpty()) {
            return AnchorSlice.exact(enabled, exact, requestedDomain);
        }
        if (!enabled) {
            return AnchorSlice.disabled(false, "route_disabled", requestedDomain);
        }
        if (anchorFrequencyIndex == null) {
            return AnchorSlice.disabled(true, "index_unavailable", requestedDomain);
        }
        if (requestedDomain.isBlank()) {
            return AnchorSlice.empty(true, "domain_required", "");
        }

        List<String> queryTokens = queryTokens(query);
        boolean cuePresent = hasQueryAnchorCue(query);
        List<EntityAnchorCandidate> candidates = new ArrayList<>();
        for (AnchorFrequencyIndex.AnchorEntity entity : anchorFrequencyIndex.entities(requestedDomain)) {
            double score = queryAnchorScore(entity, queryTokens, cuePresent, requestedDomain);
            if (score > 0.0d) {
                candidates.add(new EntityAnchorCandidate(
                        entity.name(),
                        entity.domain(),
                        entity.mentionCount(),
                        entity.confidence(),
                        score));
            }
        }
        if (candidates.isEmpty()) {
            return AnchorSlice.empty(true,
                    cuePresent ? "insufficient_anchor_evidence" : "no_query_anchor_cue",
                    requestedDomain);
        }

        int limit = normalizedLimit(requestedTopK);
        List<EntityAnchorCandidate> selectedCandidates = candidates.stream()
                .sorted(QueryTimeAnchorMap::compareAnchorCandidates)
                .limit(limit)
                .toList();
        String selectedDomain = requestedDomain;
        List<String> selected = selectedCandidates.stream()
                .map(EntityAnchorCandidate::name)
                .distinct()
                .toList();
        if (selected.isEmpty()) {
            return AnchorSlice.empty(true, "empty_anchor_selection", selectedDomain);
        }

        List<String> hashes = selected.stream()
                .map(BrainStateText::hash12)
                .filter(hash -> hash != null && !hash.isBlank())
                .distinct()
                .toList();
        List<AnchorFrequencyIndex.AnchorRelationView> relations =
                anchorFrequencyIndex.relationViews(selected, selectedDomain, limit);
        String reason = cuePresent ? "cue_seeded_landmark_anchors" : "token_overlap_auxiliary_anchors";
        return new AnchorSlice(true, true, selected, hashes, relations, reason, selectedDomain, "");
    }

    public boolean isEnabled() {
        return enabled;
    }

    private double queryAnchorScore(
            AnchorFrequencyIndex.AnchorEntity entity,
            List<String> queryTokens,
            boolean cuePresent,
            String domain) {
        if (entity == null || entity.name() == null || entity.name().isBlank()) {
            return 0.0d;
        }
        if (!AnchorFrequencyIndex.domainMatches(domain, entity.domain())) {
            return 0.0d;
        }
        int overlap = tokenOverlap(entity.name(), queryTokens);
        int neighborhoodOverlap = anchorFrequencyIndex.relationNeighborhoodTokenOverlap(
                entity.name(),
                queryTokens,
                domain);
        int evidenceOverlap = Math.max(overlap, neighborhoodOverlap);
        long relationTouches = anchorFrequencyIndex.relationTouchCount(entity.name(), domain);
        boolean landmark = anchorFrequencyIndex.hasLandmarkSignal(entity, domain);
        if (cuePresent && (relationTouches <= 0 || !landmark || evidenceOverlap <= 0)) {
            return 0.0d;
        }
        if (!cuePresent && (!strongTokenOverlap(evidenceOverlap, queryTokens) || relationTouches <= 0)) {
            return 0.0d;
        }
        double overlapTerm = queryTokens == null || queryTokens.isEmpty()
                ? 0.0d
                : Math.min(1.0d, evidenceOverlap / (double) Math.max(1, queryTokens.size()));
        double mentionTerm = Math.min(1.0d, entity.mentionCount() / 4.0d);
        double relationTerm = Math.min(1.0d, relationTouches / 4.0d);
        double landmarkTerm = landmark ? 0.12d : 0.0d;
        double cueTerm = cuePresent ? 0.10d : 0.0d;
        return AnchorFrequencyIndex.clamp01((0.42d * overlapTerm)
                + (0.16d * mentionTerm)
                + (0.18d * relationTerm)
                + (0.14d * AnchorFrequencyIndex.clamp01(entity.confidence()))
                + landmarkTerm
                + cueTerm);
    }

    private int normalizedLimit(int requestedTopK) {
        int configured = topK <= 0 ? 5 : topK;
        int requested = requestedTopK <= 0 ? configured : requestedTopK;
        return Math.max(1, Math.min(Math.min(configured, requested), 25));
    }

    private static int compareAnchorCandidates(EntityAnchorCandidate left, EntityAnchorCandidate right) {
        int c = Double.compare(right.score(), left.score());
        if (c != 0) {
            return c;
        }
        c = Long.compare(right.mentionCount(), left.mentionCount());
        if (c != 0) {
            return c;
        }
        c = Double.compare(right.confidence(), left.confidence());
        if (c != 0) {
            return c;
        }
        return String.CASE_INSENSITIVE_ORDER.compare(left.name(), right.name());
    }

    private static boolean strongTokenOverlap(int overlap, List<String> queryTokens) {
        if (overlap >= 2) {
            return true;
        }
        return overlap == 1 && queryTokens != null && queryTokens.size() <= 2;
    }

    private static int tokenOverlap(String value, List<String> queryTokens) {
        if (value == null || value.isBlank() || queryTokens == null || queryTokens.isEmpty()) {
            return 0;
        }
        Set<String> sourceTokens = new LinkedHashSet<>(queryTokens(value));
        int hits = 0;
        for (String token : queryTokens) {
            if (sourceTokens.contains(token)) {
                hits++;
            }
        }
        return hits;
    }

    private static boolean hasQueryAnchorCue(String query) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Set<String> rawTokens = rawQueryTokens(query);
        for (String cue : QUERY_ANCHOR_CUES) {
            if (isAscii(cue)) {
                if (rawTokens.contains(cue)) {
                    return true;
                }
                continue;
            }
            if (!lower.isBlank() && lower.contains(cue)) {
                return true;
            }
        }
        return false;
    }

    static List<String> queryTokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        String lower = query.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                token.append(ch);
            } else {
                addQueryToken(out, token);
            }
        }
        addQueryToken(out, token);
        return out.stream().distinct().toList();
    }

    private static Set<String> rawQueryTokens(String query) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        StringBuilder token = new StringBuilder();
        String lower = query.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                token.append(ch);
            } else {
                addRawQueryToken(out, token);
            }
        }
        addRawQueryToken(out, token);
        return out;
    }

    private static void addQueryToken(List<String> out, StringBuilder token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        String value = token.toString();
        token.setLength(0);
        if (value.length() < 2 || QUERY_ANCHOR_STOP_TOKENS.contains(value)) {
            return;
        }
        out.add(value);
    }

    private static void addRawQueryToken(Set<String> out, StringBuilder token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        String value = token.toString();
        token.setLength(0);
        if (value.length() >= 2) {
            out.add(value);
        }
    }

    private static boolean isAscii(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private record EntityAnchorCandidate(String name, String domain, long mentionCount, double confidence, double score) {
    }

    public record AnchorSlice(
            boolean enabled,
            boolean applied,
            List<String> matchedEntities,
            List<String> seedHashes,
            List<AnchorFrequencyIndex.AnchorRelationView> relations,
            String reason,
            String domain,
            String disabledReason) {
        public AnchorSlice {
            matchedEntities = matchedEntities == null ? List.of() : List.copyOf(matchedEntities);
            seedHashes = seedHashes == null ? List.of() : List.copyOf(seedHashes);
            relations = relations == null ? List.of() : List.copyOf(relations);
            reason = BrainStateText.nonBlank(reason, "none");
            domain = AnchorFrequencyIndex.normalizeOptionalDomain(domain);
            disabledReason = disabledReason == null ? "" : disabledReason.trim();
        }

        static AnchorSlice exact(boolean enabled, List<String> exactMatched, String domain) {
            return new AnchorSlice(enabled, false, exactMatched, List.of(), List.of(),
                    "exact_entity_match", domain, "");
        }

        static AnchorSlice empty(boolean enabled, String reason, String domain) {
            return new AnchorSlice(enabled, false, List.of(), List.of(), List.of(), reason, domain, "");
        }

        static AnchorSlice disabled(boolean enabled, String reason, String domain) {
            return new AnchorSlice(enabled, false, List.of(), List.of(), List.of(), reason, domain, reason);
        }

        public long seedCount() {
            return seedHashes.size();
        }
    }
}
