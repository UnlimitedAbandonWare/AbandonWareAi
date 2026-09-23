package ai.abandonware.nova.orch.uaw;

import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.example.lms.uaw.autolearn.LearningSampleValidationMetadata;
import com.example.lms.uaw.autolearn.LearningSampleValidationMetadataBuilder;
import com.example.lms.uaw.autolearn.UawAutolearnQualityTracker;
import com.example.lms.uaw.autolearn.UawDatasetWriter;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * 내부용 데이터셋 적재 API (Nova Overlay).
 *
 * - POST /internal/dataset/rag
 * - uaw.dataset-api.enabled=true 일 때만 노출됩니다(오토컨피그에서 @Bean으로 등록).
 * - uaw.dataset-api.key 값이 설정되어 있으면, 헤더 X-Internal-Key가 일치해야 합니다.
 */
@Slf4j
@RestController
@RequestMapping("/internal/dataset")
public class InternalDatasetApiController {

    private static final String DISABLED_REASON_MISSING_INTERNAL_KEY = "missing_internal_key";

    private final UawDatasetWriter writer;
    private final UawAutolearnProperties autolearnProps;
    private final LearningSampleValidationMetadataBuilder validationMetadataBuilder;
    private final UawAutolearnQualityTracker qualityTracker;
    private final String requiredKey;
    private final String disabledReason;
    private final String configuredDatasetPath;

    public InternalDatasetApiController(UawDatasetWriter writer,
                                        UawAutolearnProperties autolearnProps,
                                        LearningSampleValidationMetadataBuilder validationMetadataBuilder,
                                        UawAutolearnQualityTracker qualityTracker,
                                        Environment env) {
        this.writer = writer;
        this.autolearnProps = autolearnProps == null ? new UawAutolearnProperties() : autolearnProps;
        this.validationMetadataBuilder = validationMetadataBuilder == null
                ? new LearningSampleValidationMetadataBuilder(this.autolearnProps)
                : validationMetadataBuilder;
        this.qualityTracker = qualityTracker == null
                ? new UawAutolearnQualityTracker(this.autolearnProps, null, null)
                : qualityTracker;
        String configuredKey = env == null ? "" : env.getProperty("uaw.dataset-api.key", "");
        this.requiredKey = trimToEmpty(configuredKey);
        this.disabledReason = ConfigValueGuards.isMissing(configuredKey) ? DISABLED_REASON_MISSING_INTERNAL_KEY : "";
        this.configuredDatasetPath = resolveConfiguredDatasetPath(env, this.autolearnProps);
    }

    @PostMapping("/rag")
    public ResponseEntity<Map<String, Object>> appendRag(
            @RequestBody AppendRagRequest req,
            @RequestHeader(value = "X-Internal-Key", required = false) String key
    ) {
        if (!isBlank(disabledReason)) {
            log.warn("[AWX][dataset-api] status=disabled hasKey=false disabledReason={}", disabledReason);
            Map<String, Object> body = statusBody("disabled", false, disabledReason, configuredDatasetPath, "");
            traceDatasetApi(body);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }

        if (!requiredKey.equals(trimToEmpty(key))) {
            Map<String, Object> body = statusBody("rejected", false, "unauthorized", configuredDatasetPath, "");
            traceDatasetApi(body);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }

        if (req == null || isBlank(req.getQuestion()) || isBlank(req.getAnswer())) {
            Map<String, Object> body = statusBody("rejected", false, "invalid_request", configuredDatasetPath, "");
            traceDatasetApi(body);
            return ResponseEntity.badRequest().body(body);
        }

        String datasetName = firstNonBlank(
                req.getDatasetName(),
                (autolearnProps != null && autolearnProps.getDataset() != null) ? autolearnProps.getDataset().getName() : null,
                "manual"
        );

        String datasetPath = configuredDatasetPath;
        if (!isBlank(req.getDatasetPath()) && !sameDatasetPath(req.getDatasetPath(), datasetPath)) {
            Map<String, Object> body = statusBody("rejected", false, "dataset_path_not_allowed",
                    datasetPath, req.getSessionId());
            traceDatasetApi(body);
            log.warn("[AWX][dataset-api] status=rejected disabledReason=dataset_path_not_allowed datasetFileHash={} datasetFileLength={} datasetPathHash={} requestPathHash={}",
                    body.get("datasetFileHash"),
                    body.get("datasetFileLength"),
                    body.get("datasetPathHash"),
                    hashOrEmpty(req.getDatasetPath()));
            return ResponseEntity.badRequest().body(body);
        }

        int evidenceCount = (req.getEvidenceCount() != null)
                ? req.getEvidenceCount()
                : (autolearnProps != null ? autolearnProps.getMinEvidenceCount() : 0);
        if (evidenceCount < 0) {
            evidenceCount = 0;
        }

        int afterFilterCount = req.getAfterFilterCount() != null ? req.getAfterFilterCount() : evidenceCount;
        if (afterFilterCount < 0) {
            afterFilterCount = 0;
        }

        double contextDiversity = req.getContextDiversity() != null ? clamp01(req.getContextDiversity()) : 0.0d;
        String requestDisabledReason = reasonCode(req.getDisabledReason());
        boolean finalGate = req.getFinalGate() != null
                ? req.getFinalGate()
                : inferFinalGate(evidenceCount, contextDiversity, requestDisabledReason);

        String model = firstNonBlank(req.getModel(), "internal-api");
        String sessionId = firstNonBlank(req.getSessionId(), "internal");
        String provider = firstNonBlank(req.getProvider(), "internal-api");
        String branch = firstNonBlank(req.getBranch(), "internal_dataset_api");

        File file = new File(datasetPath);
        File parent = file.getParentFile();
        if (parent != null) {
            // Best-effort: ensure directory exists.
            parent.mkdirs();
        }

        String question = req.getQuestion().trim();
        String answer = req.getAnswer().trim();
        LearningSampleValidationMetadata validation = validationMetadataBuilder.build(
                question,
                answer,
                model,
                evidenceCount,
                afterFilterCount,
                finalGate,
                contextDiversity,
                requestDisabledReason);
        boolean providerDisabled = !requestDisabledReason.isBlank();
        validation = qualityTracker.project(validation, providerDisabled, finalGate);

        UawDatasetWriter.TrainingMetadata metadata = new UawDatasetWriter.TrainingMetadata(
                branch,
                provider,
                requestDisabledReason,
                afterFilterCount,
                finalGate,
                contextDiversity,
                validation);

        boolean accepted = false;
        if (validation.accepted()) {
            accepted = writer.append(
                    file,
                    datasetName,
                    question,
                    answer,
                    model,
                    evidenceCount,
                    sessionId,
                    metadata
            );
        }
        qualityTracker.recordSample(validation, sessionId, sampleHash(question, answer, model),
                providerDisabled, finalGate, accepted);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", accepted ? "accepted" : "rejected");
        resp.put("accepted", accepted);
        String rejectReason = firstRejectReason(validation, "writer_rejected");
        if (!accepted) {
            resp.put("disabledReason", rejectReason);
        }
        putDatasetResponseDiagnostics(resp, datasetPath);
        putSessionResponseDiagnostics(resp, sessionId);
        resp.put("validationDecision", validation.accepted() ? "accepted" : "rejected");
        resp.put("rejectReasons", validation.rejectReasons());

        Map<String, Object> trace = new LinkedHashMap<>(resp);
        trace.put("datasetName", datasetName);
        trace.put("evidenceCount", evidenceCount);
        trace.put("afterFilterCount", afterFilterCount);
        trace.put("finalGate", finalGate);
        trace.put("contextDiversity", contextDiversity);
        trace.put("model", model);
        putDatasetDiagnostics(trace, datasetPath);
        putSessionDiagnostics(trace, sessionId);
        traceDatasetApi(trace);
        log.info("[AWX][dataset-api] accepted={} status={} disabledReason={} datasetFileHash={} datasetFileLength={} datasetPathHash={} hasSessionId={} sessionHash={} evidenceCount={} afterFilterCount={} finalGate={} validationDecision={}",
                accepted,
                resp.get("status"),
                resp.getOrDefault("disabledReason", ""),
                resp.get("datasetFileHash"),
                resp.get("datasetFileLength"),
                resp.get("datasetPathHash"),
                trace.get("hasSessionId"),
                resp.get("sessionHash"),
                evidenceCount,
                afterFilterCount,
                finalGate,
                resp.get("validationDecision"));
        return ResponseEntity.status(accepted ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY).body(resp);
    }

    @Data
    public static class AppendRagRequest {
        private String question;
        private String answer;
        private Integer evidenceCount;
        private String model;
        private String datasetName;
        private String datasetPath;
        private String sessionId;
        private Integer afterFilterCount;
        private Double contextDiversity;
        private Boolean finalGate;
        private String provider;
        private String disabledReason;
        private String branch;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (!isBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static Map<String, Object> statusBody(String status,
                                                  boolean accepted,
                                                  String disabledReason,
                                                  String datasetPath,
                                                  String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("accepted", accepted);
        body.put("disabledReason", disabledReason);
        putDatasetResponseDiagnostics(body, datasetPath);
        putSessionResponseDiagnostics(body, sessionId);
        body.put("validationDecision", "");
        body.put("rejectReasons", List.of());
        return body;
    }

    private void traceDatasetApi(Map<String, Object> body) {
        if (body == null) {
            return;
        }
        try {
            TraceStore.put("uaw.dataset-api.status", traceLabelCode(body.getOrDefault("status", ""), ""));
            TraceStore.put("uaw.dataset-api.accepted", body.getOrDefault("accepted", false));
            TraceStore.put("uaw.dataset-api.disabledReason", reasonCode(String.valueOf(body.getOrDefault("disabledReason", ""))));
            traceDatasetFileDiagnostics(body);
            TraceStore.put("uaw.dataset-api.datasetPathHash", body.getOrDefault("datasetPathHash", ""));
            TraceStore.put("uaw.dataset-api.hasSessionId", body.getOrDefault("hasSessionId", false));
            TraceStore.put("uaw.dataset-api.sessionHash", body.getOrDefault("sessionHash", ""));
            TraceStore.put("uaw.dataset-api.validationDecision", traceLabelCode(body.getOrDefault("validationDecision", ""), ""));
            TraceStore.put("uaw.dataset-api.rejectReasons", safeRejectReasons(body.getOrDefault("rejectReasons", List.of())));
            TraceStore.put("uaw.dataset-api.evidenceCount", body.getOrDefault("evidenceCount", 0));
            TraceStore.put("uaw.dataset-api.afterFilterCount", body.getOrDefault("afterFilterCount", 0));
            TraceStore.put("uaw.dataset-api.finalGate", body.getOrDefault("finalGate", false));
        } catch (Throwable e) {
            traceSuppressed("traceDatasetApi", e);
            // Diagnostics must not affect the write path.
        }
    }

    private static void putDatasetDiagnostics(Map<String, Object> data, String datasetPath) {
        String path = trimToEmpty(datasetPath);
        data.put("hasDatasetPath", !path.isEmpty());
        putDatasetFileDiagnostics(data, path);
        data.put("datasetPathHash", hashOrEmpty(path));
    }

    private static void putDatasetResponseDiagnostics(Map<String, Object> data, String datasetPath) {
        String path = trimToEmpty(datasetPath);
        putDatasetFileDiagnostics(data, path);
        data.put("datasetPathHash", hashOrEmpty(path));
    }

    private static void putDatasetFileDiagnostics(Map<String, Object> data, String datasetPath) {
        String fileName = datasetFileName(datasetPath);
        data.put("datasetFileHash", fileName.isEmpty() ? "" : hashOrEmpty(fileName));
        data.put("datasetFileLength", fileName.length());
    }

    private static void putSessionDiagnostics(Map<String, Object> data, String sessionId) {
        String sid = trimToEmpty(sessionId);
        data.put("hasSessionId", !sid.isEmpty());
        data.put("sessionHash", hashOrEmpty(sid));
    }

    private static void putSessionResponseDiagnostics(Map<String, Object> data, String sessionId) {
        data.put("sessionHash", hashOrEmpty(trimToEmpty(sessionId)));
    }

    private static String datasetFileName(String path) {
        String p = trimToEmpty(path).replace('\\', '/');
        if (p.isEmpty()) {
            return "";
        }
        int idx = p.lastIndexOf('/');
        return idx >= 0 && idx + 1 < p.length() ? p.substring(idx + 1) : p;
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }

    private static void traceDatasetFileDiagnostics(Object raw) {
        TraceStore.put("uaw.dataset-api.datasetFileHash", datasetFileHash(raw));
        TraceStore.put("uaw.dataset-api.datasetFileLength", datasetFileLength(raw));
    }

    private static void traceDatasetFileDiagnostics(Map<String, Object> body) {
        if (body == null) {
            traceDatasetFileDiagnostics("");
            return;
        }
        if (body.containsKey("datasetFileHash") || body.containsKey("datasetFileLength")) {
            TraceStore.put("uaw.dataset-api.datasetFileHash", body.getOrDefault("datasetFileHash", ""));
            TraceStore.put("uaw.dataset-api.datasetFileLength", body.getOrDefault("datasetFileLength", 0));
            return;
        }
        traceDatasetFileDiagnostics(body.getOrDefault("datasetFile", ""));
    }

    private static String datasetFileHash(Object raw) {
        String value = datasetFileValue(raw);
        return value.isEmpty() ? "" : hashOrEmpty(value);
    }

    private static int datasetFileLength(Object raw) {
        return datasetFileValue(raw).length();
    }

    private static String datasetFileValue(Object raw) {
        return raw == null ? "" : trimToEmpty(String.valueOf(raw));
    }

    private static List<String> safeRejectReasons(Object raw) {
        if (raw instanceof Iterable<?> rows) {
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            for (Object row : rows) {
                out.add(SafeRedactor.safeMessage(String.valueOf(row), 120));
            }
            return List.copyOf(out);
        }
        if (raw == null) {
            return List.of();
        }
        return List.of(SafeRedactor.safeMessage(String.valueOf(raw), 120));
    }

    private boolean inferFinalGate(int evidenceCount, double contextDiversity, String requestDisabledReason) {
        if (!requestDisabledReason.isBlank()) {
            return false;
        }
        int minEvidence = autolearnProps == null ? 0 : Math.max(0, autolearnProps.getMinEvidenceCount());
        double minDiversity = autolearnProps == null ? 0.0d : clamp01(autolearnProps.getMinContextDiversity());
        return evidenceCount >= minEvidence && contextDiversity >= minDiversity;
    }

    private static String firstRejectReason(LearningSampleValidationMetadata validation, String fallback) {
        if (validation != null) {
            for (String reason : validation.rejectReasons()) {
                String r = reasonCode(reason);
                if (!r.isBlank()) {
                    return r;
                }
            }
        }
        return reasonCode(fallback);
    }

    private static String sampleHash(String question, String answer, String model) {
        return hashOrEmpty(trimToEmpty(question) + "|" + trimToEmpty(answer) + "|" + trimToEmpty(model));
    }

    private static String reasonCode(String value) {
        String raw = trimToEmpty(value);
        if (raw.isEmpty()) {
            return "";
        }
        if (containsSensitiveDiagnosticText(raw)) {
            return SafeRedactor.traceLabelOrFallback(raw, "redacted");
        }
        String v = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.:-]+", "_");
        while (v.startsWith("_")) {
            v = v.substring(1);
        }
        while (v.endsWith("_")) {
            v = v.substring(0, v.length() - 1);
        }
        return v.length() > 80 ? v.substring(0, 80) : v;
    }

    private static String traceLabelCode(Object value, String fallback) {
        return SafeRedactor.traceLabelOrFallback(value, fallback);
    }

    private static boolean containsSensitiveDiagnosticText(String value) {
        String text = trimToEmpty(value);
        if (text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("authorization")
                || lower.contains("owner-token")
                || lower.contains("owner_token")
                || lower.contains("ownertoken")
                || lower.contains("cookie")
                || lower.contains("secret=")
                || lower.contains("token=")
                || lower.contains("api_key=")
                || lower.contains("apikey=")
                || lower.contains("password=")
                || text.matches("(?i).*(sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}).*");
    }

    private static String resolveConfiguredDatasetPath(Environment env, UawAutolearnProperties props) {
        return firstNonBlank(
                env == null ? null : env.getProperty("uaw.autolearn.dataset.path"),
                env == null ? null : env.getProperty("autolearn.dataset.path"),
                env == null ? null : env.getProperty("dataset.train-file-path"),
                (props != null && props.getDataset() != null) ? props.getDataset().getPath() : null,
                "data/train_rag.jsonl"
        );
    }

    private static boolean sameDatasetPath(String requested, String configured) {
        String r = normalizePathForComparison(requested);
        String c = normalizePathForComparison(configured);
        return !r.isBlank() && r.equals(c);
    }

    private static String normalizePathForComparison(String value) {
        String v = trimToEmpty(value);
        if (v.isEmpty()) {
            return "";
        }
        try {
            return new File(v).getCanonicalFile().toPath().normalize().toString().replace('\\', '/');
        } catch (Exception e) {
            traceSuppressed("normalizePathForComparison", e);
            return new File(v).getAbsoluteFile().toPath().normalize().toString().replace('\\', '/');
        }
    }

    private static void traceSuppressed(String stage, Throwable e) {
        MDC.put("uaw.dataset-api.suppressed.stage", stage);
        MDC.put("uaw.dataset-api.suppressed.errorType",
                e == null ? "unknown" : e.getClass().getSimpleName());
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
