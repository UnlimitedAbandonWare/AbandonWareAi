package com.example.lms.service.rag.graph;

import com.example.lms.domain.ChatMessage;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.ner.NamedEntityExtractor;
import com.example.lms.service.rag.knowledge.UniversalContextLexicon;
import com.example.lms.service.vector.DocumentChunkingService;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GraphRagChunkingService {

    private static final Logger log = LoggerFactory.getLogger(GraphRagChunkingService.class);
    private static final Pattern MANUAL_ENTITY_FALLBACK =
            Pattern.compile("\\b[\\p{Lu}][\\p{L}\\p{N}_-]{2,}\\b");

    private final BrainStateProperties properties;
    private final DocumentChunkingService documentChunkingService;
    private final NamedEntityExtractor entityExtractor;
    private final Neo4jKgChunkWriter neo4jWriter;
    private final UniversalContextLexicon lexicon;
    private final VectorStoreService vectorStoreService;
    private final BrainStateService brainStateService;
    private final ChatMessageRepository chatMessageRepository;
    private final AnchorFrequencyIndex anchorFrequencyIndex;

    @Autowired(required = false)
    private GeneralGraphSourceAuthority sourceAuthority;
    @Autowired
    private com.example.lms.service.AttachmentSourceStore attachmentSources;

    /** Only an explicitly consented, current typed source may enter the existing private indexes. */
    public IngestReport ingestAttachmentSource(GeneralGraphScope scope, KgChunk.SourceRef reference) {
        String sid=scope==null?"__TRANSIENT__":Long.toString(scope.sessionId());
        if(sourceAuthority==null||attachmentSources==null||scope==null||reference==null
                ||GeneralGraphSourceAuthority.sourceAttachmentId(reference.sourceId())==null)
            return IngestReport.disabled(sid,"attachment_authority_missing");
        if(!properties.isEnabled()||!properties.getIndexing().isEnabled())
            return IngestReport.disabled(sid,"brain_state_disabled");
        attachmentWorkCurrent();
        var original=sourceAuthority.source(scope,reference);
        if(original.isEmpty())return IngestReport.disabled(sid,"attachment_source_unavailable");
        List<KgChunk> chunks=new ArrayList<>();
        try {
            var units=new com.fasterxml.jackson.databind.ObjectMapper().readTree(original.get().text());
            if(!units.isArray()||units.size()>128)return IngestReport.disabled(sid,"attachment_units_budget");
            for(var unit:units){
                String body=unit.path("text").asText("");
                if(body.isBlank()||"ARCHIVE_LIST".equals(unit.path("locatorType").asText()))continue;
                for(String piece:splitToBrainSize(body)){
                    attachmentWorkCurrent();
                    if(chunks.size()>=64)return IngestReport.disabled(sid,"attachment_chunk_budget");
                    Set<String> names=new LinkedHashSet<>();
                    addManualFallbackEntities(names,piece);
                    // Every entity is an exact original span. No injected NER/model call in this lane.
                    var entities=names.stream().filter(piece::contains).limit(8)
                        .map(name->new KgChunk.KgEntity(name,"ENTITY","general",0.55d)).toList();
                    var chunk=new KgChunk(stableId("attachment",sid,piece,reference.sourceId(),chunks.size()),
                        sid,piece,entities,coOccurrenceRelations(entities),"general",confidenceFor(entities),
                        Instant.now(),"ATTACHMENT","BRAIN_STATE","CONVERSATION","brain_state")
                        .withSource(scope,original.get());
                    chunks.add(chunk);
                }
            }
        } catch(com.fasterxml.jackson.core.JsonProcessingException invalid){
            return IngestReport.disabled(sid,"attachment_units_invalid");
        }
        if(chunks.isEmpty())return IngestReport.disabled(sid,"attachment_no_body");
        return sourceAuthority.withCurrentSource(scope,original.get(),current->{
            attachmentWorkCurrent();
            Map<String,Object> backend=new LinkedHashMap<>();
            backend.put("meaningfulGate","source_verified");
            backend.put("semanticAttempts",0);
            backend.put("semanticExtraction","RULES_ONLY");
            var report=persistChunks(sid,"ATTACHMENT",chunks,BrainStateText.hash12(current.text()),backend,IngestOptions.defaults());
            String outcome=report.captureOutcome().toUpperCase(Locale.ROOT);
            attachmentSources.recordIndex(GeneralGraphSourceAuthority.sourceAttachmentId(reference.sourceId()),
                reference.sourceRevision(),"SUCCEEDED".equals(outcome)?"READY":outcome,
                String.valueOf(report.backend().getOrDefault("vectorStatus","NOT_INDEXED")).toUpperCase(Locale.ROOT),
                report.disabledReason());
            return report;
        }).orElseGet(()->IngestReport.disabled(sid,"attachment_source_invalidated"));
    }

    private static void attachmentWorkCurrent(){
        com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
        var budget=com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        if(budget!=null&&budget.expired())throw new CancellationException("attachment_budget_exhausted");
    }

    public IngestReport ingestFinalizedTurn(GeneralGraphScope scope, long assistantMessageId) {
        return ingestFinalizedTurn(scope, null, assistantMessageId);
    }

    public IngestReport ingestFinalizedTurn(GeneralGraphScope scope, Long userMessageId, long assistantMessageId) {
        String sid = scope == null ? "__TRANSIENT__" : Long.toString(scope.sessionId());
        if (sourceAuthority == null || scope == null || !scope.memoryEnabled())
            return IngestReport.disabled(sid, "source_authority_missing");
        var finalSource = sourceAuthority.source(scope, assistantMessageId);
        if (finalSource.isEmpty() || !"ASSISTANT".equals(finalSource.get().sourceRole()))
            return IngestReport.disabled(sid, "final_source_unavailable");
        List<IngestReport> reports = new ArrayList<>();
        // Only this approved run's exact user row is eligible. A later answer must
        // never retroactively authorize earlier OFF/denied conversation turns.
        if (userMessageId != null) {
            if (userMessageId <= 0 || userMessageId >= assistantMessageId)
                return IngestReport.disabled(sid, "user_source_unavailable");
            var userSource = sourceAuthority.source(scope, userMessageId);
            if (userSource.isEmpty() || !"USER".equals(userSource.get().sourceRole()))
                return IngestReport.disabled(sid, "user_source_unavailable");
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("graph_capture_cancelled");
            reports.add(ingestSource(scope, userSource.get()));
        }
        reports.add(ingestSource(scope, finalSource.get()));
        return IngestReport.merge(sid, "finalized-transcript", reports);
    }

    IngestReport ingestSource(GeneralGraphScope scope, com.example.lms.assist.MemoryEvidence evidence) {
        String sid = Long.toString(scope.sessionId());
        if (!properties.isEnabled() || !properties.getIndexing().isEnabled())
            return IngestReport.disabled(sid, "brain_state_disabled");
        var options = IngestOptions.defaults();
        // A short correction or pronoun follow-up is still valid source evidence even
        // when it has no named entities. Preserve it for the bounded recent-source window.
        var chunks = chunkAndExtract(sid, evidence.text(), inferDomain(evidence.text(), null),
                evidence.sourceRole(), options).stream().map(c -> c.withSource(scope, evidence)).toList();
        if (chunks.isEmpty()) return new IngestReport(true, sid, "skipped", 0, 0, 0, 0,
                BrainStateText.hash12(evidence.text()), "low_signal_chunks", Map.of());
        var backend = new LinkedHashMap<String, Object>();
        backend.put("meaningfulGate", "source_verified");
        backend.put("skippedLowSignalChunks", 0);
        backend.put("persistedChunkCount", chunks.size());
        return sourceAuthority.withCurrentSource(scope, evidence, current ->
                persistChunks(sid, current.sourceRole(), chunks, BrainStateText.hash12(current.text()), backend, options))
                .orElseGet(() -> IngestReport.disabled(sid, "source_invalidated"));
    }

    public GraphRagChunkingService(BrainStateProperties properties,
                                   DocumentChunkingService documentChunkingService,
                                   NamedEntityExtractor entityExtractor,
                                   Neo4jKgChunkWriter neo4jWriter,
                                   UniversalContextLexicon lexicon,
                                   VectorStoreService vectorStoreService,
                                   BrainStateService brainStateService,
                                   ChatMessageRepository chatMessageRepository) {
        this(properties, documentChunkingService, entityExtractor, neo4jWriter, lexicon, vectorStoreService,
                brainStateService, chatMessageRepository, null);
    }

    @Autowired
    public GraphRagChunkingService(BrainStateProperties properties,
                                   DocumentChunkingService documentChunkingService,
                                   NamedEntityExtractor entityExtractor,
                                   Neo4jKgChunkWriter neo4jWriter,
                                   UniversalContextLexicon lexicon,
                                   VectorStoreService vectorStoreService,
                                   BrainStateService brainStateService,
                                   ChatMessageRepository chatMessageRepository,
                                   @Autowired(required = false) AnchorFrequencyIndex anchorFrequencyIndex) {
        this.properties = properties;
        this.documentChunkingService = documentChunkingService;
        this.entityExtractor = entityExtractor;
        this.neo4jWriter = neo4jWriter;
        this.lexicon = lexicon;
        this.vectorStoreService = vectorStoreService;
        this.brainStateService = brainStateService;
        this.chatMessageRepository = chatMessageRepository;
        this.anchorFrequencyIndex = anchorFrequencyIndex;
    }

    public IngestReport ingestConversationTurn(String sessionId, String userText, String assistantText) {
        List<IngestReport> reports = new ArrayList<>(2);
        if (StringUtils.hasText(userText)) {
            reports.add(ingestText(sessionId, userText, "USER", null));
        }
        if (StringUtils.hasText(assistantText)) {
            reports.add(ingestText(sessionId, assistantText, "ASSISTANT", null));
        }
        return IngestReport.merge(sessionId, "chat-workflow", reports);
    }

    public IngestReport ingestText(String sessionId, String text, String sourceTag, String domainHint) {
        return ingestText(sessionId, text, sourceTag, domainHint, IngestOptions.defaults());
    }

    public IngestReport ingestText(String sessionId,
                                   String text,
                                   String sourceTag,
                                   String domainHint,
                                   IngestOptions options) {
        IngestOptions laneOptions = IngestOptions.safe(options);
        if (laneOptions.requireBrainStateGate()
                && (!properties.isEnabled() || !properties.getIndexing().isEnabled())) {
            return IngestReport.disabled(sessionId, "brain_state_disabled");
        }
        if (!StringUtils.hasText(text)) {
            return new IngestReport(true, safeSession(sessionId), "skipped", 0, 0, 0, 0,
                    BrainStateText.hash12(text), "empty_text", Map.of());
        }
        String domain = inferDomain(text, domainHint);
        List<KgChunk> chunks = chunkAndExtract(safeSession(sessionId), text, domain, sourceTag, laneOptions);
        List<KgChunk> persistedChunks = meaningfulChunks(chunks);
        int skippedLowSignal = Math.max(0, chunks.size() - persistedChunks.size());
        Map<String, Object> backend = new LinkedHashMap<>();
        putLaneOptions(backend, laneOptions);
        backend.put("meaningfulGate", meaningfulGateStatus(skippedLowSignal, persistedChunks.size()));
        backend.put("persistedChunkCount", persistedChunks.size());
        backend.put("skippedLowSignalChunks", skippedLowSignal);

        if (persistedChunks.isEmpty()) {
            backend.put("neo4jStatus", "skipped");
            backend.put("neo4jDisabledReason", "low_signal_chunks");
            backend.put("neo4jFailureClass", "");
            backend.put("neo4jWriteCount", 0);
            backend.put("neo4jPortMappingCount", 0);
            putSkippedPersistence(backend);
            backend.put("disabledReason", "low_signal_chunks");
            return new IngestReport(true, safeSession(sessionId), "skipped", 0, 0, 0, 0,
                    BrainStateText.hash12(text), "low_signal_chunks", backend);
        }

        return persistChunks(safeSession(sessionId), sourceTag, persistedChunks, BrainStateText.hash12(text),
                backend, laneOptions);
    }

    /**
     * Prepared chunks are the KnowledgeDelta/BrainState path. GraphDB manual learning must use ingestText(...)
     * with IngestOptions.graphDbManual(...) so it cannot silently couple to BrainState fallback behavior.
     */
    IngestReport ingestPreparedChunks(String sessionId, String sourceTag, List<KgChunk> chunks, String textHash) {
        if (!properties.isEnabled() || !properties.getIndexing().isEnabled()) {
            return IngestReport.disabled(sessionId, "brain_state_disabled");
        }
        List<KgChunk> preparedChunks = normalizePreparedChunks(safeSession(sessionId), chunks);
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("meaningfulGate", "prepared");
        backend.put("persistedChunkCount", preparedChunks.size());
        backend.put("skippedLowSignalChunks", 0);
        if (preparedChunks.isEmpty()) {
            backend.put("neo4jStatus", "skipped");
            backend.put("neo4jDisabledReason", "empty_chunks");
            backend.put("neo4jFailureClass", "");
            backend.put("neo4jWriteCount", 0);
            backend.put("neo4jPortMappingCount", 0);
            putSkippedPersistence(backend);
            backend.put("disabledReason", "empty_chunks");
            return new IngestReport(true, safeSession(sessionId), "skipped", 0, 0, 0, 0,
                    safeTextHash(textHash), "empty_chunks", backend);
        }
        return persistChunks(safeSession(sessionId), sourceTag, preparedChunks, safeTextHash(textHash), backend,
                IngestOptions.defaults());
    }

    public List<KgChunk> chunkAndExtract(String text, String domain) {
        return chunkAndExtract("__TRANSIENT__", text, domain, "MANUAL", IngestOptions.defaults());
    }

    public IngestReport ingestSession(String sessionId, Long afterMessageId, int limit) {
        Long sid = parseSessionId(sessionId);
        if (sid == null) {
            return IngestReport.disabled(sessionId, "invalid_session_id");
        }
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<ChatMessage> messages = chatMessageRepository.findBySession_IdAndIdGreaterThanOrderByIdAsc(
                sid,
                afterMessageId == null ? 0L : Math.max(0L, afterMessageId),
                PageRequest.of(0, safeLimit));
        List<IngestReport> reports = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message == null || !StringUtils.hasText(message.getContent())) {
                continue;
            }
            reports.add(ingestText(String.valueOf(sid), message.getContent(), normalizeSourceTag(message.getRole()), null));
        }
        return IngestReport.merge(String.valueOf(sid), "session-history", reports);
    }

    private List<KgChunk> chunkAndExtract(String sessionId,
                                          String text,
                                          String domain,
                                          String sourceTag,
                                          IngestOptions options) {
        IngestOptions laneOptions = IngestOptions.safe(options);
        Map<String, Object> baseMeta = new LinkedHashMap<>();
        String parentId = stableId(laneOptions.parentIdPrefix(), sessionId, text, sourceTag, 0);
        baseMeta.put(VectorMetaKeys.META_DOC_ID, parentId);
        baseMeta.put(VectorMetaKeys.META_DOC_TYPE, laneOptions.docType());
        baseMeta.put(VectorMetaKeys.META_SOURCE_TAG, normalizeSourceTag(sourceTag));
        baseMeta.put(VectorMetaKeys.META_ORIGIN, laneOptions.origin());
        baseMeta.put(VectorMetaKeys.META_DOMAIN, domain);
        baseMeta.put("ingest_lane", laneOptions.lane());

        List<String> pieces = new ArrayList<>();
        for (DocumentChunkingService.Chunk chunk : documentChunkingService.split(text, baseMeta)) {
            pieces.addAll(splitToBrainSize(chunk.text()));
        }
        if (pieces.isEmpty()) {
            pieces.add(text);
        }

        List<KgChunk> out = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            String piece = pieces.get(i);
            List<KgChunk.KgEntity> entities = extractEntities(piece, domain, laneOptions);
            List<KgChunk.KgRelation> relations = coOccurrenceRelations(entities);
            out.add(new KgChunk(
                    stableId(laneOptions.chunkIdPrefix(), sessionId, piece, sourceTag, i),
                    safeSession(sessionId),
                    piece,
                    entities,
                    relations,
                    domain,
                    confidenceFor(entities),
                    Instant.now(),
                    normalizeSourceTag(sourceTag),
                    laneOptions.docType(),
                    laneOptions.origin(),
                    laneOptions.lane()));
        }
        return out;
    }

    private IngestReport persistChunks(String sessionId,
                                       String sourceTag,
                                       List<KgChunk> persistedChunks,
                                       String textHash,
                                       Map<String, Object> backend,
                                       IngestOptions options) {
        IngestOptions laneOptions = IngestOptions.safe(options);
        String normalizedSourceTag = normalizeSourceTag(sourceTag);
        if (persistedChunks.stream().anyMatch(c -> c == null || (!c.hasPrivateSource() && !c.isPublicManual()))) {
            return IngestReport.disabled(sessionId, "source_authority_missing");
        }
        if (persistedChunks.stream().anyMatch(KgChunk::hasPrivateSource)) {
            backend.put("anchorMapStatus", "private_scope_excluded");
        } else {
            recordAnchorFrequency(persistedChunks, backend, laneOptions);
        }
        String vectorStatus = "skipped";
        int vectorFailures = 0;
        int vectorAttempts = 0;
        String failureClass = "";

        if (laneOptions.dryRun()) {
            vectorStatus = "dry_run";
        } else if (!laneOptions.vectorEnabled()) {
            vectorStatus = "disabled";
        } else if (vectorStoreService == null) {
            vectorStatus = "unavailable";
        } else {
            for (KgChunk chunk : persistedChunks) {
                if("ATTACHMENT".equals(normalizedSourceTag))attachmentWorkCurrent();
                vectorAttempts++;
                try {
                    enqueueVector(chunk, normalizedSourceTag, laneOptions);
                } catch (Exception ex) {
                    traceSuppressed("vector.enqueue", ex);
                    vectorFailures++;
                    if (failureClass.isBlank()) {
                        failureClass = failureClass(ex);
                    }
                    logVectorFailure(chunk, ex);
                }
            }
            vectorStatus = persistenceStatus(vectorAttempts, vectorFailures, "queued");
        }

        if("ATTACHMENT".equals(normalizedSourceTag))attachmentWorkCurrent();
        String brainStateStatus = "skipped";
        try {
            if (laneOptions.dryRun()) {
                brainStateStatus = "dry_run";
            } else if (!laneOptions.brainStateEnabled()) {
                brainStateStatus = "disabled";
            } else if (brainStateService == null) {
                brainStateStatus = "unavailable";
            } else {
                brainStateService.recordChunks(persistedChunks);
                brainStateStatus = "recorded";
            }
        } catch (Exception ex) {
            traceSuppressed("brainState.record", ex);
            brainStateStatus = "failed";
            if (failureClass.isBlank()) {
                failureClass = failureClass(ex);
            }
            log.warn("[AWX][brain-state] status=failed failureClass={}", failureClass(ex));
        }

        if("ATTACHMENT".equals(normalizedSourceTag))attachmentWorkCurrent();
        Neo4jKgChunkWriter.WriteReport writeReport = null;
        String neo4jStatus = "skipped";
        String neo4jDisabledReason = "";
        String neo4jFailureClass = "";
        int neo4jWriteCount = 0;
        int neo4jPortMappingCount = 0;
        boolean neo4jAttempted = false;
        try {
            if (laneOptions.dryRun()) {
                neo4jStatus = "dry_run";
                neo4jDisabledReason = "dry_run";
            } else if (!laneOptions.neo4jEnabled()) {
                neo4jStatus = "disabled";
                neo4jDisabledReason = "lane_excludes_neo4j";
            } else if (neo4jWriter == null) {
                neo4jStatus = "unavailable";
                neo4jDisabledReason = "writer_unavailable";
            } else {
                neo4jAttempted = true;
                writeReport = neo4jWriter.writeChunks(persistedChunks);
                neo4jStatus = writeReport == null ? "no_report" : safeStatus(writeReport.status());
                neo4jDisabledReason = writeReport == null ? "no_report" : safeString(writeReport.disabledReason());
                neo4jFailureClass = writeReport == null ? "" : safeString(writeReport.failureClass());
                neo4jWriteCount = writeReport == null ? 0 : Math.max(0, writeReport.chunkCount());
                neo4jPortMappingCount = writeReport == null ? 0 : Math.max(0, writeReport.portMappingCount());
                if (failureClass.isBlank() && !neo4jFailureClass.isBlank()) {
                    failureClass = neo4jFailureClass;
                }
            }
        } catch (Exception ex) {
            traceSuppressed("neo4j.write", ex);
            neo4jStatus = "failed";
            neo4jDisabledReason = "write_failed";
            neo4jFailureClass = failureClass(ex);
            if (failureClass.isBlank()) {
                failureClass = neo4jFailureClass;
            }
            log.warn("[AWX][rag-ingest][neo4j] status=failed lane={} failureClass={}",
                    laneOptions.lane(), neo4jFailureClass);
        }

        int entityCount = persistedChunks.stream().mapToInt(c -> c.entities().size()).sum();
        int relationCount = persistedChunks.stream().mapToInt(c -> c.relations().size()).sum();
        backend.put("vectorStatus", vectorStatus);
        backend.put("vectorAttemptCount", vectorAttempts);
        backend.put("vectorQueuedCount", Math.max(0, vectorAttempts - vectorFailures));
        backend.put("vectorFailureCount", vectorFailures);
        backend.put("brainStateStatus", brainStateStatus);
        backend.put("brainStateDisabledReason", laneOptions.brainStateEnabled() ? "" : "lane_excludes_brain_state");
        backend.put("neo4jStatus", neo4jStatus);
        backend.put("neo4jDisabledReason", neo4jDisabledReason);
        backend.put("neo4jFailureClass", neo4jFailureClass);
        backend.put("neo4jWriteCount", neo4jWriteCount);
        backend.put("neo4jPortMappingCount", neo4jPortMappingCount);
        backend.put("failureClass", failureClass);

        List<String> requiredTargets = requiredPersistenceTargets(laneOptions);
        String requiredMissingReason = requiredPersistenceMissingReason(
                laneOptions,
                vectorStatus,
                brainStateStatus,
                neo4jStatus);
        boolean requiredSatisfied = requiredMissingReason.isBlank();
        backend.put("requiredPersistenceTargets", requiredTargets);
        backend.put("requiredPersistenceSatisfied", requiredSatisfied);
        backend.put("requiredPersistenceMissingReason", requiredMissingReason);

        boolean anySuccess = isVectorSuccess(vectorStatus)
                || "recorded".equals(brainStateStatus)
                || "written".equals(neo4jStatus);
        putPersistenceProof(
                backend,
                laneOptions,
                vectorAttempts,
                vectorStatus,
                neo4jAttempted,
                neo4jStatus,
                requiredSatisfied,
                anySuccess);
        String status = laneOptions.dryRun()
                ? "dry_run"
                : !requiredSatisfied && anySuccess ? "partial_indexed"
                : anySuccess ? "indexed" : "failed";
        String disabledReason = laneOptions.dryRun()
                ? ""
                : !requiredSatisfied ? requiredMissingReason
                : anySuccess ? "" : "persistence_failed";
        return new IngestReport(true, safeSession(sessionId), status, persistedChunks.size(), entityCount,
                relationCount, neo4jWriteCount, textHash, disabledReason, backend);
    }

    private void enqueueVector(KgChunk chunk, String sourceTag, IngestOptions options) {
        if (chunk == null || vectorStoreService == null) {
            return;
        }
        IngestOptions laneOptions = IngestOptions.safe(options);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(VectorMetaKeys.META_DOC_TYPE, laneOptions.docType());
        meta.put(VectorMetaKeys.META_SOURCE_TAG, normalizeSourceTag(sourceTag));
        meta.put(VectorMetaKeys.META_ORIGIN, laneOptions.origin());
        meta.put(VectorMetaKeys.META_DOMAIN, chunk.domain());
        meta.put(VectorMetaKeys.META_VERIFIED, "false");
        meta.put(VectorMetaKeys.META_VERIFICATION_NEEDED, "true");
        meta.put(VectorMetaKeys.META_CHUNK_ID, chunk.chunkId());
        meta.put(VectorMetaKeys.META_SID_LOGICAL, chunk.sessionId());
        meta.put("ingest_lane", laneOptions.lane());
        if (chunk.hasPrivateSource()) {
            meta.put("general_graph_private", "true");
            meta.put("general_graph_owner_namespace", chunk.privateScope().ownerNamespace());
            meta.put("general_graph_session_id", chunk.privateScope().sessionId());
            meta.put("general_graph_consent_epoch", chunk.privateScope().consentEpoch());
            meta.put("general_graph_source_id", chunk.sourceId());
            meta.put("general_graph_source_revision", chunk.sourceRevision());
        }
        meta.put("brain_text_hash", BrainStateText.hash12(chunk.sourceText()));
        meta.put("brain_entity_count", chunk.entities().size());
        meta.put("brain_relation_count", chunk.relations().size());
        meta.put("brain_port_mapping_count", portMappingCount(chunk));
        meta.put("brain_connector_hashes", connectorHashes(chunk));
        String vectorText = relationThumbnailVectorText(chunk, meta);
        String vectorSessionId = vectorSessionId(chunk, laneOptions);
        if (isGraphDbManualLane(laneOptions)) {
            meta.put(VectorMetaKeys.META_SID_LOGICAL, vectorSessionId);
            meta.put(VectorMetaKeys.META_ORIGINAL_SID, vectorSessionId);
            meta.put("graphdb_manual_session_hash", BrainStateText.hash12(chunk.sessionId()));
            meta.put("raw_session_id_included", "false");
        }
        vectorStoreService.enqueue(chunk.chunkId(), vectorSessionId, vectorText, meta);
    }

    private static String relationThumbnailVectorText(KgChunk chunk, Map<String, Object> meta) {
        String sourceText = chunk == null || chunk.sourceText() == null ? "" : chunk.sourceText();
        if (!isRelationThumbnailWarmupChunk(chunk)) {
            return sourceText;
        }
        String breadcrumb = relationBreadcrumb(chunk);
        if (breadcrumb.isBlank()) {
            return sourceText;
        }
        String summary = relationSummary(chunk, breadcrumb);
        if (meta != null) {
            String thumbnailHash = firstConnectorHash(chunk);
            if (thumbnailHash.isBlank()) {
                thumbnailHash = BrainStateText.hash12(sourceText + "|" + breadcrumb);
            }
            meta.put("kg_relation_thumbnail_mode", "relation_thumbnail_v1");
            meta.put("kg_relation_thumbnail_source", thumbnailSource(chunk));
            meta.put("kg_relation_thumbnail_hash", thumbnailHash);
            meta.put("kg_relation_anchor_hash12", BrainStateText.hash12(breadcrumb));
            meta.put("kg_relation_breadcrumb", breadcrumb);
            meta.put("kg_relation_summary", summary);
        }
        if (summary.isBlank()) {
            return "relationBreadcrumbs: " + breadcrumb;
        }
        return "relationBreadcrumbs: " + breadcrumb + "\nrelationSummary: " + summary;
    }

    private static boolean isRelationThumbnailWarmupChunk(KgChunk chunk) {
        if (chunk == null || chunk.relations().isEmpty()) {
            return false;
        }
        return "UAW_THUMBNAIL".equalsIgnoreCase(chunk.sourceTag())
                || "uaw_thumbnail_warmup".equalsIgnoreCase(chunk.ingestLane())
                || "uaw_thumbnail".equalsIgnoreCase(chunk.origin());
    }

    private static String relationBreadcrumb(KgChunk chunk) {
        if (chunk == null || chunk.relations().isEmpty()) {
            return "";
        }
        List<String> breadcrumbs = new ArrayList<>();
        for (KgChunk.KgRelation relation : chunk.relations()) {
            if (relation == null) {
                continue;
            }
            String source = compactLine(relation.source());
            String target = compactLine(relation.target());
            if (source.isBlank() || target.isBlank()) {
                continue;
            }
            breadcrumbs.add(source + " --" + compactLine(relation.kind()) + "--> " + target);
            if (breadcrumbs.size() >= 6) {
                break;
            }
        }
        return String.join(" | ", breadcrumbs);
    }

    private static String firstConnectorHash(KgChunk chunk) {
        if (chunk == null || chunk.relations().isEmpty()) {
            return "";
        }
        return chunk.relations().stream()
                .filter(relation -> relation != null && StringUtils.hasText(relation.connectorHash12()))
                .map(KgChunk.KgRelation::connectorHash12)
                .findFirst()
                .orElse("");
    }

    private static String thumbnailSource(KgChunk chunk) {
        String lane = chunk == null ? "" : compactLine(chunk.ingestLane());
        if (!lane.isBlank()) {
            return lane;
        }
        return chunk == null ? "" : compactLine(chunk.sourceTag()).toLowerCase(Locale.ROOT);
    }

    private static String relationSummary(KgChunk chunk, String breadcrumb) {
        int entityCount = chunk == null ? 0 : chunk.entities().size();
        int relationCount = chunk == null ? 0 : chunk.relations().size();
        return "relation thumbnail anchors=" + entityCount
                + " relations=" + relationCount
                + " breadcrumb=" + breadcrumb;
    }

    private static String compactLine(String value) {
        if (value == null) {
            return "";
        }
        String out = value.trim()
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s{2,}", " ");
        return out.length() <= 360 ? out : out.substring(0, 360).trim();
    }

    private static void putLaneOptions(Map<String, Object> backend, IngestOptions options) {
        IngestOptions laneOptions = IngestOptions.safe(options);
        backend.put("ingestLane", laneOptions.lane());
        backend.put("docType", laneOptions.docType());
        backend.put("origin", laneOptions.origin());
        backend.put("dryRun", laneOptions.dryRun());
        backend.put("vectorEnabled", laneOptions.vectorEnabled());
        backend.put("brainStateEnabled", laneOptions.brainStateEnabled());
        backend.put("neo4jEnabled", laneOptions.neo4jEnabled());
    }

    private void recordAnchorFrequency(List<KgChunk> persistedChunks,
                                       Map<String, Object> backend,
                                       IngestOptions options) {
        if (isGraphDbManualLane(IngestOptions.safe(options))) {
            backend.put("anchorMapStatus", "disabled");
            backend.put("anchorMapDisabledReason", "graphdb_manual_lane_excludes_query_time_anchor_map");
            backend.put("anchorMapEntityCount", 0);
            backend.put("anchorMapRelationCount", 0);
            backend.put("anchorMapTotalEntityCount", 0);
            backend.put("anchorMapTotalRelationCount", 0);
            return;
        }
        if (anchorFrequencyIndex == null) {
            backend.put("anchorMapStatus", "unavailable");
            backend.put("anchorMapDisabledReason", "index_unavailable");
            backend.put("anchorMapEntityCount", 0);
            backend.put("anchorMapRelationCount", 0);
            return;
        }
        if (!anchorFrequencyIndex.isEnabled()) {
            backend.put("anchorMapStatus", "disabled");
            backend.put("anchorMapDisabledReason", "route_disabled");
            backend.put("anchorMapEntityCount", 0);
            backend.put("anchorMapRelationCount", 0);
            backend.put("anchorMapTotalEntityCount", 0);
            backend.put("anchorMapTotalRelationCount", 0);
            return;
        }
        try {
            AnchorFrequencyIndex.RecordReport report = anchorFrequencyIndex.record(persistedChunks);
            backend.put("anchorMapStatus", "recorded");
            backend.put("anchorMapEntityCount", report.entityRecordCount());
            backend.put("anchorMapRelationCount", report.relationRecordCount());
            backend.put("anchorMapTotalEntityCount", report.totalEntityCount());
            backend.put("anchorMapTotalRelationCount", report.totalRelationCount());
            log.debug("[AWX][trace] anchorMapRecord status=recorded chunkCount={} entityCount={} relationCount={}",
                    report.chunkCount(),
                    report.entityRecordCount(),
                    report.relationRecordCount());
        } catch (Exception ex) {
            backend.put("anchorMapStatus", "failed");
            backend.put("anchorMapFailureClass", failureClass(ex));
            log.warn("[AWX][trace] anchorMapRecord status=failed failureClass={}", failureClass(ex));
        }
    }

    private static List<KgChunk> normalizePreparedChunks(String sessionId, List<KgChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<KgChunk> out = new ArrayList<>(chunks.size());
        int index = 0;
        String sid = safeSession(sessionId);
        for (KgChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            String sourceText = chunk.sourceText() == null ? "" : chunk.sourceText();
            String chunkId = StringUtils.hasText(chunk.chunkId())
                    ? chunk.chunkId()
                    : stableId("brain-chunk", sid, sourceText, "PREPARED", index);
            out.add(new KgChunk(
                    chunkId,
                    sid,
                    sourceText,
                    chunk.entities(),
                    chunk.relations(),
                    BrainStateText.normalizeDomain(chunk.domain()),
                    clamp01(chunk.confidence()),
                    chunk.createdAt(),
                    chunk.sourceTag(),
                    chunk.docType(),
                    chunk.origin(),
                    chunk.ingestLane()));
            index++;
        }
        return out;
    }

    private static String persistenceStatus(int attempts, int failures, String successStatus) {
        if (attempts <= 0) {
            return "skipped";
        }
        if (failures <= 0) {
            return successStatus;
        }
        if (failures >= attempts) {
            return "failed";
        }
        return "partial_failure";
    }

    private static boolean isVectorSuccess(String status) {
        return "queued".equals(status) || "partial_failure".equals(status);
    }

    private static List<String> requiredPersistenceTargets(IngestOptions options) {
        IngestOptions laneOptions = IngestOptions.safe(options);
        if (!isGraphDbManualLane(laneOptions) || laneOptions.dryRun()) {
            return List.of();
        }
        List<String> targets = new ArrayList<>(3);
        if (laneOptions.vectorEnabled()) {
            targets.add("vector");
        }
        if (laneOptions.neo4jEnabled()) {
            targets.add("neo4j");
        }
        if (laneOptions.brainStateEnabled()) {
            targets.add("brain_state");
        }
        return List.copyOf(targets);
    }

    private static void putPersistenceProof(Map<String, Object> backend,
                                            IngestOptions options,
                                            int vectorAttempts,
                                            String vectorStatus,
                                            boolean neo4jAttempted,
                                            String neo4jStatus,
                                            boolean requiredSatisfied,
                                            boolean anySuccess) {
        IngestOptions laneOptions = IngestOptions.safe(options);
        if (!isGraphDbManualLane(laneOptions) || laneOptions.dryRun()) {
            backend.put("persistenceTargetOrder", List.of());
            backend.put("persistenceAttemptedTargets", List.of());
            backend.put("persistenceSucceededTargets", List.of());
            backend.put("persistenceIncompleteTargets", List.of());
            backend.put("persistenceFailureIsolationApplied", false);
            return;
        }

        List<String> targetOrder = requiredPersistenceTargets(laneOptions);
        List<String> attempted = new ArrayList<>(2);
        List<String> succeeded = new ArrayList<>(2);
        List<String> incomplete = new ArrayList<>(2);
        if (laneOptions.vectorEnabled()) {
            if (vectorAttempts > 0) {
                attempted.add("vector");
            }
            if ("queued".equals(vectorStatus)) {
                succeeded.add("vector");
            } else {
                incomplete.add("vector");
            }
        }
        if (laneOptions.neo4jEnabled()) {
            if (neo4jAttempted) {
                attempted.add("neo4j");
            }
            if ("written".equals(neo4jStatus)) {
                succeeded.add("neo4j");
            } else {
                incomplete.add("neo4j");
            }
        }
        backend.put("persistenceTargetOrder", targetOrder);
        backend.put("persistenceAttemptedTargets", List.copyOf(attempted));
        backend.put("persistenceSucceededTargets", List.copyOf(succeeded));
        backend.put("persistenceIncompleteTargets", List.copyOf(incomplete));
        backend.put("persistenceFailureIsolationApplied", !requiredSatisfied && anySuccess);
    }

    private static String requiredPersistenceMissingReason(IngestOptions options,
                                                           String vectorStatus,
                                                           String brainStateStatus,
                                                           String neo4jStatus) {
        IngestOptions laneOptions = IngestOptions.safe(options);
        if (!isGraphDbManualLane(laneOptions) || laneOptions.dryRun()) {
            return "";
        }
        if (laneOptions.vectorEnabled() && !"queued".equals(vectorStatus)) {
            return "vector_persistence_incomplete";
        }
        if (laneOptions.neo4jEnabled() && !"written".equals(neo4jStatus)) {
            return "neo4j_persistence_incomplete";
        }
        if (laneOptions.brainStateEnabled() && !"recorded".equals(brainStateStatus)) {
            return "brain_state_mirror_incomplete";
        }
        return "";
    }

    private static boolean isGraphDbManualLane(IngestOptions options) {
        return options != null && "graphdb_manual_learning".equals(options.lane());
    }

    private static String vectorSessionId(KgChunk chunk, IngestOptions options) {
        String sessionId = chunk == null ? "" : chunk.sessionId();
        if (isGraphDbManualLane(options)) {
            return "graphdb-manual:" + BrainStateText.hash12(sessionId);
        }
        return sessionId;
    }

    private static void putSkippedPersistence(Map<String, Object> backend) {
        backend.put("vectorStatus", "skipped");
        backend.put("vectorAttemptCount", 0);
        backend.put("vectorQueuedCount", 0);
        backend.put("vectorFailureCount", 0);
        backend.put("brainStateStatus", "skipped");
        backend.put("failureClass", "");
    }

    private static String safeTextHash(String textHash) {
        if (!StringUtils.hasText(textHash)) {
            return "";
        }
        String value = textHash.trim().replaceAll("[^A-Za-z0-9_\\-]", "");
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private static String safeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "unknown";
    }

    private static String safeString(String value) {
        return value == null ? "" : value.trim().replaceAll("[\\r\\n\\t]+", " ");
    }

    private static String failureClass(Exception ex) {
        if (ex == null) {
            return "unknown";
        }
        String className = ex.getClass().getSimpleName();
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        if (ex instanceof CancellationException || ex instanceof InterruptedException
                || className.toLowerCase(Locale.ROOT).contains("cancel")
                || className.toLowerCase(Locale.ROOT).contains("interrupt")
                || message.contains("cancelled")
                || message.contains("canceled")
                || message.contains("interrupted")) {
            return "cancelled";
        }
        return StringUtils.hasText(className) ? className : "unknown";
    }

    private static void logVectorFailure(KgChunk chunk, Exception ex) {
        log.warn("[AWX][rag-ingest][vector] status=failed lane={} chunkHash={} failureClass={}",
                chunk == null ? "unknown" : chunk.ingestLane(),
                BrainStateText.hash12(chunk == null ? "" : chunk.chunkId()),
                failureClass(ex));
    }

    private List<KgChunk> meaningfulChunks(List<KgChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        if (!properties.getIndexing().isMeaningfulGateEnabled()) {
            return chunks;
        }
        int minEntities = Math.max(0, properties.getIndexing().getMinEntitiesPerChunk());
        double minConfidence = properties.getIndexing().getMinChunkConfidence();
        return chunks.stream()
                .filter(chunk -> isMeaningful(chunk, minEntities, minConfidence))
                .toList();
    }

    private static boolean isMeaningful(KgChunk chunk, int minEntities, double minConfidence) {
        if (chunk == null) {
            return false;
        }
        int entityCount = chunk.entities() == null ? 0 : chunk.entities().size();
        return entityCount >= minEntities && clamp01(chunk.confidence()) >= minConfidence;
    }

    private String meaningfulGateStatus(int skippedLowSignal, int persistedCount) {
        if (!properties.getIndexing().isMeaningfulGateEnabled()) {
            return "disabled";
        }
        if (persistedCount <= 0 && skippedLowSignal > 0) {
            return "skipped_low_signal";
        }
        if (skippedLowSignal > 0) {
            return "filtered_low_signal";
        }
        return "passed";
    }

    private List<String> splitToBrainSize(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        int max = Math.max(128, properties.getChunking().getMaxChunkSize());
        int overlap = Math.max(0, Math.min(properties.getChunking().getOverlap(), max - 1));
        if (text.length() <= max) {
            return List.of(text);
        }
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + max);
            out.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return out;
    }

    private List<KgChunk.KgEntity> extractEntities(String text, String domain, IngestOptions options) {
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<String> extracted = entityExtractor == null ? List.of() : entityExtractor.extract(text);
            for (String value : extracted) {
                String entity = sanitizeEntity(value);
                if (!entity.isBlank()) {
                    seen.add(entity);
                }
            if (seen.size() >= properties.getChunking().getMaxEntitiesPerChunk()) {
                break;
            }
        }
        } catch (Exception ex) {
            traceSuppressed("entityExtractor.extract", ex);
            // extractor is already fail-soft; keep this path no-throw for chat capture
        }
        if (isGraphDbManualLane(options) && seen.size() < 2) {
            addManualFallbackEntities(seen, text);
        }
        return seen.stream()
                .map(name -> new KgChunk.KgEntity(name, "ENTITY", domain, 0.75d))
                .toList();
    }

    private void addManualFallbackEntities(Set<String> seen, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        Matcher matcher = MANUAL_ENTITY_FALLBACK.matcher(text);
        while (matcher.find() && seen.size() < properties.getChunking().getMaxEntitiesPerChunk()) {
            String entity = sanitizeEntity(matcher.group());
            if (!entity.isBlank()) {
                seen.add(entity);
            }
        }
    }

    private static List<KgChunk.KgRelation> coOccurrenceRelations(List<KgChunk.KgEntity> entities) {
        if (entities == null || entities.size() < 2) {
            return List.of();
        }
        List<KgChunk.KgRelation> out = new ArrayList<>();
        int max = Math.min(entities.size(), 12);
        for (int i = 0; i < max; i++) {
            for (int j = i + 1; j < max; j++) {
                out.add(GraphRagPortMappingConnector.semanticRelation(
                        entities.get(i).name(),
                        entities.get(j).name(),
                        "CO_MENTIONED_WITH",
                        0.55d,
                        "co-occurrence"));
            }
        }
        return out;
    }

    private static int portMappingCount(KgChunk chunk) {
        if (chunk == null || chunk.relations() == null || chunk.relations().isEmpty()) {
            return 0;
        }
        return (int) chunk.relations().stream()
                .filter(relation -> relation != null && StringUtils.hasText(relation.connectorHash12()))
                .count();
    }

    private static List<String> connectorHashes(KgChunk chunk) {
        if (chunk == null || chunk.relations() == null || chunk.relations().isEmpty()) {
            return List.of();
        }
        return chunk.relations().stream()
                .filter(relation -> relation != null && StringUtils.hasText(relation.connectorHash12()))
                .map(KgChunk.KgRelation::connectorHash12)
                .distinct()
                .limit(16)
                .toList();
    }

    private String inferDomain(String text, String domainHint) {
        if (StringUtils.hasText(domainHint)) {
            return BrainStateText.normalizeDomain(domainHint);
        }
        try {
            String attr = lexicon == null ? null : lexicon.inferAttribute(text);
            if (StringUtils.hasText(attr)) {
                return BrainStateText.normalizeDomain(attr);
            }
        } catch (Exception ex) {
            traceSuppressed("inferDomain.lexicon", ex);
            // fall through
        }
        return "GENERAL";
    }

    private static double confidenceFor(List<KgChunk.KgEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0.25d;
        }
        return 0.65d;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String normalizeSourceTag(String sourceTag) {
        String raw = BrainStateText.nonBlank(sourceTag, "MANUAL").trim().toUpperCase(Locale.ROOT);
        return switch (raw) {
            case "USER", "ASSISTANT", "SYSTEM", "MANUAL", "GRAPHDB_MANUAL", "UAW_THUMBNAIL", "KNOWLEDGE_DELTA" -> raw;
            default -> "MANUAL";
        };
    }

    private static String sanitizeEntity(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim().replaceAll("[\\r\\n\\t]+", " ");
        v = v.replaceAll("\\s{2,}", " ");
        if (v.length() < 2) {
            return "";
        }
        if (v.length() > 80) {
            return v.substring(0, 80);
        }
        return v;
    }

    private static String safeSession(String sessionId) {
        return BrainStateText.nonBlank(sessionId, "__TRANSIENT__");
    }

    private static String safeReportSession(String sessionId) {
        String safe = safeSession(sessionId);
        return "__TRANSIENT__".equals(safe) ? safe : BrainStateText.hash12(safe);
    }

    private static Long parseSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String raw = sessionId.trim();
        if (raw.regionMatches(true, 0, "chat-", 0, 5)) {
            raw = raw.substring(5);
        }
        if (!raw.matches("\\d+")) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException ex) {
            traceSuppressed("parseSessionId", ex);
            return null;
        }
    }

    private static String stableId(String prefix, String sessionId, String text, String sourceTag, int index) {
        String material = safeSession(sessionId) + "|" + normalizeSourceTag(sourceTag) + "|" + index + "|"
                + BrainStateText.hash12(text);
        return prefix + ":" + DigestUtils.sha1Hex(material);
    }

    private static void traceSuppressed(String stage, Exception ex) {
        String safeStage = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(failureClass(ex), "unknown");
        TraceStore.put("retrieval.kg.graphRagChunking.suppressed." + safeStage, true);
        TraceStore.put("retrieval.kg.graphRagChunking." + safeStage + ".errorType", errorType);
        if (log.isDebugEnabled()) {
            log.debug("[GraphRagChunkingService] fail-soft stage={} err={}", safeStage, failureClass(ex));
        }
    }

    public record IngestReport(
            boolean enabled,
            String sessionId,
            String status,
            int chunkCount,
            int entityCount,
            int relationCount,
            int neo4jWriteCount,
            String textHash,
            String disabledReason,
            Map<String, Object> backend) {

        public IngestReport {
            sessionId = safeReportSession(sessionId);
            backend = backend == null ? Map.of() : Map.copyOf(backend);
        }

        public static IngestReport disabled(String sessionId, String reason) {
            return new IngestReport(false, safeSession(sessionId), "disabled", 0, 0, 0, 0, "",
                    reason, Map.of());
        }

        /** Queue acceptance and partial backend writes are not completed indexing. */
        public String captureOutcome() {
            if (!enabled) return "disabled";
            Object aggregate = backend.get("captureOutcome");
            if (aggregate instanceof String outcome && List.of(
                    "succeeded", "queued", "partial_indexed", "skipped", "disabled", "failed").contains(outcome)) {
                return outcome;
            }
            if ("skipped".equals(status) || "dry_run".equals(status)) return "skipped";
            if ("partial_indexed".equals(status)) return "partial_indexed";
            if (!"indexed".equals(status)) return "failed";
            String vector = String.valueOf(backend.getOrDefault("vectorStatus", "skipped"));
            String brain = String.valueOf(backend.getOrDefault("brainStateStatus", "skipped"));
            String neo4j = String.valueOf(backend.getOrDefault("neo4jStatus", "skipped"));
            boolean accepted = "queued".equals(vector) || "partial_failure".equals(vector)
                    || "recorded".equals(brain) || "written".equals(neo4j);
            if (!accepted) return "failed";
            if (List.of(vector, brain, neo4j).stream().anyMatch(value -> List.of(
                    "failed", "partial_failure", "unavailable", "disabled", "no_report").contains(value))) {
                return "partial_indexed";
            }
            return "queued".equals(vector) ? "queued" : "succeeded";
        }

        static IngestReport merge(String sessionId, String status, List<IngestReport> reports) {
            if (reports == null || reports.isEmpty()) {
                return new IngestReport(true, safeSession(sessionId), status, 0, 0, 0, 0, "", "",
                        Map.of("captureOutcome", "skipped"));
            }
            int chunks = reports.stream().mapToInt(IngestReport::chunkCount).sum();
            int entities = reports.stream().mapToInt(IngestReport::entityCount).sum();
            int relations = reports.stream().mapToInt(IngestReport::relationCount).sum();
            int neo4j = reports.stream().mapToInt(IngestReport::neo4jWriteCount).sum();
            Map<String, Object> backend = new LinkedHashMap<>();
            backend.put("reports", reports.size());
            List<String> outcomes = reports.stream().map(IngestReport::captureOutcome).toList();
            boolean accepted = outcomes.stream().anyMatch(value -> List.of("succeeded", "queued", "partial_indexed").contains(value));
            boolean incomplete = outcomes.stream().anyMatch(value -> List.of("failed", "disabled", "partial_indexed").contains(value));
            String captureOutcome = accepted && incomplete ? "partial_indexed"
                    : outcomes.contains("failed") ? "failed"
                    : outcomes.contains("queued") ? "queued"
                    : outcomes.contains("succeeded") ? "succeeded" : "skipped";
            backend.put("captureOutcome", captureOutcome);
            int disabledReports = (int) reports.stream().filter(report -> !report.enabled()).count();
            if (disabledReports == reports.size()) {
                backend.put("disabledReports", disabledReports);
                return new IngestReport(false, safeSession(sessionId), "disabled", chunks, entities, relations, neo4j,
                        "", commonDisabledReason(reports), backend);
            }
            return new IngestReport(true, safeSession(sessionId), status, chunks, entities, relations, neo4j,
                    "", "", backend);
        }

        private static String commonDisabledReason(List<IngestReport> reports) {
            String common = null;
            for (IngestReport report : reports) {
                String reason = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(
                        report.disabledReason(), "all_children_disabled");
                if (common != null && !common.equals(reason)) {
                    return "all_children_disabled";
                }
                common = reason;
            }
            return StringUtils.hasText(common) ? common : "all_children_disabled";
        }
    }

    public record IngestOptions(
            boolean vectorEnabled,
            boolean brainStateEnabled,
            boolean neo4jEnabled,
            boolean dryRun,
            boolean requireBrainStateGate,
            String docType,
            String origin,
            String lane,
            String parentIdPrefix,
            String chunkIdPrefix) {

        public IngestOptions {
            docType = safeOption(docType, "BRAIN_STATE");
            origin = safeOption(origin, "CONVERSATION");
            lane = safeOption(lane, "brain_state");
            parentIdPrefix = safeOption(parentIdPrefix, "brain-parent");
            chunkIdPrefix = safeOption(chunkIdPrefix, "brain-chunk");
        }

        public static IngestOptions defaults() {
            return new IngestOptions(true, true, true, false, true,
                    "BRAIN_STATE", "CONVERSATION", "brain_state", "brain-parent", "brain-chunk");
        }

        public static IngestOptions graphDbManual(boolean dryRun,
                                                  boolean vectorEnabled,
                                                  boolean neo4jEnabled,
                                                  boolean brainStateMirrorEnabled) {
            return new IngestOptions(vectorEnabled, false, neo4jEnabled, dryRun, false,
                    "GRAPHDB_MANUAL_LEARNING", "MANUAL_GRAPHDB", "graphdb_manual_learning",
                    "graphdb-parent", "graphdb-chunk");
        }

        static IngestOptions safe(IngestOptions options) {
            return options == null ? defaults() : options;
        }

        private static String safeOption(String value, String fallback) {
            String raw = value == null ? "" : value.trim();
            return raw.isBlank() ? fallback : raw;
        }
    }
}
