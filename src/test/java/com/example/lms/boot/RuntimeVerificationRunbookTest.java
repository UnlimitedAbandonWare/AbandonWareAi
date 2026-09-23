package com.example.lms.boot;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeVerificationRunbookTest {

    @Test
    void runbooksExposeSequentialControlPlaneTopologyVerifier() throws Exception {
        String agents = Files.readString(Path.of("AGENTS.md"), StandardCharsets.UTF_8);
        String runme = readIfReadable(Path.of("RUNME_agent.md"));
        String verifier = Files.readString(Path.of("scripts/verify_control_plane_topology.ps1"), StandardCharsets.UTF_8);

        assertTrue(agents.contains("scripts\\verify_control_plane_topology.ps1"));
        if (runme != null) {
            assertTrue(runme.contains(".\\scripts\\verify_control_plane_topology.ps1"));
            assertTrue(runme.contains("Do not run the `bootRun` smoke scripts in parallel"));
        }
        assertTrue(verifier.contains("smoke_learning_ops_collector.ps1"));
        assertTrue(verifier.contains("smoke_gpu_gateway_preflight.ps1"));
        assertTrue(verifier.contains("Keep them sequential"));
        assertTrue(verifier.contains("Invoke-Native"));
        assertTrue(verifier.contains("failed exit=$LASTEXITCODE"));
        assertTrue(verifier.contains("RagLearningOpsCurationCollectorTest"));
        assertTrue(verifier.contains("checkLangchain4jVersionPurity"));
    }

    @Test
    void topologySmokesForceFreshBootRunTaskOutputs() throws Exception {
        String collector = Files.readString(Path.of("scripts/smoke_learning_ops_collector.ps1"), StandardCharsets.UTF_8);
        String gpu = Files.readString(Path.of("scripts/smoke_gpu_gateway_preflight.ps1"), StandardCharsets.UTF_8);

        assertTrue(collector.contains("\"bootRun\", \"--rerun-tasks\", \"--no-daemon\", \"-x\", \"test\""));
        assertTrue(gpu.contains("\"bootRun\", \"--rerun-tasks\", \"--no-daemon\", \"-x\", \"test\""));
    }

    @Test
    void topologyVerifierRefreshesSplitMainClassesBeforeBootRunSmokes() throws Exception {
        String verifier = Files.readString(Path.of("scripts/verify_control_plane_topology.ps1"), StandardCharsets.UTF_8);
        int buildSurface = verifier.indexOf("Invoke-Step \"build-surface\"");
        int smokes = verifier.indexOf("macmini-learning-ops-collector");
        assertTrue(buildSurface >= 0 && smokes > buildSurface);
        String buildBlock = verifier.substring(buildSurface, smokes);

        assertTrue(buildBlock.contains("\"compileJava\""));
        assertTrue(buildBlock.contains("\"--rerun-tasks\""));
    }

    @Test
    void topologyVerifierRejectsMaskedFocusedSelectorsWithFreshPerClassReports() throws Exception {
        String verifier = Files.readString(Path.of("scripts/verify_control_plane_topology.ps1"), StandardCharsets.UTF_8);
        int focusedTests = verifier.indexOf("Invoke-Step \"focused-tests\"");
        int buildSurface = verifier.indexOf("Invoke-Step \"build-surface\"");
        assertTrue(focusedTests >= 0 && buildSurface > focusedTests);
        String focusedBlock = verifier.substring(focusedTests, buildSurface);

        assertTrue(verifier.contains("$FocusedTestClasses = @("));
        assertTrue(focusedBlock.contains("\"--rerun-tasks\""));
        assertTrue(focusedBlock.contains("$FocusedTestsStartedAtUtc = [DateTime]::UtcNow"));
        assertTrue(focusedBlock.contains("Assert-FocusedTestReports"));
        assertTrue(focusedBlock.indexOf("Assert-FocusedTestReports") > focusedBlock.indexOf("Invoke-Native"));
        assertTrue(verifier.contains("TEST-$className.xml"));
        assertTrue(verifier.contains("LastWriteTimeUtc"));
        assertTrue(verifier.contains("$executed = $tests - $skipped"));
        assertTrue(verifier.contains("$executed -lt 1"));
        assertTrue(verifier.contains("tests=$tests executed=$executed failures=$failures errors=$errors skipped=$skipped"));
    }

    @Test
    void fullSuiteStaleClassOutputRecoveryIsDocumentedAndExecutable() throws Exception {
        String agents = Files.readString(Path.of("AGENTS.md"), StandardCharsets.UTF_8);
        String verifier = Files.readString(Path.of("scripts/verify_full_test_refresh.ps1"), StandardCharsets.UTF_8);

        assertTrue(agents.contains("scripts\\verify_full_test_refresh.ps1"));
        assertTrue(agents.contains("NoClassDefFoundError") && agents.contains("--rerun-tasks"));
        assertTrue(verifier.contains("NoClassDefFoundError"));
        assertTrue(verifier.contains("$env:AWX_SPLIT_BUILD_OUTPUTS = \"1\""));
        assertTrue(verifier.contains("--project-cache-dir"));
        assertTrue(verifier.contains("--fail-fast"));
        assertTrue(verifier.contains("--rerun-tasks"));
    }

    @Test
    void learningOpsCollectorSmokeLeavesHibernateManagedIndexesToJpa() throws Exception {
        String collector = Files.readString(Path.of("scripts/smoke_learning_ops_collector.ps1"), StandardCharsets.UTF_8);

        assertTrue(collector.contains("CREATE TABLE IF NOT EXISTS rag_ops_ledger"));
        assertTrue(!collector.contains("CREATE INDEX IF NOT EXISTS idx_rag_ops_type_created"));
    }

    @Test
    void topologySmokesPropagateProjectCacheDirToNestedBootRun() throws Exception {
        String verifier = Files.readString(Path.of("scripts/verify_control_plane_topology.ps1"), StandardCharsets.UTF_8);
        String collector = Files.readString(Path.of("scripts/smoke_learning_ops_collector.ps1"), StandardCharsets.UTF_8);
        String gpu = Files.readString(Path.of("scripts/smoke_gpu_gateway_preflight.ps1"), StandardCharsets.UTF_8);

        assertTrue(verifier.contains("$env:AWX_PROJECT_CACHE_DIR = $ProjectCacheDir"));
        assertTrue(collector.contains("AWX_PROJECT_CACHE_DIR"));
        assertTrue(collector.contains("\"--project-cache-dir\""));
        assertTrue(gpu.contains("AWX_PROJECT_CACHE_DIR"));
        assertTrue(gpu.contains("\"--project-cache-dir\""));
    }

    @Test
    void standaloneBootRunSmokesPropagateProjectCacheDir() throws Exception {
        String dataset = Files.readString(Path.of("scripts/smoke_dataset_api.ps1"), StandardCharsets.UTF_8);
        String brainState = Files.readString(Path.of("scripts/smoke_graph_rag_brain_state.ps1"), StandardCharsets.UTF_8);
        String graphDb = Files.readString(Path.of("scripts/smoke_graphdb_manual_learning.ps1"), StandardCharsets.UTF_8);

        assertTrue(dataset.contains("AWX_PROJECT_CACHE_DIR"));
        assertTrue(dataset.contains("\"--project-cache-dir\""));
        assertTrue(brainState.contains("AWX_PROJECT_CACHE_DIR"));
        assertTrue(brainState.contains("\"--project-cache-dir\""));
        assertTrue(graphDb.contains("AWX_PROJECT_CACHE_DIR"));
        assertTrue(graphDb.contains("\"--project-cache-dir\""));
    }

    @Test
    void topologySmokesClassifyConcurrentGradleAndBootRunBeforeLaunch() throws Exception {
        String verifier = Files.readString(Path.of("scripts/verify_control_plane_topology.ps1"), StandardCharsets.UTF_8);
        String collector = Files.readString(Path.of("scripts/smoke_learning_ops_collector.ps1"), StandardCharsets.UTF_8);
        String gpu = Files.readString(Path.of("scripts/smoke_gpu_gateway_preflight.ps1"), StandardCharsets.UTF_8);

        assertTrue(verifier.contains("Assert-NoGradleCollision"));
        assertTrue(verifier.contains("gradle-cache-collision"));
        assertTrue(collector.contains("Assert-NoGradleCollision"));
        assertTrue(collector.contains("gradle-cache-collision"));
        assertTrue(gpu.contains("Assert-NoGradleCollision"));
        assertTrue(gpu.contains("gradle-cache-collision"));
    }

    @Test
    void topologyVerifierWaitsBrieflyForPriorSmokeCleanupBeforeFailClosedCollision() throws Exception {
        String verifier = Files.readString(Path.of("scripts/verify_control_plane_topology.ps1"), StandardCharsets.UTF_8);

        assertTrue(verifier.contains("[int]$CollisionGraceSeconds"));
        assertTrue(verifier.contains("Wait-ForNoGradleCollision"));
        assertTrue(verifier.contains("Start-Sleep -Milliseconds"));
        assertTrue(verifier.contains("throw \"[AWX][topology-verify] gradle-cache-collision"));
    }

    @Test
    void gpuGatewaySmokeWaitsBrieflyForPriorBootRunCleanupBeforeNextBootRun() throws Exception {
        String gpu = Files.readString(Path.of("scripts/smoke_gpu_gateway_preflight.ps1"), StandardCharsets.UTF_8);

        assertTrue(gpu.contains("[int]$CollisionGraceSeconds"));
        assertTrue(gpu.contains("Wait-ForNoGradleCollision"));
        assertTrue(gpu.contains("Start-Sleep -Milliseconds"));
        assertTrue(gpu.contains("throw \"[AWX][gpu-gateway-smoke] gradle-cache-collision"));
    }

    private static String readIfReadable(Path path) throws Exception {
        try {
            if (!Files.isReadable(path)) {
                System.out.println("[AWX][test][quarantine] unreadable=" + path);
                return null;
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.nio.file.AccessDeniedException e) {
            System.out.println("[AWX][test][quarantine] unreadable=" + path);
            return null;
        }
    }
}
