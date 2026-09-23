package com.example.lms.uaw.autolearn;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

@Component
public class UawDatasetWriter {

    private static final System.Logger LOG = System.getLogger(UawDatasetWriter.class.getName());
    private static final String TRACE_PREFIX = "uaw.autolearn.datasetWriter.";

    private final ObjectMapper om = new ObjectMapper();

    private final UawDatasetTrainingDataFilter trainingDataFilter;

    public UawDatasetWriter(UawDatasetTrainingDataFilter trainingDataFilter) {
        this.trainingDataFilter = trainingDataFilter;
    }

    public synchronized boolean append(File file,
                                       String datasetName,
                                       String question,
                                       String answer,
                                       String modelUsed,
                                       int evidenceCount,
                                       String sessionId) {
        return append(file, datasetName, question, answer, modelUsed, evidenceCount, sessionId,
                TrainingMetadata.empty());
    }

    public synchronized boolean append(File file,
                                       String datasetName,
                                       String question,
                                       String answer,
                                       String modelUsed,
                                       int evidenceCount,
                                       String sessionId,
                                       TrainingMetadata metadata) {
        String redactedQuestion = safeRedact(question);
        String redactedAnswer = safeRedact(answer);
        int redactionCount = redactionCount(question, redactedQuestion) + redactionCount(answer, redactedAnswer);

        if (file == null) {
            return reject("file_missing", redactedQuestion, redactedAnswer, modelUsed);
        }
        if (redactedAnswer.isBlank()) {
            return reject("blank_answer", redactedQuestion, redactedAnswer, modelUsed);
        }

        if (trainingDataFilter != null && trainingDataFilter.shouldExclude(redactedQuestion, redactedAnswer, modelUsed)) {
            return reject("training_filter_rejected", redactedQuestion, redactedAnswer, modelUsed);
        }

        TrainingMetadata meta = metadata == null ? TrainingMetadata.empty() : metadata;
        LearningSampleValidationMetadata validation = meta.validation() == null
                ? missingValidationMetadata()
                : meta.validation();
        if (!meta.finalGate()) {
            return reject("final_gate_failed", redactedQuestion, redactedAnswer, modelUsed);
        }
        String disabledReason = safeReason(meta.disabledReason());
        if (meta.disabledReason() != null && !meta.disabledReason().isBlank()) {
            return reject(disabledReason, redactedQuestion, redactedAnswer, modelUsed);
        }
        if (isMissingValidationMetadata(validation)) {
            return reject("missing_validation_metadata", redactedQuestion, redactedAnswer, modelUsed);
        }
        if (validation.needleRoi().needleSignalCandidate() && !validation.needleRoi().promoted()) {
            return reject("needle_roi_rejected", redactedQuestion, redactedAnswer, modelUsed);
        }
        if (!validation.accepted()) {
            return reject("validation_rejected", redactedQuestion, redactedAnswer, modelUsed);
        }
        if (validation.anomalies() != null && !validation.anomalies().flags().isEmpty()) {
            return reject("validation_anomaly", redactedQuestion, redactedAnswer, modelUsed);
        }
        LearningSampleValidationMetadata.Feedback feedback = validation.feedback();
        if (feedback != null && "QUARANTINE".equalsIgnoreCase(feedback.vectorDecision())) {
            return reject("vector_quarantine", redactedQuestion, redactedAnswer, modelUsed);
        }
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();

            ObjectNode n = om.createObjectNode();
            n.put("ts", Instant.now().toString());
            n.put("source", "uaw_autolearn");
            if (sessionId != null) n.put("sessionHash", SafeRedactor.hashValue(sessionId));
            if (datasetName != null) n.put("dataset", datasetName);

            n.put("question", redactedQuestion);
            n.put("answer", redactedAnswer);
            if (modelUsed != null) n.put("model", modelUsed);
            n.put("branch", meta.branch());
            n.put("provider", meta.provider());
            n.put("disabledReason", disabledReason);
            n.put("evidenceCount", evidenceCount);
            n.put("afterFilterCount", meta.afterFilterCount());
            n.put("finalGate", meta.finalGate());
            n.put("contextDiversity", meta.contextDiversity());
            n.put("redactionApplied", redactionCount > 0);
            n.put("redactionCount", redactionCount);
            n.set("validation", validationNode(validation));
            n.set("evaluationCriteria", stringArray(validation.evaluationCriteria()));
            n.put("id", sha1((datasetName == null ? "" : datasetName) + "|" + redactedQuestion + "|" + redactedAnswer));

            String line = om.writeValueAsString(n);
            appendLineLocked(file, line);
            traceSuccess(redactionCount, redactedQuestion, redactedAnswer, line.length());
            return true;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "[AWX][uaw][dataset] append failed path redacted");
            return reject("append_io_failed", redactedQuestion, redactedAnswer, modelUsed,
                    SafeRedactor.safeMessage(e.getMessage(), 240));
        }
    }

    public record TrainingMetadata(String branch,
                                   String provider,
                                   String disabledReason,
                                   int afterFilterCount,
                                   boolean finalGate,
                                   double contextDiversity,
                                   LearningSampleValidationMetadata validation) {
        public TrainingMetadata(String branch,
                                 String provider,
                                 String disabledReason,
                                 int afterFilterCount,
                                 boolean finalGate,
                                 double contextDiversity) {
            this(branch, provider, disabledReason, afterFilterCount, finalGate, contextDiversity,
                    missingValidationMetadata());
        }

        public static TrainingMetadata empty() {
            return new TrainingMetadata("uaw_autolearn", "local", "", 0, true, 1.0d,
                    missingValidationMetadata());
        }
    }

    private static void appendLineLocked(File file, String line) throws Exception {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
             FileLock ignored = channel.lock()) {
            channel.write(ByteBuffer.wrap(bytes));
        }
    }

    private static String safeRedact(String value) {
        String input = value == null ? "" : value;
        String redacted = SafeRedactor.redact(input);
        return redacted == null ? "" : redacted;
    }

    private static int redactionCount(String original, String redacted) {
        String a = original == null ? "" : original;
        String b = redacted == null ? "" : redacted;
        return a.equals(b) ? 0 : 1;
    }

    private static boolean reject(String reason, String question, String answer, String modelUsed) {
        return reject(reason, question, answer, modelUsed, null);
    }

    private static boolean reject(String reason, String question, String answer, String modelUsed, String detail) {
        traceFailure(reason, question, answer, modelUsed, detail);
        return false;
    }

    private static void traceFailure(String reason, String question, String answer, String modelUsed, String detail) {
        try {
            TraceStore.put(TRACE_PREFIX + "lastFailureReason", normalizeReason(reason));
            TraceStore.put(TRACE_PREFIX + "lastFailureDetail", safeFailureDetail(detail));
            TraceStore.put(TRACE_PREFIX + "lastQuestionHash", SafeRedactor.hashValue(question));
            TraceStore.put(TRACE_PREFIX + "lastAnswerHash", SafeRedactor.hashValue(answer));
            TraceStore.put(TRACE_PREFIX + "lastModelHash", SafeRedactor.hashValue(modelUsed));
            TraceStore.put(TRACE_PREFIX + "lastQuestionLength", question == null ? 0 : question.length());
            TraceStore.put(TRACE_PREFIX + "lastAnswerLength", answer == null ? 0 : answer.length());
        } catch (Exception ignore) {
            LOG.log(System.Logger.Level.DEBUG, "[AWX][uaw][dataset] failure trace skipped");
        }
    }

    private static void traceSuccess(int redactionCount, String question, String answer, int lineLength) {
        try {
            TraceStore.put(TRACE_PREFIX + "lastFailureReason", null);
            TraceStore.put(TRACE_PREFIX + "lastFailureDetail", null);
            TraceStore.put(TRACE_PREFIX + "lastRedactionCount", Math.max(0, redactionCount));
            TraceStore.put(TRACE_PREFIX + "lastQuestionHash", SafeRedactor.hashValue(question));
            TraceStore.put(TRACE_PREFIX + "lastAnswerHash", SafeRedactor.hashValue(answer));
            TraceStore.put(TRACE_PREFIX + "lastLineLength", Math.max(0, lineLength));
        } catch (Exception ignore) {
            LOG.log(System.Logger.Level.DEBUG, "[AWX][uaw][dataset] success trace skipped");
        }
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "writer_rejected";
        }
        String safe = safeReason(reason);
        return safe == null || safe.isBlank() ? "writer_rejected" : safe.trim();
    }

    private static String safeReason(String reason) {
        return SafeRedactor.traceLabelOrFallback(reason, "");
    }

    private static String safeFailureDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        return SafeRedactor.traceLabelOrFallback(detail, "");
    }

    private static LearningSampleValidationMetadata missingValidationMetadata() {
        return new LearningSampleValidationMetadata(
                "unknown",
                List.of(),
                0.0d,
                0.0d,
                0.0d,
                0.0d,
                new LearningSampleValidationMetadata.Requery(false, false),
                0.0d,
                0.0d,
                0.0d,
                List.of("missing_validation_metadata"),
                List.of("validation_metadata_required"),
                LearningSampleValidationMetadata.Thresholds.defaults(),
                LearningSampleValidationMetadata.Runtime.defaults(),
                LearningSampleValidationMetadata.Anomalies.none(),
                new LearningSampleValidationMetadata.Feedback(0.0d, "QUARANTINE"));
    }

    private static boolean isMissingValidationMetadata(LearningSampleValidationMetadata validation) {
        return validation != null
                && (validation.rejectReasons().contains("missing_validation_metadata")
                || validation.evaluationCriteria().contains("validation_metadata_required"));
    }

    private ObjectNode validationNode(LearningSampleValidationMetadata validation) {
        ObjectNode node = om.createObjectNode();
        node.put("accepted", validation.accepted());
        node.put("questionType", validation.questionType());
        node.set("selfAskLanes", stringArray(validation.selfAskLanes()));
        node.put("selfAskLaneCoverage", validation.selfAskLaneCoverage());
        node.put("refutabilityScore", validation.refutabilityScore());
        node.put("riskScore", validation.riskScore());
        node.put("causalNeedScore", validation.causalNeedScore());
        node.put("contradictionScore", validation.contradictionScore());
        node.put("contradictionCause", validation.contradictionCause());
        ObjectNode requery = om.createObjectNode();
        requery.put("required", validation.requery().required());
        requery.put("confirmed", validation.requery().confirmed());
        node.set("requery", requery);
        node.put("contaminationScore", validation.contaminationScore());
        node.put("legacyContextScore", validation.legacyContextScore());
        node.put("contextContaminationScore", validation.contextContaminationScore());
        node.put("sampleScore", validation.sampleScore());
        node.set("rejectReasons", stringArray(validation.rejectReasons()));
        node.set("thresholds", thresholdsNode(validation.thresholds()));
        node.set("runtime", runtimeNode(validation.runtime()));
        node.set("anomalies", anomaliesNode(validation.anomalies()));
        node.set("feedback", feedbackNode(validation.feedback()));
        node.set("needleRoi", needleRoiNode(validation.needleRoi()));
        return node;
    }

    private ObjectNode thresholdsNode(LearningSampleValidationMetadata.Thresholds thresholds) {
        LearningSampleValidationMetadata.Thresholds t = thresholds == null
                ? LearningSampleValidationMetadata.Thresholds.defaults()
                : thresholds;
        ObjectNode node = om.createObjectNode();
        node.put("sampleScoreMin", t.sampleScoreMin());
        node.put("contaminationMax", t.contaminationMax());
        node.put("contextContaminationMax", t.contextContaminationMax());
        node.put("contradictionMax", t.contradictionMax());
        node.put("requeryPenalty", t.requeryPenalty());
        node.put("mode", t.mode());
        return node;
    }

    private ObjectNode runtimeNode(LearningSampleValidationMetadata.Runtime runtime) {
        LearningSampleValidationMetadata.Runtime r = runtime == null
                ? LearningSampleValidationMetadata.Runtime.defaults()
                : runtime;
        ObjectNode node = om.createObjectNode();
        node.put("evidenceCount", r.evidenceCount());
        node.put("afterFilterCount", r.afterFilterCount());
        node.put("contextDiversity", r.contextDiversity());
        node.put("laneCoverage", r.laneCoverage());
        node.put("errorRateWindow", r.errorRateWindow());
        return node;
    }

    private ObjectNode anomaliesNode(LearningSampleValidationMetadata.Anomalies anomalies) {
        LearningSampleValidationMetadata.Anomalies a = anomalies == null
                ? LearningSampleValidationMetadata.Anomalies.none()
                : anomalies;
        ObjectNode node = om.createObjectNode();
        node.set("flags", stringArray(a.flags()));
        node.put("spike", a.spike());
        node.put("drift", a.drift());
        return node;
    }

    private ObjectNode feedbackNode(LearningSampleValidationMetadata.Feedback feedback) {
        LearningSampleValidationMetadata.Feedback f = feedback == null
                ? LearningSampleValidationMetadata.Feedback.none()
                : feedback;
        ObjectNode node = om.createObjectNode();
        node.put("cfvmReward", f.cfvmReward());
        node.put("vectorDecision", f.vectorDecision());
        return node;
    }

    private ObjectNode needleRoiNode(LearningSampleValidationMetadata.NeedleRoi needleRoi) {
        LearningSampleValidationMetadata.NeedleRoi n = needleRoi == null
                ? LearningSampleValidationMetadata.NeedleRoi.none()
                : needleRoi;
        ObjectNode node = om.createObjectNode();
        node.put("needleSignalCandidate", n.needleSignalCandidate());
        node.put("signalValueScore", n.signalValueScore());
        node.put("promoted", n.promoted());
        node.put("rejectReason", n.rejectReason());
        return node;
    }

    private ArrayNode stringArray(Iterable<String> values) {
        ArrayNode array = om.createArrayNode();
        if (values == null) {
            return array;
        }
        for (String value : values) {
            if (value != null) {
                array.add(value);
            }
        }
        return array;
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte v : b) sb.append(String.format("%02x", v));
            return sb.toString();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "[AWX][uaw][dataset] sample id hash fallback");
            return Integer.toHexString(s.hashCode());
        }
    }
}
