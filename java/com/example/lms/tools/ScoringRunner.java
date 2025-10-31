package com.example.lms.tools;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;



/**
 * Simple scoring harness used to verify that the core patches required
 * by the GPT Pro Agent directives have been applied.  The runner performs
 * a series of static inspections on the source tree to detect the presence
 * of key patterns and configuration values.  Each check contributes
 * points towards an overall score (out of 100).  The results are written
 * to {@code verification/scoring-report.txt}.
 *
 * <p>This harness is intentionally lightweight and does not attempt to
 * execute any retrieval pipelines or run unit tests.  It focuses on
 * structural verification of the codebase and configuration.  When a
 * pattern is not found the corresponding check receives zero points.
 * The final score is computed as the sum of all individual check scores.</p>
 */
public class ScoringRunner {

    public static void main(String[] args) throws Exception {
        int score = 0;
        StringBuilder report = new StringBuilder();
        report.append("=== Scoring Report ===\n");

        // 1. Version purity task defined in build.gradle
        boolean purityTask = fileContains("src/build.gradle", "checkLangchain4jVersionPurity");
        if (purityTask) {
            score += 10;
            report.append("[A] LangChain4j version purity gate: OK (+10)\n");
        } else {
            report.append("[A] LangChain4j version purity gate: MISSING (+0)\n");
        }

        // 2. GenericDocClassifier domain guard
        boolean genericDomain = fileContains(
                "src/main/java/com/example/lms/service/rag/filter/GenericDocClassifier.java",
                "return false;", "GENERAL", "EDUCATION");
        if (genericDomain) {
            score += 10;
            report.append("[B] GenericDocClassifier domain guard: OK (+10)\n");
        } else {
            report.append("[B] GenericDocClassifier domain guard: MISSING (+0)\n");
        }

        // 3. WebSearchRetriever domain‑aware generic penalty and filtering
        boolean webFilter = fileContains(
                "src/main/java/com/example/lms/service/rag/WebSearchRetriever.java",
                "isGeneral", "genericClassifier.penalty(a, domain)", "isGenericSnippet(s, domain)");
        if (webFilter) {
            score += 10;
            report.append("[C] WebSearchRetriever generic handling: OK (+10)\n");
        } else {
            report.append("[C] WebSearchRetriever generic handling: MISSING (+0)\n");
        }

        // 4. Query hygiene diversity for GENERAL domain
        boolean hygiene = fileContains(
                "src/main/java/com/example/lms/search/SmartQueryPlanner.java",
                "cap = Math.min(8", "jaccard = 0.60");
        if (hygiene) {
            score += 10;
            report.append("[D] Query hygiene adjustments for GENERAL: OK (+10)\n");
        } else {
            report.append("[D] Query hygiene adjustments for GENERAL: MISSING (+0)\n");
        }

        // 5. DuckDuckGo fallback default ON in code and YAML
        boolean fallbackCode = fileContains(
                "src/main/java/com/example/lms/service/NaverSearchService.java",
                "@Value(\"${naver.fallback.duckduckgo.enabled:true}\")");
        boolean fallbackYaml = fileContains(
                "src/main/resources/application.yml",
                "duckduckgo:", "enabled: ${NAV_FALLBACK_DDG:true}");
        if (fallbackCode && fallbackYaml) {
            score += 10;
            report.append("[E] DuckDuckGo fallback default ON: OK (+10)\n");
        } else {
            report.append("[E] DuckDuckGo fallback default ON: MISSING (+0)\n");
        }

        // 6. Adaptive provider routing (provider allowlist filter)
        boolean adaptive = fileContains(
                "src/main/java/com/example/lms/integration/handlers/AdaptiveWebSearchHandler.java",
                "new java.util.HashSet", "allow.contains");
        if (adaptive) {
            score += 10;
            report.append("[F] Adaptive provider allowlist routing: OK (+10)\n");
        } else {
            report.append("[F] Adaptive provider allowlist routing: MISSING (+0)\n");
        }

        // 7. General vector index routing (chooseIndex & pinecone.index.general)
        boolean hybrid = fileContains(
                "src/main/java/com/example/lms/service/rag/HybridRetriever.java",
                "chooseIndex", "pinecone.index.general");
        if (hybrid) {
            score += 10;
            report.append("[G] GENERAL vector index routing: OK (+10)\n");
        } else {
            report.append("[G] GENERAL vector index routing: MISSING (+0)\n");
        }

        // 8. Generic penalty domain integration (penalty returns 0 for GENERAL)
        boolean penaltyZero = fileContains(
                "src/main/java/com/example/lms/service/rag/filter/GenericDocClassifier.java",
                "return 0.0;");
        if (penaltyZero) {
            score += 5;
            report.append("[H] Generic penalty zero for GENERAL/EDU: OK (+5)\n");
        } else {
            report.append("[H] Generic penalty zero for GENERAL/EDU: MISSING (+0)\n");
        }

        // 9. Education hardening: classifier exists and used in retriever
        boolean eduClassifier = fileContains(
                "src/main/java/com/example/lms/service/rag/filter/EducationDocClassifier.java",
                "class EducationDocClassifier");
        boolean eduUse = fileContains(
                "src/main/java/com/example/lms/service/rag/WebSearchRetriever.java",
                "educationClassifier.isEducation");
        if (eduClassifier && eduUse) {
            score += 10;
            report.append("[I] Education classifier integration: OK (+10)\n");
        } else {
            report.append("[I] Education classifier integration: MISSING (+0)\n");
        }

        // 10. MOE promotion thresholds and domain logic
        boolean moeProps = fileContains(
                "src/main/java/com/example/lms/config/MoeRoutingProps.java",
                "tokensThreshold = 280", "uncertaintyThreshold = 0.35", "webEvidenceThreshold = 0.55");
        boolean routerCore = fileContains(
                "src/main/java/com/example/lms/service/routing/ModelRouterCore.java",
                "isGeneralIntent", "s.maxTokens() >= props.getTokensThreshold()", "s.uncertainty() >= props.getUncertaintyThreshold()", "s.theta() >= props.getWebEvidenceThreshold()");
        if (moeProps && routerCore) {
            score += 15;
            report.append("[J] MOE escalation thresholds & logic: OK (+15)\n");
        } else {
            report.append("[J] MOE escalation thresholds & logic: MISSING (+0)\n");
        }

        // Final score
        report.append("-----------------------------\n");
        report.append("Total Score: ").append(score).append(" / 100\n");

        // Write the report to the verification directory
        File outDir = new File("verification");
        outDir.mkdirs();
        File reportFile = new File(outDir, "scoring-report.txt");
        try (FileWriter fw = new FileWriter(reportFile)) {
            fw.write(report.toString());
        }
        // Also log to stdout for immediate feedback when run via gradle
        System.out.println(report.toString());
    }

    /**
     * Utility to check whether a file contains all of the supplied substrings.
     * When the file does not exist or an IO error occurs this returns
     * {@code false}.  Substring checks are case sensitive.
     *
     * @param relativePath path relative to the project root
     * @param substrings one or more substrings to search for
     * @return true if all substrings are present in the file, false otherwise
     */
    private static boolean fileContains(String relativePath, String... substrings) {
        try {
            Path p = Paths.get(relativePath);
            if (!Files.exists(p)) {
                // Try project root fallback (without src prefix)
                p = Paths.get("ai_core_src_8", relativePath);
            }
            if (!Files.exists(p)) return false;
            String content = Files.readString(p);
            for (String s : substrings) {
                if (!content.contains(s)) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}