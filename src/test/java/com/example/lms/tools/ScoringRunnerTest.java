package com.example.lms.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScoringRunnerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path root;

    private void writeKeywordCompleteSourceFixture() throws Exception {
        write("build.gradle.kts", """
                val langchain4jVersion = "1.0.1"
                implementation("dev.langchain4j:langchain4j:$langchain4jVersion")
                tasks.register("checkLangchain4jVersionPurity")
                sourceSets {
                    main {
                        java { srcDirs("main/java") }
                        resources { srcDirs("main/resources") }
                    }
                }
                """);
        write("main/java/com/example/lms/service/rag/learn/CfvmKAllocationTuner.java", """
                package com.example.lms.service.rag.learn;
                class CfvmKAllocationTuner {
                    double boltzmannSoftmax;
                    int[][] nineTileMatrix;
                    String lissajousTrajectory;
                    String rawTileVectorStorePush;
                }
                """);
        write("main/java/com/example/lms/artplate/ArtPlateEvolver.java", """
                package com.example.lms.artplate;
                class ArtPlateEvolver {
                    String AP1, AP2, AP3, AP4, AP5, AP6, AP7, AP8, AP9;
                    String emergentPlate;
                    String abFivePercent;
                }
                """);
        write("main/java/com/example/lms/service/rag/fusion/TailWeightedPowerMeanFuser.java", """
                package com.example.lms.service.rag.fusion;
                class TailWeightedPowerMeanFuser {
                    double tailWeightedPowerMean;
                }
                """);
        write("main/java/com/nova/protocol/fusion/CvarAggregator.java", """
                package com.nova.protocol.fusion;
                class CvarAggregator {
                    double cvarAlpha;
                }
                """);
        write("main/java/com/nova/protocol/alloc/SimpleRiskKAllocator.java", """
                package com.nova.protocol.alloc;
                class SimpleRiskKAllocator {
                    double riskAdjustedSoftmax;
                }
                """);
        write("main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java", """
                package com.example.lms.service.rag.rerank;
                class DppDiversityReranker {
                    double determinantalKernelSelection;
                    double determinant;
                }
                """);
        write("main/java/com/example/lms/service/rag/mp/LowRankWhiteningStats.java", """
                package com.example.lms.service.rag.mp;
                class LowRankWhiteningStats {
                    double zcaWhitening;
                    double invSqrtEigenvalue;
                }
                """);
        write("main/java/com/example/lms/service/guard/PIISanitizer.java", """
                package com.example.lms.service.guard;
                class PIISanitizer {
                    String email = "[redacted-email]";
                    String phone = "[redacted-phone]";
                    String token = "[redacted-token]";
                    String api = "api_key=[redacted]";
                }
                """);
        write("main/java/com/example/lms/service/guard/CitationGate.java", """
                package com.example.lms.service.guard;
                class CitationGate {
                    String canonicalPipelineNamespace;
                }
                """);
        write("main/java/com/example/lms/nova/gate/CitationGate.java", """
                package com.example.lms.nova.gate;
                class CitationGateAlias {
                    String deprecatedAliases;
                }
                """);
        write("main/java/com/example/lms/prompt/StandardPromptBuilder.java", """
                package com.example.lms.prompt;
                class StandardPromptBuilder {
                    String buildPrompt = "PromptBuilder.build(PromptContext)";
                    String trace = "TraceStore.put";
                }
                """);

        writeContractTestSource(
                "src/test/java/com/example/lms/cfvm/CfvmSnapshotRoundTripTest.java",
                "com.example.lms.cfvm", "CfvmSnapshotRoundTripTest");
        writeContractTestSource(
                "src/test/java/com/example/lms/artplate/NineArtPlateGateRolloutTest.java",
                "com.example.lms.artplate", "NineArtPlateGateRolloutTest");
        writeContractTestSource(
                "src/test/java/com/nova/protocol/fusion/NovaNextFusionServiceTest.java",
                "com.nova.protocol.fusion", "NovaNextFusionServiceTest");
        writeContractTestSource(
                "src/test/java/com/example/lms/service/guard/PIISanitizerTest.java",
                "com.example.lms.service.guard", "PIISanitizerTest");
        writeContractTestSource(
                "src/test/java/com/example/lms/service/rag/CitationGateEvidenceComposerBoundaryContractTest.java",
                "com.example.lms.service.rag", "CitationGateEvidenceComposerBoundaryContractTest");
        writeContractTestSource(
                "src/test/java/com/example/lms/prompt/PromptBuilderBoundaryTest.java",
                "com.example.lms.prompt", "PromptBuilderBoundaryTest");

    }

    @Test
    void keywordOnlySourceEarnsNoCorrectnessPointsWithoutEvidence() throws Exception {
        writeKeywordCompleteSourceFixture();

        String text = runScore("keyword-only", null);

        assertTrue(text.contains("Dynamic RAG Source Score"));
        assertTrue(text.contains("Total Score: 15 / 100"));
        assertCategory(text, "active-root", 0, 10, "evidence-needed");
        assertCategory(text, "cfvm", 0, 15, "evidence-needed");
        assertCategory(text, "artplate", 0, 10, "evidence-needed");
        assertCategory(text, "hypernova", 0, 20, "evidence-needed");
        assertCategory(text, "pii", 0, 10, "evidence-needed");
        assertCategory(text, "citation-gate", 0, 10, "evidence-needed");
        assertCategory(text, "prompt-trace", 0, 10, "evidence-needed");
    }

    @Test
    void absentEvidenceFileFailsClosedWithoutThrowing() throws Exception {
        writeKeywordCompleteSourceFixture();

        String text = runScore("missing-evidence", root.resolve("missing-evidence.json"));

        assertTrue(text.contains("Total Score: 15 / 100"));
        assertCategory(text, "active-root", 0, 10, "evidence-needed");
    }

    @Test
    void malformedEvidenceMarksCorrectnessMetricsInvalid() throws Exception {
        writeKeywordCompleteSourceFixture();
        Path evidence = root.resolve("evidence-malformed.json");
        Files.writeString(evidence, "{not-json", StandardCharsets.UTF_8);

        String text = runScore("malformed-evidence", evidence);

        assertTrue(text.contains("Total Score: 15 / 100"));
        assertCategory(text, "active-root", 0, 10, "metric-input-invalid");
        assertCategory(text, "prompt-trace", 0, 10, "metric-input-invalid");
    }

    @Test
    void missingRequiredCheckFailsOnlyThatCategoryClosed() throws Exception {
        writeKeywordCompleteSourceFixture();
        Path evidence = writeEvidence(
                "missing-check",
                Set.of("hypernovaBehavior"),
                Map.of(),
                false,
                Set.of());

        String text = runScore("missing-check", evidence);

        assertTrue(text.contains("Total Score: 80 / 100"));
        assertCategory(text, "hypernova", 0, 20, "evidence-needed");
        assertCategory(text, "cfvm", 15, 15, "artifact-consistent-pass");
    }

    @Test
    void failedStructuredCheckEarnsZeroForItsCategory() throws Exception {
        writeKeywordCompleteSourceFixture();
        Path evidence = writeEvidence(
                "failed-check",
                Set.of(),
                Map.of("piiRedaction", "FAIL"),
                false,
                Set.of());

        String text = runScore("failed-check", evidence);

        assertTrue(text.contains("Total Score: 90 / 100"));
        assertCategory(text, "pii", 0, 10, "failed");
        assertCategory(text, "citation-gate", 10, 10, "artifact-consistent-pass");
    }

    @Test
    void staleSourceIdentityInvalidatesEveryCorrectnessCheck() throws Exception {
        writeKeywordCompleteSourceFixture();
        Path evidence = writeEvidence("stale", Set.of(), Map.of(), true, Set.of());

        String text = runScore("stale", evidence);

        assertTrue(text.contains("Total Score: 15 / 100"));
        assertCategory(text, "active-root", 0, 10, "metric-input-invalid");
        assertCategory(text, "hypernova", 0, 20, "metric-input-invalid");
    }

    @Test
    void artifactHashMismatchInvalidatesOnlyTheAffectedCheck() throws Exception {
        writeKeywordCompleteSourceFixture();
        Path evidence = writeEvidence(
                "hash-mismatch",
                Set.of(),
                Map.of(),
                false,
                Set.of("cfvmBehavior"));

        String text = runScore("hash-mismatch", evidence);

        assertTrue(text.contains("Total Score: 85 / 100"));
        assertCategory(text, "cfvm", 0, 15, "metric-input-invalid");
        assertCategory(text, "artplate", 10, 10, "artifact-consistent-pass");
    }

    @Test
    void validCurrentStructuredArtifactsCanEarnPointsWithoutClaimingExecutionAttestation() throws Exception {
        writeKeywordCompleteSourceFixture();
        Path evidence = writeEvidence("valid", Set.of(), Map.of(), false, Set.of());

        String text = runScore("valid", evidence);

        assertTrue(text.contains("Total Score: 100 / 100"));
        assertTrue(text.contains("Evidence Provenance: local-artifact-consistency"));
        assertTrue(text.contains("Execution Observed: false"));
        for (Map.Entry<String, EvidenceSpec> entry : evidenceSpecs().entrySet()) {
            assertCategory(text, entry.getValue().categoryId(),
                    entry.getValue().points(), entry.getValue().points(), "artifact-consistent-pass");
        }
    }

    @Test
    void referencedTestMustExistInTheCurrentSourceImage() throws Exception {
        writeKeywordCompleteSourceFixture();
        Files.delete(root.resolve(
                "src/test/java/com/example/lms/service/guard/PIISanitizerTest.java"));
        Path evidence = writeEvidence("missing-test-source", Set.of(), Map.of(), false, Set.of());

        String text = runScore("missing-test-source", evidence);

        assertTrue(text.contains("Total Score: 90 / 100"));
        assertCategory(text, "pii", 0, 10,
                "test-source-contract-invalid:path="
                        + "src/test/java/com/example/lms/service/guard/PIISanitizerTest.java");
        assertCategory(text, "citation-gate", 10, 10, "artifact-consistent-pass");
    }

    @Test
    void malformedJUnitSourceFailsClosedWithRelativeDiagnostic() throws Exception {
        writeKeywordCompleteSourceFixture();
        Files.write(
                root.resolve("src/test/java/com/example/lms/service/guard/PIISanitizerTest.java"),
                new byte[] {(byte) 0xC3, (byte) 0x28});
        Path evidence = writeEvidence(
                "malformed-test-source", Set.of(), Map.of(), false, Set.of());

        String text = runScore("malformed-test-source", evidence);
        String piiLine = text.lines()
                .filter(candidate -> candidate.startsWith("[score][pii] "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing pii line in\n" + text));

        assertTrue(piiLine.contains(
                "reason=test-source-encoding-invalid:path="
                        + "src/test/java/com/example/lms/service/guard/PIISanitizerTest.java"), piiLine);
        assertTrue(piiLine.contains("MISSING (+0/10)"), piiLine);
        assertFalse(piiLine.contains(root.toString()), piiLine);
        assertFalse(piiLine.contains("Input length"), piiLine);
    }

    @Test
    void commentsStringsNestedAndDisabledTestsCannotImpersonateAnEnabledTopLevelTest() throws Exception {
        writeKeywordCompleteSourceFixture();
        write("src/test/java/com/example/lms/service/guard/PIISanitizerTest.java", """
                package com.example.lms.service.guard;
                @interface Test {}
                class PIISanitizerTest {
                    // @Test void commentOnly() {}
                    String marker = "@org.junit.jupiter.api.Test";
                    @Test void localAnnotationOnly() {}
                    class Nested {
                        @org.junit.jupiter.api.Test void nestedOnly() {}
                    }
                    @org.junit.jupiter.api.Disabled
                    @org.junit.jupiter.api.Test
                    void disabledOnly() {}
                }
                """);
        Path evidence = writeEvidence("decoy-test-source", Set.of(), Map.of(), false, Set.of());

        String text = runScore("decoy-test-source", evidence);

        assertTrue(text.contains("Total Score: 90 / 100"));
        assertCategory(text, "pii", 0, 10,
                "test-source-contract-invalid:path="
                        + "src/test/java/com/example/lms/service/guard/PIISanitizerTest.java");
        assertCategory(text, "citation-gate", 10, 10, "artifact-consistent-pass");
    }

    @Test
    void everyFixtureProvenSilentCatchReducesTheScoreWhileLegitimateHandlingDoesNot() throws Exception {
        writeKeywordCompleteSourceFixture();
        write("main/java/com/example/lms/silent/SilentCatchMatrix.java", """
                package com.example.lms.silent;
                class SilentCatchMatrix {
                    void empty() { try {} catch (java.io.IOException ex) {} }
                    void commentOnly() { try {} catch (RuntimeException ex) {
                        // intentionally blank is still silent
                    } }
                    void ignoredAccessor() { try {} catch (Exception ignored) {
                        ignored.getMessage();
                    } }
                    void nestedEmpty() { try {} catch (IllegalStateException ex) { { } } }
                    void multiline() { try {} catch
                        (NumberFormatException ex)
                        {
                        }
                    }
                    void traced() { try {} catch (Exception ex) {
                        TraceStore.put("fixed.reason", "failed");
                    } }
                    void logged() { try {} catch (Exception ex) {
                        log.warn("fixed_reason");
                    } }
                    void rethrown() { try {} catch (RuntimeException ex) {
                        throw ex;
                    } }
                }
                """);
        Path evidence = writeEvidence("silent-catch-matrix", Set.of(), Map.of(), false, Set.of());

        String text = runScore("silent-catch-matrix", evidence);

        assertTrue(text.contains("[score][silent-catch] provenSilentCatchMatches=5"), text);
        assertCategory(text, "silent-catch", 10, 15, "debt-present");
        assertEquals(95, totalScore(text));
    }

    @Test
    void largeFileConcentrationReducesTotalComparedWithEquivalentSplitSource() throws Exception {
        writeKeywordCompleteSourceFixture();
        StringBuilder concentrated = new StringBuilder(
                "package com.example.lms.structure;\nclass Concentrated {\n");
        for (int i = 0; i < 2_001; i++) {
            concentrated.append("    void m").append(i).append("() {}\n");
        }
        concentrated.append("}\n");
        Path concentratedPath = root.resolve(
                "main/java/com/example/lms/structure/Concentrated.java");
        write("main/java/com/example/lms/structure/Concentrated.java", concentrated.toString());
        Path concentratedEvidence = writeEvidence(
                "large-concentrated", Set.of(), Map.of(), false, Set.of());
        String concentratedReport = runScore("large-concentrated", concentratedEvidence);

        Files.delete(concentratedPath);
        StringBuilder splitA = new StringBuilder("package com.example.lms.structure;\nclass SplitA {\n");
        StringBuilder splitB = new StringBuilder("package com.example.lms.structure;\nclass SplitB {\n");
        for (int i = 0; i < 1_001; i++) {
            splitA.append("    void a").append(i).append("() {}\n");
            splitB.append("    void b").append(i).append("() {}\n");
        }
        splitA.append("}\n");
        splitB.append("}\n");
        write("main/java/com/example/lms/structure/SplitA.java", splitA.toString());
        write("main/java/com/example/lms/structure/SplitB.java", splitB.toString());
        Path splitEvidence = writeEvidence("large-split", Set.of(), Map.of(), false, Set.of());
        String splitReport = runScore("large-split", splitEvidence);

        assertTrue(concentratedReport.contains(
                "[score][structure] largeActiveSourceFiles=1 thresholdLines=2000 penaltyPoints=2 maxPenalty=10"),
                concentratedReport);
        assertTrue(splitReport.contains(
                "[score][structure] largeActiveSourceFiles=0 thresholdLines=2000 penaltyPoints=0 maxPenalty=10"),
                splitReport);
        assertEquals(98, totalScore(concentratedReport));
        assertEquals(100, totalScore(splitReport));
    }

    @Test
    void reportBytesAreStableWhenEquivalentSourceFilesAreCreatedInReverseOrder() throws Exception {
        writeKeywordCompleteSourceFixture();
        write("main/java/com/example/lms/order/First.java", "package com.example.lms.order; class First {}\n");
        write("main/java/com/example/lms/order/Second.java", "package com.example.lms.order; class Second {}\n");
        Path forwardEvidence = writeEvidence("order-forward", Set.of(), Map.of(), false, Set.of());
        String forward = runScore("order-forward", forwardEvidence);

        Files.delete(root.resolve("main/java/com/example/lms/order/First.java"));
        Files.delete(root.resolve("main/java/com/example/lms/order/Second.java"));
        write("main/java/com/example/lms/order/Second.java", "package com.example.lms.order; class Second {}\n");
        write("main/java/com/example/lms/order/First.java", "package com.example.lms.order; class First {}\n");
        Path reverseEvidence = writeEvidence("order-reverse", Set.of(), Map.of(), false, Set.of());
        String reverse = runScore("order-reverse", reverseEvidence);

        assertEquals(forward, reverse);
    }

    @Test
    void rootGradleExposesDesktopSourceScoreReportTask() throws Exception {
        String build = Files.readString(Path.of("build.gradle.kts"));

        assertTrue(build.contains("tasks.register<JavaExec>(\"sourceScoreReport\")"));
        assertTrue(build.contains("com.example.lms.tools.ScoringRunner"));
        assertTrue(build.contains("sourceScoreOutput"));
    }

    @Test
    void reportsLargeActiveSourceFileHotspotsWithLineCounts() throws Exception {
        StringBuilder giant = new StringBuilder("package com.example.lms.big;\nclass Giant {\n");
        for (int i = 0; i < 2_001; i++) {
            giant.append("    void m").append(i).append("() {}\n");
        }
        giant.append("}\n");
        write("main/java/com/example/lms/big/Giant.java", giant.toString());

        Path report = root.resolve("verification/source-score-large.txt");

        ScoringRunner.main(new String[]{"--root", root.toString(), "--output", report.toString()});

        String text = Files.readString(report);
        assertTrue(text.contains("[score][structure] largeActiveSourceFiles=1 thresholdLines=2000"));
        assertTrue(text.contains("[score][structure][large-file] main/java/com/example/lms/big/Giant.java lines=2004"));
    }

    @Test
    void readFailuresIncludeTheSourcePath() throws Exception {
        Path badFile = root.resolve("main/java/com/example/lms/bad/Bad.java");
        Files.createDirectories(badFile.getParent());
        Files.write(badFile, new byte[] {(byte) 0x80});

        Exception failure = assertThrows(Exception.class,
                () -> ScoringRunner.main(new String[]{"--root", root.toString()}));

        assertTrue(failure.getMessage().contains("main/java/com/example/lms/bad/Bad.java"),
                failure.getMessage());
    }

    private String runScore(String slug, Path evidence) throws Exception {
        Path report = root.resolve("verification/source-score-" + slug + ".txt");
        List<String> args = new ArrayList<>(List.of(
                "--root", root.toString(),
                "--output", report.toString()));
        if (evidence != null) {
            args.add("--evidence");
            args.add(evidence.toString());
        }
        ScoringRunner.main(args.toArray(String[]::new));
        return Files.readString(report);
    }

    private Path writeEvidence(
            String slug,
            Set<String> omittedChecks,
            Map<String, String> statuses,
            boolean staleSource,
            Set<String> mismatchedHashes
    ) throws Exception {
        String currentSourceHead = sourceIdentity(root);
        String sourceHead = staleSource ? "stale-source-identity" : currentSourceHead;
        Map<String, Object> checks = new LinkedHashMap<>();
        for (Map.Entry<String, EvidenceSpec> entry : evidenceSpecs().entrySet()) {
            String checkName = entry.getKey();
            if (omittedChecks.contains(checkName)) {
                continue;
            }
            EvidenceSpec spec = entry.getValue();
            String status = statuses.getOrDefault(checkName, "PASS");
            Path artifact = root.resolve("verification/source-score-evidence/" + checkName + ".json");
            Files.createDirectories(artifact.getParent());
            Map<String, Object> artifactBody = new LinkedHashMap<>();
            artifactBody.put("schemaVersion", 1);
            artifactBody.put("sourceHead", sourceHead);
            artifactBody.put("check", checkName);
            artifactBody.put("status", status);
            artifactBody.put(spec.commandField(), spec.commandOrTest());
            artifactBody.put("tests", 1);
            artifactBody.put("failures", "PASS".equals(status) ? 0 : 1);
            artifactBody.put("errors", 0);
            if (spec.activeCallPath() != null) {
                artifactBody.put("activeCallPath", spec.activeCallPath());
            }
            Files.writeString(artifact, JSON.writeValueAsString(artifactBody), StandardCharsets.UTF_8);

            Map<String, Object> check = new LinkedHashMap<>();
            check.put("status", status);
            check.put(spec.commandField(), spec.commandOrTest());
            check.put("artifactSha256", mismatchedHashes.contains(checkName)
                    ? "0".repeat(64)
                    : sha256(artifact));
            if (spec.activeCallPath() != null) {
                check.put("activeCallPath", spec.activeCallPath());
            }
            checks.put(checkName, check);
        }

        Path evidence = root.resolve("evidence-" + slug + ".json");
        Files.writeString(evidence, JSON.writeValueAsString(Map.of(
                "schemaVersion", 1,
                "sourceHead", sourceHead,
                "checks", checks)), StandardCharsets.UTF_8);
        return evidence;
    }

    private static Map<String, EvidenceSpec> evidenceSpecs() {
        Map<String, EvidenceSpec> specs = new LinkedHashMap<>();
        specs.put("sourceSetVersionPurity", new EvidenceSpec(
                "active-root", 10, "command",
                "checkSourceSetHygiene+checkLangchain4jVersionPurity", null));
        specs.put("cfvmBehavior", new EvidenceSpec(
                "cfvm", 15, "test", "com.example.lms.cfvm.CfvmSnapshotRoundTripTest", null));
        specs.put("artPlateBehavior", new EvidenceSpec(
                "artplate", 10, "test", "com.example.lms.artplate.NineArtPlateGateRolloutTest", null));
        specs.put("hypernovaBehavior", new EvidenceSpec(
                "hypernova", 20, "test", "com.nova.protocol.fusion.NovaNextFusionServiceTest", null));
        specs.put("piiRedaction", new EvidenceSpec(
                "pii", 10, "test", "com.example.lms.service.guard.PIISanitizerTest", null));
        specs.put("citationOwnership", new EvidenceSpec(
                "citation-gate", 10, "test",
                "com.example.lms.service.rag.CitationGateEvidenceComposerBoundaryContractTest",
                "ChatApiController->ChatWorkflow->RagEvidenceAttributionService->CitationGate"));
        specs.put("promptBoundary", new EvidenceSpec(
                "prompt-trace", 10, "test", "com.example.lms.prompt.PromptBuilderBoundaryTest",
                "ChatWorkflow->PromptBuilder.build(PromptContext)->TraceStore"));
        return specs;
    }

    private static String sourceIdentity(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> files = new ArrayList<>();
        for (String buildFile : List.of("build.gradle.kts", "build.gradle")) {
            Path candidate = root.resolve(buildFile);
            if (Files.isRegularFile(candidate)) {
                files.add(candidate);
            }
        }
        for (String sourceRoot : List.of("main/java", "app/src/main/java_clean", "src/test/java")) {
            Path candidate = root.resolve(sourceRoot);
            if (!Files.isDirectory(candidate)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(candidate)) {
                files.addAll(paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .toList());
            }
        }
        files.sort(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')));
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(file));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void assertCategory(
            String report,
            String category,
            int earned,
            int maximum,
            String reason
    ) {
        String line = report.lines()
                .filter(candidate -> candidate.startsWith("[score][" + category + "] "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing category " + category + " in\n" + report));
        assertTrue(line.contains("(+" + earned + "/" + maximum + ")"), line);
        assertTrue(line.contains("reason=" + reason), line);
    }

    private static int totalScore(String report) {
        Matcher matcher = Pattern.compile("=== Total Score: (\\d+) / 100 ===").matcher(report);
        assertTrue(matcher.find(), report);
        return Integer.parseInt(matcher.group(1));
    }

    private void write(String relativePath, String content) throws Exception {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void writeContractTestSource(String relativePath, String packageName, String className)
            throws Exception {
        write(relativePath, "package " + packageName + ";\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "class " + className + " { @Test void contract() {} }\n");
    }

    private record EvidenceSpec(
            String categoryId,
            int points,
            String commandField,
            String commandOrTest,
            String activeCallPath
    ) {
    }
}
