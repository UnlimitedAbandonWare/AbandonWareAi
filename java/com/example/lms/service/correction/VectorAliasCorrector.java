package com.example.lms.service.correction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;




/**
 * A lightweight alias corrector that performs simple, deterministic normalization
 * and optional alias replacement without external vector/ONNX dependencies.
 *
 * <p>It is intentionally minimal so that projects missing the full alias-correction
 * stack can still compile and run with sensible defaults.</p>
 *
 * Configuration (optional):
 *  - correction.alias.threshold (unused here but kept for forward compatibility)
 *  - correction.alias.max-candidates (unused)
 */
@Service
public class VectorAliasCorrector {

    private final Map<String, String> aliasMap = new LinkedHashMap<>();

    public VectorAliasCorrector(
            @Value("${correction.alias.threshold:0.62}") double threshold,
            @Value("${correction.alias.max-candidates:3}") int maxCandidates) {
        // Preload a tiny built-in alias map. Extend as needed.
        // Full query aliases (lower-cased key).
        aliasMap.put("k8 plus", "\"K8 Plus\" 미니PC");
        aliasMap.put("win11 pro", "Windows 11 Pro");
        aliasMap.put("win11", "Windows 11");
        // Add more as required by the domain.
    }

    /** Initialize resources. Kept for API compatibility with existing bootstrap code. */
    public void init() {
        // No-op (would load tiles or models in the full implementation).
    }

    /**
     * Attempts to correct or normalize the given input.
     * @return Optional corrected string; empty if no change suggested.
     */
    public Optional<String> correct(String input) {
        if (input == null) return Optional.empty();
        String s = normalize(input);
        String byFull = aliasMap.get(s.trim().toLowerCase());
        String out = byFull != null ? byFull : s;
        return out.equals(input) ? Optional.empty() : Optional.of(out);
    }

    // --- helpers ---

    /** Basic normalization: trim, unify whitespace and dash variants, strip zero-width chars. */
    private String normalize(String input) {
        String s = input.replace('\u00A0', ' ') // NBSP -> space
                .replaceAll("[\u200B\u200C\u200D\uFEFF]", "") // zero-width
                .replaceAll("[--−]+", "-") // dash variants -> '-'
                .replace("“", "\"").replace("”", "\"").replace("‘", "'").replace("’", "'")
                .replace("`", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }
}