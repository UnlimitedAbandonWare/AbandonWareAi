package com.example.lms.image.style;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;




/**
 * A naive in-memory implementation of {@link ImageFranchiseStore}.  This
 * implementation performs simple lexical matching against alias lists
 * defined within known {@link FranchiseProfile}s.  It does not rely on
 * external vector stores or cross-encoder rerankers but honours the
 * configured similarity threshold by treating any lexical hit as a match.
 *
 * <p>If more sophisticated matching is required this class can be
 * swapped out for an implementation backed by an {@code EmbeddingStore}
 * or other RAG component.</p>
 */
@Component
@RequiredArgsConstructor
public class ImageFranchiseStoreImpl implements ImageFranchiseStore {

    /** Minimum similarity required for a match.  Lexical hits always
     * produce a score of 1.0. */
    @Value("${image.franchise.threshold:0.62}")
    private double threshold;

    /** Predefined style cards keyed by the primary franchise name. */
    private final Map<String, FranchiseProfile> profiles = initProfiles();

    /** Initialise the built-in franchise profiles. */
    private static Map<String, FranchiseProfile> initProfiles() {
        Map<String, FranchiseProfile> map = new LinkedHashMap<>();
        // Style card for Shakugan no Shana (작안의 샤나)
        map.put("shakugan no shana", new FranchiseProfile(
                "Shakugan no Shana",
                List.of("shakugan no shana", "작안의 샤나", "shana", "샤나", "flame haze"),
                List.of("flame-eye", "school uniform-inspired", "dynamic pose"),
                List.of("crimson", "black", "gold"),
                List.of("school uniform-inspired"),
                List.of("dynamic angle"),
                List.of("sci-fi city", "modern vehicles"),
                List.of("random wildlife", "generic landscape"),
                true,
                true
        ));
        // Style card for Spice and Wolf (늑대와 향신료)
        map.put("spice and wolf", new FranchiseProfile(
                "Spice and Wolf",
                List.of("spice and wolf", "늑대와 향신료", "wolf and spice", "holo", "horo", "늑대"),
                List.of("medieval trade", "rural market", "wheat fields", "traveling merchant"),
                List.of("warm amber", "desaturated green", "brown leather"),
                List.of("medieval cloak", "simple tunic"),
                List.of("mid shot", "soft natural light"),
                List.of("futuristic tech", "neon city"),
                List.of("generic landscape"),
                true,
                true
        ));
        return map;
    }

    @Override
    public Optional<FranchiseProfile> resolve(String query, String memory) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String lower = query.toLowerCase(Locale.ROOT);
        // Simple lexical matching: return the first profile whose alias appears
        for (FranchiseProfile fp : profiles.values()) {
            for (String alias : fp.aliases()) {
                if (lower.contains(alias.toLowerCase(Locale.ROOT))) {
                    // Lexical hit yields score 1.0 which exceeds any threshold <=1.0
                    return Optional.of(fp);
                }
            }
        }
        // Optionally inspect memory context
        if (memory != null && !memory.isBlank()) {
            String memLower = memory.toLowerCase(Locale.ROOT);
            for (FranchiseProfile fp : profiles.values()) {
                for (String alias : fp.aliases()) {
                    if (memLower.contains(alias.toLowerCase(Locale.ROOT))) {
                        return Optional.of(fp);
                    }
                }
            }
        }
        return Optional.empty();
    }
}