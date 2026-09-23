package com.example.lms.learning.context;

import com.example.lms.learning.chat.LearningActorRole;
import com.example.lms.learning.chat.LearningSignal;
import com.example.lms.learning.chat.LearningSignalKind;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * RAG-facing projection of Training RAG support data.
 *
 * <p>This type is the adapter boundary consumed by chat/RAG prompt composition
 * and trace metadata; it must not reintroduce legacy LMS entity assumptions.
 */
public record RagLearningSupportContext(
        LearningActorRole actorRole,
        List<LearningSignal> contextSignals,
        String contextSummary,
        Set<String> sourceTags,
        String degradedReason) {

    public RagLearningSupportContext {
        actorRole = actorRole == null ? LearningActorRole.ANONYMOUS : actorRole;
        contextSignals = contextSignals == null ? Collections.emptyList() : List.copyOf(contextSignals);
        contextSummary = sanitize(contextSummary, 480);
        sourceTags = sourceTags == null || sourceTags.isEmpty()
                ? sourceTagsFrom(contextSignals)
                : Collections.unmodifiableSet(new LinkedHashSet<>(sourceTags));
        degradedReason = sanitize(degradedReason, 120);
    }

    public static RagLearningSupportContext empty() {
        return empty(LearningActorRole.ANONYMOUS);
    }

    public static RagLearningSupportContext empty(LearningActorRole role) {
        return new RagLearningSupportContext(role, Collections.emptyList(), "", Collections.emptySet(), "");
    }

    public static RagLearningSupportContext degraded(String reason) {
        return degraded(LearningActorRole.ANONYMOUS, reason);
    }

    public static RagLearningSupportContext degraded(LearningActorRole role, String reason) {
        return new RagLearningSupportContext(
                role,
                List.of(new LearningSignal(
                        LearningSignalKind.CONTEXT_DEGRADED,
                        "trainingRagSupport",
                        "reason=" + sanitize(reason, 80),
                        0.0d)),
                "",
                Collections.emptySet(),
                reason);
    }

    public static RagLearningSupportContext fromSnapshot(LearningContextSnapshot snapshot) {
        if (snapshot == null) {
            return empty();
        }
        return new RagLearningSupportContext(
                snapshot.actorRole(),
                snapshot.contextSignals(),
                snapshot.contextSummary(),
                snapshot.sourceTags(),
                snapshot.degradedReason());
    }

    public LearningActorRole role() {
        return actorRole;
    }

    public List<LearningSignal> learningSignals() {
        return contextSignals;
    }

    public String summary() {
        return contextSummary;
    }

    public boolean degraded() {
        return StringUtils.hasText(degradedReason);
    }

    private static Set<String> sourceTagsFrom(List<LearningSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (LearningSignal signal : signals) {
            if (signal == null || signal.kind() == null || signal.kind().isBlank()) {
                continue;
            }
            String tag = signal.kind().trim().toUpperCase(Locale.ROOT);
            if (!LearningSignalKind.CONTEXT_DEGRADED.value().equals(tag)) {
                tags.add(tag);
            }
        }
        return Collections.unmodifiableSet(tags);
    }

    private static String sanitize(String raw, int maxLen) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String normalized = raw.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen - 1)) + "...";
    }
}
