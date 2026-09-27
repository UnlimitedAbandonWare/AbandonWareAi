package com.example.lms.service.rag;

import ai.abandonware.nova.orch.trace.OrchEventEmitter;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.CitationGate;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.guard.EvidenceGate;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceContext;
import com.example.lms.util.MetadataUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RagEvidenceAttributionService {

    private static final Logger log = LoggerFactory.getLogger(RagEvidenceAttributionService.class);

    private static final Pattern MARKER_PATTERN = Pattern.compile("\\[([WVD])(\\d+)]");
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[^\\s\\]\\)\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final int MAX_APPENDIX_LINES = 12;
    private static final Set<String> FINAL_EVIDENCE_HEADINGS = Set.of(
            "### sources",
            "### evidence",
            "### references",
            "### citations");

    private final EvidenceGate evidenceGate;
    private final CitationGate citationGate;
    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;

    public enum PromotionStatus {
        PROMOTED,
        CONFIRMED_EMPTY,
        FAILED,
        UNAVAILABLE
    }

    public enum PromotionReason {
        PROMOTED("promoted"),
        NO_CITABLE_LOCATOR("no_citable_locator"),
        EVIDENCE_GATE_BLOCKED("evidence_gate_blocked"),
        CITATION_GATE_BLOCKED("citation_gate_blocked"),
        GATE_EXCEPTION("gate_exception"),
        SERVICE_UNAVAILABLE("service_unavailable"),
        CALLER_FAILURE("caller_failure");

        private final String traceValue;

        PromotionReason(String traceValue) {
            this.traceValue = traceValue;
        }

        public String traceValue() {
            return traceValue;
        }
    }

    public record PromotionResult(
            PromotionStatus status,
            PromotionReason reason,
            List<RagEvidenceMetadata> evidence,
            int webCandidateCount,
            int webCitableLocatorCount,
            int vectorCandidateCount,
            int vectorCitableLocatorCount,
            int localCandidateCount,
            int localCitableLocatorCount) {

        public PromotionResult {
            if (status == null || reason == null) {
                throw new IllegalArgumentException("promotion status and reason are required");
            }
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            validateCounts(webCandidateCount, webCitableLocatorCount, "web");
            validateCounts(vectorCandidateCount, vectorCitableLocatorCount, "vector");
            validateCounts(localCandidateCount, localCitableLocatorCount, "local");
            switch (status) {
                case PROMOTED -> {
                    int totalCandidates = webCandidateCount + vectorCandidateCount + localCandidateCount;
                    if (reason != PromotionReason.PROMOTED
                            || evidence.isEmpty()
                            || totalCandidates <= 0
                            || evidence.size() > totalCandidates) {
                        throw new IllegalArgumentException("promoted result requires promoted evidence");
                    }
                    validatePromotedEvidence(
                            evidence,
                            webCitableLocatorCount,
                            vectorCitableLocatorCount,
                            localCitableLocatorCount);
                }
                case CONFIRMED_EMPTY -> {
                    if (!evidence.isEmpty()
                            || (reason != PromotionReason.NO_CITABLE_LOCATOR
                            && reason != PromotionReason.EVIDENCE_GATE_BLOCKED
                            && reason != PromotionReason.CITATION_GATE_BLOCKED)) {
                        throw new IllegalArgumentException("confirmed-empty result has contradictory authority");
                    }
                }
                case FAILED -> {
                    if (!evidence.isEmpty()
                            || (reason != PromotionReason.GATE_EXCEPTION
                            && reason != PromotionReason.CALLER_FAILURE)
                            || (reason == PromotionReason.CALLER_FAILURE
                            && (webCandidateCount != 0
                            || vectorCandidateCount != 0
                            || localCandidateCount != 0))) {
                        throw new IllegalArgumentException("failed result must carry a bounded failure reason only");
                    }
                }
                case UNAVAILABLE -> {
                    if (!evidence.isEmpty() || reason != PromotionReason.SERVICE_UNAVAILABLE
                            || webCandidateCount != 0 || webCitableLocatorCount != 0
                            || vectorCandidateCount != 0 || vectorCitableLocatorCount != 0
                            || localCandidateCount != 0 || localCitableLocatorCount != 0) {
                        throw new IllegalArgumentException("unavailable result cannot carry evidence lineage");
                    }
                }
            }
        }

        public int retrievalCandidateCount() {
            return webCandidateCount + vectorCandidateCount;
        }

        public int retrievalCitableLocatorCount() {
            return webCitableLocatorCount + vectorCitableLocatorCount;
        }

        public static PromotionResult unavailable() {
            return new PromotionResult(
                    PromotionStatus.UNAVAILABLE,
                    PromotionReason.SERVICE_UNAVAILABLE,
                    List.of(),
                    0, 0, 0, 0, 0, 0);
        }

        public static PromotionResult callerFailure() {
            return new PromotionResult(
                    PromotionStatus.FAILED,
                    PromotionReason.CALLER_FAILURE,
                    List.of(),
                    0, 0, 0, 0, 0, 0);
        }

        private static void validateCounts(int candidates, int citableLocators, String lane) {
            if (candidates < 0 || citableLocators < 0 || citableLocators > candidates) {
                throw new IllegalArgumentException("invalid " + lane + " promotion counts");
            }
        }

        private static void validatePromotedEvidence(
                List<RagEvidenceMetadata> evidence,
                int webCitableLocators,
                int vectorCitableLocators,
                int localCitableLocators) {
            int webEvidence = 0;
            int vectorEvidence = 0;
            int localEvidence = 0;
            for (RagEvidenceMetadata item : evidence) {
                if (item == null || (item.source() == null && item.filePath() == null)) {
                    throw new IllegalArgumentException("promoted evidence requires a citable locator");
                }
                switch (String.valueOf(item.kind())) {
                    case "WEB" -> webEvidence++;
                    case "VECTOR" -> vectorEvidence++;
                    case "LOCAL_DOC" -> localEvidence++;
                    default -> throw new IllegalArgumentException("promoted evidence has an unsupported lane");
                }
            }
            if (webEvidence > webCitableLocators
                    || vectorEvidence > vectorCitableLocators
                    || localEvidence > localCitableLocators) {
                throw new IllegalArgumentException("promoted evidence exceeds typed locator authority");
            }
        }
    }

    public RagEvidenceAttributionService(
            EvidenceGate evidenceGate,
            CitationGate citationGate,
            ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        this.evidenceGate = evidenceGate;
        this.citationGate = citationGate;
        this.debugEventStoreProvider = debugEventStoreProvider;
    }

    public List<RagEvidenceMetadata> promoteForPrompt(
            String question,
            List<Content> webDocs,
            List<Content> vectorDocs,
            List<Document> localDocs,
            QueryDomain domain,
            boolean followUp) {
        return promoteForPromptDetailed(question, webDocs, vectorDocs, localDocs, domain, followUp).evidence();
    }

    public PromotionResult promoteForPromptDetailed(
            String question,
            List<Content> webDocs,
            List<Content> vectorDocs,
            List<Document> localDocs,
            QueryDomain domain,
            boolean followUp) {
        long started = System.nanoTime();
        List<Candidate> candidates = new ArrayList<>();
        candidates.addAll(fromContents("WEB", "W", webDocs));
        candidates.addAll(fromContents("VECTOR", "V", vectorDocs));
        candidates.addAll(fromDocuments("LOCAL_DOC", "D", localDocs));

        List<Candidate> rawCitableCandidates = citableCandidates(candidates);
        int webCandidateCount = candidateCount(candidates, "WEB");
        int webCitableLocatorCount = candidateCount(rawCitableCandidates, "WEB");
        int vectorCandidateCount = candidateCount(candidates, "VECTOR");
        int vectorCitableLocatorCount = candidateCount(rawCitableCandidates, "VECTOR");
        int localCandidateCount = candidateCount(candidates, "LOCAL_DOC");
        int localCitableLocatorCount = candidateCount(rawCitableCandidates, "LOCAL_DOC");

        List<String> vectorLines = new ArrayList<>();
        List<String> kbLines = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.text == null || candidate.text.isBlank()) {
                continue;
            }
            if ("VECTOR".equals(candidate.metadata.kind())) {
                vectorLines.add(candidate.text);
            } else {
                kbLines.add(candidate.text);
            }
        }

        boolean evidencePassed = false;
        boolean citationSoftPassed = false;
        boolean citationMinPassed = false;
        int citationMin = effectiveMinCitations();
        PromotionReason reason = null;
        List<Candidate> filteredCandidates = List.of();
        try {
            evidencePassed = evidenceGate == null || evidenceGate.hasSufficientCoverage(
                    question,
                    vectorLines,
                    List.of(),
                    kbLines,
                    followUp,
                    domain == null ? QueryDomain.GENERAL : domain);
            filteredCandidates = filteredCitableCandidates(question, candidates);
            List<String> sources = filteredCandidates.stream()
                    .map(c -> locatorKey(c.metadata))
                    .filter(s -> s != null && !s.isBlank())
                    .distinct()
                    .toList();
            citationSoftPassed = citationGate == null || citationGate.ok(sources, citationMin, 0.0d);
            citationMinPassed = citationGate == null || sources.size() >= citationMin;
            if (candidates.isEmpty()) {
                reason = PromotionReason.NO_CITABLE_LOCATOR;
            } else if (!evidencePassed) {
                reason = PromotionReason.EVIDENCE_GATE_BLOCKED;
            } else if (filteredCandidates.isEmpty()) {
                reason = PromotionReason.NO_CITABLE_LOCATOR;
            } else if (!citationSoftPassed || !citationMinPassed) {
                reason = PromotionReason.CITATION_GATE_BLOCKED;
            }
        } catch (Throwable ex) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "promotion.gate");
            evidencePassed = false;
            citationSoftPassed = false;
            citationMinPassed = false;
            reason = PromotionReason.GATE_EXCEPTION;
            long stageMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            recordPromotion(question, candidates, List.of(), false, false,
                    false, citationMin, reason.traceValue(), stageMs);
            return new PromotionResult(
                    PromotionStatus.FAILED,
                    reason,
                    List.of(),
                    webCandidateCount,
                    webCitableLocatorCount,
                    vectorCandidateCount,
                    vectorCitableLocatorCount,
                    localCandidateCount,
                    localCitableLocatorCount);
        }

        List<RagEvidenceMetadata> promoted = (evidencePassed && citationSoftPassed && citationMinPassed
                && !filteredCandidates.isEmpty())
                ? filteredCandidates.stream().map(Candidate::metadata).toList()
                : List.of();
        PromotionStatus status = promoted.isEmpty()
                ? PromotionStatus.CONFIRMED_EMPTY
                : PromotionStatus.PROMOTED;
        if (status == PromotionStatus.PROMOTED) {
            reason = PromotionReason.PROMOTED;
        } else if (reason == null) {
            reason = PromotionReason.NO_CITABLE_LOCATOR;
        }
        long stageMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        recordPromotion(question, candidates, promoted, evidencePassed, citationSoftPassed,
                citationMinPassed, citationMin, reason.traceValue(), stageMs);
        return new PromotionResult(
                status,
                reason,
                promoted,
                webCandidateCount,
                webCitableLocatorCount,
                vectorCandidateCount,
                vectorCitableLocatorCount,
                localCandidateCount,
                localCitableLocatorCount);
    }

    public String appendFinalEvidenceAppendix(String answer, List<RagEvidenceMetadata> evidence) {
        if (answer == null || answer.isBlank() || evidence == null || evidence.isEmpty()) {
            return answer;
        }
        String trimmed = answer.trim();
        if (hasFinalEvidenceAppendixHeading(trimmed)) {
            return answer;
        }

        Set<String> usedMarkers = new LinkedHashSet<>();
        Matcher matcher = MARKER_PATTERN.matcher(trimmed);
        while (matcher.find()) {
            usedMarkers.add(matcher.group(1) + matcher.group(2));
        }

        List<RagEvidenceMetadata> selected;
        if (usedMarkers.isEmpty()) {
            selected = evidence.stream()
                    .filter(e -> e != null && e.marker() != null)
                    .limit(3)
                    .toList();
        } else {
            selected = evidence.stream()
                    .filter(e -> e != null && e.marker() != null)
                    .filter(e -> usedMarkers.contains(e.marker()))
                    .limit(MAX_APPENDIX_LINES)
                    .toList();
            if (selected.size() < usedMarkers.size()) {
                try {
                    TraceStore.put("rag.evidence.appendix.markerMismatch", true);
                    TraceStore.put("rag.evidence.appendix.requestedMarkerCount", usedMarkers.size());
                    TraceStore.put("rag.evidence.appendix.matchedMarkerCount", selected.size());
                } catch (Throwable ignore) {
                    log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "appendix.markerMismatchTrace");
                    RagEvidenceTraceSuppressions.trace("appendix.markerMismatchTrace", ignore);
                }
            }
        }
        if (selected.isEmpty()) {
            return answer;
        }

        try {
            TraceStore.put("rag.evidence.appendix.count", selected.size());
            TraceStore.put("rag.evidence.appendix.mode", usedMarkers.isEmpty() ? "top_passed_no_markers" : "answer_markers");
            appendBreadcrumb("RagEvidenceAttributionService", "answer_appendix", Map.of(
                    "selectedCount", selected.size(),
                    "markerMode", usedMarkers.isEmpty() ? "top_passed_no_markers" : "answer_markers"));
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "appendix.trace");
            RagEvidenceTraceSuppressions.trace("appendix.trace", ignore);
        }

        StringBuilder sb = new StringBuilder(trimmed);
        sb.append("\n\n---\n### Sources\n");
        for (RagEvidenceMetadata item : selected) {
            sb.append("- ").append(formatAppendixLine(item)).append('\n');
        }
        return sb.toString();
    }

    public static boolean hasFinalEvidenceAppendixHeading(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        boolean inFence = false;
        char fenceMarker = 0;
        int fenceLength = 0;
        for (String line : answer.split("\\R", -1)) {
            String trimmed = line.strip();
            if (inFence) {
                int closingLength = leadingRun(trimmed, fenceMarker);
                if (closingLength >= fenceLength && trimmed.substring(closingLength).isBlank()) {
                    inFence = false;
                    fenceMarker = 0;
                    fenceLength = 0;
                }
                continue;
            }

            int backtickLength = leadingRun(trimmed, '`');
            int tildeLength = leadingRun(trimmed, '~');
            if (backtickLength >= 3 || tildeLength >= 3) {
                inFence = true;
                fenceMarker = backtickLength >= 3 ? '`' : '~';
                fenceLength = Math.max(backtickLength, tildeLength);
                continue;
            }

            if (FINAL_EVIDENCE_HEADINGS.contains(trimmed.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static int leadingRun(String value, char marker) {
        int length = 0;
        while (length < value.length() && value.charAt(length) == marker) {
            length++;
        }
        return length;
    }

    private List<Candidate> fromContents(String kind, String prefix, List<Content> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        int rank = 0;
        for (Content doc : docs) {
            if (doc == null) {
                continue;
            }
            rank++;
            String text = contentText(doc);
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> metadata = contentMetadata(doc);
            String sourceUrl = sanitizePublicUrl(firstNonBlank(value(metadata, "url"), value(metadata, "link"),
                    value(metadata, "source"), value(metadata, "uri"), value(metadata, "document_url")));
            if (sourceUrl == null) {
                sourceUrl = sanitizePublicUrl(firstUrlFromText(text));
            }
            RagEvidenceMetadata item = new RagEvidenceMetadata(
                    prefix + rank,
                    kind,
                    sanitizeTitle(firstNonBlank(value(metadata, "title"), value(metadata, "document_title"),
                            value(metadata, "name"), value(metadata, "fileName"), value(metadata, "filename"))),
                    sourceUrl,
                    sanitizeFilePath(firstNonBlank(value(metadata, "filePath"), value(metadata, "file_path"),
                            value(metadata, "source_path"), value(metadata, "path"),
                            value(metadata, "documentPath"), value(metadata, "filename"))),
                    firstInt(metadata, "lineStart", "line_start", "startLine", "start_line", "chunkStartLine", "line"),
                    firstInt(metadata, "lineEnd", "line_end", "endLine", "end_line", "chunkEndLine"),
                    rank,
                    confidence(metadata),
                    confidenceSource(metadata)
            );
            out.add(new Candidate(item, text, SafeRedactor.hash12(text)));
        }
        return out;
    }

    private List<Candidate> fromDocuments(String kind, String prefix, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        int rank = 0;
        for (Document doc : docs) {
            if (doc == null) {
                continue;
            }
            rank++;
            String text;
            try {
                text = doc.text();
            } catch (Throwable ignore) {
                log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "document.text");
                text = null;
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> metadata;
            try {
                metadata = MetadataUtils.toMap(doc.metadata());
            } catch (Throwable ignore) {
                log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "document.metadata");
                metadata = Map.of();
            }
            String sourceUrl = sanitizePublicUrl(firstNonBlank(value(metadata, "source"), value(metadata, "url"),
                    value(metadata, "link"), value(metadata, "uri"), value(metadata, "document_url")));
            if (sourceUrl == null) {
                sourceUrl = sanitizePublicUrl(firstUrlFromText(text));
            }
            RagEvidenceMetadata item = new RagEvidenceMetadata(
                    prefix + rank,
                    kind,
                    sanitizeTitle(firstNonBlank(value(metadata, "title"), value(metadata, "document_title"),
                            value(metadata, "name"), value(metadata, "fileName"), value(metadata, "filename"))),
                    sourceUrl,
                    sanitizeFilePath(firstNonBlank(value(metadata, "filePath"), value(metadata, "file_path"),
                            value(metadata, "source_path"), value(metadata, "path"),
                            value(metadata, "documentPath"), value(metadata, "filename"))),
                    firstInt(metadata, "lineStart", "line_start", "startLine", "start_line", "chunkStartLine", "line"),
                    firstInt(metadata, "lineEnd", "line_end", "endLine", "end_line", "chunkEndLine"),
                    rank,
                    confidence(metadata),
                    confidenceSource(metadata)
            );
            out.add(new Candidate(item, text, SafeRedactor.hash12(text)));
        }
        return out;
    }

    private void recordPromotion(
            String question,
            List<Candidate> candidates,
            List<RagEvidenceMetadata> promoted,
            boolean evidencePassed,
            boolean citationSoftPassed,
            boolean citationMinPassed,
            int citationMin,
            String reason,
            long stageMs) {
        int candidateCount = candidates == null ? 0 : candidates.size();
        int promotedCount = promoted == null ? 0 : promoted.size();
        List<Candidate> citableCandidates = citableCandidates(candidates);
        int citableLocatorCount = citableCandidates.size();
        long distinctCitableLocatorCount = citableCandidates.stream()
                .map(c -> locatorKey(c.metadata))
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .count();
        List<Map<String, Object>> publicTrace = toTraceList(promoted);
        List<Map<String, Object>> candidateTrace = candidateTrace(candidates);
        double diversity = sourceDiversity(promoted);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("candidateCount", candidateCount);
        decision.put("citableLocatorCount", citableLocatorCount);
        decision.put("distinctCitableLocatorCount", distinctCitableLocatorCount);
        decision.put("promotedCount", promotedCount);
        decision.put("evidenceGatePassed", evidencePassed);
        decision.put("citationGateSoftPassed", citationSoftPassed);
        decision.put("citationGateMinPassed", citationMinPassed);
        decision.put("citationMin", citationMin);
        decision.put("stageMs", stageMs);
        decision.put("sourceDiversity", diversity);
        if (reason != null && !reason.isBlank()) {
            decision.put("disabledReason", reason);
        }

        try {
            TraceStore.put("rag.evidence.promotion.candidateCount", candidateCount);
            TraceStore.put("rag.evidence.promotion.citableLocatorCount", citableLocatorCount);
            TraceStore.put("rag.evidence.promotion.distinctCitableLocatorCount", distinctCitableLocatorCount);
            TraceStore.put("rag.evidence.promotion.promotedCount", promotedCount);
            TraceStore.put("rag.evidence.promotion.evidenceGatePassed", evidencePassed);
            TraceStore.put("rag.evidence.promotion.citationGateSoftPassed", citationSoftPassed);
            TraceStore.put("rag.evidence.promotion.citationGateMinPassed", citationMinPassed);
            TraceStore.put("rag.evidence.promotion.citationMin", citationMin);
            TraceStore.put("rag.evidence.promotion.stageMs", stageMs);
            TraceStore.put("rag.evidence.promotion.sourceDiversity", diversity);
            if (reason != null && !reason.isBlank()) {
                TraceStore.put("rag.evidence.promotion.disabledReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            }
            TraceStore.put("rag.evidence.candidates", candidateTrace);
            TraceStore.put("rag.evidence.public", publicTrace);
            TraceStore.put("prompt.citableEvidenceCount", promotedCount);
            TraceContext.current().setFlag("rag.evidence.promotedCount", promotedCount);
            appendBreadcrumb("RagEvidenceAttributionService", promotedCount > 0 ? "evidence_promoted" : "evidence_blocked",
                    decision);
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "promotion.trace");
            RagEvidenceTraceSuppressions.trace("promotion.trace", ignore);
        }

        try {
            String queryHash = SafeRedactor.hash12(question);
            OrchEventEmitter.ragEvent(
                    "rag.pipeline",
                    "prompt",
                    "evidence_promotion",
                    "complete",
                    "RagEvidenceAttributionService",
                    promotedCount > 0 ? "ok" : "blocked",
                    Map.of(
                            "queryHash", queryHash == null ? "" : queryHash,
                            "queryLen", question == null ? 0 : question.length(),
                            "requestedTopK", candidateCount,
                            "mode", "gate_promote"),
                    Map.of(
                            "returnedCount", candidateCount,
                            "afterFilterCount", candidateCount,
                            "selectedCount", candidateCount,
                            "promotedCount", promotedCount,
                            "stageMs", stageMs,
                            "sourceDiversity", diversity),
                    reason == null ? Map.of() : Map.of(
                            "reasonCode", reason,
                            "failureClass", reason,
                            "exceptionType", "None"),
                    Map.of(
                            "action", promotedCount > 0 ? "promote" : "block",
                            "applied", true,
                            "reasonCode", reason == null ? "passed" : reason,
                            "breadcrumbId", traceBreadcrumbId()));
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "promotion.ragEvent");
            RagEvidenceTraceSuppressions.trace("promotion.ragEvent", ignore);
        }

        emitDebug(decision);
    }

    private static String traceBreadcrumbId() {
        Object traceId = TraceStore.get("trace.id");
        String hash = traceId == null ? null : SafeRedactor.hashValue(String.valueOf(traceId));
        return hash == null || hash.isBlank() ? "evidence_promotion" : hash;
    }

    private void emitDebug(Map<String, Object> decision) {
        try {
            DebugEventStore store = debugEventStoreProvider == null ? null : debugEventStoreProvider.getIfAvailable();
            if (store == null) {
                return;
            }
            store.emit(
                    DebugProbeType.ORCHESTRATION,
                    Boolean.TRUE.equals(decision.get("evidenceGatePassed"))
                            && Boolean.TRUE.equals(decision.get("citationGateMinPassed"))
                            ? DebugEventLevel.INFO : DebugEventLevel.WARN,
                    "rag.evidence.promotion",
                    "RAG evidence promotion evaluated.",
                    "RagEvidenceAttributionService.promoteForPrompt",
                    decision,
                    null);
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "promotion.debugEvent");
            RagEvidenceTraceSuppressions.trace("promotion.debugEvent", ignore);
        }
    }

    private static void appendBreadcrumb(String component, String decision, Map<String, Object> data) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("v", 1);
        row.put("seq", TraceStore.nextSequence("ml.breadcrumbs.v1"));
        row.put("ts", Instant.now().toString());
        row.put("component", component);
        row.put("rules", "EvidenceGate+CitationGate");
        row.put("decision", decision);
        row.put("requestId", SafeRedactor.hashValue(firstNonBlank(MDC.get("x-request-id"), String.valueOf(TraceStore.get("requestId")))));
        row.put("sessionId", SafeRedactor.hashValue(firstNonBlank(MDC.get("sessionId"), MDC.get("sid"), String.valueOf(TraceStore.get("sessionId")))));
        Map<String, Object> safeData = new LinkedHashMap<>();
        safeData.put("queryRedacted", true);
        if (data != null && !data.isEmpty()) {
            safeData.putAll(data);
        }
        row.put("data", safeData);
        TraceStore.append("ml.breadcrumbs.v1", row);
        TraceStore.put("cihRag.breadcrumb.queryRedacted", true);
    }

    private static List<Map<String, Object>> toTraceList(List<RagEvidenceMetadata> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (RagEvidenceMetadata item : evidence) {
            if (item != null) {
                out.add(item.toTraceMap());
            }
        }
        return out;
    }

    private static List<Map<String, Object>> candidateTrace(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.metadata == null) {
                continue;
            }
            Map<String, Object> row = candidate.metadata.toTraceMap();
            row.put("snippetHash", candidate.snippetHash);
            out.add(row);
        }
        return out;
    }

    private static int effectiveMinCitations() {
        try {
            GuardContext ctx = GuardContextHolder.getOrDefault();
            if (ctx != null && ctx.isCheapSearchMode()) {
                return 1;
            }
            if (ctx != null && ctx.getMinCitations() != null && ctx.getMinCitations() > 0) {
                return ctx.getMinCitations();
            }
            if (ctx != null && ctx.getMode() != null) {
                return switch (ctx.getMode()) {
                    case "BRAVE" -> 2;
                    case "ZERO_BREAK", "RULE_BREAK" -> 1;
                    default -> 3;
                };
            }
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "effectiveMinCitations");
            RagEvidenceTraceSuppressions.trace("kindRank.parse", ignore);
        }
        return 3;
    }

    private static String formatAppendixLine(RagEvidenceMetadata item) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(item.marker()).append("] ");
        String safeFilePath = safeAppendixFilePath(item.filePath());
        String label = firstNonBlank(item.title(), safeFilePath, item.source(), item.kind());
        sb.append(label == null ? "evidence" : label);
        if (item.source() != null && !item.source().isBlank() && !item.source().equals(label)) {
            sb.append(" - ").append(item.source());
        }
        if (safeFilePath != null && !safeFilePath.isBlank() && !safeFilePath.equals(label)) {
            sb.append(" (file: ").append(safeFilePath);
            if (item.lineStart() != null) {
                sb.append(':').append(item.lineStart());
                if (item.lineEnd() != null && !item.lineEnd().equals(item.lineStart())) {
                    sb.append('-').append(item.lineEnd());
                }
            }
            sb.append(')');
        } else if (item.lineStart() != null) {
            sb.append(" (line ").append(item.lineStart());
            if (item.lineEnd() != null && !item.lineEnd().equals(item.lineStart())) {
                sb.append('-').append(item.lineEnd());
            }
            sb.append(')');
        }
        if (item.confidence() != null) {
            sb.append(" (confidence ")
                    .append(String.format(Locale.ROOT, "%.3f", item.confidence()))
                    .append(')');
        } else if ("unavailable".equals(item.confidenceSource())) {
            sb.append(" (confidence unavailable)");
        }
        return sb.toString();
    }

    private static String safeAppendixFilePath(String filePath) {
        String path = firstNonBlank(filePath);
        if (path == null) {
            return null;
        }
        return "pathHash=" + SafeRedactor.hashValue(path) + " pathLength=" + path.length();
    }

    private static List<Candidate> citableCandidates(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(c -> c != null && hasCitableLocator(c.metadata))
                .toList();
    }

    private static int candidateCount(List<Candidate> candidates, String kind) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        return (int) candidates.stream()
                .filter(c -> c != null
                        && c.metadata != null
                        && kind.equals(c.metadata.kind()))
                .count();
    }

    private static List<Candidate> filteredCitableCandidates(String question, List<Candidate> candidates) {
        List<Candidate> citable = citableCandidates(candidates);
        if (citable.isEmpty() || !strictNamedOfficialEvidencePrompt(question)) {
            return citable;
        }
        List<String> domains = namedOfficialEvidenceDomains(question);
        List<Candidate> official = citable.stream()
                .filter(candidate -> candidate != null
                        && candidate.metadata != null
                        && sourceMatchesAnyDomain(candidate.metadata.source(), domains))
                .toList();
        try {
            TraceStore.put("rag.evidence.promotion.namedOfficialFiltered", true);
            TraceStore.put("rag.evidence.promotion.namedOfficialFilteredCount", official.size());
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "namedOfficial.trace");
            RagEvidenceTraceSuppressions.trace("namedOfficial.trace", ignore);
        }
        return official;
    }

    private static boolean hasCitableLocator(RagEvidenceMetadata metadata) {
        return locatorKey(metadata) != null;
    }

    private static String locatorKey(RagEvidenceMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        String source = firstNonBlank(metadata.source());
        if (source != null) {
            return "url:" + source.toLowerCase(Locale.ROOT);
        }
        String path = firstNonBlank(metadata.filePath());
        if (path != null) {
            return "file:" + path.replace('\\', '/').toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static boolean strictNamedOfficialEvidencePrompt(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return !q.isBlank()
                && !hasExplicitEvidenceDomain(q)
                && !namedOfficialEvidenceDomains(q).isEmpty()
                && (q.contains("official source")
                || q.contains("official sources")
                || q.contains("official/external")
                || q.contains("official evidence")
                || looksLikeNamedOfficialSourceDomainPrompt(q));
    }

    private static boolean looksLikeNamedOfficialSourceDomainPrompt(String q) {
        String normalized = q == null ? "" : q.toLowerCase(Locale.ROOT);
        boolean namedOfficialTarget = normalized.contains("openai") || normalized.contains("supabase");
        boolean sourceDomainPrompt = normalized.contains("source domain")
                || normalized.contains("source domains")
                || normalized.contains("\uCD9C\uCC98 \uB3C4\uBA54\uC778")
                || (normalized.contains("\uB3C4\uBA54\uC778")
                && (normalized.contains("\uCD9C\uCC98")
                || normalized.contains("\uADFC\uAC70")
                || normalized.contains("\uAC80\uC99D")));
        return namedOfficialTarget && sourceDomainPrompt;
    }

    private static boolean hasExplicitEvidenceDomain(String question) {
        String q = question == null ? "" : question;
        return Pattern.compile("\\b(?:site:)?[a-z0-9][a-z0-9-]*\\.(?:com|org|net|io|ai|dev|co|kr|edu|gov)\\b",
                Pattern.CASE_INSENSITIVE).matcher(q).find();
    }

    private static List<String> namedOfficialEvidenceDomains(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<String> domains = new ArrayList<>();
        if (q.contains("openai")) {
            domains.add("developers.openai.com");
        }
        if (q.contains("supabase")) {
            domains.add("supabase.com");
        }
        return domains;
    }

    private static boolean sourceMatchesAnyDomain(String source, List<String> domains) {
        if (source == null || source.isBlank() || domains == null || domains.isEmpty()) {
            return false;
        }
        String host;
        try {
            host = URI.create(source).getHost();
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "namedOfficial.host");
            RagEvidenceTraceSuppressions.trace("namedOfficial.host", ignore);
            return false;
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        for (String domain : domains) {
            String normalizedDomain = domain == null ? "" : domain.toLowerCase(Locale.ROOT);
            if (normalizedHost.equals(normalizedDomain) || normalizedHost.endsWith("." + normalizedDomain)) {
                return true;
            }
        }
        return false;
    }

    private static double sourceDiversity(List<RagEvidenceMetadata> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0.0d;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (RagEvidenceMetadata item : evidence) {
            if (item == null) {
                continue;
            }
            String key = host(firstNonBlank(item.source(), item.filePath(), item.title()));
            if (key != null && !key.isBlank()) {
                seen.add(key);
            }
        }
        return evidence.isEmpty() ? 0.0d : (double) seen.size() / (double) evidence.size();
    }

    private static String host(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            String h = uri.getHost();
            if (h != null && !h.isBlank()) {
                return h.toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "hostOf.parse");
            RagEvidenceTraceSuppressions.trace("hostOf.parse", ignore);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String contentText(Content content) {
        try {
            if (content != null && content.textSegment() != null && content.textSegment().text() != null) {
                return content.textSegment().text();
            }
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "content.text");
            RagEvidenceTraceSuppressions.trace("content.text", ignore);
        }
        return null;
    }

    private static Map<String, Object> contentMetadata(Content content) {
        try {
            if (content != null && content.textSegment() != null) {
                return MetadataUtils.toMap(content.textSegment().metadata());
            }
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "content.metadata");
            RagEvidenceTraceSuppressions.trace("content.metadata", ignore);
        }
        return Map.of();
    }

    private static String value(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer firstInt(Map<String, Object> metadata, String... keys) {
        if (metadata == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            Integer parsed = toInt(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static Integer toInt(Object value) {
        if (value instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "toInt");
                return null;
            }
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ignore) {
                log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "toInt");
                return null;
            }
        }
        return null;
    }

    private static Double confidence(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        for (String key : confidenceKeys()) {
            Object value = metadata.get(key);
            Double parsed = toDouble(value);
            if (parsed != null) {
                return Math.max(0.0d, Math.min(1.0d, parsed));
            }
        }
        return null;
    }

    private static String confidenceSource(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "unavailable";
        }
        for (String key : confidenceKeys()) {
            if (toDouble(metadata.get(key)) != null) {
                return key;
            }
        }
        return "unavailable";
    }

    private static List<String> confidenceKeys() {
        return List.of(
                "_nova.compositionScore",
                "_nova.rerankConfidence",
                "rerankConfidence",
                "rerank_confidence",
                "grandas_adjusted_score",
                "grandas_base_score",
                "relevanceScore",
                "rerankScore",
                "vectorScore",
                "score");
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            if (!Double.isFinite(parsed)) {
                log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "toDouble");
                return null;
            }
            return parsed;
        }
        if (value instanceof String s) {
            try {
                double parsed = Double.parseDouble(s.trim());
                if (!Double.isFinite(parsed)) {
                    log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "toDouble");
                    return null;
                }
                return parsed;
            } catch (NumberFormatException ignore) {
                log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "toDouble");
                return null;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value.trim())) {
                return value.trim();
            }
        }
        return null;
    }

    private static String firstUrlFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = URL_IN_TEXT.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String url = matcher.group();
        while (url != null && !url.isBlank()) {
            char last = url.charAt(url.length() - 1);
            if (last == '.' || last == ',' || last == ';' || last == ':' || last == '!' || last == '?') {
                url = url.substring(0, url.length() - 1).trim();
                continue;
            }
            break;
        }
        return url;
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        String s = value.replace('\u0000', ' ').replaceAll("\\s+", " ").trim();
        if (max > 0 && s.length() > max) {
            return s.substring(0, max);
        }
        return s;
    }

    private static String sanitizeTitle(String value) {
        return limit(SafeRedactor.safeMessage(value, 180), 180);
    }

    private static String sanitizeFilePath(String value) {
        if (value == null) {
            return null;
        }
        String safe = value.replace('\u0000', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (safe.isBlank() || "null".equalsIgnoreCase(safe)) {
            return null;
        }
        return limit(SafeRedactor.redact(safe), 1000);
    }

    private static String sanitizePublicUrl(String value) {
        String raw = firstNonBlank(value);
        if (raw == null) {
            return null;
        }
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            URI clean = new URI(
                    scheme.toLowerCase(Locale.ROOT),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    null,
                    null,
                    null);
            String publicUrl = clean.toString() + (uri.getRawPath() == null ? "" : uri.getRawPath());
            return publicUrl.length() <= 1000 ? publicUrl : null;
        } catch (Throwable ignore) {
            log.debug("[RagEvidenceAttributionService] fail-soft stage={}", "sanitizePublicUrl");
            return null;
        }
    }

    private record Candidate(RagEvidenceMetadata metadata, String text, String snippetHash) {
    }
}
