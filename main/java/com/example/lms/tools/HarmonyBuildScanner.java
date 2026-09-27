package com.example.lms.tools;

import com.example.lms.harmony.HarmonyEvidenceContract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Static Harmony gate scanner for the active Desktop source root.
 *
 * <p>The scanner never executes providers or runtime calls. It checks source,
 * tests, and resources for the HB-01..HB-12 evidence required by the Harmony
 * directive and prints count-only diagnostics.</p>
 */
public class HarmonyBuildScanner {

    private static final Pattern EMPTY_CATCH = Pattern.compile(
            "catch[ \\t]*\\([^\\r\\n)]*(Exception|Throwable|RuntimeException|IOException|"
                    + "IllegalStateException)[^\\r\\n)]*\\)[ \\t]*\\{[ \\t]*(?://[^\\r\\n]*)?[ \\t]*\\}",
            Pattern.MULTILINE);
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|"
                    + "pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "(?m)^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;");
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:public\\s+)?(?:final\\s+|abstract\\s+)?(?:class|interface|enum|record)\\s+([A-Za-z0-9_]+)");

    private static final List<String> TRACE_KEYS = List.of(
            "boosterMode.active",
            "boosterMode.excludedModes",
            "boosterMode.exclusionReason",
            "retrievalOrder.lastSetBy",
            "extremeZ.activated",
            "extremeZ.triggerReasons",
            "extremeZ.subQueryCount",
            "extremeZ.parallelBranchCount",
            "extremeZ.mergedDocCount",
            "extremeZ.rrfApplied",
            "extremeZ.timeoutMs",
            "extremeZ.bypassReason",
            "extremeZ.cancelShieldWrapped",
            "extremeZ.timeBudgetConsumedMs",
            "hypernova.cvarPhi",
            "hypernova.dppApplied",
            "hypernova.sourceScoreScaleMismatchCount",
            "hypernova.twpmP",
            "hypernova.cvarAlpha",
            "hypernova.cvarFusedScore",
            "hypernova.riskKAlloc",
            "hypernova.clampApplied",
            "hypernova.finalGatePassed",
            "cihRag.breadcrumb.queryRedacted",
            "cihRag.iqrIterations",
            "cihRag.activeFileCount",
            "cihRag.skippedFileCount",
            "cihRag.mlaBreadcrumbCount",
            "cihRag.onnxRerankApplied",
            "cihRag.routedModel",
            "cihRag.ucb1Reward",
            "moe.evolverPlateRegistered",
            "moe.evolverCandidatePlateId",
            "moe.abSlot",
            "moe.evolver.abSlot",
            "moe.selectedPlate",
            "moe.signalVector",
            "moe.criticAttempts",
            "moe.criticExhausted",
            "moe.criticLastReason",
            "overdrive.triggerReasons",
            "overdrive.trigger.sparse",
            "overdrive.trigger.lowAuth",
            "overdrive.trigger.contradicted",
            "overdrive.blackbox.available",
            "overdrive.finalCandidateCount",
            "overdrive.stagesApplied",
            "overdrive.exactPhraseProbeUsed",
            "overdrive.bypassReason",
            "embed.sourceDim",
            "embed.targetDim",
            "embed.sliceMethod",
            "embed.normalizeApplied",
            "embed.sliceReason",
            "cfvm.activeTile",
            "cfvm.triggered",
            "cfvm.jb.score",
            "cfvm.cb.score",
            "cfvm.boltzmannWeight",
            "cfvm.rawTileId",
            "cfvm.retrievalOrderAdjusted",
            "cfvm.recoveryPath",
            "cfvm.boltzmannTemp",
            "cfvm.tempSource",
            "cfvm.tempAnnealApplied",
            "chain.steps.planned",
            "llm.versionPurity",
            "llm.primary.port",
            "strategy.conflict.overdriveDeferred",
            "hypernova.whitening.applied",
            "hypernova.whitening.method",
            "hypernova.whitening.provider"
    );

    private static final List<TestGroup> TEST_GROUPS = List.of(
            new TestGroup("S01", "Overdrive", List.of("Overdrive", "DynamicContextCompress")),
            new TestGroup("S02", "CFVM", List.of("Cfvm", "RawMatrix", "RawSlot", "RetrievalOrder")),
            new TestGroup("S03", "MoE", List.of("NineArtPlateGate", "RgbStrategySelector",
                    "MoeCandidateRouter", "CriticNode", "ArtPlate")),
            new TestGroup("S04", "Matryoshka", List.of("Embedding", "Matryoshka", "OllamaEmbedding")),
            new TestGroup("S05", "ExtremeZ", List.of("ExtremeZ", "QueryBurst", "CancelShield")),
            new TestGroup("S06", "HYPERNOVA", List.of("TailWeighted", "CvarAgg", "RiskK",
                    "DppDiversity", "Hypernova")),
            new TestGroup("S07", "CIH-RAG", List.of("AttachmentContext", "LlmRouter", "OnnxCrossEncoder")),
            new TestGroup("S08", "Version Purity", List.of("ProviderGuard", "VersionPurity", "LlmConfig"))
    );

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        HarmonyReport report = scan(parsed.root());
        Path parent = parsed.output().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String rendered = report.render();
        Files.writeString(parsed.output(), rendered);
        System.out.println(rendered);
    }

    static HarmonyReport scan(Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        HarmonyEvidenceContract contract;
        try {
            contract = HarmonyEvidenceContract.loadClasspath();
        } catch (RuntimeException error) {
            return HarmonyReport.blockedContract(normalizedRoot, error.getClass().getSimpleName());
        }
        SourceFingerprint sourceFingerprint = sourceFingerprint(normalizedRoot, contract);
        String mainJava = readTree(normalizedRoot.resolve("main/java"), ".java")
                + readTree(normalizedRoot.resolve("app/src/main/java_clean"), ".java");
        String resources = readTree(normalizedRoot.resolve("main/resources"), ".yml", ".yaml", ".properties")
                + readTree(normalizedRoot.resolve("app/src/main/resources"), ".yml", ".yaml", ".properties");
        List<String> testPaths = collectRelativePaths(normalizedRoot, normalizedRoot.resolve("src/test/java"));
        String lowerMainJava = mainJava.toLowerCase(Locale.ROOT);
        String secretScanText = mainJava + "\n" + resources;

        Map<String, Boolean> traceCoverage = new LinkedHashMap<>();
        LinkedHashSet<String> traceKeys = new LinkedHashSet<>(TRACE_KEYS);
        traceKeys.addAll(contract.requiredTraceKeys());
        for (String key : traceKeys) {
            traceCoverage.put(key, containsTraceKey(mainJava, key));
        }

        Map<TestGroup, Boolean> testCoverage = new LinkedHashMap<>();
        for (TestGroup group : TEST_GROUPS) {
            testCoverage.put(group, testPaths.stream().anyMatch(group::matches));
        }

        int exactEmptyCatchMatches = countMatches(EMPTY_CATCH, mainJava);
        int secretPatternHits = countMatches(SECRET_PATTERN, secretScanText);
        DuplicateFqcnSummary duplicateFqcnSummary = duplicateFqcnSummary(normalizedRoot);

        List<HarmonyBreak> breaks = List.of(
                hb(contract, "HB-01",
                        exactEmptyCatchMatches <= 45 && lowerMainJava.contains("failsoft")),
                hb(contract, "HB-02",
                        traceCoverage.get("retrievalOrder.lastSetBy")
                                && containsAll(mainJava, "class RetrievalOrderService")),
                hb(contract, "HB-03",
                        traceCoverage.get("boosterMode.active")
                                && traceCoverage.get("boosterMode.excludedModes")
                                && traceCoverage.get("boosterMode.exclusionReason")),
                hb(contract, "HB-04",
                        traceCoverage.get("hypernova.dppApplied")
                                && containsAll(mainJava, "DppDiversityReranker")),
                hb(contract, "HB-05",
                        containsTraceKey(mainJava, "hypernova.twpmP")
                                && containsAll(mainJava, "TailWeightedPowerMeanFuser")),
                hb(contract, "HB-06",
                        traceCoverage.get("hypernova.sourceScoreScaleMismatchCount")
                                || containsTraceKey(mainJava, "fusion.scoreNormalized")),
                hb(contract, "HB-07",
                        traceCoverage.get("extremeZ.cancelShieldWrapped")
                                && traceCoverage.get("extremeZ.timeBudgetConsumedMs")
                                && mainJava.contains("cancel(false)")),
                hb(contract, "HB-08",
                        traceCoverage.get("cfvm.boltzmannTemp") && traceCoverage.get("cfvm.tempSource")),
                hb(contract, "HB-09",
                        containsAny(mainJava, "cfvm.rawTileId", "cfvm.rawTile.enabled",
                                "cfvm.rawTileBuilderDisabled")),
                hb(contract, "HB-10",
                        traceCoverage.get("moe.evolverPlateRegistered")
                                && !lowerMainJava.contains("setprompttemplate(\"")),
                hb(contract, "HB-11",
                        traceCoverage.get("extremeZ.timeBudgetConsumedMs")
                                || containsTraceKey(mainJava, "timeBudget.firstExhaustedStage")),
                hb(contract, "HB-12",
                        traceCoverage.get("hypernova.whitening.provider"))
        );

        return new HarmonyReport(
                normalizedRoot,
                traceCoverage,
                testCoverage,
                breaks,
                contract,
                "",
                sourceFingerprint,
                exactEmptyCatchMatches,
                duplicateFqcnSummary,
                secretPatternHits);
    }

    private static HarmonyBreak hb(
            HarmonyEvidenceContract contract,
            String id,
            boolean done) {
        HarmonyEvidenceContract.HarmonyBreakDefinition definition = contract.breaks().stream()
                .filter(candidate -> id.equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing contract break " + id));
        return new HarmonyBreak(
                id,
                definition.label(),
                done ? contract.verifiedStatus() : contract.blockedStatus(),
                definition.weight());
    }

    private static boolean containsTraceKey(String content, String key) {
        return content.contains("\"" + key + "\"") || content.contains("'" + key + "'");
    }

    private static boolean containsAll(String content, String... needles) {
        for (String needle : needles) {
            if (!content.contains(needle)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAny(String content, String... needles) {
        for (String needle : needles) {
            if (content.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static SourceFingerprint sourceFingerprint(
            Path root,
            HarmonyEvidenceContract contract) {
        List<SourceRootFingerprint> roots = new ArrayList<>();
        List<String> aggregateRows = new ArrayList<>();
        int errorCount = 0;
        int totalFiles = 0;
        for (HarmonyEvidenceContract.SourceRoot sourceRoot : contract.activeSourceRoots()) {
            Path absoluteRoot = root.resolve(sourceRoot.path());
            boolean exists = Files.isDirectory(absoluteRoot);
            List<String> fileRows = new ArrayList<>();
            if (exists) {
                try (Stream<Path> paths = Files.walk(absoluteRoot)) {
                    List<Path> files = paths
                            .filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(
                                    path -> absoluteRoot.relativize(path).toString().replace('\\', '/')))
                            .toList();
                    for (Path path : files) {
                        try {
                            String relative = absoluteRoot.relativize(path).toString().replace('\\', '/');
                            fileRows.add(relative + "\0" + sha256(path));
                        } catch (IOException error) {
                            errorCount++;
                        }
                    }
                } catch (IOException error) {
                    errorCount++;
                }
            }
            String rootSha256 = sha256(String.join("\n", fileRows));
            int fileCount = fileRows.size();
            totalFiles += fileCount;
            roots.add(new SourceRootFingerprint(
                    sourceRoot.id(),
                    sourceRoot.path(),
                    exists,
                    fileCount,
                    rootSha256));
            aggregateRows.add(sourceRoot.id()
                    + "\0" + Boolean.toString(exists)
                    + "\0" + fileCount
                    + "\0" + rootSha256);
        }
        String status = errorCount == 0
                ? contract.verifiedStatus()
                : contract.blockedStatus();
        return new SourceFingerprint(
                status,
                totalFiles,
                errorCount,
                sha256(String.join("\n", aggregateRows)),
                roots);
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(String value) {
        MessageDigest digest = sha256Digest();
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String readTree(Path root, String... suffixes) throws IOException {
        if (!Files.isDirectory(root)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> hasSuffix(path, suffixes))
                    .toList()) {
                out.append(Files.readString(path)).append('\n');
            }
        }
        return out.toString();
    }

    private static boolean hasSuffix(Path path, String... suffixes) {
        String value = path.toString().toLowerCase(Locale.ROOT);
        for (String suffix : suffixes) {
            if (value.endsWith(suffix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> collectRelativePaths(Path sourceRoot, Path root) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                out.add(sourceRoot.relativize(path).toString().replace('\\', '/'));
            }
        }
        return out;
    }

    private static DuplicateFqcnSummary duplicateFqcnSummary(Path root) throws IOException {
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        Map<String, Path> javaRoots = new LinkedHashMap<>();
        javaRoots.put("main/java", root.resolve("main/java"));
        javaRoots.put("app/src/main/java_clean", root.resolve("app/src/main/java_clean"));
        for (Map.Entry<String, Path> entry : javaRoots.entrySet()) {
            String rootLabel = entry.getKey();
            Path javaRoot = entry.getValue();
            if (!Files.isDirectory(javaRoot)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(javaRoot)) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .toList()) {
                    String text = Files.readString(path);
                    Matcher pkg = PACKAGE_PATTERN.matcher(text);
                    Matcher type = TYPE_PATTERN.matcher(text);
                    if (pkg.find() && type.find()) {
                        String fqcn = pkg.group(1) + "." + type.group(1);
                        counts.computeIfAbsent(fqcn, ignored -> new LinkedHashMap<>())
                                .merge(rootLabel, 1, Integer::sum);
                    }
                }
            }
        }
        int duplicates = 0;
        int sameSourceRootDuplicates = 0;
        int crossSourceRootDuplicates = 0;
        for (Map<String, Integer> byRoot : counts.values()) {
            int total = byRoot.values().stream().mapToInt(Integer::intValue).sum();
            if (total > 1) {
                duplicates++;
            }
            if (byRoot.values().stream().anyMatch(count -> count > 1)) {
                sameSourceRootDuplicates++;
            }
            if (byRoot.size() > 1) {
                crossSourceRootDuplicates++;
            }
        }
        return new DuplicateFqcnSummary(duplicates, sameSourceRootDuplicates, crossSourceRootDuplicates);
    }

    record Args(Path root, Path output) {
        static Args parse(String[] args) {
            Path root = Paths.get("").toAbsolutePath().normalize();
            Path output = root.resolve("verification/harmony-build-report.txt");
            for (int i = 0; args != null && i < args.length; i++) {
                if ("--root".equals(args[i]) && i + 1 < args.length) {
                    root = Paths.get(args[++i]).toAbsolutePath().normalize();
                    if (!output.isAbsolute() || output.startsWith(Paths.get("").toAbsolutePath().normalize())) {
                        output = root.resolve("verification/harmony-build-report.txt");
                    }
                } else if ("--output".equals(args[i]) && i + 1 < args.length) {
                    output = Paths.get(args[++i]).toAbsolutePath().normalize();
                }
            }
            return new Args(root, output);
        }
    }

    record TestGroup(String id, String label, List<String> patterns) {
        boolean matches(String path) {
            String lowerPath = path.toLowerCase(Locale.ROOT);
            for (String pattern : patterns) {
                if (lowerPath.contains(pattern.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
    }

    record HarmonyBreak(String id, String label, String status, double weight) {
    }

    record SourceRootFingerprint(
            String id,
            String path,
            boolean exists,
            int fileCount,
            String sha256) {
    }

    record SourceFingerprint(
            String status,
            int fileCount,
            int errorCount,
            String sha256,
            List<SourceRootFingerprint> roots) {
        SourceFingerprint {
            roots = List.copyOf(roots);
        }
    }

    record DuplicateFqcnSummary(int duplicateCount,
                                int sameSourceRootDuplicateCount,
                                int crossSourceRootDuplicateCount) {
    }

    record HarmonyReport(Path root, Map<String, Boolean> traceCoverage,
                          Map<TestGroup, Boolean> testCoverage,
                          List<HarmonyBreak> breaks,
                          HarmonyEvidenceContract contract,
                          String contractErrorType,
                          SourceFingerprint sourceFingerprint,
                          int exactEmptyCatchMatches,
                          DuplicateFqcnSummary duplicateFqcnSummary,
                          int secretPatternHits) {
        static HarmonyReport blockedContract(Path root, String errorType) {
            return new HarmonyReport(
                    root,
                    Map.of(),
                    Map.of(),
                    List.of(new HarmonyBreak(
                            "HB-CONTRACT",
                            "Harmony evidence contract integrity",
                            "BLOCKED_EVIDENCE",
                            100.0d)),
                    null,
                    errorType,
                    new SourceFingerprint(
                            "BLOCKED_EVIDENCE",
                            0,
                            1,
                            sha256(""),
                            List.of()),
                    0,
                    new DuplicateFqcnSummary(0, 0, 0),
                    0);
        }

        String render() {
            StringBuilder report = new StringBuilder();
            report.append("=== Dynamic RAG Harmony Build Report ===\n");
            report.append("root=").append(root).append('\n');
            report.append("[harmony][contract] status=")
                    .append(contract == null ? "BLOCKED_EVIDENCE" : contract.verifiedStatus())
                    .append(" schemaVersion=")
                    .append(contract == null ? "" : contract.schemaVersion())
                    .append(" contractId=")
                    .append(contract == null ? "" : contract.contractId())
                    .append(" sha256=")
                    .append(contract == null ? "" : contract.sha256())
                    .append(" errorType=")
                    .append(contractErrorType == null ? "" : contractErrorType)
                    .append('\n');
            report.append("[harmony][sourceset] activeRoots=main/java,main/resources,src/test/java,src/test/resources,app/src/main/java_clean,app/src/main/resources\n");
            report.append("[harmony][fingerprint] status=")
                    .append(sourceFingerprint.status())
                    .append(" roots=")
                    .append(sourceFingerprint.roots().size())
                    .append(" fileCount=")
                    .append(sourceFingerprint.fileCount())
                    .append(" errorCount=")
                    .append(sourceFingerprint.errorCount())
                    .append(" sha256=")
                    .append(sourceFingerprint.sha256())
                    .append('\n');
            for (SourceRootFingerprint item : sourceFingerprint.roots()) {
                report.append("[harmony][fingerprint-root] id=")
                        .append(item.id())
                        .append(" path=")
                        .append(item.path())
                        .append(" exists=")
                        .append(item.exists())
                        .append(" fileCount=")
                        .append(item.fileCount())
                        .append(" sha256=")
                        .append(item.sha256())
                        .append('\n');
            }
            for (Map.Entry<String, Boolean> entry : traceCoverage.entrySet()) {
                report.append("[harmony][trace] ")
                        .append(entry.getKey())
                        .append('=')
                        .append(entry.getValue() ? "FOUND" : "MISSING")
                        .append('\n');
            }
            for (Map.Entry<TestGroup, Boolean> entry : testCoverage.entrySet()) {
                report.append("[harmony][tests] ")
                        .append(entry.getKey().id())
                        .append('=')
                        .append(entry.getValue() ? "FOUND" : "MISSING")
                        .append(" label=")
                        .append(entry.getKey().label())
                        .append('\n');
            }
            for (HarmonyBreak item : breaks) {
                report.append("[harmony][")
                        .append(item.id())
                        .append("] ")
                        .append(item.status())
                        .append(" label=")
                        .append(item.label())
                        .append(" weight=")
                        .append(item.weight())
                        .append('\n');
            }
            report.append("[harmony][runtime-evidence] status=BLOCKED_EVIDENCE reason=static_scanner_has_no_typed_trace_frame\n");
            boolean promotionAllowed = false;
            report.append("[harmony][score] score=")
                    .append(promotionAllowed ? "100.0" : "0.0")
                    .append(" promotionAllowed=")
                    .append(promotionAllowed)
                    .append(" status=")
                    .append(promotionAllowed ? "DONE" : "BLOCKED_EVIDENCE")
                    .append('\n');
            report.append("[harmony][catch] exactEmptyCatchMatches=")
                    .append(exactEmptyCatchMatches)
                    .append('\n');
            report.append("[harmony][duplicate-fqcn] duplicateCount=")
                    .append(duplicateFqcnSummary.duplicateCount())
                    .append(" sameSourceRootDuplicateCount=")
                    .append(duplicateFqcnSummary.sameSourceRootDuplicateCount())
                    .append(" crossSourceRootDuplicateCount=")
                    .append(duplicateFqcnSummary.crossSourceRootDuplicateCount())
                    .append(" compileBlockingDuplicateCount=")
                    .append(duplicateFqcnSummary.sameSourceRootDuplicateCount())
                    .append('\n');
            report.append("[harmony][security] secretPatternHits=")
                    .append(secretPatternHits)
                    .append('\n');
            return report.toString();
        }
    }
}
