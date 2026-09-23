package com.example.lms.api;

import com.example.lms.ensemble.EvidenceGroundedTriadicDebugAdjudicator;
import com.example.lms.service.trace.DebugCopilotService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;

/** Admin diagnostics bridge for the demand-driven triadic debug adjudicator. */
@RestController
@RequestMapping("/api/diagnostics/debug/triadic-adjudication")
public class TriadicDebugAdjudicationController {

    private final DebugCopilotService debugCopilotService;

    public TriadicDebugAdjudicationController(DebugCopilotService debugCopilotService) {
        this.debugCopilotService = debugCopilotService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> latest() {
        return safeMap(debugCopilotService.latestTriadicAdjudication());
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> run(@RequestBody(required = false) PatchCandidateRequest request) {
        EvidenceGroundedTriadicDebugAdjudicator.PatchCandidate candidate = request == null
                ? null
                : new EvidenceGroundedTriadicDebugAdjudicator.PatchCandidate(
                        request.summary(), request.targetFiles(), request.diffSha256());
        return safeMap(debugCopilotService.adjudicateLatestPatchCandidate(candidate));
    }

    private static Map<String, Object> safeMap(
            EvidenceGroundedTriadicDebugAdjudicator.Adjudication adjudication) {
        return adjudication == null
                ? EvidenceGroundedTriadicDebugAdjudicator.Adjudication.hold(
                        "adjudication_missing", "none", 0, 0, 0.0d, 0.0d, 0).toSafeMap()
                : adjudication.toSafeMap();
    }

    public record PatchCandidateRequest(
            String summary,
            List<String> targetFiles,
            String diffSha256) {
    }
}
