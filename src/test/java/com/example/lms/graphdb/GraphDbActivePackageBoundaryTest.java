package com.example.lms.graphdb;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphDbActivePackageBoundaryTest {

    private static final Path MAIN_ROOT = Path.of("main/java");
    private static final Path APP_CLEAN_ROOT = Path.of("app/src/main/java_clean");
    private static final Path GRAPHDB_ROOT = MAIN_ROOT.resolve("com/example/lms/graphdb");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;");

    @Test
    void graphDbManualLaneStaysOnExplicitActiveBoundary() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path source : activeJavaSources()) {
            String text = Files.readString(source);
            if (!mentionsGraphDbManualLane(source, text)) {
                continue;
            }
            if (!isAllowedGraphDbBoundaryFile(source)) {
                violations.add(source.toString().replace('\\', '/'));
            }
        }

        assertTrue(violations.isEmpty(),
                "GraphDB manual-learning lane must stay in com.example.lms.graphdb, security/config guards, "
                        + "or the existing GraphRagChunkingService option seam: " + violations);
    }

    @Test
    void graphDbPackageDoesNotShadowFlatRootsOrVendorNamespace() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path source : activeJavaSources()) {
            String text = Files.readString(source);
            Matcher matcher = PACKAGE_PATTERN.matcher(text);
            if (!matcher.find()) {
                continue;
            }
            String pkg = matcher.group(1);
            if (pkg.equals("graphdb")
                    || pkg.startsWith("graphdb.")
                    || pkg.equals("service.graphdb")
                    || pkg.startsWith("service.graphdb.")
                    || pkg.equals("service.rag.graphdb")
                    || pkg.startsWith("service.rag.graphdb.")
                    || (pkg.contains(".graphdb") && !isCanonicalGraphDbPackage(pkg))
                    || pkg.equals("dev.langchain4j")
                    || pkg.startsWith("dev.langchain4j.")) {
                violations.add(source.toString().replace('\\', '/') + " package " + pkg);
            }
        }

        assertTrue(violations.isEmpty(),
                "GraphDB/manual-learning work must not introduce flat graphdb roots or dev.langchain4j shadows: "
                        + violations);
    }

    @Test
    void graphDbFacadeDoesNotCallQueryTimeBrainStateOrKgRetrievalDirectly() throws Exception {
        List<String> violations = new ArrayList<>();
        if (Files.exists(GRAPHDB_ROOT)) {
            try (var stream = Files.walk(GRAPHDB_ROOT)) {
                for (Path source : stream.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(source);
                    for (String forbidden : queryTimeCouplingTokens()) {
                        if (text.contains(forbidden)) {
                            violations.add(source.toString().replace('\\', '/') + " -> " + forbidden);
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "GraphDB manual ingestion facade must not couple to KG retrieval scoring, rag.eval, "
                        + "or BrainState local-only fallback: "
                        + violations);
    }

    @Test
    void graphDbClassesAreNotAddedToAppJavaCleanShim() throws Exception {
        List<String> graphDbClasses = activeJavaSources().stream()
                .filter(path -> path.getFileName().toString().startsWith("GraphDb"))
                .map(path -> path.toString().replace('\\', '/'))
                .toList();

        assertFalse(graphDbClasses.isEmpty(), "Expected active GraphDB manual-learning classes to exist");
        assertTrue(graphDbClasses.stream().allMatch(path -> path.startsWith("main/java/com/example/lms/graphdb/")),
                "GraphDB classes must stay in the root runtime package, not :app java_clean or flat roots: "
                        + graphDbClasses);
    }

    @Test
    void graphDbRuntimeFilesUseCanonicalPackageNamespace() throws Exception {
        List<String> violations = new ArrayList<>();
        if (Files.exists(GRAPHDB_ROOT)) {
            try (var stream = Files.walk(GRAPHDB_ROOT)) {
                for (Path source : stream.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(source);
                    Matcher matcher = PACKAGE_PATTERN.matcher(text);
                    if (!matcher.find() || !isCanonicalGraphDbPackage(matcher.group(1))) {
                        violations.add(source.toString().replace('\\', '/'));
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "GraphDB manual-learning runtime files must use com.example.lms.graphdb only: " + violations);
    }

    private static boolean mentionsGraphDbManualLane(Path source, String text) {
        String normalized = source.toString().replace('\\', '/');
        return normalized.contains("/graphdb/")
                || text.contains("GraphDbManualLearning")
                || text.contains("GraphDbClient")
                || text.contains("GraphDbBrainSnapshot")
                || text.contains("graphdb_manual_learning")
                || text.contains("GRAPHDB_MANUAL")
                || text.contains("simultaneousIngest")
                || text.contains("requiredPersistence")
                || text.contains("nonDryRunLiveProof")
                || text.contains("neo4jBackendEnabled")
                || text.contains("\"/api/admin/graph")
                || text.contains("path.startsWith(\"/api/admin/graph/")
                || text.contains("graphdb.manual-learning");
    }

    private static boolean isAllowedGraphDbBoundaryFile(Path source) {
        String normalized = source.toString().replace('\\', '/');
        return normalized.startsWith("main/java/com/example/lms/graphdb/")
                || normalized.equals("main/java/com/example/lms/service/rag/graph/KgChunk.java")
                || normalized.equals("main/java/com/example/lms/service/rag/graph/Neo4jKgChunkWriter.java")
                || normalized.equals("main/java/com/example/lms/service/rag/graph/GraphRagChunkingService.java")
                || normalized.equals("main/java/com/example/lms/service/VectorStoreService.java")
                || normalized.equals("main/java/com/example/lms/config/AppSecurityConfig.java")
                || normalized.equals("main/java/com/example/lms/config/AdminTokenGuardWebMvcConfig.java")
                || normalized.equals("main/java/com/example/lms/security/AdminTokenGuardFilter.java");
    }

    private static List<String> queryTimeCouplingTokens() {
        return List.of(
                "KnowledgeGraphHandler",
                "KnowledgeGraphRetrievalHandler",
                "Neo4jKnowledgeGraphClient",
                "KnowledgeDeltaGraphProjector",
                "UnifiedRagOrchestrator",
                "RagGraphExecutor",
                "BrainStateService",
                "SparseNodeInferenceService",
                "QueryTimeAnchorMap",
                "AnchorFrequencyIndex",
                "querySparseInference",
                "querySparseInferenceLocalOnly",
                "kg_score",
                "kgScore",
                "kg_path_score",
                "rag.eval.kgAxis",
                "scoreFromMetadata");
    }

    private static boolean isCanonicalGraphDbPackage(String pkg) {
        return pkg.equals("com.example.lms.graphdb") || pkg.startsWith("com.example.lms.graphdb.");
    }

    private static List<Path> activeJavaSources() throws IOException {
        List<Path> out = new ArrayList<>();
        addJavaSources(out, MAIN_ROOT);
        addJavaSources(out, APP_CLEAN_ROOT);
        return out;
    }

    private static void addJavaSources(List<Path> out, Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.filter(path -> path.toString().endsWith(".java"))
                    .forEach(out::add);
        }
    }
}
