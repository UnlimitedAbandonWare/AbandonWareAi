package com.example.lms.service.guard;

import com.example.lms.service.VectorMetaKeys;
import com.example.lms.service.scope.ScopeHeuristics;
import com.example.lms.service.scope.ScopeLabel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Scope guard: labels segments with scope metadata and (optionally) routes low-confidence
 * / no-anchor items to quarantine.
 *
 * <p>Fail-soft: never throws; if disabled or errors occur, it should not block ingest.</p>
 */
@Service
public class VectorScopeGuard {

    @Value("${vector.scope.minConfidence:0.55}")
    double minConfidence;

    @Value("${vector.scope.requireAnchorForDocTypes:KB,TRAIN}")
    String requireAnchorForDocTypes;

    public record IngestDecision(boolean allow, String reason, Map<String, Object> metaEnrich) {
    }

    public IngestDecision inspectIngest(String docType, String text, Map<String, Object> meta) {
        ScopeLabel label = ScopeHeuristics.infer(text, meta);

        boolean requireAnchor = requiresAnchor(docType);
        boolean anchorOk = !requireAnchor || StringUtils.hasText(label.anchorKey());
        boolean confOk = label.confidence() >= minConfidence;
        boolean allow = anchorOk && confOk;

        Map<String, Object> enrich = new HashMap<>();
        enrich.put(VectorMetaKeys.META_SCOPE_ANCHOR_KEY, label.anchorKey());
        enrich.put(VectorMetaKeys.META_SCOPE_KIND, label.kind());
        if (StringUtils.hasText(label.partKey())) {
            enrich.put(VectorMetaKeys.META_SCOPE_PART_KEY, label.partKey());
        }
        enrich.put(VectorMetaKeys.META_SCOPE_CONF, label.confidence());
        enrich.put(VectorMetaKeys.META_SCOPE_REASON, label.reason());

        String reason = label.reason()
                + (requireAnchor && !anchorOk ? "|anchor_required" : "")
                + (!confOk ? "|low_conf" : "");

        return new IngestDecision(allow, reason, enrich);
    }

    private boolean requiresAnchor(String docType) {
        if (!StringUtils.hasText(docType) || !StringUtils.hasText(requireAnchorForDocTypes)) return false;
        for (String t : requireAnchorForDocTypes.split(",")) {
            if (docType.equalsIgnoreCase(t.trim())) return true;
        }
        return false;
    }
}
