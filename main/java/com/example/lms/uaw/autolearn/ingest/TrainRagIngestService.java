package com.example.lms.uaw.autolearn.ingest;

import com.example.lms.orchestration.control.RagControlLearningGate;
import com.example.lms.orchestration.control.RagControlRuntimeAdapter;
import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.vector.VectorSidService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.uaw.autolearn.PreemptionToken;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.example.lms.uaw.autolearn.UawAutolearnQualityTracker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Incremental ingest of train_rag.jsonl with a persistent checkpoint.
 */

// MERGE_HOOK:PROJ_AGENT::UAW_TRAIN_INGEST_IDS_SID_V2
@Service
public class TrainRagIngestService {

    private static final Logger log = LoggerFactory.getLogger(TrainRagIngestService.class);
    private static final String PROJECT_ID = "dynamic-rag-orchestration-platform";
    private static final String PROJECT_NAME = "Dynamic RAG Orchestration Platform";
    private static final String MEMORY_NAMESPACE = "dynamic-rag-orchestration-platform/source-purity";
    private static final String DEFAULT_AGENT_HANDOFF_MANIFEST = "data/agent-handoff/codex/manifest.json";
    private static final String PROJECTION_NONE = "NONE";
    private static final String PROJECTION_METADATA_ONLY = "METADATA_ONLY";
    private static final String PROJECTION_RAW_SHADOW_QUARANTINE = "RAW_SHADOW_QUARANTINE";
    private static final Set<String> SAFE_METADATA_TOKENS = Set.of(
            "uaw_autolearn",
            "unknown", "factual", "factual_entity", "causal", "comparison", "recency", "long_tail",
            "BQ", "ER", "RC",
            "provider_disabled", "evidence_conflict", "evidence_starvation", "retrieval_degraded",
            "model_required", "final_gate_failed", "context_contamination", "threshold", "requery",
            "writer", "validation", "other",
            "cause_effect_support", "alternative_cause_checked", "both_sides_covered",
            "comparison_axis_explicit", "freshness_or_source_date_checked",
            "official_or_primary_source_preferred", "bq_er_rc_lane_coverage",
            "long_tail_context_supported", "requery_confirmation_required", "validation_metadata_required",
            "after_filter_starvation", "sample_score_below_threshold", "contamination_risk",
            "legacy_context_risk", "contradiction_risk", "unconfirmed_high_risk_requery",
            "unconfirmed_history_contamination_requery", "missing_validation_metadata",
            "validation_rejected", "sample_score_threshold", "context_contamination_threshold",
            "requery_unconfirmed", "citation_or_entity_match", "answer_supported_by_evidence",
            "needle_roi_rejected",
            "spike", "drift", "signal_shift",
            "SHADOW_REVIEW", "QUARANTINE", "accepted", "rejected", "not_candidate",
            "signal_below_threshold", "needle_roi_below_threshold", "needle_roi_not_promoted",
            "needle_roi_requery_unconfirmed", "needle_roi_runtime_failure",
            "disabled_by_config", "missing_key", "missing_credentials", "placeholder_key",
            "privacy_block", "provider_unavailable", "provider_timeout", "rate_limited");

    private final VectorStoreService vectorStoreService;
    private final VectorSidService vectorSidService;
    private final UawAutolearnProperties props;
    private final ObjectMapper om = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UawAutolearnQualityTracker qualityTracker;

    private RagControlLearningGate ragControlLearningGate;

    private record Indexed(String id, String sid, String text, Map<String, Object> meta) {
    }

    private static final class IngestCounters {
        int readLines;
        int parsedLines;
        int skippedBlankLines;
        int skippedInvalidLines;
        int queuedDocs;
        int failedBatches;
        String lastReason = "";
    }

    public TrainRagIngestService(VectorStoreService vectorStoreService,
            VectorSidService vectorSidService,
            UawAutolearnProperties props) {
        this.vectorStoreService = vectorStoreService;
        this.vectorSidService = vectorSidService;
        this.props = props;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRagControlLearningGate(RagControlLearningGate ragControlLearningGate) {
        this.ragControlLearningGate = ragControlLearningGate;
    }

    private static final class IngestState {
        public long offset;
        // Legacy read-only field. New state writes fileHash/fileLength instead of raw local paths.
        public String file;
        public String fileHash;
        public int fileLength;
        public String updatedAt;

        public static IngestState empty(Path file) {
            IngestState s = new IngestState();
            s.offset = 0L;
            setFileFingerprint(s, file);
            s.updatedAt = Instant.now().toString();
            return s;
        }
    }

    public int ingestNewSamples(Path jsonlPath, String datasetName, PreemptionToken token) {
        IngestCounters counters = new IngestCounters();
        if (jsonlPath == null || !Files.exists(jsonlPath)) {
            String path = jsonlPath == null ? "" : String.valueOf(jsonlPath);
            log.debug("[UAW] train jsonl not found pathHash={} pathLength={}",
                    SafeRedactor.hashValue(path), path.length());
            counters.lastReason = "file_missing";
            traceIngestCounters(counters, 0);
            return 0;
        }
        if (token != null && token.shouldAbort()) {
            counters.lastReason = "preempted_before_start";
            traceIngestCounters(counters, 0);
            return 0;
        }

        Path statePath = Path.of(props.getRetrain().getIngestStatePath());
        IngestState state = loadState(statePath, jsonlPath);

        int maxLines = Math.max(1, props.getRetrain().getMaxIngestLinesPerRun());
        int batchSize = Math.min(50, Math.max(5, maxLines / 4));

        long lastProcessedOffset = state.offset;
        int acceptedDocs = 0;

        try (RandomAccessFile raf = new RandomAccessFile(jsonlPath.toFile(), "r")) {
            long len = raf.length();
            if (state.offset > len) {
                // file truncated/rotated
                state.offset = 0L;
                lastProcessedOffset = 0L;
            }
            raf.seek(state.offset);

            List<Indexed> batch = new ArrayList<>();

            int processedLines = 0;
            while (processedLines < maxLines) {
                if (token != null && token.shouldAbort()) {
                    break;
                }

                String line = readUtf8Line(raf);
                if (line == null) {
                    break;
                }
                lastProcessedOffset = raf.getFilePointer();
                processedLines++;
                counters.readLines++;

                if (line.isBlank()) {
                    counters.skippedBlankLines++;
                    continue;
                }

                Map<String, Object> m = parseJson(line);
                if (m.isEmpty()) {
                    counters.skippedInvalidLines++;
                    continue;
                }
                counters.parsedLines++;
                String answer = Objects.toString(m.getOrDefault("answer", ""), "");
                if (answer.isBlank()) {
                    counters.skippedInvalidLines++;
                    continue;
                }

                String question = Objects.toString(m.getOrDefault("question", ""), "");
                String modelUsed = Objects.toString(m.getOrDefault("model", ""), "");

                // --- sid 결정 로직을 먼저 수행 ---
                Object sessionId = m.get("sessionId");
                String sid = (sessionId == null) ? "" : sessionId.toString().trim();
                if (sid.isBlank()) {
                    Object sessionHash = m.get("sessionHash");
                    sid = (sessionHash == null) ? "" : sessionHash.toString().trim();
                }
                String logicalSid = sid; // 원래 sid 보존용

                if (sid.isBlank() || LangChainRAGService.GLOBAL_SID.equals(sid)) {
                    sid = vectorSidService.resolveActiveSid(LangChainRAGService.GLOBAL_SID);
                    logicalSid = LangChainRAGService.GLOBAL_SID;
                }
                // ------------------------------------------

                String stableId = sha1((datasetName == null ? "" : datasetName) + "|" + question + "|" + answer);
                String vectorId = "uaw:" + stableId;

                Map<String, Object> meta = new HashMap<>();
                meta.put(VectorMetaKeys.META_DOC_TYPE, "KB");
                meta.put(VectorMetaKeys.META_SOURCE_TAG, "UAW_AUTOLRN");
                meta.put(VectorMetaKeys.META_ORIGIN, "SYSTEM");
                meta.put(VectorMetaKeys.META_VERIFIED, "false");
                meta.put(VectorMetaKeys.META_VERIFICATION_NEEDED, "true");
                meta.put(VectorMetaKeys.META_SHADOW_WRITE, "true");
                meta.put(VectorMetaKeys.META_SHADOW_REASON, "uaw_autolearn_unverified");
                meta.put(VectorMetaKeys.META_SHADOW_TARGET_SID, hashOrEmpty(sid));
                meta.put(VectorMetaKeys.META_CITATION_COUNT, 0);
                meta.put(VectorMetaKeys.META_PROJECT_ID, PROJECT_ID);
                meta.put(VectorMetaKeys.META_PROJECT_NAME, PROJECT_NAME);
                meta.put(VectorMetaKeys.META_MEMORY_KIND, "autolearn_shadow_qa");
                meta.put(VectorMetaKeys.META_RETENTION_POLICY, "quarantine_until_verified");
                meta.put(VectorMetaKeys.META_CONTEXT_PURITY_SCORE, "0.50");
                meta.put(VectorMetaKeys.META_CONTEXT_CONTAMINATION_SCORE, "0.50");
                meta.put(VectorMetaKeys.META_DELETE_SCORE, "0.55");
                meta.put(VectorMetaKeys.META_MEMORY_VALUE, "0.50");
                meta.put(VectorMetaKeys.META_DELETE_DECISION, "QUARANTINE");
                meta.put(VectorMetaKeys.META_DELETE_REASON, "autolearn_unverified_shadow");
                meta.put(VectorMetaKeys.META_SOURCE_PATH, "var/train_rag.jsonl");
                meta.put(VectorMetaKeys.META_ACTIVE_SOURCE_ROOT, "false");
                meta.put(VectorMetaKeys.META_AGENT_HANDOFF_MANIFEST, DEFAULT_AGENT_HANDOFF_MANIFEST);
                meta.put(VectorMetaKeys.META_AGENT_HANDOFF_MANIFEST_HASH, hashOrEmpty(DEFAULT_AGENT_HANDOFF_MANIFEST));
                meta.put(VectorMetaKeys.META_AGENT_HANDOFF_SAMPLE_HASH, agentHandoffSampleHash(question, answer, modelUsed));

                meta.put("type", "qa");
                meta.put("dataset", hashOrEmpty(datasetName));
                meta.put("source", safeMetadataToken(
                        Objects.toString(m.getOrDefault("source", "uaw_autolearn"), "uaw_autolearn")));
                meta.put("uaw_id", stableId);
                meta.put("ts", safeTimestamp(m.get("ts")));
                meta.put("sid_logical", safeLogicalSid(logicalSid)); // 결정된 논리적 SID 저장

                copyHashedIfPresent(m, meta, "branch");
                copyHashedIfPresent(m, meta, "model");
                copyHashedIfPresent(m, meta, "provider");
                copyTokenIfPresent(m, meta, "disabledReason");
                copyNonNegativeIntIfPresent(m, meta, "afterFilterCount", 0);
                copyBooleanIfPresent(m, meta, "finalGate");
                copyUnitIntervalIfPresent(m, meta, "contextDiversity", 0.0d);
                copyValidationMeta(m, meta);
                meta.put(VectorMetaKeys.META_AGENT_HANDOFF_DECISION, agentHandoffDecision(meta));

                String projectionMode = vectorProjectionMode();
                meta.put("vector_projection_mode", projectionMode);
                if (PROJECTION_NONE.equals(projectionMode)) {
                    counters.lastReason = "vector_projection_none";
                    saveState(statePath, jsonlPath, lastProcessedOffset);
                    continue;
                }
                String content = PROJECTION_RAW_SHADOW_QUARANTINE.equals(projectionMode)
                        ? rawContent(question, answer)
                        : metadataOnlyContent(stableId, meta);
                batch.add(new Indexed(vectorId, sid, content, meta));
                counters.queuedDocs++;

                if (batch.size() >= batchSize) {
                    if (token != null && token.shouldAbort()) {
                        counters.lastReason = "preempted_before_flush";
                        break;
                    }
                    boolean ok = upsertSegments(batch);
                    if (!ok) {
                        counters.failedBatches++;
                        counters.lastReason = "vector_upsert_fail";
                        break;
                    }
                    acceptedDocs += batch.size();
                    batch.clear();
                    saveState(statePath, jsonlPath, lastProcessedOffset);
                }
            }

            if (!batch.isEmpty() && (token == null || !token.shouldAbort())) {
                boolean ok = upsertSegments(batch);
                if (ok) {
                    acceptedDocs += batch.size();
                    saveState(statePath, jsonlPath, lastProcessedOffset);
                } else {
                    counters.failedBatches++;
                    counters.lastReason = "vector_upsert_fail";
                }
            } else if (!batch.isEmpty()) {
                counters.lastReason = "preempted_before_final_flush";
            }
        } catch (Exception e) {
            log.warn("[UAW] ingest error. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            counters.failedBatches++;
            counters.lastReason = "ingest_fail";
            recordExternalError("ingest_fail");
        } finally {
            traceIngestCounters(counters, acceptedDocs);
        }

        return acceptedDocs;
    }

    private boolean upsertSegments(List<Indexed> batch) {
        try {
            if (batch == null || batch.isEmpty()) {
                return true;
            }

            observeRagControlShadow(batch);

            for (Indexed it : batch) {
                if (it == null || it.text() == null || it.text().isBlank())
                    continue;
                String sid = (it.sid() == null || it.sid().isBlank())
                        ? vectorSidService.resolveActiveSid(LangChainRAGService.GLOBAL_SID)
                        : it.sid().trim();
                vectorStoreService.enqueue(it.id(), sid, it.text(), it.meta());
            }
            vectorStoreService.flush();
            log.info("[AWX_MEMORY_INGEST][doc] projectId={} namespace={} count={} action={}",
                    PROJECT_ID, MEMORY_NAMESPACE, batch.size(), vectorProjectionMode().toLowerCase(Locale.ROOT));
            return true;
        } catch (Exception e) {
            log.warn("[UAW] vector upsert failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            recordExternalError("vector_upsert_fail");
            return false;
        }
    }

    private void observeRagControlShadow(List<Indexed> batch) {
        if (ragControlLearningGate == null) {
            TraceStore.put("uaw.retrain.ragControl.present", false);
            TraceStore.put("uaw.retrain.ragControl.failureClass", "learning_gate_unavailable");
            return;
        }
        try {
            TraceStore.put("uaw.retrain.ragControl.present", true);
            TraceStore.put("uaw.retrain.ragControl.failureClass", null);
            int eligibleCount = (int) batch.stream()
                    .filter(Objects::nonNull)
                    .filter(indexed -> indexed.text() != null && !indexed.text().isBlank())
                    .count();
            RagControlLearningGate.Decision decision = ragControlLearningGate.evaluate(
                    RagControlLearningGate.Boundary.TRAIN_RAG_BACKGROUND_SHADOW,
                    new RagControlRuntimeAdapter.RuntimeInput(
                            true,
                            eligibleCount,
                            eligibleCount,
                            false,
                            false,
                            false,
                            false));
            TraceStore.put("uaw.retrain.ragControl.shadowOnly", decision.shadowOnly());
            TraceStore.put("uaw.retrain.ragControl.holdWrites", decision.holdWrites());
            TraceStore.put("uaw.retrain.ragControl.action",
                    decision.plan().action().name().toLowerCase(Locale.ROOT));
            TraceStore.put("uaw.retrain.ragControl.reasonCode", decision.plan().reasonCode());
        } catch (RuntimeException observationFailure) {
            TraceStore.put("uaw.retrain.ragControl.failureClass", "shadow_observation_failed");
            TraceStore.put("uaw.retrain.ragControl.errorType", SafeRedactor.traceLabelOrFallback(
                    observationFailure.getClass().getSimpleName(), "runtime_exception"));
        }
    }

    private Map<String, Object> parseJson(String line) {
        try {
            return om.readValue(line, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.debug("[UAW] train json parse skipped lineLength={}", line == null ? 0 : line.length());
            return Map.of();
        }
    }

    private String vectorProjectionMode() {
        UawAutolearnProperties.Retrain retrain = props == null ? null : props.getRetrain();
        if (retrain != null && retrain.isRawContentToVector()) {
            return PROJECTION_RAW_SHADOW_QUARANTINE;
        }
        String mode = retrain == null ? "" : Objects.toString(retrain.getVectorProjectionMode(), "");
        String normalized = mode.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case PROJECTION_NONE -> PROJECTION_NONE;
            case PROJECTION_RAW_SHADOW_QUARANTINE -> PROJECTION_RAW_SHADOW_QUARANTINE;
            default -> PROJECTION_METADATA_ONLY;
        };
    }

    private static String rawContent(String question, String answer) {
        String raw = question == null || question.isBlank() ? answer : (question + "\n\n" + answer);
        return SafeRedactor.redact(raw);
    }

    private static String metadataOnlyContent(String stableId, Map<String, Object> meta) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("UAW AutoLearn metadata projection\n");
        sb.append("dataset=").append(safeToken(meta.get("dataset"))).append('\n');
        sb.append("uawId=").append(safeToken(stableId)).append('\n');
        sb.append("validationDecision=").append(safeToken(meta.get(VectorMetaKeys.META_LEARNING_VALIDATION_DECISION))).append('\n');
        sb.append("vectorDecision=").append(safeToken(meta.get(VectorMetaKeys.META_LEARNING_VECTOR_DECISION))).append('\n');
        sb.append("deleteDecision=").append(safeToken(meta.get(VectorMetaKeys.META_DELETE_DECISION))).append('\n');
        sb.append("sampleScore=").append(safeToken(meta.get(VectorMetaKeys.META_LEARNING_SAMPLE_SCORE))).append('\n');
        sb.append("contextContaminationScore=").append(safeToken(meta.get(VectorMetaKeys.META_CONTEXT_CONTAMINATION_SCORE))).append('\n');
        sb.append("rejectReasons=").append(safeToken(meta.get(VectorMetaKeys.META_LEARNING_REJECT_REASONS))).append('\n');
        sb.append("source=").append(safeToken(meta.get("source")));
        return sb.toString();
    }

    private static String safeToken(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("(?i)\\bBearer\\s+[^\\s,;]+", "<redacted-bearer>")
                .replaceAll("(?i)\\b(?:authorization|ownertoken|api[-_]?key|client[-_]?secret|secret|token|password)\\s*[:=]\\s*[^\\s,;]+",
                        "<redacted-secret>")
                .replaceAll("(?i)\\bsb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}\\b", "<redacted-secret>");
    }

    private static String agentHandoffDecision(Map<String, Object> meta) {
        if (meta == null) {
            return "UNKNOWN";
        }
        if ("QUARANTINE".equalsIgnoreCase(Objects.toString(meta.get(VectorMetaKeys.META_DELETE_DECISION), ""))) {
            return "QUARANTINE";
        }
        if ("rejected".equalsIgnoreCase(Objects.toString(meta.get(VectorMetaKeys.META_LEARNING_VALIDATION_DECISION), ""))) {
            return "REJECTED";
        }
        return "ACCEPTED";
    }

    private static String agentHandoffSampleHash(String question, String answer, String modelUsed) {
        return hashOrEmpty((question == null ? "" : question)
                + '\0' + (answer == null ? "" : answer)
                + '\0' + (modelUsed == null ? "" : modelUsed));
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }

    private static String safeLogicalSid(String value) {
        String normalized = value == null ? "" : value.trim();
        return LangChainRAGService.GLOBAL_SID.equals(normalized)
                ? LangChainRAGService.GLOBAL_SID
                : hashOrEmpty(normalized);
    }

    private static String safeTimestamp(Object value) {
        String raw = value == null ? "" : String.valueOf(value).trim();
        if (!raw.isBlank()) {
            try {
                return Instant.parse(raw).toString();
            } catch (RuntimeException ignored) {
                // Invalid or attacker-controlled timestamps are replaced by a canonical instant.
                TraceStore.put("uaw.retrain.ingest.timestampFallbackReason", "invalid_timestamp");
                TraceStore.inc("uaw.retrain.ingest.timestampFallbackCount");
            }
        }
        return Instant.now().toString();
    }

    private static String safeMetadataToken(Object value) {
        if (value == null) {
            return "";
        }
        String token = String.valueOf(value).trim();
        if (token.isBlank()) {
            return "";
        }
        if (SAFE_METADATA_TOKENS.contains(token)) {
            return token;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        return SAFE_METADATA_TOKENS.contains(lower) ? lower : hashOrEmpty(token);
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String msg = messageOf(t);
        return msg == null ? 0 : msg.length();
    }

    private static void copyHashedIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, hashOrEmpty(String.valueOf(value)));
        }
    }

    private static void copyTokenIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value instanceof String) {
            target.put(key, safeMetadataToken(value));
        }
    }

    private static void copyNonNegativeIntIfPresent(
            Map<String, Object> source,
            Map<String, Object> target,
            String key,
            int fallback) {
        if (source == null || target == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, readNonNegativeInt(value, fallback));
        }
    }

    private static void copyUnitIntervalIfPresent(
            Map<String, Object> source,
            Map<String, Object> target,
            String key,
            double fallback) {
        if (source == null || target == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, readUnitInterval(value, fallback));
        }
    }

    private static void copyBooleanIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null || key.isBlank()) {
            return;
        }
        Object value = source.get(key);
        if (value instanceof Boolean booleanValue) {
            target.put(key, booleanValue);
        }
    }

    private static void copyValidationMeta(Map<String, Object> source, Map<String, Object> target) {
        if (source == null || target == null) {
            return;
        }
        Object raw = source.get("validation");
        if (!(raw instanceof Map<?, ?> validation)) {
            return;
        }
        copyTokenNested(validation, target, "questionType", VectorMetaKeys.META_LEARNING_QUESTION_TYPE);
        copyJoined(validation, target, "selfAskLanes", VectorMetaKeys.META_LEARNING_SELF_ASK_LANES);
        copyFiniteNested(validation, target, "selfAskLaneCoverage",
                VectorMetaKeys.META_LEARNING_SELF_ASK_LANE_COVERAGE, 0.0d);
        copyFiniteNested(validation, target, "sampleScore", VectorMetaKeys.META_LEARNING_SAMPLE_SCORE, 0.0d);
        copyFiniteNested(validation, target, "riskScore", VectorMetaKeys.META_LEARNING_RISK_SCORE, 0.0d);
        copyFiniteNested(validation, target, "contradictionScore",
                VectorMetaKeys.META_LEARNING_CONTRADICTION_SCORE, 0.0d);
        copyTokenNested(validation, target, "contradictionCause", VectorMetaKeys.META_LEARNING_CONTRADICTION_CAUSE);
        copyFiniteNested(validation, target, "contaminationScore",
                VectorMetaKeys.META_LEARNING_CONTAMINATION_SCORE, 0.50d);
        copyFiniteNested(validation, target, "legacyContextScore",
                VectorMetaKeys.META_LEARNING_LEGACY_CONTEXT_SCORE, 0.0d);
        copyJoined(validation, target, "rejectReasons", VectorMetaKeys.META_LEARNING_REJECT_REASONS);
        if (source.containsKey("evaluationCriteria")) {
            copyJoined(source, target, "evaluationCriteria", VectorMetaKeys.META_LEARNING_EVALUATION_CRITERIA);
        } else {
            copyJoined(validation, target, "evaluationCriteria", VectorMetaKeys.META_LEARNING_EVALUATION_CRITERIA);
        }
        Object requery = validation.get("requery");
        if (requery instanceof Map<?, ?> requeryMap) {
            copyBooleanNested(requeryMap, target, "required", VectorMetaKeys.META_LEARNING_REQUERY_REQUIRED);
            copyBooleanNested(requeryMap, target, "confirmed", VectorMetaKeys.META_LEARNING_REQUERY_CONFIRMED);
        }
        Object thresholds = validation.get("thresholds");
        if (thresholds instanceof Map<?, ?> thresholdMap) {
            copyFiniteNested(thresholdMap, target, "sampleScoreMin",
                    VectorMetaKeys.META_LEARNING_DYNAMIC_SAMPLE_THRESHOLD, 0.55d);
            copyFiniteNested(thresholdMap, target, "contaminationMax",
                    VectorMetaKeys.META_LEARNING_DYNAMIC_CONTAMINATION_THRESHOLD, 0.35d);
            copyFiniteNested(thresholdMap, target, "contextContaminationMax",
                    VectorMetaKeys.META_LEARNING_CONTEXT_CONTAMINATION_THRESHOLD, 0.40d);
            copyFiniteNested(thresholdMap, target, "contradictionMax",
                    VectorMetaKeys.META_LEARNING_CONTRADICTION_THRESHOLD, 0.60d);
            copyFiniteNested(thresholdMap, target, "requeryPenalty",
                    VectorMetaKeys.META_LEARNING_REQUERY_PENALTY, 0.0d);
        }
        Object runtime = validation.get("runtime");
        if (runtime instanceof Map<?, ?> runtimeMap) {
            copyFiniteNested(runtimeMap, target, "errorRateWindow",
                    VectorMetaKeys.META_LEARNING_ERROR_RATE_WINDOW, 0.0d);
        }
        Object anomalies = validation.get("anomalies");
        if (anomalies instanceof Map<?, ?> anomaliesMap) {
            copyJoined(anomaliesMap, target, "flags", VectorMetaKeys.META_LEARNING_ANOMALY_FLAGS);
            copyBooleanNested(anomaliesMap, target, "spike", VectorMetaKeys.META_LEARNING_ANOMALY_SPIKE);
            copyBooleanNested(anomaliesMap, target, "drift", VectorMetaKeys.META_LEARNING_ANOMALY_DRIFT);
        }
        Object feedback = validation.get("feedback");
        if (feedback instanceof Map<?, ?> feedbackMap) {
            copyFiniteNested(feedbackMap, target, "cfvmReward", VectorMetaKeys.META_LEARNING_CFVM_REWARD, 0.0d);
            copyVectorDecisionNested(feedbackMap, target, "vectorDecision",
                    VectorMetaKeys.META_LEARNING_VECTOR_DECISION);
        }
        Object needleRoi = validation.get("needleRoi");
        if (needleRoi instanceof Map<?, ?> needleRoiMap) {
            copyBooleanNested(needleRoiMap, target, "needleSignalCandidate",
                    VectorMetaKeys.META_LEARNING_ROI_NEEDLE_SIGNAL_CANDIDATE);
            copyFiniteNested(needleRoiMap, target, "signalValueScore",
                    VectorMetaKeys.META_LEARNING_ROI_SIGNAL_VALUE_SCORE, 0.0d);
            copyBooleanNested(needleRoiMap, target, "promoted", VectorMetaKeys.META_LEARNING_ROI_PROMOTED);
            copyTokenNested(needleRoiMap, target, "rejectReason", VectorMetaKeys.META_LEARNING_ROI_REJECT_REASON);
        }
        boolean accepted = acceptedFromValidation(validation);
        target.putIfAbsent(VectorMetaKeys.META_LEARNING_VECTOR_DECISION,
                accepted ? "SHADOW_REVIEW" : "QUARANTINE");
        target.put(VectorMetaKeys.META_LEARNING_VALIDATION_DECISION, accepted ? "accepted" : "rejected");
        applyValidationPurityDecision(validation, target, accepted);
    }

    private static void copyTokenNested(
            Map<?, ?> source,
            Map<String, Object> target,
            String sourceKey,
            String targetKey) {
        if (source == null || target == null || sourceKey == null || targetKey == null) {
            return;
        }
        Object value = source.get(sourceKey);
        if (value instanceof String) {
            target.put(targetKey, safeMetadataToken(value));
        }
    }

    private static void copyBooleanNested(
            Map<?, ?> source,
            Map<String, Object> target,
            String sourceKey,
            String targetKey) {
        if (source == null || target == null || sourceKey == null || targetKey == null) {
            return;
        }
        Object value = source.get(sourceKey);
        if (value instanceof Boolean booleanValue) {
            target.put(targetKey, booleanValue);
        }
    }

    private static void copyVectorDecisionNested(
            Map<?, ?> source,
            Map<String, Object> target,
            String sourceKey,
            String targetKey) {
        if (source == null || target == null || sourceKey == null || targetKey == null) {
            return;
        }
        Object value = source.get(sourceKey);
        if (value instanceof String raw) {
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if ("SHADOW_REVIEW".equals(normalized) || "QUARANTINE".equals(normalized)) {
                target.put(targetKey, normalized);
                return;
            }
        }
        if (value != null) {
            target.put(targetKey, "QUARANTINE");
        }
    }

    private static void copyFiniteNested(Map<?, ?> source,
            Map<String, Object> target,
            String sourceKey,
            String targetKey,
            double fallback) {
        if (source == null || target == null || sourceKey == null || targetKey == null) {
            return;
        }
        Object value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, readUnitInterval(value, fallback));
        }
    }

    private static void copyJoined(Map<?, ?> source, Map<String, Object> target, String sourceKey, String targetKey) {
        if (source == null || target == null || sourceKey == null || targetKey == null) {
            return;
        }
        Object value = source.get(sourceKey);
        if (value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> out = new ArrayList<>();
            for (Object item : iterable) {
                if (item instanceof String) {
                    String s = safeMetadataToken(item);
                    if (!s.isBlank()) {
                        out.add(s);
                    }
                }
            }
            if (!out.isEmpty()) {
                target.put(targetKey, String.join(",", out));
            }
            return;
        }
        if (value instanceof String) {
            String s = safeMetadataToken(value);
            if (!s.isBlank()) {
                target.put(targetKey, s);
            }
        }
    }

    private static void applyValidationPurityDecision(Map<?, ?> validation,
            Map<String, Object> target,
            boolean accepted) {
        double contamination = readUnitInterval(validation.get("contaminationScore"), 0.50d);
        double legacy = readUnitInterval(validation.get("legacyContextScore"), 0.0d);
        double explicitContextContamination = readUnitInterval(
                validation.get("contextContaminationScore"), Double.NaN);
        double sampleScore = readUnitInterval(validation.get("sampleScore"), 0.0d);
        double contradictionScore = readUnitInterval(validation.get("contradictionScore"), 0.0d);
        Map<?, ?> thresholds = asMap(validation.get("thresholds"));
        Map<?, ?> runtime = asMap(validation.get("runtime"));
        Map<?, ?> anomalies = asMap(validation.get("anomalies"));
        Map<?, ?> feedback = asMap(validation.get("feedback"));
        Map<?, ?> needleRoi = asMap(validation.get("needleRoi"));
        String rejectReasons = joined(validation.get("rejectReasons"));
        double contaminationScore = Double.isFinite(explicitContextContamination)
                ? clamp01(explicitContextContamination)
                : clamp01(Math.max(contamination, legacy * 0.75d));
        double purityScore = clamp01(1.0d - contaminationScore);
        double runtimeEvidenceCount = readNonNegativeDouble(runtime.get("evidenceCount"), -1.0d);
        double runtimeAfterFilterCount = readNonNegativeDouble(runtime.get("afterFilterCount"),
                readNonNegativeDouble(target.get("afterFilterCount"), -1.0d));
        boolean afterFilterStarvation = rejectReasons.contains("after_filter_starvation")
                || (runtimeEvidenceCount > 0.0d && runtimeAfterFilterCount == 0.0d);
        double deleteScore = clamp01(Math.max(
                Math.max(contaminationScore, contradictionScore),
                Math.max(afterFilterStarvation ? 0.65d : 0.0d, accepted ? 1.0d - sampleScore : 0.55d)));
        double contaminationThreshold = readUnitInterval(thresholds.get("contaminationMax"), 0.35d);
        double contextContaminationThreshold = readUnitInterval(
                thresholds.get("contextContaminationMax"), 0.40d);
        double contradictionThreshold = readUnitInterval(thresholds.get("contradictionMax"), 0.60d);
        String anomalyFlags = joined(anomalies.get("flags"));
        String vectorDecision = safeVectorDecision(feedback.get("vectorDecision"), "QUARANTINE");
        boolean needleCandidate = readBoolean(needleRoi.get("needleSignalCandidate"), false);
        boolean needlePromoted = readBoolean(needleRoi.get("promoted"), false);
        boolean quarantine = !accepted
                || contamination >= contaminationThreshold
                || contaminationScore >= contextContaminationThreshold
                || contradictionScore >= contradictionThreshold
                || rejectReasons.contains("contradiction_risk")
                || afterFilterStarvation
                || (needleCandidate && !needlePromoted)
                || !anomalyFlags.isBlank()
                || "QUARANTINE".equalsIgnoreCase(vectorDecision);
        target.put(VectorMetaKeys.META_CONTEXT_CONTAMINATION_SCORE, String.format(Locale.ROOT, "%.4f", contaminationScore));
        target.put(VectorMetaKeys.META_CONTEXT_PURITY_SCORE, String.format(Locale.ROOT, "%.4f", purityScore));
        target.put(VectorMetaKeys.META_DELETE_SCORE, String.format(Locale.ROOT, "%.4f", deleteScore));
        target.put(VectorMetaKeys.META_DELETE_DECISION, quarantine ? "QUARANTINE" : "SHADOW_REVIEW");
        target.put(VectorMetaKeys.META_DELETE_REASON,
                quarantine && (contradictionScore >= contradictionThreshold || rejectReasons.contains("contradiction_risk"))
                        ? "validation_contradiction_risk"
                        : quarantine && afterFilterStarvation ? "validation_after_filter_starvation"
                        : quarantine && needleCandidate && !needlePromoted ? "validation_needle_roi_rejected"
                        : quarantine ? "validation_anomaly_or_threshold" : "autolearn_validated_shadow");
    }

    private static boolean acceptedFromValidation(Map<?, ?> validation) {
        Object explicit = validation.get("accepted");
        if (explicit instanceof Boolean b) {
            return !b ? false : inferredAccepted(validation);
        }
        if (explicit instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) {
                return inferredAccepted(validation);
            }
            if ("false".equalsIgnoreCase(s.trim())) {
                return false;
            }
        }
        Object reasons = validation.get("rejectReasons");
        if (reasons instanceof Iterable<?> iterable) {
            for (Object reason : iterable) {
                if (reason != null && !String.valueOf(reason).trim().isBlank()) {
                    return false;
                }
            }
            return inferredAccepted(validation);
        }
        String joinedReasons = joined(reasons);
        if (!joinedReasons.isBlank()) {
            return false;
        }
        return inferredAccepted(validation);
    }

    private static boolean inferredAccepted(Map<?, ?> validation) {
        Map<?, ?> thresholds = asMap(validation.get("thresholds"));
        Map<?, ?> anomalies = asMap(validation.get("anomalies"));
        Map<?, ?> feedback = asMap(validation.get("feedback"));
        Map<?, ?> needleRoi = asMap(validation.get("needleRoi"));
        double sampleScore = readUnitInterval(validation.get("sampleScore"), 0.0d);
        double contamination = readUnitInterval(validation.get("contaminationScore"), 0.50d);
        double legacy = readUnitInterval(validation.get("legacyContextScore"), 0.0d);
        double contradictionScore = readUnitInterval(validation.get("contradictionScore"), 0.0d);
        double sampleScoreMin = readUnitInterval(thresholds.get("sampleScoreMin"), 0.55d);
        double contaminationThreshold = readUnitInterval(thresholds.get("contaminationMax"), 0.35d);
        double contextContaminationThreshold = readUnitInterval(
                thresholds.get("contextContaminationMax"), 0.40d);
        double contradictionThreshold = readUnitInterval(thresholds.get("contradictionMax"), 0.60d);
        double contextContamination = clamp01(Math.max(contamination, legacy * 0.75d));
        String anomalyFlags = joined(anomalies.get("flags"));
        String vectorDecision = safeVectorDecision(feedback.get("vectorDecision"), "QUARANTINE");
        Map<?, ?> requery = asMap(validation.get("requery"));
        Map<?, ?> runtime = asMap(validation.get("runtime"));
        boolean requeryRequired = readBoolean(requery.get("required"), false);
        boolean requeryConfirmed = readBoolean(requery.get("confirmed"), false);
        boolean needleCandidate = readBoolean(needleRoi.get("needleSignalCandidate"), false);
        boolean needlePromoted = readBoolean(needleRoi.get("promoted"), false);
        double evidenceCount = readNonNegativeDouble(runtime.get("evidenceCount"), -1.0d);
        double afterFilterCount = readNonNegativeDouble(runtime.get("afterFilterCount"), -1.0d);
        boolean afterFilterStarvation = evidenceCount > 0.0d && afterFilterCount == 0.0d;
        return sampleScore >= sampleScoreMin
                && contamination < contaminationThreshold
                && contextContamination < contextContaminationThreshold
                && contradictionScore < contradictionThreshold
                && !afterFilterStarvation
                && anomalyFlags.isBlank()
                && !"QUARANTINE".equalsIgnoreCase(vectorDecision)
                && (!needleCandidate || needlePromoted)
                && (!requeryRequired || requeryConfirmed);
    }

    private void recordExternalError(String reason) {
        try {
            if (qualityTracker != null) {
                qualityTracker.recordExternalError(reason);
            }
        } catch (Exception ignore) {
            log.debug("[UAW] quality tracker hook skipped reason={} errorHash={} errorLength={}",
                    safeReason(reason), SafeRedactor.hashValue(messageOf(ignore)), messageLength(ignore));
        }
    }

    private static void traceIngestCounters(IngestCounters counters, int acceptedDocs) {
        IngestCounters c = counters == null ? new IngestCounters() : counters;
        TraceStore.put("uaw.retrain.ingest.count", Math.max(0, acceptedDocs));
        TraceStore.put("uaw.retrain.ingest.parsed", Math.max(0, c.parsedLines));
        TraceStore.put("uaw.retrain.ingest.queued", Math.max(0, c.queuedDocs));
        TraceStore.put("uaw.retrain.ingest.failed", Math.max(0, c.failedBatches));
        Map<String, Object> summary = new HashMap<>();
        summary.put("readLines", Math.max(0, c.readLines));
        summary.put("parsedLines", Math.max(0, c.parsedLines));
        summary.put("skippedBlankLines", Math.max(0, c.skippedBlankLines));
        summary.put("skippedInvalidLines", Math.max(0, c.skippedInvalidLines));
        summary.put("queuedDocs", Math.max(0, c.queuedDocs));
        summary.put("failedBatches", Math.max(0, c.failedBatches));
        summary.put("acceptedDocs", Math.max(0, acceptedDocs));
        summary.put("reason", safeReason(c.lastReason));
        TraceStore.put("uaw.retrain.ingest.summary", summary);
    }

    private static String safeReason(String reason) {
        if (reason == null) {
            return "";
        }
        String v = reason.replace('\n', ' ').replace('\r', ' ').trim();
        return v.length() <= 64 ? v : v.substring(0, 64);
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String joined(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<String> out = new ArrayList<>();
            for (Object item : iterable) {
                if (item != null) {
                    String s = String.valueOf(item).trim();
                    if (!s.isBlank()) {
                        out.add(s);
                    }
                }
            }
            return String.join(",", out);
        }
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int readNonNegativeInt(Object value, int fallback) {
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            if (Double.isFinite(parsed)
                    && parsed >= 0.0d
                    && parsed <= Integer.MAX_VALUE
                    && parsed == Math.rint(parsed)) {
                return (int) parsed;
            }
        }
        return Math.max(0, fallback);
    }

    private static double readNonNegativeDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            if (Double.isFinite(parsed) && parsed >= 0.0d) {
                return parsed;
            }
        }
        return fallback;
    }

    private static double readUnitInterval(Object value, double fallback) {
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            if (Double.isFinite(parsed) && parsed >= 0.0d && parsed <= 1.0d) {
                return parsed;
            }
        }
        return fallback;
    }

    private static String safeVectorDecision(Object value, String fallback) {
        if (value instanceof String raw) {
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if ("SHADOW_REVIEW".equals(normalized) || "QUARANTINE".equals(normalized)) {
                return normalized;
            }
        }
        return fallback;
    }

    private static double readDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            if (Double.isFinite(parsed)) {
                return parsed;
            }
            log.debug("[UAW] train numeric parse skipped valueLength={}",
                    String.valueOf(value).length());
            return fallback;
        }
        if (value == null) {
            return fallback;
        }
        try {
            String s = String.valueOf(value).trim();
            if (s.isBlank()) {
                return fallback;
            }
            double parsed = Double.parseDouble(s);
            if (Double.isFinite(parsed)) {
                return parsed;
            }
            log.debug("[UAW] train numeric parse skipped valueLength={}",
                    String.valueOf(value).length());
            return fallback;
        } catch (NumberFormatException ignore) {
            log.debug("[UAW] train numeric parse skipped valueLength={}",
                    value == null ? 0 : String.valueOf(value).length());
            return fallback;
        }
    }

    private static boolean readBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        return fallback;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private IngestState loadState(Path statePath, Path jsonlPath) {
        try {
            if (statePath == null || !Files.exists(statePath)) {
                return IngestState.empty(jsonlPath);
            }
            IngestState s = om.readValue(Files.readString(statePath, StandardCharsets.UTF_8), IngestState.class);
            if (!sameStateFile(s, jsonlPath)) {
                // different file -> reset
                return IngestState.empty(jsonlPath);
            }
            return s;
        } catch (Exception e) {
            String path = absolutePath(statePath);
            log.debug("[UAW] ingest state load skipped pathHash={} pathLength={} errorHash={} errorLength={}",
                    SafeRedactor.hashValue(path), path.length(),
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return IngestState.empty(jsonlPath);
        }
    }

    private void saveState(Path statePath, Path jsonlPath, long offset) {
        if (statePath == null)
            return;
        try {
            Files.createDirectories(statePath.getParent());
            IngestState s = new IngestState();
            s.offset = Math.max(0L, offset);
            setFileFingerprint(s, jsonlPath);
            s.updatedAt = Instant.now().toString();

            Path tmp = statePath.resolveSibling(statePath.getFileName().toString() + ".tmp");
            Files.writeString(tmp, om.writeValueAsString(s), StandardCharsets.UTF_8);
            Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignore) {
            String path = absolutePath(statePath);
            log.debug("[UAW] ingest state save skipped pathHash={} pathLength={} errorHash={} errorLength={}",
                    SafeRedactor.hashValue(path), path.length(),
                    SafeRedactor.hashValue(messageOf(ignore)), messageLength(ignore));
        }
    }

    private static String readUtf8Line(RandomAccessFile raf) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            int b;
            boolean gotAny = false;
            while ((b = raf.read()) != -1) {
                gotAny = true;
                if (b == '\n') {
                    break;
                }
                if (b != '\r') {
                    out.write(b);
                }
            }
            if (!gotAny && out.size() == 0) {
                return null;
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("[UAW] utf8 line read skipped errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return null;
        }
    }

    private static void setFileFingerprint(IngestState state, Path file) {
        if (state == null) {
            return;
        }
        String path = absolutePath(file);
        state.file = null;
        state.fileHash = path.isBlank() ? "" : SafeRedactor.hashValue(path);
        state.fileLength = path.length();
    }

    private static boolean sameStateFile(IngestState state, Path jsonlPath) {
        if (state == null || jsonlPath == null) {
            return false;
        }
        String path = absolutePath(jsonlPath);
        if (path.isBlank()) {
            return false;
        }
        if (state.fileHash != null && !state.fileHash.isBlank()) {
            return SafeRedactor.hashValue(path).equals(state.fileHash);
        }
        return state.file != null && state.file.equals(path);
    }

    private static String absolutePath(Path path) {
        return path == null ? "" : path.toAbsolutePath().toString();
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte v : b)
                sb.append(String.format(Locale.ROOT, "%02x", v));
            return sb.toString();
        } catch (Exception e) {
            log.debug("[UAW] train sample hash fallback errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
            return Integer.toHexString(s.hashCode());
        }
    }
}
