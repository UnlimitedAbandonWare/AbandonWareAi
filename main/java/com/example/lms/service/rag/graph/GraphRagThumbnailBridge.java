package com.example.lms.service.rag.graph;

import com.example.lms.search.TraceStore;
import com.example.lms.uaw.thumbnail.UawThumbnailPersistedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
public class GraphRagThumbnailBridge {

    private static final Logger log = LoggerFactory.getLogger(GraphRagThumbnailBridge.class);
    private static final String THUMBNAIL_SESSION = "__UAW_THUMBNAIL__";
    private static final int MAX_RELATION_THUMBNAIL_ANCHORS = 8;
    private static final int MAX_RELATION_THUMBNAIL_PAIRS = 16;

    private final GraphRagChunkingService chunkingService;
    private final Executor thumbnailExecutor;

    public GraphRagThumbnailBridge(GraphRagChunkingService chunkingService) {
        this(chunkingService, Runnable::run);
    }

    @Autowired
    public GraphRagThumbnailBridge(
            GraphRagChunkingService chunkingService,
            @Qualifier("applicationTaskExecutor") Executor thumbnailExecutor) {
        this.chunkingService = chunkingService;
        this.thumbnailExecutor = thumbnailExecutor;
    }

    @EventListener
    public void captureThumbnail(UawThumbnailPersistedEvent event) {
        if (event == null || event.graphText().isBlank()) {
            return;
        }
        try {
            thumbnailExecutor.execute(() -> {
                try {
                    ingestNow(event);
                } finally {
                    TraceStore.inc("uaw.thumbnail.relationThumbnail.submit.completedCount");
                }
            });
            TraceStore.inc("uaw.thumbnail.relationThumbnail.submit.acceptedCount");
        } catch (RejectedExecutionException rejected) {
            TraceStore.inc("uaw.thumbnail.relationThumbnail.submit.rejectedCount");
            TraceStore.put("uaw.thumbnail.relationThumbnail.submit.failureClass",
                    rejected.getClass().getSimpleName());
            TraceStore.put("uaw.thumbnail.relationThumbnail.submit.fallback", "skip_thumbnail_ingest");
            log.debug("[AWX][brain-state][thumbnail] submission rejected failureClass={} captionHash={}",
                    rejected.getClass().getSimpleName(), BrainStateText.hash12(event.caption()));
        }
    }

    void ingestNow(UawThumbnailPersistedEvent event) {
        try {
            if (event == null || event.graphText().isBlank()) {
                return;
            }
            List<KgChunk> chunks = relationThumbnailChunks(event);
            recordRelationThumbnailTrace(event, chunks);
            if (!chunks.isEmpty()) {
                chunkingService.ingestPreparedChunks(
                        THUMBNAIL_SESSION,
                        "UAW_THUMBNAIL",
                        chunks,
                        BrainStateText.hash12(event.graphText()));
                return;
            }
            chunkingService.ingestText(THUMBNAIL_SESSION, event.graphText(), "UAW_THUMBNAIL", event.knowledgeDomain());
        } catch (Exception ex) {
            String failureClass = ex == null ? "unknown" : ex.getClass().getSimpleName();
            TraceStore.put("uaw.thumbnail.relationThumbnail.ingest.failed", true);
            TraceStore.put("uaw.thumbnail.relationThumbnail.ingest.failureClass", failureClass);
            TraceStore.put("uaw.thumbnail.relationThumbnail.ingest.fallback", "skip_thumbnail_ingest");
            log.debug("[AWX][brain-state][thumbnail] skipped failureClass={} captionHash={}",
                    failureClass, BrainStateText.hash12(event.caption()));
        }
    }

    private static List<KgChunk> relationThumbnailChunks(UawThumbnailPersistedEvent event) {
        String graphText = event == null ? "" : event.graphText();
        List<String> anchors = safeAnchors(
                event == null ? List.of() : event.anchors(),
                event == null ? "" : event.caption());
        if (graphText.isBlank() || anchors.isEmpty()) {
            return List.of();
        }
        String domain = BrainStateText.normalizeDomain(event.knowledgeDomain());
        String entityType = BrainStateText.nonBlank(event.entityType(), "THUMBNAIL_ANCHOR");
        List<KgChunk> chunks = new ArrayList<>();
        for (int i = 0; i < anchors.size(); i++) {
            if (chunks.size() >= MAX_RELATION_THUMBNAIL_PAIRS) {
                break;
            }
            for (int j = i + 1; j < anchors.size() && chunks.size() < MAX_RELATION_THUMBNAIL_PAIRS; j++) {
                String left = anchors.get(i);
                String right = anchors.get(j);
                KgChunk.KgRelation relation = GraphRagPortMappingConnector.semanticRelation(
                        left,
                        right,
                        "UAW_THUMBNAIL_RELATED_TO",
                        event.confidenceScore(),
                        "uaw-thumbnail:" + event.planId());
                List<KgChunk.KgEntity> pairEntities = List.of(
                        new KgChunk.KgEntity(left, entityType, domain, event.confidenceScore()),
                        new KgChunk.KgEntity(right, entityType, domain, event.confidenceScore()));
                chunks.add(new KgChunk(
                        "uaw-thumb-rel:" + relation.connectorHash12(),
                        THUMBNAIL_SESSION,
                        relationThumbnailText(graphText, relation),
                        pairEntities,
                        List.of(relation),
                        domain,
                        event.confidenceScore(),
                        Instant.now(),
                        "UAW_THUMBNAIL",
                        "BRAIN_STATE",
                        "uaw_thumbnail",
                        "uaw_thumbnail_warmup"));
            }
        }
        return List.copyOf(chunks);
    }

    private static void recordRelationThumbnailTrace(UawThumbnailPersistedEvent event, List<KgChunk> chunks) {
        String prefix = "uaw.thumbnail.relationThumbnail.";
        int inputAnchorCount = event == null || event.anchors() == null ? 0 : event.anchors().size();
        int selectedAnchorCount = safeAnchors(
                event == null ? List.of() : event.anchors(),
                event == null ? "" : event.caption()).size();
        int emittedPairCount = chunks == null ? 0 : chunks.size();
        boolean sliced = inputAnchorCount > selectedAnchorCount || emittedPairCount >= MAX_RELATION_THUMBNAIL_PAIRS;
        TraceStore.put(prefix + "inputAnchorCount", inputAnchorCount);
        TraceStore.put(prefix + "selectedAnchorCount", selectedAnchorCount);
        TraceStore.put(prefix + "anchorBudget", MAX_RELATION_THUMBNAIL_ANCHORS);
        TraceStore.put(prefix + "pairBudget", MAX_RELATION_THUMBNAIL_PAIRS);
        TraceStore.put(prefix + "emittedPairCount", emittedPairCount);
        TraceStore.put(prefix + "sliced", sliced);
    }

    private static String relationThumbnailText(String graphText, KgChunk.KgRelation relation) {
        String source = relation == null ? "" : compactLine(relation.source());
        String target = relation == null ? "" : compactLine(relation.target());
        String kind = relation == null ? "" : compactLine(relation.kind());
        String breadcrumb = source + " --" + kind + "--> " + target;
        return "relationBreadcrumbs: " + breadcrumb
                + "\nrelationSummary: UAW thumbnail relation pair " + breadcrumb;
    }

    private static String compactLine(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim()
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ");
        return out.length() <= 240 ? out : out.substring(0, 240).trim();
    }

    private static List<String> safeAnchors(List<String> anchors) {
        return safeAnchors(anchors, "");
    }

    private static List<String> safeAnchors(List<String> anchors, String priorityText) {
        if (anchors == null || anchors.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<AnchorCandidate> candidates = new ArrayList<>();
        String priority = normalizeAnchorPriorityText(priorityText);
        int index = 0;
        for (String anchor : anchors) {
            int currentIndex = index++;
            if (anchor == null) {
                continue;
            }
            String value = anchor.trim().replaceAll("[\\r\\n\\t]+", " ");
            if (value.isBlank()) {
                continue;
            }
            String safe = value.length() <= 120 ? value : value.substring(0, 120).trim();
            if (seen.add(safe)) {
                candidates.add(new AnchorCandidate(safe, currentIndex, anchorPriority(safe, priority)));
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(AnchorCandidate::priority).reversed()
                        .thenComparingInt(AnchorCandidate::index))
                .limit(MAX_RELATION_THUMBNAIL_ANCHORS)
                .map(AnchorCandidate::value)
                .toList();
    }

    private static int anchorPriority(String anchor, String priorityText) {
        if (anchor == null || anchor.isBlank() || priorityText == null || priorityText.isBlank()) {
            return 0;
        }
        String normalized = normalizeAnchorPriorityText(anchor);
        if (normalized.isBlank()) {
            return 0;
        }
        String paddedPriority = " " + priorityText + " ";
        if (paddedPriority.contains(" " + normalized + " ")) {
            return 2;
        }
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 3 && paddedPriority.contains(" " + token + " ")) {
                return 1;
            }
        }
        return 0;
    }

    private static String normalizeAnchorPriorityText(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s{2,}", " ");
    }

    private record AnchorCandidate(String value, int index, int priority) {
    }
}
