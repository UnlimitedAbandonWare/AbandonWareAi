package ai.abandonware.nova.orch.anchor;

import java.util.List;

public record AnchorNarrowingResult(
        List<String> anchors,
        double anchorConfidence,
        double driftScore,
        int acceptedCandidateCount,
        int rejectedCandidateCount,
        String reason
) {
    public AnchorNarrowingResult {
        anchors = anchors == null ? List.of() : List.copyOf(anchors);
        reason = reason == null ? "" : reason;
    }

    public int totalCandidateCount() {
        return Math.max(0, acceptedCandidateCount) + Math.max(0, rejectedCandidateCount);
    }
}
