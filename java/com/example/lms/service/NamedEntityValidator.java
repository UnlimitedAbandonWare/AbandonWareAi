// src/main/java/com/example/lms/service/verification/NamedEntityValidator.java
package com.example.lms.service.verification;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;



/**
 * A simple validator that extracts named entities from an answer and
 * verifies that those entities appear in the provided evidence.
 *
 * This class is intentionally lightweight and does not depend on any third-party
 * NER libraries. It uses regular expressions to detect potential named entities
 * such as model names (e.g. Snapdragon 888) or proper nouns.  If an entity
 * detected in the answer is absent from the evidence, the validator will flag
 * it as unsupported.
 *
 * The validator can be toggled or extended in the future to use a more
 * sophisticated NER service.
 */
public class NamedEntityValidator {

    public static class ValidationResult {
        private final boolean entityMismatch;
        private final List<String> missingEntities;

        public ValidationResult(boolean entityMismatch, List<String> missingEntities) {
            this.entityMismatch = entityMismatch;
            this.missingEntities = missingEntities == null ? List.of() : missingEntities;
        }

        /** Returns true if any entities in the answer are not present in the evidence. */
        public boolean isEntityMismatch() {
            return entityMismatch;
        }

        /** Returns a list of entities that were not found in the evidence. */
        public List<String> getMissingEntities() {
            return missingEntities;
        }
    }

    /**
     * Extracts candidate named entities from the provided text.  This method
     * currently identifies sequences of capitalised words and common chipset
     * patterns (e.g. Snapdragon 888, Exynos 2500) as potential entities.  It
     * also includes support for detecting transliterations of well-known chip
     * names in Korean (e.g. 스냅드래곤, 엑시노스).
     *
     * @param text the text from which to extract entities
     * @return a list of extracted entity strings
     */
    public List<String> extractEntities(String text) {
        List<String> entities = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return entities;
        }
        // Regex patterns for chipset and product names
        String[] patterns = {
                "(?i)\\bSnapdragon\\s+\\d+[A-Za-z]*\\b",
                "(?i)\\bExynos\\s+\\d+[A-Za-z]*\\b",
                "(?i)\\bA\\d+\\s*Bionic\\b",
                "(?i)\\bCore\\s+Ultra\\s+\\d+\\b",
                "(?i)\\bRyzen\\s+[3579]\\s+\\d{3,5}[A-Z]?\\b",
                "(?i)(스냅드래곤\\s*\\d+)",
                "(?i)(엑시노스\\s*\\d+)",
                "(?i)(바이오닉\\s*\\d+)"
        };
        for (String pattern : patterns) {
            Matcher matcher = Pattern.compile(pattern).matcher(text);
            while (matcher.find()) {
                String entity = matcher.group(0).trim();
                if (entity.length() > 1 && !entities.contains(entity)) {
                    entities.add(entity);
                }
            }
        }
        return entities;
    }

    /**
     * Finds all entities that appear in the answer but not in the provided evidence.
     *
     * @param answer   the answer text to check
     * @param evidence a collection of evidence strings; each may be a snippet or
     *                   aggregated context used to support the answer
     * @return a list of unsupported entities
     */
    public List<String> findUnsupportedEntities(String answer, List<String> evidence) {
        List<String> missing = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return missing;
        }
        // Flatten evidence into a single lower-case string for containment checks
        StringBuilder evBuilder = new StringBuilder();
        if (evidence != null) {
            for (String ev : evidence) {
                if (ev != null) {
                    evBuilder.append(ev.toLowerCase(Locale.ROOT)).append(" ");
                }
            }
        }
        String evidenceText = evBuilder.toString();
        // Extract candidate entities from the answer
        for (String entity : extractEntities(answer)) {
            if (!evidenceText.contains(entity.toLowerCase(Locale.ROOT))) {
                missing.add(entity);
            }
        }
        return missing;
    }

    /**
     * Validates that all extracted entities from the answer are present in the evidence.
     *
     * @param answer   the answer text to validate
     * @param evidence a collection of evidence strings
     * @return a ValidationResult with mismatch flag and missing entities list
     */
    public ValidationResult validateAnswerEntities(String answer, List<String> evidence) {
        List<String> missing = findUnsupportedEntities(answer, evidence);
        return new ValidationResult(!missing.isEmpty(), missing);
    }
}