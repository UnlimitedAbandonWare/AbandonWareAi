package com.example.lms.service.rag.langgraph;

import com.example.lms.service.guard.ContextContaminationSignatures;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ContextContaminationAnalyzer {

    private static final String VERSION = "langgraph-contamination-v1";

    public LangGraphContaminationReport analyze(String runId,
                                                String threadIdHash,
                                                String queryHash,
                                                String graphMode,
                                                List<LangGraphNodeSnapshotRecorder.NodeSnapshot> snapshots,
                                                Map<String, Object> trace) {
        List<LangGraphContaminationReport.NodeReport> nodes = new ArrayList<>();
        if (snapshots != null) {
            for (LangGraphNodeSnapshotRecorder.NodeSnapshot snapshot : snapshots) {
                if (snapshot == null) {
                    continue;
                }
                nodes.add(analyzeNode(snapshot));
            }
        }
        LangGraphContaminationReport.ContaminationSummary summary = summarize(nodes);
        Map<String, Object> safeTrace = safeTrace(trace, nodes);
        safeTrace.put("schemaVersion", VERSION);
        return new LangGraphContaminationReport(
                runId,
                threadIdHash,
                queryHash,
                graphMode == null || graphMode.isBlank() ? "offline-replay" : graphMode,
                List.copyOf(nodes),
                summary,
                safeTrace);
    }

    private LangGraphContaminationReport.NodeReport analyzeNode(
            LangGraphNodeSnapshotRecorder.NodeSnapshot snapshot) {
        Map<String, Object> input = snapshot.inputContext() == null ? Map.of() : snapshot.inputContext();
        Map<String, Object> output = snapshot.outputContext() == null ? Map.of() : snapshot.outputContext();

        RiskSignals inRisk = inspect(input);
        RiskSignals outRisk = inspect(output);
        boolean memoryLeak = outRisk.memoryLeak && !inRisk.memoryLeak;
        boolean promptInjection = outRisk.promptInjection && !inRisk.promptInjection;
        boolean staleContext = outRisk.staleContext && !inRisk.staleContext;
        boolean privateChatTranscript = outRisk.privateChatTranscript && !inRisk.privateChatTranscript;

        Set<String> suspectFields = new LinkedHashSet<>();
        suspectFields.addAll(outRisk.suspectFields);
        if (!memoryLeak) {
            suspectFields.removeIf(s -> s.contains("memory"));
        }
        if (!promptInjection) {
            suspectFields.removeIf(s -> s.contains("promptInjection"));
        }
        if (!staleContext) {
            suspectFields.removeIf(s -> s.contains("stale"));
        }

        double score = clamp01(outRisk.score - (inRisk.score * 0.35d));
        if (memoryLeak) {
            score += 0.25d;
        }
        if (promptInjection) {
            score += 0.30d;
        }
        if (staleContext) {
            score += 0.18d;
        }
        if (privateChatTranscript) {
            score += 0.12d;
        }
        score = round3(clamp01(score));

        Map<String, Integer> sourceMix = sourceMix(output);
        List<String> actions = recommendedActions(
                memoryLeak, promptInjection, staleContext, privateChatTranscript, score, sourceMix);
        Map<String, String> snapshotIds = new LinkedHashMap<>();
        snapshotIds.put("input", snapshot.inputSnapshotId());
        snapshotIds.put("output", snapshot.outputSnapshotId());
        Map<String, String> fieldHashes = fieldHashes(outRisk, suspectFields);
        List<String> riskMarkers = riskMarkers(memoryLeak, promptInjection, staleContext, privateChatTranscript, score);
        return new LangGraphContaminationReport.NodeReport(
                snapshot.node(),
                snapshotIds,
                delta(input, output),
                score,
                sourceMix,
                memoryLeak,
                promptInjection,
                staleContext,
                List.copyOf(suspectFields),
                actions,
                contextSummary(input, output, sourceMix, fieldHashes.size(), riskMarkers),
                fieldHashes,
                riskMarkers);
    }

    private static RiskSignals inspect(Object value) {
        RiskSignals signals = new RiskSignals();
        inspectValue("$", value, signals);
        signals.score = clamp01(signals.score);
        return signals;
    }

    @SuppressWarnings("unchecked")
    private static void inspectValue(String path, Object value, RiskSignals signals) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                String child = path + "." + safeFieldLabel(entry.getKey());
                inspectValue(child, entry.getValue(), signals);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int i = 0;
            for (Object item : iterable) {
                inspectValue(path + "[" + i++ + "]", item, signals);
            }
            return;
        }
        String text = String.valueOf(value);
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "ignore previous", "ignore all previous", "system prompt",
                "developer message", "prompt injection", "bypass", "override instructions",
                "### instruction", "### instructions", "jailbreak")) {
            signals.promptInjection = true;
            signals.score += 0.55d;
            signals.suspectFields.add(path + ":promptInjection");
            signals.fieldHashes.put(path, SafeRedactor.hash12(text));
            signals.riskMarkers.add("promptInjection");
        }
        if (containsAny(lower, "session_memory", "session memory", "cross-session", "previous session",
                "other user's", "memory leak", "private memory", "conversation_memory")) {
            signals.memoryLeak = true;
            signals.score += 0.45d;
            signals.suspectFields.add(path + ":memory");
            signals.fieldHashes.put(path, SafeRedactor.hash12(text));
            signals.riskMarkers.add("memoryLeak");
        }
        if (containsAny(lower, "stale", "outdated", "expired", "old vector", "legacy context",
                "2023-only", "ageDays=900", "ageDays=730")) {
            signals.staleContext = true;
            signals.score += 0.32d;
            signals.suspectFields.add(path + ":stale");
            signals.fieldHashes.put(path, SafeRedactor.hash12(text));
            signals.riskMarkers.add("staleContext");
        }
        if (containsAny(lower, "contamination_risk", "context_contamination_threshold", "vector_quarantine")) {
            signals.score += 0.25d;
            signals.suspectFields.add(path + ":validation");
            signals.fieldHashes.put(path, SafeRedactor.hash12(text));
            signals.riskMarkers.add("validationSignal");
        }
        ContextContaminationSignatures.PrivateChatTranscriptSignals privateChat =
                ContextContaminationSignatures.inspectPrivateChatTranscript(text);
        if (privateChat.match()) {
            signals.privateChatTranscript = true;
            signals.score += 0.65d;
            signals.suspectFields.add(path + ":privateChatTranscript");
            signals.fieldHashes.put(path, SafeRedactor.hash12(text));
            signals.riskMarkers.add("privateChatTranscript");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> sourceMix(Map<String, Object> context) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Object docs = context == null ? null : context.get("docs");
        if (docs instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof Map<?, ?> map) {
                    Object source = map.get("source");
                    String key = safeSourceKey(source);
                    out.put(key, out.getOrDefault(key, 0) + 1);
                }
            }
        }
        Object explicit = context == null ? null : context.get("sourceMix");
        if (explicit instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry == null || entry.getKey() == null || !(entry.getValue() instanceof Number n)) {
                    continue;
                }
                String key = safeSourceKey(entry.getKey());
                if (!key.isBlank()) {
                    out.put(key, out.getOrDefault(key, 0) + n.intValue());
                }
            }
        }
        return out;
    }

    private static List<String> recommendedActions(boolean memoryLeak,
                                                   boolean promptInjection,
                                                   boolean staleContext,
                                                   boolean privateChatTranscript,
                                                   double score,
                                                   Map<String, Integer> sourceMix) {
        List<String> out = new ArrayList<>();
        if (privateChatTranscript) {
            out.add("QUARANTINE:privateChatTranscript");
            out.add("BLOCK:promptContext.memory");
        }
        if (promptInjection) {
            out.add("BLOCK:outputContext.docs.snippet");
            out.add("QUARANTINE:promptContext.web");
        }
        if (memoryLeak) {
            out.add("BLOCK:promptContext.memory");
            out.add("QUARANTINE:sessionMemory");
        }
        if (staleContext) {
            out.add("COMPRESS:promptContext.rag");
            out.add("QUARANTINE:staleVectorDocs");
        }
        if (score >= 0.50d && sourceMix != null && sourceMix.containsKey("WEB")) {
            out.add("COMPRESS:searchResults");
        }
        if (out.isEmpty()) {
            out.add("ALLOW");
        }
        return List.copyOf(new LinkedHashSet<>(out));
    }

    private static Map<String, Object> delta(Map<String, Object> input, Map<String, Object> output) {
        Set<String> inputKeys = input == null ? Set.of() : input.keySet();
        Set<String> outputKeys = output == null ? Set.of() : output.keySet();
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String key : outputKeys) {
            if (!inputKeys.contains(key)) {
                added.add(safeFieldLabel(key));
            } else if (!String.valueOf(input.get(key)).equals(String.valueOf(output.get(key)))) {
                changed.add(safeFieldLabel(key));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("addedKeys", added);
        out.put("changedKeys", changed);
        out.put("inputKeyCount", inputKeys.size());
        out.put("outputKeyCount", outputKeys.size());
        return out;
    }

    private static String safeFieldLabel(Object key) {
        String label = SafeRedactor.traceLabelOrFallback(key, "field");
        return label == null || label.isBlank() ? "field" : label;
    }

    private static String safeSourceKey(Object source) {
        if (source == null || String.valueOf(source).isBlank()) {
            return "UNKNOWN";
        }
        String label = SafeRedactor.traceLabelOrFallback(source, "UNKNOWN");
        return label == null || label.isBlank()
                ? "UNKNOWN"
                : label.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, Object> contextSummary(Map<String, Object> input,
                                                      Map<String, Object> output,
                                                      Map<String, Integer> sourceMix,
                                                      int fieldHashCount,
                                                      List<String> riskMarkers) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inputKeyCount", input == null ? 0 : input.size());
        out.put("outputKeyCount", output == null ? 0 : output.size());
        out.put("docCount", docCount(output));
        out.put("sourceMix", sourceMix == null ? Map.of() : sourceMix);
        out.put("fieldHashCount", fieldHashCount);
        out.put("riskMarkers", riskMarkers == null ? List.of() : riskMarkers);
        out.put("hasTraceStore", output != null && output.containsKey("traceStore"));
        out.put("hasDebug", output != null && output.containsKey("debug"));
        out.put("hasPrompt", output != null
                && (output.containsKey("promptHash") || Boolean.TRUE.equals(output.get("promptPresent"))));
        return out;
    }

    private static int docCount(Map<String, Object> context) {
        Object docs = context == null ? null : context.get("docs");
        if (!(docs instanceof Iterable<?> iterable)) {
            return 0;
        }
        int count = 0;
        for (Object ignored : iterable) {
            count++;
        }
        return count;
    }

    private static Map<String, String> fieldHashes(RiskSignals signals, Set<String> suspectFields) {
        if (signals == null || signals.fieldHashes.isEmpty() || suspectFields == null || suspectFields.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String suspect : suspectFields) {
            if (suspect == null || suspect.isBlank()) {
                continue;
            }
            String path = suspect;
            int sep = path.lastIndexOf(':');
            if (sep > 0) {
                path = path.substring(0, sep);
            }
            String hash = signals.fieldHashes.get(path);
            if (hash != null && !hash.isBlank()) {
                out.put(path, hash);
            }
        }
        return out;
    }

    private static List<String> riskMarkers(boolean memoryLeak,
                                            boolean promptInjection,
                                            boolean staleContext,
                                            boolean privateChatTranscript,
                                            double score) {
        List<String> out = new ArrayList<>();
        if (privateChatTranscript) {
            out.add("privateChatTranscript");
        }
        if (memoryLeak) {
            out.add("memoryLeak");
        }
        if (promptInjection) {
            out.add("promptInjection");
        }
        if (staleContext) {
            out.add("staleContext");
        }
        if (score >= 0.50d) {
            out.add("highScore");
        }
        if (out.isEmpty()) {
            out.add("clean");
        }
        return List.copyOf(new LinkedHashSet<>(out));
    }

    private static LangGraphContaminationReport.ContaminationSummary summarize(
            List<LangGraphContaminationReport.NodeReport> nodes) {
        LangGraphContaminationReport.NodeReport highest = null;
        for (LangGraphContaminationReport.NodeReport node : nodes) {
            if (node == null) {
                continue;
            }
            if (highest == null || node.contaminationScore() > highest.contaminationScore()) {
                highest = node;
            }
        }
        Map<String, List<String>> fieldActions = new LinkedHashMap<>();
        String likely = "none";
        double max = 0.0d;
        String nodeName = "none";
        if (highest != null) {
            nodeName = highest.node();
            max = highest.contaminationScore();
            likely = likelySource(highest);
            fieldActions.put(nodeName, highest.recommendedActions());
        }
        return new LangGraphContaminationReport.ContaminationSummary(nodeName, likely, max, fieldActions);
    }

    private static String likelySource(LangGraphContaminationReport.NodeReport node) {
        if (node.riskMarkers() != null && node.riskMarkers().contains("privateChatTranscript")) {
            return "private_chat_transcript";
        }
        if (node.memoryLeakFlag()) {
            return "session_memory";
        }
        if (node.promptInjectionFlag() && node.sourceMix().containsKey("WEB")) {
            return "search_results";
        }
        if (node.staleContextFlag() && node.sourceMix().containsKey("VECTOR")) {
            return "rag_vector";
        }
        if (node.promptInjectionFlag()) {
            return "prompt_builder";
        }
        if (node.staleContextFlag()) {
            return "stale_context";
        }
        return node.contaminationScore() > 0.0d ? "autolearn_or_trace" : "none";
    }

    private static Map<String, Object> safeTrace(Map<String, Object> trace,
                                                 List<LangGraphContaminationReport.NodeReport> nodes) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeCount", nodes == null ? 0 : nodes.size());
        List<Object> breadcrumbs = new ArrayList<>();
        if (trace != null) {
            Object events = trace.get("orch.events.v1");
            if (events instanceof Iterable<?> iterable) {
                for (Object event : iterable) {
                    breadcrumbs.add(SafeRedactor.diagnosticValue("orch.events.v1", event, 500));
                    if (breadcrumbs.size() >= 40) {
                        break;
                    }
                }
            }
            for (String key : List.of(
                    "learning.validation.contaminationSignals",
                    "learning.validation.contextContaminationScore",
                    "learning.feedback.vectorDecision",
                    "rag.eval.contaminationSignals",
                    "memory.recovery.count",
                    "prompt.builder.contaminationFlag")) {
                if (trace.containsKey(key)) {
                    out.put(key, SafeRedactor.diagnosticValue(key, trace.get(key), 500));
                }
            }
        }
        out.put("breadcrumbIds", breadcrumbs);
        return out;
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private static final class RiskSignals {
        private boolean memoryLeak;
        private boolean promptInjection;
        private boolean staleContext;
        private boolean privateChatTranscript;
        private double score;
        private final Set<String> suspectFields = new LinkedHashSet<>();
        private final Map<String, String> fieldHashes = new LinkedHashMap<>();
        private final Set<String> riskMarkers = new LinkedHashSet<>();
    }
}
