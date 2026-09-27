package com.example.lms.graphdb;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.example.lms.file.FileIngestionService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.graph.GraphRagChunkingService;
import com.example.lms.trace.SafeRedactor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;

@Service
public class GraphDbManualLearningService {

    public static final String LANE = "graphdb_manual_learning";
    public static final String SOURCE_TAG = "GRAPHDB_MANUAL";
    private static final List<String> ADMIN_ROUTES = List.of(
            "POST /api/admin/graph/learn-text",
            "POST /api/admin/graph/learn-file",
            "GET /api/admin/graph/learn-status",
            "GET /api/admin/graph/learn-evidence",
            "GET /api/admin/graph/learn-summary",
            "GET /api/admin/graph/learn-snapshot");
    private static final List<String> ACCEPTED_TOKEN_HEADERS = List.of("X-Admin-Token", "X-Owner-Token");
    private static final List<String> BACKEND_URI_ENV = List.of("RETRIEVAL_KG_NEO4J_URI", "NEO4J_URI");
    private static final List<String> BACKEND_USER_ENV = List.of("RETRIEVAL_KG_NEO4J_USER", "NEO4J_USER");
    private static final List<String> BACKEND_PASSWORD_ENV = List.of("RETRIEVAL_KG_NEO4J_PASSWORD", "NEO4J_PASSWORD");
    private static final List<String> NON_DRY_RUN_READINESS_EVIDENCE_FIELDS = List.of(
            "uriPresent",
            "uriSource",
            "userPresent",
            "userSource",
            "passwordPresent",
            "passwordSource",
            "unsafeDefaultPassword",
            "uriParseable",
            "endpointHost",
            "endpointPort",
            "tcp",
            "neo4jService",
            "neo4jCli",
            "neo4jAdmin",
            "docker",
            "podman");
    private static final List<String> NON_DRY_RUN_READINESS_FAILURE_CLASSES = List.of(
            "missing_uri",
            "missing_user",
            "missing_password",
            "unsafe_default_credentials",
            "unparseable_uri",
            "missing_host",
            "bolt_unreachable",
            "live_write_read_unverified");
    private static final List<String> NON_DRY_RUN_LIVE_PROOF_STAGES = List.of(
            "readiness_env",
            "readiness_bolt_tcp",
            "boot_status",
            "learn_text_write",
            "learn_file_write",
            "learn_evidence_readback",
            "learn_summary_projection",
            "learn_snapshot_projection");

    private final GraphDbManualLearningProperties properties;
    private final GraphRagChunkingService chunkingService;
    private final GraphDbClient graphDbClient;
    private final FileIngestionService fileIngestionService;

    public GraphDbManualLearningService(GraphDbManualLearningProperties properties,
                                        GraphRagChunkingService chunkingService,
                                        GraphDbClient graphDbClient,
                                        FileIngestionService fileIngestionService) {
        this.properties = properties;
        this.chunkingService = chunkingService;
        this.graphDbClient = graphDbClient;
        this.fileIngestionService = fileIngestionService;
    }

    public LearnReport learnText(String sessionId, String text, String domain, Boolean dryRun) {
        if (!properties.isEnabled()) {
            return disabled(sessionId, "route_disabled", effectiveDryRun(dryRun), "text");
        }
        if (!StringUtils.hasText(text)) {
            return disabled(sessionId, "missing_text", effectiveDryRun(dryRun), "text");
        }

        String boundedText = bounded(text);
        boolean usedDryRun = effectiveDryRun(dryRun);
        GraphRagChunkingService.IngestOptions options = GraphRagChunkingService.IngestOptions.graphDbManual(
                usedDryRun,
                properties.isVectorEnabled(),
                properties.isNeo4jEnabled(),
                properties.isBrainStateMirrorEnabled());
        GraphRagChunkingService.IngestReport report = chunkingService.ingestText(
                sessionId,
                boundedText,
                SOURCE_TAG,
                domain,
                options);
        return fromIngest(report, usedDryRun, "text", boundedText.length() < text.length());
    }

    public LearnReport learnFile(String sessionId,
                                 String fileName,
                                 String mimeType,
                                 byte[] bytes,
                                 String domain,
                                 Boolean dryRun) {
        if (!properties.isEnabled()) {
            return disabled(sessionId, "route_disabled", effectiveDryRun(dryRun), "file");
        }
        if (bytes == null || bytes.length == 0) {
            return fileDisabled(sessionId, "missing_file", dryRun, fileName, mimeType, "", bytes);
        }
        String text;
        try {
            text = fileIngestionService.extractText(fileName, mimeType, bytes);
        } catch (Exception ex) {
            traceSuppressed("graphDb.manual.fileExtract", ex);
            return fileDisabled(sessionId, "file_extract_failed", dryRun, fileName, mimeType,
                    failureClass(ex), bytes);
        }
        if (!StringUtils.hasText(text)) {
            return fileDisabled(sessionId, "unsupported_or_empty_file", dryRun, fileName, mimeType, "", bytes);
        }
        LearnReport report = learnText(sessionId, text, domain, dryRun);
        Map<String, Object> manifest = new LinkedHashMap<>(report.manifest());
        manifest.put("inputKind", "file");
        manifest.put("fileNamePresent", StringUtils.hasText(fileName));
        manifest.put("mimeTypePresent", StringUtils.hasText(mimeType));
        addFileBoundary(manifest, bytes);
        return report.withManifest(manifest);
    }

    public LearnReport status() {
        Map<String, Object> manifest = baseManifest(properties.isDryRunDefault(), "status", false);
        manifest.put("statusOnly", true);
        Map<String, Object> graphDb = graphDbClient.status();
        enrichBackendReadiness(manifest, graphDb);
        attachRouteState(manifest, properties.isDryRunDefault());
        String status = safe(manifest.get("routeStatus"));
        String disabledReason = safe(manifest.get("routeDisabledReason"));
        return new LearnReport(
                properties.isEnabled(),
                LANE,
                status,
                disabledReason,
                properties.isDryRunDefault(),
                "",
                0,
                0,
                0,
                0,
                "",
                manifest,
                graphDb);
    }

    public LearnReport learnEvidence(String domain, int limit) {
        Map<String, Object> manifest = baseManifest(properties.isDryRunDefault(), "evidence", false);
        manifest.put("readOnly", true);
        manifest.put("limit", Math.max(1, Math.min(limit, 50)));
        if (!properties.isEnabled()) {
            manifest.put("disabledReason", "route_disabled");
            Map<String, Object> graphDb = graphDbClient.status();
            enrichBackendReadiness(manifest, graphDb);
            attachRouteState(manifest, false);
            return new LearnReport(false, LANE, "disabled", "route_disabled", properties.isDryRunDefault(),
                    "", 0, 0, 0, 0, "", manifest, graphDb);
        }
        Map<String, Object> graphDb = graphDbClient.manualEvidence(domain, limit);
        enrichBackendReadiness(manifest, graphDb);
        attachRouteState(manifest, false);
        return new LearnReport(true, LANE, safe(graphDb.get("status")), safe(graphDb.get("disabledReason")),
                properties.isDryRunDefault(), "", 0, 0, 0, 0, "", manifest, graphDb);
    }

    public LearnReport learnSummary(String domain, int limit) {
        Map<String, Object> manifest = baseManifest(properties.isDryRunDefault(), "summary", false);
        int safeLimit = Math.max(1, Math.min(limit, 50));
        manifest.put("readOnly", true);
        manifest.put("limit", safeLimit);
        manifest.put("projection", "community_summary");
        if (!properties.isEnabled()) {
            manifest.put("disabledReason", "route_disabled");
            Map<String, Object> graphDb = graphDbClient.status();
            enrichBackendReadiness(manifest, graphDb);
            attachRouteState(manifest, false);
            return new LearnReport(false, LANE, "disabled", "route_disabled", properties.isDryRunDefault(),
                    "", 0, 0, 0, 0, "", manifest, graphDb);
        }

        Map<String, Object> evidence = graphDbClient.manualEvidence(domain, safeLimit);
        enrichBackendReadiness(manifest, evidence);
        attachRouteState(manifest, false);
        Map<String, Object> graphDb = new LinkedHashMap<>();
        List<Map<String, Object>> communities = communities(evidence);
        List<Map<String, Object>> multiHopEvidence = multiHopEvidence(evidence);
        graphDb.put("backend", "neo4j");
        graphDb.put("writeBoundary", LANE);
        graphDb.put("readBoundary", LANE);
        graphDb.put("summaryBoundary", LANE + "_community_summary");
        graphDb.put("multiHopBoundary", LANE + "_multi_hop_evidence");
        graphDb.put("projectionSource", "Neo4jKgChunkWriter.readManualEvidence");
        graphDb.put("enabled", evidence.getOrDefault("enabled", false));
        graphDb.put("status", safeCode(evidence.get("status")));
        graphDb.put("disabledReason", safeCode(evidence.get("disabledReason")));
        graphDb.put("returnedCount", intValue(evidence.get("returnedCount")));
        graphDb.put("communityCount", communities.size());
        graphDb.put("communities", communities);
        graphDb.put("multiHopEvidence", multiHopEvidence);
        graphDb.put("brainStateCoupled", false);
        graphDb.put("queryTimeRetrievalCoupled", false);
        graphDb.put("queryTimeAnchorMapCoupled", false);
        graphDb.put("rawTextIncluded", false);
        graphDb.put("rawEntityValuesIncluded", false);
        graphDb.put("rawIdentifiersIncluded", false);
        graphDb.put("rawSecretsIncluded", false);
        return new LearnReport(true, LANE, safe(graphDb.get("status")), safe(graphDb.get("disabledReason")),
                properties.isDryRunDefault(), "", 0, 0, 0, 0, "", manifest, graphDb);
    }

    public LearnReport learnSnapshot(String domain, int limit) {
        LearnReport summary = learnSummary(domain, limit);
        Map<String, Object> manifest = new LinkedHashMap<>(summary.manifest());
        manifest.put("inputKind", "snapshot");
        manifest.put("projection", "graphdb_brain_snapshot");
        GraphDbBrainSnapshot snapshot = GraphDbBrainSnapshot.fromSummary(summary.graphDb());
        Map<String, Object> graphDb = new LinkedHashMap<>(summary.graphDb());
        graphDb.put("snapshot", snapshot.toMap());
        graphDb.put("snapshotBoundary", snapshot.snapshotBoundary());
        graphDb.put("brainStateCoupled", false);
        graphDb.put("queryTimeRetrievalCoupled", false);
        graphDb.put("queryTimeAnchorMapCoupled", false);
        graphDb.put("rawTextIncluded", false);
        graphDb.put("rawEntityValuesIncluded", false);
        graphDb.put("rawIdentifiersIncluded", false);
        graphDb.put("rawSecretsIncluded", false);
        return summary.withManifestAndGraphDb(manifest, graphDb);
    }

    private LearnReport fromIngest(GraphRagChunkingService.IngestReport report,
                                   boolean dryRun,
                                   String inputKind,
                                   boolean inputTruncated) {
        Map<String, Object> backend = report.backend();
        Map<String, Object> manifest = baseManifest(dryRun, inputKind, inputTruncated);
        manifest.put("sessionHash", hash12(report.sessionId()));
        manifest.put("status", safeCode(report.status()));
        manifest.put("textHash", hashToken(report.textHash()));
        manifest.put("chunkCount", report.chunkCount());
        manifest.put("entityCount", report.entityCount());
        manifest.put("relationCount", report.relationCount());
        manifest.put("neo4jWriteCount", report.neo4jWriteCount());
        manifest.put("vectorStatus", safeCode(backend.get("vectorStatus")));
        manifest.put("vectorAttemptCount", intValue(backend.get("vectorAttemptCount")));
        manifest.put("vectorQueuedCount", intValue(backend.get("vectorQueuedCount")));
        manifest.put("vectorFailureCount", intValue(backend.get("vectorFailureCount")));
        manifest.put("brainStateStatus", safeCode(backend.get("brainStateStatus")));
        manifest.put("brainStateDisabledReason", safeCode(backend.get("brainStateDisabledReason")));
        manifest.put("anchorMapStatus", safeCode(backend.get("anchorMapStatus")));
        manifest.put("anchorMapDisabledReason", safeCode(backend.get("anchorMapDisabledReason")));
        manifest.put("neo4jStatus", safeCode(backend.get("neo4jStatus")));
        manifest.put("neo4jDisabledReason", safeCode(backend.get("neo4jDisabledReason")));
        manifest.put("neo4jFailureClass", safeCode(backend.get("neo4jFailureClass")));
        manifest.put("neo4jPortMappingCount", intValue(backend.get("neo4jPortMappingCount")));
        manifest.put("requiredPersistenceTargets", codeList(backend.get("requiredPersistenceTargets")));
        manifest.put("requiredPersistenceSatisfied", booleanValue(
                backend.get("requiredPersistenceSatisfied"),
                dryRun || "indexed".equals(report.status())));
        manifest.put("requiredPersistenceMissingReason", safeCode(backend.get("requiredPersistenceMissingReason")));
        manifest.put("persistenceTargetOrder", codeList(backend.get("persistenceTargetOrder")));
        manifest.put("persistenceAttemptedTargets", codeList(backend.get("persistenceAttemptedTargets")));
        manifest.put("persistenceSucceededTargets", codeList(backend.get("persistenceSucceededTargets")));
        manifest.put("persistenceIncompleteTargets", codeList(backend.get("persistenceIncompleteTargets")));
        manifest.put("persistenceFailureIsolationApplied",
                booleanValue(backend.get("persistenceFailureIsolationApplied"), false));
        manifest.put("failureClass", safeCode(backend.get("failureClass")));

        Map<String, Object> graphDb = graphDbClient.status();
        enrichBackendReadiness(manifest, graphDb);
        attachRouteState(manifest, dryRun);
        return new LearnReport(
                report.enabled(),
                LANE,
                safeCode(report.status()),
                safeCode(report.disabledReason()),
                dryRun,
                report.sessionId(),
                report.chunkCount(),
                report.entityCount(),
                report.relationCount(),
                report.neo4jWriteCount(),
                hashToken(report.textHash()),
                manifest,
                graphDb);
    }

    private LearnReport disabled(String sessionId, String reason, boolean dryRun, String inputKind) {
        Map<String, Object> manifest = baseManifest(dryRun, inputKind, false);
        String safeReason = safeCode(reason);
        manifest.put("sessionHash", hash12(sessionId == null ? "__TRANSIENT__" : sessionId));
        manifest.put("status", "disabled");
        manifest.put("disabledReason", safeReason);
        Map<String, Object> graphDb = graphDbClient.status();
        enrichBackendReadiness(manifest, graphDb);
        attachRouteState(manifest, dryRun);
        return new LearnReport(false, LANE, "disabled", safeReason, dryRun,
                sessionId == null ? "__TRANSIENT__" : sessionId, 0, 0, 0, 0, "", manifest, graphDb);
    }

    LearnReport fileReadFailed(String sessionId,
                               String fileName,
                               String mimeType,
                               Boolean dryRun,
                               Exception ex) {
        return fileDisabled(sessionId, "file_read_failed", dryRun, fileName, mimeType,
                failureClass(ex));
    }

    private LearnReport fileDisabled(String sessionId,
                                     String reason,
                                     Boolean dryRun,
                                     String fileName,
                                     String mimeType,
                                     String failureClass) {
        return fileDisabled(sessionId, reason, dryRun, fileName, mimeType, failureClass, null);
    }

    private LearnReport fileDisabled(String sessionId,
                                     String reason,
                                     Boolean dryRun,
                                     String fileName,
                                     String mimeType,
                                     String failureClass,
                                     byte[] bytes) {
        LearnReport report = disabled(sessionId, reason, effectiveDryRun(dryRun), "file");
        Map<String, Object> manifest = new LinkedHashMap<>(report.manifest());
        manifest.put("fileNamePresent", StringUtils.hasText(fileName));
        manifest.put("mimeTypePresent", StringUtils.hasText(mimeType));
        manifest.put("failureClass", safeCode(failureClass));
        addFileBoundary(manifest, bytes);
        return report.withManifest(manifest);
    }

    private static void addFileBoundary(Map<String, Object> manifest, byte[] bytes) {
        int byteLength = bytes == null ? 0 : Math.max(0, bytes.length);
        manifest.put("fileContentBoundary", "FileIngestionService.extractText");
        manifest.put("fileByteLength", byteLength);
        manifest.put("fileByteHash", byteLength == 0 ? "" : DigestUtils.sha256Hex(bytes).substring(0, 12));
        manifest.put("rawFileNameIncluded", false);
        manifest.put("rawFileBytesIncluded", false);
    }

    private Map<String, Object> baseManifest(boolean dryRun, String inputKind, boolean inputTruncated) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("lane", LANE);
        manifest.put("inputKind", inputKind);
        manifest.put("dryRun", dryRun);
        manifest.put("inputTruncated", inputTruncated);
        manifest.put("docType", "GRAPHDB_MANUAL_LEARNING");
        manifest.put("sourceTag", SOURCE_TAG);
        manifest.put("origin", "MANUAL_GRAPHDB");
        manifest.put("vectorEnabled", properties.isVectorEnabled());
        manifest.put("neo4jEnabled", properties.isNeo4jEnabled());
        boolean brainStateMirrorRequested = properties.isBrainStateMirrorEnabled();
        manifest.put("brainStateMirrorRequested", brainStateMirrorRequested);
        manifest.put("brainStateMirrorEnabled", false);
        manifest.put("brainStateMirrorSuppressedReason", brainStateMirrorRequested
                ? "graphdb_manual_lane_excludes_brain_state"
                : "");
        manifest.put("simultaneousIngestRequired", true);
        manifest.put("simultaneousIngestTargets", List.of("vector", "neo4j"));
        manifest.put("simultaneousIngestMode", "same_request_vector_then_neo4j");
        manifest.put("simultaneousIngestExecutionOrder", List.of("vector", "neo4j"));
        manifest.put("simultaneousIngestAtomic", false);
        manifest.put("simultaneousIngestFailureIsolation", "continue_remaining_targets");
        manifest.put("simultaneousIngestPartialStatus", "partial_indexed");
        manifest.put("simultaneousIngestProofFields", List.of(
                "vectorStatus",
                "vectorQueuedCount",
                "neo4jStatus",
                "neo4jWriteCount",
                "requiredPersistenceSatisfied",
                "requiredPersistenceMissingReason",
                "persistenceAttemptedTargets",
                "persistenceSucceededTargets",
                "persistenceIncompleteTargets"));
        manifest.put("simultaneousIngestConfigured", properties.isVectorEnabled() && properties.isNeo4jEnabled());
        manifest.put("simultaneousIngestDisabledReason", simultaneousIngestDisabledReason());
        manifest.put("persistenceTargetOrder", List.of());
        manifest.put("persistenceAttemptedTargets", List.of());
        manifest.put("persistenceSucceededTargets", List.of());
        manifest.put("persistenceIncompleteTargets", List.of());
        manifest.put("persistenceFailureIsolationApplied", false);
        manifest.put("featureFlagProperty", "graphdb.manual-learning.enabled");
        manifest.put("featureFlagEnv", "GRAPHDB_MANUAL_LEARNING_ENABLED");
        manifest.put("backendConfigPrefix", "retrieval.kg.neo4j");
        manifest.put("backendEnabledEnv", "RETRIEVAL_KG_NEO4J_ENABLED");
        manifest.put("backendUriEnv", BACKEND_URI_ENV);
        manifest.put("backendUserEnv", BACKEND_USER_ENV);
        manifest.put("backendPasswordEnv", BACKEND_PASSWORD_ENV);
        manifest.put("backendDatabaseEnv", List.of("RETRIEVAL_KG_NEO4J_DATABASE", "NEO4J_DATABASE"));
        manifest.put("backendTimeoutEnv", List.of("RETRIEVAL_KG_NEO4J_TIMEOUT_MS", "NEO4J_TIMEOUT_MS"));
        manifest.put("nonDryRunRequiresReachableBolt", true);
        manifest.put("nonDryRunRequiresNonPlaceholderCredentials", true);
        manifest.put("nonDryRunReadinessCommand",
                "powershell -NoProfile -ExecutionPolicy Bypass -File .\\scripts\\smoke_graphdb_manual_learning.ps1 -ReadinessOnly");
        manifest.put("nonDryRunSmokeCommand",
                "powershell -NoProfile -ExecutionPolicy Bypass -File .\\scripts\\smoke_graphdb_manual_learning.ps1 -NonDryRun");
        manifest.put("nonDryRunReadinessEvidenceFields", NON_DRY_RUN_READINESS_EVIDENCE_FIELDS);
        manifest.put("nonDryRunReadinessFailureClasses", NON_DRY_RUN_READINESS_FAILURE_CLASSES);
        manifest.put("nonDryRunLiveProofStages", NON_DRY_RUN_LIVE_PROOF_STAGES);
        manifest.put("nonDryRunReadinessRawSecretsIncluded", false);
        manifest.put("liveWriteReadProofRequired", true);
        manifest.put("operatorDiagnosticsRedacted", true);
        manifest.put("writeBoundary", LANE);
        manifest.put("readBoundary", LANE);
        manifest.put("vectorWriteBoundary", "VectorStoreService.enqueue");
        manifest.put("vectorWriteMode", "buffered_enqueue");
        manifest.put("vectorSessionIdMode", "hash_only_namespace");
        manifest.put("vectorRawSessionIdIncluded", false);
        manifest.put("vectorPayloadRawTextRequired", true);
        manifest.put("vectorRawTextMetadataIncluded", false);
        manifest.put("neo4jWriteBoundary", "Neo4jKgChunkWriter.writeChunks");
        manifest.put("neo4jReadBoundary", "Neo4jKgChunkWriter.readManualEvidence");
        manifest.put("neo4jChunkUpsertScope", "KgChunkNode.sessionHash_textHash_ingestLane");
        manifest.put("neo4jManualEvidenceScope", "ingestLane=graphdb_manual_learning");
        manifest.put("neo4jManualRelationScope", "RELATED_TO.source=graphdb_manual_learning");
        manifest.put("neo4jRawTextPersisted", false);
        manifest.put("neo4jRawSessionIdPersisted", false);
        manifest.put("neo4jRawEntityValuesReturned", false);
        manifest.put("brainStateFallbackCoupled", false);
        manifest.put("queryTimeRetrievalCoupled", false);
        manifest.put("queryTimeAnchorMapCoupled", false);
        manifest.put("rawTextIncluded", false);
        manifest.put("rawIdentifiersIncluded", false);
        manifest.put("rawSecretsIncluded", false);
        manifest.put("adminBoundary", "/api/admin/graph");
        manifest.put("securityRole", "ROLE_ADMIN");
        manifest.put("adminTokenGuarded", true);
        manifest.put("acceptedTokenHeaders", ACCEPTED_TOKEN_HEADERS);
        manifest.put("adminTokenHeaderAccepted", true);
        manifest.put("ownerTokenHeaderAccepted", true);
        manifest.put("authTokenValuesIncluded", false);
        manifest.put("authGuardBoundary", "AdminTokenGuardFilter+ROLE_ADMIN");
        manifest.put("csrfExempt", true);
        manifest.put("routes", ADMIN_ROUTES);
        return manifest;
    }

    private String simultaneousIngestDisabledReason() {
        boolean vector = properties.isVectorEnabled();
        boolean neo4j = properties.isNeo4jEnabled();
        if (vector && neo4j) {
            return "";
        }
        if (!vector && !neo4j) {
            return "vector_and_neo4j_disabled";
        }
        return vector ? "neo4j_disabled" : "vector_disabled";
    }

    private void enrichBackendReadiness(Map<String, Object> manifest, Map<String, Object> graphDb) {
        boolean backendEnabled = booleanValue(graphDb == null ? null : graphDb.get("enabled"), false);
        String backendDisabledReason = safe(graphDb == null ? null : graphDb.get("disabledReason"));
        manifest.put("neo4jBackendEnabled", backendEnabled);
        manifest.put("neo4jBackendDisabledReason", backendDisabledReason);
        manifest.put("neo4jEndpointHost", safe(graphDb == null ? null : graphDb.get("endpointHost")));

        boolean laneConfigured = properties.isEnabled() && properties.isVectorEnabled() && properties.isNeo4jEnabled();
        boolean backendConfigured = laneConfigured && backendEnabled;
        manifest.put("simultaneousIngestBackendConfigured", backendConfigured);
        manifest.put("nonDryRunLiveProofStatus", backendConfigured ? "required_unverified" : "blocked");
        manifest.put("nonDryRunLiveProofBlockedReason", backendConfigured
                ? ""
                : nonDryRunLiveProofBlockedReason(backendEnabled, backendDisabledReason));
    }

    private String nonDryRunLiveProofBlockedReason(boolean backendEnabled, String backendDisabledReason) {
        if (!properties.isEnabled()) {
            return "route_disabled";
        }
        String laneReason = simultaneousIngestDisabledReason();
        if (StringUtils.hasText(laneReason)) {
            return laneReason;
        }
        if (!backendEnabled) {
            return "neo4j_backend_disabled"
                    + (StringUtils.hasText(backendDisabledReason) ? ":" + backendDisabledReason : "");
        }
        return "";
    }

    private void attachRouteState(Map<String, Object> manifest, boolean dryRunRoute) {
        String routeStatus = statusRouteState(manifest, dryRunRoute);
        manifest.put("routeStatus", routeStatus);
        manifest.put("routeDisabledReason", statusDisabledReason(routeStatus, manifest));
    }

    private String statusRouteState(Map<String, Object> manifest, boolean dryRunRoute) {
        if (!properties.isEnabled()) {
            return "disabled";
        }
        if (dryRunRoute) {
            return "dry_run_ready";
        }
        String liveProof = safe(manifest.get("nonDryRunLiveProofStatus"));
        if ("required_unverified".equals(liveProof)) {
            return "ready_unverified";
        }
        if ("blocked".equals(liveProof)) {
            return "blocked";
        }
        return "ready";
    }

    private String statusDisabledReason(String status, Map<String, Object> manifest) {
        if ("disabled".equals(status)) {
            return "route_disabled";
        }
        if ("blocked".equals(status)) {
            String reason = safe(manifest.get("nonDryRunLiveProofBlockedReason"));
            return StringUtils.hasText(reason) ? reason : "non_dry_run_live_proof_blocked";
        }
        if ("ready_unverified".equals(status)) {
            return "non_dry_run_live_proof_required";
        }
        return "";
    }

    private boolean effectiveDryRun(Boolean dryRun) {
        return dryRun == null ? properties.isDryRunDefault() : dryRun;
    }

    private String bounded(String text) {
        String safe = text == null ? "" : text;
        int max = properties.getMaxTextChars();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<Map<String, Object>> communities(Map<String, Object> evidence) {
        Map<String, CommunityAccumulator> grouped = new LinkedHashMap<>();
        for (Map<String, Object> candidate : candidates(evidence)) {
            String textHash = hashToken(candidate.get("textHash"));
            String chunkHash = candidateChunkHash(candidate);
            for (String entityHash : entityHashes(candidate)) {
                CommunityAccumulator community = grouped.computeIfAbsent(entityHash, CommunityAccumulator::new);
                community.chunkHashes.add(chunkHash);
                if (StringUtils.hasText(textHash)) {
                    community.textHashes.add(textHash);
                }
                community.entityHashes.add(entityHash);
                for (Map<String, Object> hop : mapList(candidate.get("hops"))) {
                    if (!isManualRelationHop(hop)) {
                        continue;
                    }
                    community.hopHashes.add(hash12(entityHash + "|" + safeCode(hop.get("kind")) + "|"
                            + hopTargetHash(hop) + "|" + hopConnectorHash(hop) + "|" + hopRelationSource(hop)));
                }
            }
        }
        return grouped.values().stream()
                .sorted(Comparator.comparingInt(CommunityAccumulator::score).reversed()
                        .thenComparing(CommunityAccumulator::communityId))
                .limit(12)
                .map(CommunityAccumulator::toMap)
                .toList();
    }

    private static List<Map<String, Object>> multiHopEvidence(Map<String, Object> evidence) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> candidate : candidates(evidence)) {
            String chunkHash = candidateChunkHash(candidate);
            for (Map<String, Object> hop : mapList(candidate.get("hops"))) {
                if (!isManualRelationHop(hop)) {
                    continue;
                }
                String targetHash = hopTargetHash(hop);
                String connectorHash = hopConnectorHash(hop);
                String pathHash = hash12(chunkHash + "|" + safeCode(hop.get("kind")) + "|"
                        + targetHash + "|" + connectorHash + "|" + hopRelationSource(hop));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chunkHash", chunkHash);
                item.put("pathHash", pathHash);
                item.put("kind", safeCode(hop.get("kind")));
                item.put("targetHash", targetHash);
                item.put("connectorHash", connectorHash);
                item.put("relationSource", hopRelationSource(hop));
                out.add(Map.copyOf(item));
            }
        }
        return out.stream()
                .sorted(Comparator.comparing(item -> safe(item.get("pathHash"))))
                .limit(24)
                .toList();
    }

    private static String candidateChunkHash(Map<String, Object> candidate) {
        String direct = hashToken(candidate.get("chunkHash"));
        return StringUtils.hasText(direct) ? direct : hash12(safe(candidate.get("chunkId")));
    }

    private static List<Map<String, Object>> candidates(Map<String, Object> evidence) {
        return mapList(evidence == null ? null : evidence.get("candidates"));
    }

    private static List<String> entityHashes(Map<String, Object> candidate) {
        return stringList(candidate.get("entityHashes")).stream()
                .map(GraphDbManualLearningService::hashToken)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private static String hopTargetHash(Map<String, Object> hop) {
        return hashToken(hop.get("targetHash"));
    }

    private static String hopConnectorHash(Map<String, Object> hop) {
        String connector = hashToken(hop.get("connectorHash"));
        return StringUtils.hasText(connector) ? connector : hashToken(hop.get("pathHash"));
    }

    private static String hopRelationSource(Map<String, Object> hop) {
        String source = safeCode(hop.get("relationSource"));
        return LANE.equals(source) ? LANE : source;
    }

    private static boolean isManualRelationHop(Map<String, Object> hop) {
        return LANE.equals(hopRelationSource(hop));
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(GraphDbManualLearningService::safe)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private static List<String> codeList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(GraphDbManualLearningService::safeCode)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String raw && StringUtils.hasText(raw)) {
            return Boolean.parseBoolean(raw.trim());
        }
        return fallback;
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> safeMap = new LinkedHashMap<>();
                raw.forEach((key, mapValue) -> safeMap.put(safe(key), mapValue));
                out.add(Map.copyOf(safeMap));
            }
        }
        return List.copyOf(out);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isFinite(numeric)) {
                return Math.max(0, number.intValue());
            }
            traceSuppressed("graphDb.manual.intValue", new NumberFormatException("non_finite"));
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(safe(value)));
        } catch (NumberFormatException ignored) {
            traceSuppressed("graphDb.manual.intValue", ignored);
            return 0;
        }
    }

    private static String hash12(String value) {
        String safe = value == null ? "" : value;
        return DigestUtils.sha256Hex(safe).substring(0, 12);
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(ignored);
        TraceStore.put("graphdb.manual.suppressed.stage", safeStage);
        TraceStore.put("graphdb.manual.suppressed.errorType", safeErrorType);
        TraceStore.put("graphdb.manual.suppressed." + safeStage, true);
        TraceStore.put("graphdb.manual.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(Throwable ignored) {
        if (ignored instanceof NumberFormatException) {
            return "invalid_number";
        }
        return ignored == null ? "unknown" : SafeRedactor.traceLabelOrFallback(ignored.getClass().getSimpleName(), "unknown");
    }

    private static String hashToken(Object value) {
        String raw = safe(value).trim();
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String lower = raw.toLowerCase();
        if (lower.matches("[a-f0-9]{12,64}")) {
            return lower;
        }
        return hash12(raw);
    }

    private static String failureClass(Exception ex) {
        if (ex == null) {
            return "";
        }
        String className = ex.getClass().getSimpleName();
        String name = safe(className).toLowerCase(Locale.ROOT);
        String message = safe(ex.getMessage()).toLowerCase(Locale.ROOT);
        if (ex instanceof CancellationException || ex instanceof InterruptedException
                || name.contains("cancel")
                || name.contains("interrupt")
                || message.contains("cancelled")
                || message.contains("canceled")
                || message.contains("interrupted")) {
            return "cancelled";
        }
        return className;
    }

    private static String safeCode(Object value) {
        String raw = safe(value).trim();
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("secret")
                || lower.contains("token")
                || lower.contains("authorization")
                || lower.contains("bearer")
                || lower.startsWith("sk-")) {
            return "redacted";
        }
        if (!raw.matches("[A-Za-z0-9_.:-]{1,120}")) {
            return "redacted";
        }
        return raw;
    }

    private static final class CommunityAccumulator {
        private final String communityId;
        private final List<String> chunkHashes = new ArrayList<>();
        private final List<String> textHashes = new ArrayList<>();
        private final List<String> entityHashes = new ArrayList<>();
        private final List<String> hopHashes = new ArrayList<>();

        private CommunityAccumulator(String entityHash) {
            this.communityId = "community:" + entityHash;
        }

        private int score() {
            return distinctCount(chunkHashes) + distinctCount(entityHashes) + distinctCount(hopHashes);
        }

        private String communityId() {
            return communityId;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("communityId", communityId);
            out.put("chunkCount", distinctCount(chunkHashes));
            out.put("entityCount", distinctCount(entityHashes));
            out.put("hopCount", distinctCount(hopHashes));
            out.put("chunkHashes", distinct(chunkHashes, 8));
            out.put("textHashes", distinct(textHashes, 8));
            out.put("entityHashes", distinct(entityHashes, 8));
            out.put("hopHashes", distinct(hopHashes, 8));
            return Map.copyOf(out);
        }

        private static int distinctCount(List<String> values) {
            return distinct(values, Integer.MAX_VALUE).size();
        }

        private static List<String> distinct(List<String> values, int limit) {
            return values.stream()
                    .filter(StringUtils::hasText)
                    .distinct()
                    .limit(limit)
                    .toList();
        }
    }

    public record LearnReport(
            boolean enabled,
            String lane,
            String status,
            String disabledReason,
            boolean dryRun,
            @JsonIgnore
            String sessionId,
            int chunkCount,
            int entityCount,
            int relationCount,
            int neo4jWriteCount,
            String textHash,
            Map<String, Object> manifest,
            Map<String, Object> graphDb) {

        public LearnReport {
            manifest = manifest == null ? Map.of() : Map.copyOf(manifest);
            graphDb = graphDb == null ? Map.of() : Map.copyOf(graphDb);
        }

        @JsonProperty("sessionHash")
        public String sessionHash() {
            return hash12(sessionId);
        }

        @JsonProperty("rawIdentifiersIncluded")
        public boolean rawIdentifiersIncluded() {
            return false;
        }

        @Override
        public String toString() {
            return "LearnReport[enabled=" + enabled
                    + ", lane=" + lane
                    + ", status=" + status
                    + ", disabledReason=" + disabledReason
                    + ", dryRun=" + dryRun
                    + ", sessionHash=" + sessionHash()
                    + ", chunkCount=" + chunkCount
                    + ", entityCount=" + entityCount
                    + ", relationCount=" + relationCount
                    + ", neo4jWriteCount=" + neo4jWriteCount
                    + ", textHash=" + textHash
                    + ", manifest=" + manifest
                    + ", graphDb=" + graphDb
                    + "]";
        }

        LearnReport withManifest(Map<String, Object> manifest) {
            return new LearnReport(enabled, lane, status, disabledReason, dryRun, sessionId, chunkCount, entityCount,
                    relationCount, neo4jWriteCount, textHash, manifest, graphDb);
        }

        LearnReport withManifestAndGraphDb(Map<String, Object> manifest, Map<String, Object> graphDb) {
            return new LearnReport(enabled, lane, status, disabledReason, dryRun, sessionId, chunkCount, entityCount,
                    relationCount, neo4jWriteCount, textHash, manifest, graphDb);
        }
    }
}
