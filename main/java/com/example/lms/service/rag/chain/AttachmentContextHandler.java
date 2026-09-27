package com.example.lms.service.rag.chain;

import com.example.lms.service.AttachmentService;
import com.example.lms.service.AttachmentOwnerIdentity;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import com.example.lms.service.rag.rerank.DppDiversityReranker;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Attempts to enrich the prompt context with any user attachments
 * associated with the current session.
 */
public class AttachmentContextHandler implements ChainLink {
    private static final Logger log = LoggerFactory.getLogger(AttachmentContextHandler.class);

    private final AttachmentService attachmentService;
    private final CrossEncoderReranker biEncoderReranker;
    private final CrossEncoderReranker onnxReranker;
    private final DppDiversityReranker dppDiversityReranker;

    public AttachmentContextHandler(AttachmentService attachmentService) {
        this(attachmentService, null, null, null);
    }

    public AttachmentContextHandler(AttachmentService attachmentService,
                                    CrossEncoderReranker biEncoderReranker,
                                    CrossEncoderReranker onnxReranker,
                                    DppDiversityReranker dppDiversityReranker) {
        this.attachmentService = attachmentService;
        this.biEncoderReranker = biEncoderReranker;
        this.onnxReranker = onnxReranker;
        this.dppDiversityReranker = dppDiversityReranker;
    }


    @Override
    public ChainOutcome handle(ChainContext ctx, Chain next) {
        try {
            // Look up any attachments associated with the current session.  When
            // attachments exist, invoke ctx.withAttachment for each so that
            // downstream prompt builders can incorporate summaries or previews.
            AttachmentOwnerIdentity ownerIdentity = ctx == null ? null : ctx.attachmentOwnerIdentity();
            var atts = attachmentService == null || ownerIdentity == null
                    ? java.util.List.<com.example.lms.dto.AttachmentDto>of()
                    : attachmentService.findBySession(ctx.sessionId(), ownerIdentity);
            int activeCount = 0;
            int skippedCount = 0;
            List<String> attachmentIds = new ArrayList<>();
            if (atts != null) {
                for (var att : atts) {
                    try {
                        ctx.withAttachment(att);
                        if (att != null && att.id() != null && !att.id().isBlank()) {
                            attachmentIds.add(att.id());
                        }
                        activeCount++;
                    } catch (Exception attachEx) {
                        skippedCount++;
                        TraceStore.put("cihRag.attachment.skipReason." + skippedCount,
                                SafeRedactor.traceLabelOrFallback(attachEx.getClass().getSimpleName(), "unknown_attach_error"));
                        log.debug("[AWX][rag][chain] attachment skip errorType={} skippedTotal={}",
                                SafeRedactor.traceLabelOrFallback(attachEx.getClass().getSimpleName(), "unknown"),
                                skippedCount);
                    }
                }
            }
            CihPipelineTrace pipeline = runIqrPipeline(ctx, attachmentIds, ownerIdentity);
            traceCihRag(activeCount, skippedCount, pipeline);
            return next.proceed(ctx);
        } catch (Exception e) {
            traceCihRag(0, 1, CihPipelineTrace.disabled("attachment_context_error"));
            log.warn("[AWX][rag][chain] attachmentContext failed failureReason={} errorType={} sessionHash={}",
                    "attachment-context-handler-error",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.hash12(ctx == null ? null : ctx.sessionId()));
            return next.proceed(ctx);
        }
    }

    private CihPipelineTrace runIqrPipeline(
            ChainContext ctx,
            List<String> attachmentIds,
            AttachmentOwnerIdentity ownerIdentity) {
        if (attachmentService == null) {
            return CihPipelineTrace.disabled("attachment_service_unavailable");
        }
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return CihPipelineTrace.disabled("no_attachments");
        }
        if (ownerIdentity == null) {
            return CihPipelineTrace.disabled("missing_attachment_owner");
        }
        try {
            List<Document> docs = attachmentService.asDocumentsForSession(
                    attachmentIds,
                    ctx == null ? null : ctx.sessionId(),
                    ownerIdentity);
            int iterations = 1;
            if (docs == null || docs.isEmpty()) {
                return CihPipelineTrace.empty(iterations, "no_attachment_docs");
            }
            List<Document> refined = new ArrayList<>(docs);
            String query = ctx == null ? "" : ctx.userMessage();
            CihPipelineTrace trace = CihPipelineTrace.pipeline(iterations, refined.size());
            refined = applyCrossEncoder("bi_encoder", biEncoderReranker, query, refined, trace);
            refined = applyCrossEncoder("onnx", onnxReranker, query, refined, trace);
            refined = applyDpp(query, refined, trace);
            if (ctx != null && !refined.isEmpty()) {
                ctx.withLocalDocs(refined);
            }
            trace.localDocCount = refined.size();
            return trace;
        } catch (Exception ex) {
            TraceStore.put("cihRag.suppressed.iqrPipeline", true);
            TraceStore.put("cihRag.suppressed.iqrPipeline.errorType",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
            log.debug("[AWX][rag][chain] iqr pipeline fail-soft errorType={}",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"));
            return CihPipelineTrace.empty(1, "iqr_pipeline_failed");
        }
    }

    private static List<Document> applyCrossEncoder(String stage,
                                                    CrossEncoderReranker reranker,
                                                    String query,
                                                    List<Document> docs,
                                                    CihPipelineTrace trace) {
        if ("bi_encoder".equals(stage)) {
            trace.biEncoderApplied = false;
            trace.biEncoderDisabledReason = "bi_encoder_unavailable";
        } else {
            trace.onnxRerankApplied = false;
            trace.onnxRerankDisabledReason = "onnx_unavailable";
        }
        if (reranker == null || docs == null || docs.isEmpty()) {
            return docs == null ? List.of() : docs;
        }
        try {
            List<Content> reranked = reranker.rerank(query, toContents(docs), Math.max(1, Math.min(8, docs.size())));
            List<Document> ordered = documentsInContentOrder(reranked, docs);
            if ("bi_encoder".equals(stage)) {
                trace.biEncoderApplied = true;
                trace.biEncoderDisabledReason = null;
            } else {
                trace.onnxRerankApplied = true;
                trace.onnxRerankDisabledReason = null;
            }
            return ordered;
        } catch (Exception ex) {
            traceSuppressed(stage, ex);
            String reason = stage + "_failed";
            if ("bi_encoder".equals(stage)) {
                trace.biEncoderDisabledReason = reason;
            } else {
                trace.onnxRerankDisabledReason = reason;
            }
            return docs;
        }
    }

    private List<Document> applyDpp(String query, List<Document> docs, CihPipelineTrace trace) {
        trace.dppApplied = false;
        trace.dppDisabledReason = "dpp_unavailable";
        if (dppDiversityReranker == null || docs == null || docs.size() < 2) {
            return docs == null ? List.of() : docs;
        }
        try {
            int k = Math.max(1, Math.min(8, docs.size()));
            List<Document> out = dppDiversityReranker.rerank(
                    new DppDiversityReranker.Config(0.65d, k),
                    docs,
                    query,
                    k,
                    AttachmentContextHandler::documentText,
                    ignored -> 0.5d);
            trace.dppApplied = true;
            trace.dppDisabledReason = null;
            return out == null || out.isEmpty() ? docs : out;
        } catch (Exception ex) {
            trace.dppDisabledReason = "dpp_failed";
            traceSuppressed("dpp", ex);
            return docs;
        }
    }

    private static List<Content> toContents(List<Document> docs) {
        List<Content> out = new ArrayList<>();
        if (docs == null) {
            return out;
        }
        for (Document doc : docs) {
            String text = documentText(doc);
            if (text.isBlank()) {
                continue;
            }
            out.add(Content.from(TextSegment.from(text, metadata(doc))));
        }
        return out;
    }

    private static Metadata metadata(Document doc) {
        try {
            return doc.metadata() == null ? Metadata.from(Map.of()) : doc.metadata();
        } catch (Exception ex) {
            traceSuppressed("metadata", ex);
            return Metadata.from(Map.of());
        }
    }

    private static List<Document> documentsInContentOrder(List<Content> ordered, List<Document> original) {
        if (ordered == null || ordered.isEmpty() || original == null || original.isEmpty()) {
            return original == null ? List.of() : original;
        }
        Map<String, List<Document>> byText = new HashMap<>();
        for (Document doc : original) {
            byText.computeIfAbsent(documentText(doc), ignored -> new ArrayList<>()).add(doc);
        }
        List<Document> out = new ArrayList<>();
        for (Content content : ordered) {
            String text = contentText(content);
            List<Document> matches = byText.get(text);
            if (matches != null && !matches.isEmpty()) {
                out.add(matches.remove(0));
            }
        }
        return out.isEmpty() ? original : out;
    }

    private static String contentText(Content content) {
        try {
            return content == null || content.textSegment() == null ? "" : content.textSegment().text();
        } catch (Exception ex) {
            traceSuppressed("contentText", ex);
            return "";
        }
    }

    private static String documentText(Document doc) {
        try {
            String text = doc == null ? null : doc.text();
            return text == null ? "" : text;
        } catch (Exception ex) {
            traceSuppressed("documentText", ex);
            return "";
        }
    }

    private static void traceSuppressed(String stage, Exception ex) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = SafeRedactor.traceLabelOrFallback(
                ex == null ? null : ex.getClass().getSimpleName(), "unknown");
        TraceStore.put("cihRag.suppressed.stage", safeStage);
        TraceStore.put("cihRag.suppressed.errorType", safeErrorType);
        TraceStore.put("cihRag.suppressed." + safeStage, true);
        TraceStore.put("cihRag.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static void traceCihRag(int activeCount, int skippedCount, CihPipelineTrace pipeline) {
        CihPipelineTrace trace = pipeline == null ? CihPipelineTrace.disabled("unknown") : pipeline;
        TraceStore.put("cihRag.activeFileCount", Math.max(0, activeCount));
        TraceStore.put("cihRag.skippedFileCount", Math.max(0, skippedCount));
        TraceStore.put("cihRag.iqrIterations", Math.max(0, trace.iqrIterations));
        TraceStore.put("cihRag.iqrDisabledReason", trace.iqrDisabledReason);
        TraceStore.put("cihRag.localDocCount", Math.max(0, trace.localDocCount));
        TraceStore.put("cihRag.biEncoderApplied", trace.biEncoderApplied);
        TraceStore.put("cihRag.biEncoderDisabledReason", trace.biEncoderDisabledReason);
        TraceStore.put("cihRag.onnxRerankApplied", trace.onnxRerankApplied);
        TraceStore.put("cihRag.onnxRerankDisabledReason", trace.onnxRerankDisabledReason);
        TraceStore.put("cihRag.dppApplied", trace.dppApplied);
        TraceStore.put("cihRag.dppDisabledReason", trace.dppDisabledReason);
        TraceStore.put("cihRag.mlaBreadcrumbCount", mlaBreadcrumbCount());
        TraceStore.put("cihRag.implementationStage", trace.implementationStage);
    }

    private static int mlaBreadcrumbCount() {
        Object rows = TraceStore.get("ml.breadcrumbs.v1");
        if (rows instanceof Collection<?> collection) {
            return collection.size();
        }
        return rows == null ? 0 : 1;
    }

    private static final class CihPipelineTrace {
        private int iqrIterations;
        private String iqrDisabledReason;
        private int localDocCount;
        private boolean biEncoderApplied;
        private String biEncoderDisabledReason;
        private boolean onnxRerankApplied;
        private String onnxRerankDisabledReason;
        private boolean dppApplied;
        private String dppDisabledReason;
        private String implementationStage;
        private static CihPipelineTrace pipeline(int iterations, int localDocCount) {
            CihPipelineTrace trace = new CihPipelineTrace();
            trace.iqrIterations = Math.max(0, iterations);
            trace.localDocCount = Math.max(0, localDocCount);
            trace.biEncoderDisabledReason = "bi_encoder_unavailable";
            trace.onnxRerankDisabledReason = "onnx_unavailable";
            trace.dppDisabledReason = "dpp_unavailable";
            trace.implementationStage = "iqr_pipeline";
            return trace;
        }

        private static CihPipelineTrace empty(int iterations, String reason) {
            CihPipelineTrace trace = pipeline(iterations, 0);
            trace.iqrDisabledReason = reason;
            trace.implementationStage = "iqr_pipeline_empty";
            return trace;
        }

        private static CihPipelineTrace disabled(String reason) {
            CihPipelineTrace trace = empty(0, reason);
            trace.implementationStage = "disabled";
            return trace;
        }
    }
}
