package com.example.lms.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Static score-analysis harness for the active Desktop source root.
 *
 * <p>The runner intentionally does not execute providers or retrieval
 * pipelines. It verifies the source families called out by
 * {@code source_score_analysis.md} and writes a deterministic report.</p>
 */
public class ScoringRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_EVIDENCE_BYTES = 64 * 1024;
    private static final int MAX_TEST_SOURCE_BYTES = 256 * 1024;
    private static final int MAX_TEXT_LENGTH = 512;
    private static final int MAX_TEST_COUNT = 1_000_000;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Map<String, EvidenceSpec> EVIDENCE_SPECS = evidenceSpecs();

    private static final Pattern CATCH_HEADER = Pattern.compile("\\bcatch\\s*\\(");
    private static final Pattern TRAILING_IDENTIFIER = Pattern.compile(
            "([A-Za-z_$][A-Za-z0-9_$]*)\\s*$");
    private static final int LARGE_SOURCE_THRESHOLD_LINES = 2_000;
    private static final int MAX_STRUCTURAL_PENALTY = 10;

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        ScoreReport report = score(parsed.root(), parsed.evidence());
        Path parent = parsed.output().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String rendered = report.render();
        Files.writeString(parsed.output(), rendered);
        System.out.println(rendered);
    }

    static ScoreReport score(Path root) throws IOException {
        return score(root, null);
    }

    static ScoreReport score(Path root, Path evidencePath) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        String sourceIdentityHash = sourceIdentity(normalizedRoot);
        EvidenceEvaluation evidence = evaluateEvidence(
                normalizedRoot, evidencePath, sourceIdentityHash);
        String mainJava = readTree(normalizedRoot.resolve("main/java"));
        int provenSilentCatchMatches = provenSilentCatchMatches(mainJava);
        List<LargeSourceFile> largeActiveSourceFiles = new ArrayList<>();
        largeActiveSourceFiles.addAll(collectLargeJavaFiles(
                normalizedRoot, normalizedRoot.resolve("main/java"), LARGE_SOURCE_THRESHOLD_LINES));
        largeActiveSourceFiles.addAll(collectLargeJavaFiles(
                normalizedRoot, normalizedRoot.resolve("app/src/main/java_clean"),
                LARGE_SOURCE_THRESHOLD_LINES));
        largeActiveSourceFiles.sort((a, b) -> {
            int byLines = Integer.compare(b.lines(), a.lines());
            return byLines != 0 ? byLines : a.path().compareTo(b.path());
        });

        List<Check> checks = new ArrayList<>();
        for (Map.Entry<String, EvidenceSpec> entry : EVIDENCE_SPECS.entrySet()) {
            EvidenceSpec spec = entry.getValue();
            EvidenceOutcome outcome = evidence.outcome(entry.getKey());
            checks.add(new Check(
                    spec.categoryId(),
                    spec.label(),
                    outcome.ok() ? spec.points() : 0,
                    spec.points(),
                    outcome.ok(),
                    outcome.reason()));
        }
        checks.add(new Check(
                "silent-catch",
                "Proven silent catch debt",
                silentCatchPoints(provenSilentCatchMatches),
                15,
                provenSilentCatchMatches == 0,
                provenSilentCatchMatches == 0 ? "passed" : "debt-present"));
        int structuralPenaltyPoints = Math.min(
                MAX_STRUCTURAL_PENALTY,
                2 * Math.min(MAX_STRUCTURAL_PENALTY / 2, largeActiveSourceFiles.size()));
        return new ScoreReport(
                normalizedRoot,
                sourceIdentityHash,
                checks,
                provenSilentCatchMatches,
                largeActiveSourceFiles,
                structuralPenaltyPoints);
    }

    private static Map<String, EvidenceSpec> evidenceSpecs() {
        Map<String, EvidenceSpec> specs = new LinkedHashMap<>();
        specs.put("sourceSetVersionPurity", new EvidenceSpec(
                "active-root", "Desktop active sourceSet and LangChain4j purity gates",
                10, "command", "checkSourceSetHygiene+checkLangchain4jVersionPurity", null));
        specs.put("cfvmBehavior", new EvidenceSpec(
                "cfvm", "CFVM executable behavior contract",
                15, "test", "com.example.lms.cfvm.CfvmSnapshotRoundTripTest", null));
        specs.put("artPlateBehavior", new EvidenceSpec(
                "artplate", "ArtPlate executable behavior contract",
                10, "test", "com.example.lms.artplate.NineArtPlateGateRolloutTest", null));
        specs.put("hypernovaBehavior", new EvidenceSpec(
                "hypernova", "HYPERNOVA executable behavior contract",
                20, "test", "com.nova.protocol.fusion.NovaNextFusionServiceTest", null));
        specs.put("piiRedaction", new EvidenceSpec(
                "pii", "PII redaction behavior contract",
                10, "test", "com.example.lms.service.guard.PIISanitizerTest", null));
        specs.put("citationOwnership", new EvidenceSpec(
                "citation-gate", "Canonical citation response-path contract",
                10, "test",
                "com.example.lms.service.rag.CitationGateEvidenceComposerBoundaryContractTest",
                "ChatApiController->ChatWorkflow->RagEvidenceAttributionService->CitationGate"));
        specs.put("promptBoundary", new EvidenceSpec(
                "prompt-trace", "PromptBuilder and trace active-path contract",
                10, "test", "com.example.lms.prompt.PromptBuilderBoundaryTest",
                "ChatWorkflow->PromptBuilder.build(PromptContext)->TraceStore"));
        return Collections.unmodifiableMap(specs);
    }

    private static EvidenceEvaluation evaluateEvidence(
            Path root, Path evidencePath, String sourceIdentityHash) {
        if (evidencePath == null) {
            return EvidenceEvaluation.all("evidence-needed");
        }
        BoundedJson envelope = readBoundedJson(root, evidencePath);
        if (envelope.kind() == ReadKind.MISSING) {
            return EvidenceEvaluation.all("evidence-needed");
        }
        if (envelope.kind() != ReadKind.OK) {
            return EvidenceEvaluation.all("metric-input-invalid");
        }
        JsonNode rootNode = envelope.node();
        if (!hasExactFields(rootNode, Set.of("schemaVersion", "sourceHead", "checks"))
                || !isSchemaVersionOne(rootNode)
                || !sourceIdentityHash.equals(textField(rootNode, "sourceHead"))) {
            return EvidenceEvaluation.all("metric-input-invalid");
        }
        JsonNode checksNode = rootNode.get("checks");
        if (checksNode == null || !checksNode.isObject()) {
            return EvidenceEvaluation.all("metric-input-invalid");
        }
        Set<String> suppliedNames = fieldNames(checksNode);
        if (!EVIDENCE_SPECS.keySet().containsAll(suppliedNames)) {
            return EvidenceEvaluation.all("metric-input-invalid");
        }

        Map<String, EvidenceOutcome> outcomes = new LinkedHashMap<>();
        for (Map.Entry<String, EvidenceSpec> entry : EVIDENCE_SPECS.entrySet()) {
            JsonNode checkNode = checksNode.get(entry.getKey());
            outcomes.put(entry.getKey(), evaluateCheck(
                    root,
                    sourceIdentityHash,
                    entry.getKey(),
                    entry.getValue(),
                    checkNode));
        }
        return new EvidenceEvaluation(Collections.unmodifiableMap(outcomes));
    }

    private static EvidenceOutcome evaluateCheck(
            Path root,
            String sourceIdentityHash,
            String checkName,
            EvidenceSpec spec,
            JsonNode checkNode) {
        if (checkNode == null) {
            return EvidenceOutcome.missing();
        }
        Set<String> requiredFields = new HashSet<>(Set.of(
                "status", spec.commandField(), "artifactSha256"));
        if (spec.activeCallPath() != null) {
            requiredFields.add("activeCallPath");
        }
        if (!hasExactFields(checkNode, requiredFields)) {
            return EvidenceOutcome.invalid();
        }
        String status = textField(checkNode, "status");
        String expectedArtifactHash = textField(checkNode, "artifactSha256");
        if (!("PASS".equals(status) || "FAIL".equals(status))
                || !spec.commandOrTest().equals(textField(checkNode, spec.commandField()))
                || expectedArtifactHash == null
                || !SHA256.matcher(expectedArtifactHash).matches()
                || (spec.activeCallPath() != null
                && !spec.activeCallPath().equals(textField(checkNode, "activeCallPath")))) {
            return EvidenceOutcome.invalid();
        }
        if ("test".equals(spec.commandField())) {
            TestSourceQualification qualification =
                    qualifiedJUnitFiveTestSource(root, spec.commandOrTest());
            if (!qualification.qualified()) {
                return EvidenceOutcome.invalidTestSource(qualification);
            }
        }

        Path artifactPath = root.resolve(
                "verification/source-score-evidence/" + checkName + ".json");
        BoundedJson artifact = readBoundedJson(root, artifactPath);
        if (artifact.kind() == ReadKind.MISSING) {
            return EvidenceOutcome.missing();
        }
        if (artifact.kind() != ReadKind.OK
                || !expectedArtifactHash.equals(sha256(artifact.bytes()))) {
            return EvidenceOutcome.invalid();
        }

        JsonNode artifactNode = artifact.node();
        Set<String> artifactFields = new HashSet<>(Set.of(
                "schemaVersion", "sourceHead", "check", "status",
                spec.commandField(), "tests", "failures", "errors"));
        if (spec.activeCallPath() != null) {
            artifactFields.add("activeCallPath");
        }
        if (!hasExactFields(artifactNode, artifactFields)
                || !isSchemaVersionOne(artifactNode)
                || !sourceIdentityHash.equals(textField(artifactNode, "sourceHead"))
                || !checkName.equals(textField(artifactNode, "check"))
                || !status.equals(textField(artifactNode, "status"))
                || !spec.commandOrTest().equals(textField(artifactNode, spec.commandField()))
                || (spec.activeCallPath() != null
                && !spec.activeCallPath().equals(textField(artifactNode, "activeCallPath")))) {
            return EvidenceOutcome.invalid();
        }

        Integer tests = boundedCount(artifactNode, "tests");
        Integer failures = boundedCount(artifactNode, "failures");
        Integer errors = boundedCount(artifactNode, "errors");
        if (tests == null || failures == null || errors == null
                || tests < 1 || failures > tests || errors > tests
                || (long) failures + errors > tests) {
            return EvidenceOutcome.invalid();
        }
        if ("PASS".equals(status)) {
            return failures == 0 && errors == 0
                    ? EvidenceOutcome.artifactConsistentPass()
                    : EvidenceOutcome.invalid();
        }
        return failures + errors > 0
                ? EvidenceOutcome.failed()
                : EvidenceOutcome.invalid();
    }

    private static TestSourceQualification qualifiedJUnitFiveTestSource(Path root, String testFqcn) {
        String pathToken = "src/test/java/" + testFqcn.replace('.', '/') + ".java";
        int separator = testFqcn.lastIndexOf('.');
        if (separator <= 0 || separator == testFqcn.length() - 1) {
            return TestSourceQualification.invalid("test-source-contract-invalid", pathToken);
        }
        String packageName = testFqcn.substring(0, separator);
        String className = testFqcn.substring(separator + 1);
        Path sourcePath = root.resolve(pathToken);
        java.util.concurrent.atomic.AtomicReference<String> readReason =
                new java.util.concurrent.atomic.AtomicReference<>("test-source-contract-invalid");
        String source = readBoundedTestSource(root, sourcePath, readReason::set);
        if (source == null) {
            return TestSourceQualification.invalid(readReason.get(), pathToken);
        }
        String code = maskJavaCommentsAndLiterals(source);
        Pattern packagePattern = Pattern.compile(
                "(?m)^\\s*package\\s+" + Pattern.quote(packageName) + "\\s*;");
        Matcher packageMatcher = packagePattern.matcher(code);
        if (!packageMatcher.find() || packageMatcher.find()
                || countMatches(Pattern.compile("(?m)^\\s*package\\s+[^;]+;"), code) != 1) {
            return TestSourceQualification.invalid("test-source-contract-invalid", pathToken);
        }

        Matcher classMatcher = Pattern.compile(
                "\\bclass\\s+" + Pattern.quote(className) + "\\b").matcher(code);
        int classStart = -1;
        int classOpen = -1;
        while (classMatcher.find()) {
            if (braceDepthAt(code, 0, classMatcher.start()) != 0) {
                continue;
            }
            int candidateOpen = code.indexOf('{', classMatcher.end());
            if (candidateOpen >= 0 && braceDepthAt(code, 0, candidateOpen) == 0) {
                classStart = classMatcher.start();
                classOpen = candidateOpen;
                break;
            }
        }
        if (classOpen < 0) {
            return TestSourceQualification.invalid("test-source-contract-invalid", pathToken);
        }
        int classClose = matchingBrace(code, classOpen);
        if (classClose < 0) {
            return TestSourceQualification.invalid("test-source-contract-invalid", pathToken);
        }

        AnnotationResolution testAnnotations = resolveAnnotation(
                code, "Test", "org.junit.jupiter.api.Test");
        AnnotationResolution disabledAnnotations = resolveAnnotation(
                code, "Disabled", "org.junit.jupiter.api.Disabled");
        if (!testAnnotations.unambiguous() || !disabledAnnotations.unambiguous()) {
            return TestSourceQualification.invalid("test-source-contract-invalid", pathToken);
        }
        for (AnnotationUse disabled : disabledAnnotations.uses()) {
            if (disabled.start() < classStart
                    && disabled.start() > packageMatcher.end()
                    && braceDepthAt(code, 0, disabled.start()) == 0) {
                return TestSourceQualification.invalid("test-source-contract-invalid", pathToken);
            }
        }

        for (AnnotationUse test : testAnnotations.uses()) {
            if (test.start() <= classOpen || test.start() >= classClose
                    || braceDepthAt(code, classOpen, test.start()) != 1) {
                continue;
            }
            Matcher method = directTestMethodPattern().matcher(code);
            method.region(test.end(), classClose);
            if (!method.lookingAt()) {
                continue;
            }
            int methodOpen = method.end() - 1;
            if (methodOpen <= test.end()
                    || code.charAt(methodOpen) != '{'
                    || braceDepthAt(code, classOpen, methodOpen) != 1) {
                continue;
            }
            int memberStart = Math.max(
                    classOpen,
                    Math.max(code.lastIndexOf(';', test.start()), code.lastIndexOf('}', test.start())));
            boolean disabled = false;
            for (AnnotationUse disabledUse : disabledAnnotations.uses()) {
                if (disabledUse.start() > memberStart
                        && disabledUse.start() < methodOpen
                        && braceDepthAt(code, classOpen, disabledUse.start()) == 1) {
                    disabled = true;
                    break;
                }
            }
            if (!disabled) {
                return TestSourceQualification.valid(pathToken);
            }
        }
        return TestSourceQualification.invalid("test-source-contract-invalid", pathToken);
    }

    private static Pattern directTestMethodPattern() {
        return Pattern.compile(
                "(?s)\\s*(?:@[A-Za-z_$][\\w.$]*(?:\\s*\\([^{};]*\\))?\\s*)*"
                        + "(?:(?:public|protected|private|static|final|synchronized|strictfp)\\s+)*"
                        + "void\\s+[A-Za-z_$][\\w$]*\\s*\\([^{};]*\\)\\s*"
                        + "(?:throws\\s+[^{};]+\\s*)?\\{");
    }

    private static AnnotationResolution resolveAnnotation(
            String code, String simpleName, String fqcn) {
        Pattern fullyQualified = Pattern.compile(
                "@\\s*" + Pattern.quote(fqcn) + "\\b");
        Pattern bare = Pattern.compile("@\\s*" + Pattern.quote(simpleName) + "\\b");
        List<AnnotationUse> qualifiedUses = annotationUses(fullyQualified, code);
        List<AnnotationUse> bareUses = annotationUses(bare, code);

        Pattern exactImport = Pattern.compile(
                "(?m)^\\s*import\\s+" + Pattern.quote(fqcn) + "\\s*;");
        Pattern anySimpleImport = Pattern.compile(
                "(?m)^\\s*import\\s+(?:static\\s+)?[^;]*\\."
                        + Pattern.quote(simpleName) + "\\s*;");
        Pattern junitWildcardImport = Pattern.compile(
                "(?m)^\\s*import\\s+org\\.junit[^;]*\\.\\*\\s*;");
        Pattern localType = Pattern.compile(
                "(?:@\\s*interface|\\bclass|\\binterface|\\benum|\\brecord)\\s+"
                        + Pattern.quote(simpleName) + "\\b");

        boolean bareResolved = bareUses.isEmpty()
                || (countMatches(exactImport, code) == 1
                && countMatches(anySimpleImport, code) == 1
                && !junitWildcardImport.matcher(code).find()
                && !localType.matcher(code).find());
        if (!bareResolved) {
            return new AnnotationResolution(false, List.of());
        }
        List<AnnotationUse> uses = new ArrayList<>(qualifiedUses);
        uses.addAll(bareUses);
        uses.sort(Comparator.comparingInt(AnnotationUse::start));
        return new AnnotationResolution(true, List.copyOf(uses));
    }

    private static List<AnnotationUse> annotationUses(Pattern pattern, String code) {
        List<AnnotationUse> uses = new ArrayList<>();
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            uses.add(new AnnotationUse(matcher.start(), matcher.end()));
        }
        return uses;
    }

    private static int braceDepthAt(String code, int start, int exclusiveEnd) {
        int depth = 0;
        int safeStart = Math.max(0, start);
        int safeEnd = Math.min(code.length(), Math.max(safeStart, exclusiveEnd));
        for (int i = safeStart; i < safeEnd; i++) {
            char ch = code.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth < 0) {
                    return -1;
                }
            }
        }
        return depth;
    }

    private static int matchingBrace(String code, int open) {
        return matchingDelimiter(code, open, '{', '}');
    }

    private static int matchingDelimiter(String code, int open, char opening, char closing) {
        if (open < 0 || open >= code.length() || code.charAt(open) != opening) {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == opening) {
                depth++;
            } else if (ch == closing) {
                depth--;
                if (depth == 0) {
                    return i;
                }
                if (depth < 0) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String maskJavaCommentsAndLiterals(String source) {
        char[] masked = source.toCharArray();
        int state = 0;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (state == 0) {
                if (ch == '/' && next == '/') {
                    mask(masked, i, i + 2);
                    state = 1;
                    i++;
                } else if (ch == '/' && next == '*') {
                    mask(masked, i, i + 2);
                    state = 2;
                    i++;
                } else if (ch == '"' && i + 2 < source.length()
                        && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                    mask(masked, i, i + 3);
                    state = 5;
                    i += 2;
                } else if (ch == '"') {
                    mask(masked, i, i + 1);
                    state = 3;
                } else if (ch == '\'') {
                    mask(masked, i, i + 1);
                    state = 4;
                }
            } else if (state == 1) {
                if (ch == '\r' || ch == '\n') {
                    state = 0;
                } else {
                    masked[i] = ' ';
                }
            } else if (state == 2) {
                if (ch == '*' && next == '/') {
                    mask(masked, i, i + 2);
                    state = 0;
                    i++;
                } else if (ch != '\r' && ch != '\n') {
                    masked[i] = ' ';
                }
            } else if (state == 3 || state == 4) {
                char delimiter = state == 3 ? '"' : '\'';
                if (ch == '\\' && i + 1 < source.length()) {
                    mask(masked, i, i + 2);
                    i++;
                } else {
                    if (ch != '\r' && ch != '\n') {
                        masked[i] = ' ';
                    }
                    if (ch == delimiter) {
                        state = 0;
                    }
                }
            } else if (state == 5) {
                if (ch == '"' && i + 2 < source.length()
                        && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"'
                        && !isEscaped(source, i)) {
                    mask(masked, i, i + 3);
                    state = 0;
                    i += 2;
                } else if (ch != '\r' && ch != '\n') {
                    masked[i] = ' ';
                }
            }
        }
        if (state == 2 || state == 3 || state == 4 || state == 5) {
            return "";
        }
        return new String(masked);
    }

    private static int provenSilentCatchMatches(String source) {
        if (source == null || source.isEmpty()) {
            return 0;
        }
        String code = maskJavaCommentsAndLiterals(source);
        if (code.isEmpty()) {
            return 0;
        }
        Matcher catches = CATCH_HEADER.matcher(code);
        int count = 0;
        int searchFrom = 0;
        while (searchFrom < code.length() && catches.find(searchFrom)) {
            int parameterOpen = code.indexOf('(', catches.start());
            int parameterClose = matchingDelimiter(code, parameterOpen, '(', ')');
            if (parameterClose < 0) {
                break;
            }
            int bodyOpen = parameterClose + 1;
            while (bodyOpen < code.length() && Character.isWhitespace(code.charAt(bodyOpen))) {
                bodyOpen++;
            }
            if (bodyOpen >= code.length() || code.charAt(bodyOpen) != '{') {
                searchFrom = parameterClose + 1;
                continue;
            }
            int bodyClose = matchingBrace(code, bodyOpen);
            if (bodyClose < 0) {
                break;
            }
            String caughtVariable = trailingIdentifier(
                    code.substring(parameterOpen + 1, parameterClose));
            if (caughtVariable != null && isProvenSilentCatchBody(
                    code.substring(bodyOpen + 1, bodyClose), caughtVariable)) {
                count++;
            }
            // Search within the body too, so a legitimate outer handler does not
            // hide a nested proven-silent catch from the debt count.
            searchFrom = bodyOpen + 1;
        }
        return count;
    }

    private static String trailingIdentifier(String catchParameter) {
        Matcher matcher = TRAILING_IDENTIFIER.matcher(catchParameter == null ? "" : catchParameter);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean isProvenSilentCatchBody(String body, String caughtVariable) {
        String compact = body.replaceAll("[\\s{}]", "");
        if (compact.isEmpty()) {
            return true;
        }
        String ignoredAccessor = caughtVariable + ".getMessage();";
        int offset = 0;
        while (compact.startsWith(ignoredAccessor, offset)) {
            offset += ignoredAccessor.length();
        }
        return offset == compact.length();
    }

    private static void mask(char[] chars, int start, int exclusiveEnd) {
        for (int i = start; i < exclusiveEnd && i < chars.length; i++) {
            if (chars[i] != '\r' && chars[i] != '\n') {
                chars[i] = ' ';
            }
        }
    }

    private static boolean isEscaped(String source, int index) {
        int slashes = 0;
        for (int i = index - 1; i >= 0 && source.charAt(i) == '\\'; i--) {
            slashes++;
        }
        return (slashes & 1) == 1;
    }

    private static String readBoundedTestSource(
            Path root,
            Path candidate,
            java.util.function.Consumer<String> diagnostic) {
        try {
            Path lexicalRoot = root.toAbsolutePath().normalize();
            Path lexicalCandidate = candidate.toAbsolutePath().normalize();
            if (!lexicalCandidate.startsWith(lexicalRoot)) {
                return null;
            }
            Path cursor = lexicalRoot;
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) || isLinkOrOther(cursor)) {
                return null;
            }
            for (Path part : lexicalRoot.relativize(lexicalCandidate)) {
                cursor = cursor.resolve(part);
                if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) || isLinkOrOther(cursor)) {
                    return null;
                }
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    lexicalCandidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > MAX_TEST_SOURCE_BYTES
                    || !lexicalCandidate.getFileName().toString().endsWith(".java")) {
                return null;
            }
            if (!lexicalCandidate.toRealPath().startsWith(lexicalRoot.toRealPath())) {
                return null;
            }
            byte[] bytes = readBoundedNoFollow(lexicalCandidate, MAX_TEST_SOURCE_BYTES);
            if (bytes == null) {
                return null;
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            String reasonCode = "test-source-encoding-invalid";
            diagnostic.accept(reasonCode);
            return null;
        } catch (IOException | RuntimeException ex) {
            String reasonCode = "test-source-read-failed";
            diagnostic.accept(reasonCode);
            return null;
        }
    }

    private static BoundedJson readBoundedJson(Path root, Path candidate) {
        try {
            Path lexicalRoot = root.toAbsolutePath().normalize();
            Path lexicalCandidate = candidate.toAbsolutePath().normalize();
            if (!lexicalCandidate.startsWith(lexicalRoot)) {
                return BoundedJson.invalid();
            }

            Path cursor = lexicalRoot;
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                return BoundedJson.invalid();
            }
            if (isLinkOrOther(cursor)) {
                return BoundedJson.invalid();
            }
            for (Path part : lexicalRoot.relativize(lexicalCandidate)) {
                cursor = cursor.resolve(part);
                if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    return BoundedJson.missing();
                }
                if (isLinkOrOther(cursor)) {
                    return BoundedJson.invalid();
                }
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    lexicalCandidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > MAX_EVIDENCE_BYTES) {
                return BoundedJson.invalid();
            }
            Path realRoot = lexicalRoot.toRealPath();
            Path realCandidate = lexicalCandidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) {
                return BoundedJson.invalid();
            }
            byte[] bytes = readBoundedNoFollow(lexicalCandidate, MAX_EVIDENCE_BYTES);
            if (bytes == null) {
                return BoundedJson.invalid();
            }
            JsonNode node = JSON.readTree(bytes);
            return node == null ? BoundedJson.invalid() : BoundedJson.ok(node, bytes);
        } catch (IOException | RuntimeException ex) {
            return BoundedJson.invalid();
        }
    }

    private static boolean isLinkOrOther(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isSymbolicLink() || attributes.isOther();
    }

    private static byte[] readBoundedNoFollow(Path path, int maxBytes) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ByteBuffer buffer = ByteBuffer.allocate(8 * 1024);
            int total = 0;
            while (channel.read(buffer) >= 0) {
                int read = buffer.position();
                if (read == 0) {
                    buffer.clear();
                    continue;
                }
                total += read;
                if (total > maxBytes) {
                    return null;
                }
                out.write(buffer.array(), 0, read);
                buffer.clear();
            }
            return out.toByteArray();
        }
    }

    private static boolean hasExactFields(JsonNode node, Set<String> expected) {
        return node != null && node.isObject() && fieldNames(node).equals(expected);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static boolean isSchemaVersionOne(JsonNode node) {
        JsonNode version = node.get("schemaVersion");
        return version != null && version.isIntegralNumber() && version.intValue() == 1;
    }

    private static String textField(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.textValue();
        return text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH ? null : text;
    }

    private static Integer boundedCount(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return null;
        }
        int count = value.intValue();
        return count < 0 || count > MAX_TEST_COUNT ? null : count;
    }

    private static String sourceIdentity(Path root) throws IOException {
        MessageDigest digest = sha256Digest();
        List<Path> files = new ArrayList<>();
        for (String buildFile : List.of("build.gradle.kts", "build.gradle")) {
            Path candidate = root.resolve(buildFile);
            if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                files.add(candidate);
            }
        }
        for (String sourceRoot : List.of("main/java", "app/src/main/java_clean", "src/test/java")) {
            Path candidate = root.resolve(sourceRoot);
            if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(candidate)) {
                files.addAll(paths
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.toString().endsWith(".java"))
                        .toList());
            }
        }
        files.sort(Comparator.comparing(path -> root.relativize(path).toString().replace('\\', '/')));
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            updateDigest(digest, file);
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateDigest(MessageDigest digest, Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(8 * 1024);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                if (buffer.hasRemaining()) {
                    digest.update(buffer);
                }
                buffer.clear();
            }
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = sha256Digest();
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static int silentCatchPoints(int provenSilentCatchMatches) {
        return Math.max(0, 15 - Math.min(15, provenSilentCatchMatches));
    }

    private static int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String readTree(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                out.append(readFile(path)).append('\n');
            }
        }
        return out.toString();
    }

    private static String readFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IOException("source score read failed path=" + path.toString().replace('\\', '/'), ex);
        }
    }

    private static List<LargeSourceFile> collectLargeJavaFiles(
            Path sourceRoot, Path root, int thresholdLines) throws IOException {
        List<LargeSourceFile> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                int lines = Files.readAllLines(path).size();
                if (lines > thresholdLines) {
                    String relativePath = sourceRoot.relativize(path)
                            .toString()
                            .replace('\\', '/');
                    out.add(new LargeSourceFile(relativePath, lines));
                }
            }
        }
        return out;
    }

    record Args(Path root, Path output, Path evidence) {
        static Args parse(String[] args) {
            Path root = Paths.get("").toAbsolutePath().normalize();
            Path output = root.resolve("verification/source-score-report.txt");
            Path evidence = null;
            for (int i = 0; args != null && i < args.length; i++) {
                if ("--root".equals(args[i]) && i + 1 < args.length) {
                    root = Paths.get(args[++i]).toAbsolutePath().normalize();
                    if (!output.isAbsolute() || output.startsWith(Paths.get("").toAbsolutePath().normalize())) {
                        output = root.resolve("verification/source-score-report.txt");
                    }
                } else if ("--output".equals(args[i]) && i + 1 < args.length) {
                    output = Paths.get(args[++i]).toAbsolutePath().normalize();
                } else if ("--evidence".equals(args[i]) && i + 1 < args.length) {
                    evidence = Paths.get(args[++i]).toAbsolutePath().normalize();
                }
            }
            return new Args(root, output, evidence);
        }
    }

    record Check(String id, String label, int earned, int points, boolean ok, String reason) {
    }

    record LargeSourceFile(String path, int lines) {
    }

    record EvidenceSpec(
            String categoryId,
            String label,
            int points,
            String commandField,
            String commandOrTest,
            String activeCallPath) {
    }

    record AnnotationUse(int start, int end) {
    }

    record AnnotationResolution(boolean unambiguous, List<AnnotationUse> uses) {
    }

    record TestSourceQualification(boolean qualified, String reasonCode, String pathToken) {
        static TestSourceQualification valid(String pathToken) {
            return new TestSourceQualification(true, "test-source-qualified", pathToken);
        }

        static TestSourceQualification invalid(String reasonCode, String pathToken) {
            return new TestSourceQualification(false, reasonCode, pathToken);
        }
    }

    record EvidenceOutcome(boolean ok, String reason) {
        static EvidenceOutcome artifactConsistentPass() {
            return new EvidenceOutcome(true, "artifact-consistent-pass");
        }

        static EvidenceOutcome failed() {
            return new EvidenceOutcome(false, "failed");
        }

        static EvidenceOutcome missing() {
            return new EvidenceOutcome(false, "evidence-needed");
        }

        static EvidenceOutcome invalid() {
            return new EvidenceOutcome(false, "metric-input-invalid");
        }

        static EvidenceOutcome invalidTestSource(TestSourceQualification qualification) {
            return new EvidenceOutcome(
                    false,
                    qualification.reasonCode() + ":path=" + qualification.pathToken());
        }
    }

    record EvidenceEvaluation(Map<String, EvidenceOutcome> outcomes) {
        static EvidenceEvaluation all(String reason) {
            Map<String, EvidenceOutcome> outcomes = new LinkedHashMap<>();
            for (String checkName : EVIDENCE_SPECS.keySet()) {
                outcomes.put(checkName, new EvidenceOutcome(false, reason));
            }
            return new EvidenceEvaluation(Collections.unmodifiableMap(outcomes));
        }

        EvidenceOutcome outcome(String checkName) {
            return outcomes.getOrDefault(checkName, EvidenceOutcome.invalid());
        }
    }

    enum ReadKind {
        OK,
        MISSING,
        INVALID
    }

    record BoundedJson(ReadKind kind, JsonNode node, byte[] bytes) {
        static BoundedJson ok(JsonNode node, byte[] bytes) {
            return new BoundedJson(ReadKind.OK, node, bytes);
        }

        static BoundedJson missing() {
            return new BoundedJson(ReadKind.MISSING, null, new byte[0]);
        }

        static BoundedJson invalid() {
            return new BoundedJson(ReadKind.INVALID, null, new byte[0]);
        }
    }

    record ScoreReport(Path root, String sourceIdentityHash,
                       List<Check> checks, int provenSilentCatchMatches,
                       List<LargeSourceFile> largeActiveSourceFiles,
                       int structuralPenaltyPoints) {
        int total() {
            return Math.max(0,
                    checks.stream().mapToInt(Check::earned).sum() - structuralPenaltyPoints);
        }

        String render() {
            StringBuilder report = new StringBuilder();
            report.append("=== Dynamic RAG Source Score ===\n");
            report.append("root=").append(root).append('\n');
            report.append("Source Identity SHA-256: ").append(sourceIdentityHash).append('\n');
            report.append("Evidence Provenance: local-artifact-consistency\n");
            report.append("Execution Observed: false\n");
            for (Check check : checks) {
                report.append("[score][").append(check.id()).append("] ")
                        .append(check.ok() ? "OK" : "MISSING")
                        .append(" (+").append(check.earned()).append('/').append(check.points()).append(") ")
                        .append("reason=").append(check.reason()).append(' ')
                        .append(check.label()).append('\n');
            }
            report.append("[score][silent-catch] provenSilentCatchMatches=")
                    .append(provenSilentCatchMatches).append('\n');
            report.append("[score][structure] largeActiveSourceFiles=")
                    .append(largeActiveSourceFiles.size())
                    .append(" thresholdLines=").append(LARGE_SOURCE_THRESHOLD_LINES)
                    .append(" penaltyPoints=").append(structuralPenaltyPoints)
                    .append(" maxPenalty=").append(MAX_STRUCTURAL_PENALTY)
                    .append('\n');
            for (LargeSourceFile largeFile : largeActiveSourceFiles.stream().limit(20).toList()) {
                report.append("[score][structure][large-file] ")
                        .append(largeFile.path())
                        .append(" lines=")
                        .append(largeFile.lines())
                        .append('\n');
            }
            report.append("\n=== Total Score: ").append(total()).append(" / 100 ===\n");
            return report.toString();
        }
    }
}
