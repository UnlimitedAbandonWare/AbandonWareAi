package com.example.lms.image.style;

import java.util.List;



/**
 * A record representing the stylistic profile of a known franchise.  Style
 * cards encapsulate visual motifs, palette choices, wardrobe hints and
 * camera perspectives along with negative cues and copyright policies.
 *
 * <p>The fields are intentionally simple lists to allow easy serialisation
 * and extension.  Additional metadata may be added in the future without
 * breaking existing code.</p>
 */
public record FranchiseProfile(
        String franchise,
        List<String> aliases,
        List<String> visualMotifs,
        List<String> palette,
        List<String> wardrobe,
        List<String> camera,
        List<String> avoid,
        List<String> negativeSoft,
        boolean copyrightSensitive,
        boolean rewriteToInspired
) {
}