package com.example.lms.harmony;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Strict, hash-pinned authority for the HB-01 through HB-12 evidence board.
 */
public final class HarmonyEvidenceContract {

    static final String RESOURCE_NAME = "mcp/harmony-evidence-contract.v1.json";
    private static final String EXPECTED_SHA256 =
            "560438505e10525ada56118b9b26360eed14c7f29b3a811541bbbce877694a09";
    private static final String SCHEMA_VERSION = "awx.harmony.evidence-contract.v1";
    private static final String CONTRACT_ID = "HB-01-12";
    private static final String VERIFIED_STATUS = "DONE";
    private static final String BLOCKED_STATUS = "BLOCKED_EVIDENCE";
    private static final List<String> EXPECTED_ROOT_IDS = List.of(
            "mainJava", "mainResources", "testJava", "testResources", "appJavaClean", "appResources");
    private static final List<String> EXPECTED_ROOT_PATHS = List.of(
            "main/java",
            "main/resources",
            "src/test/java",
            "src/test/resources",
            "app/src/main/java_clean",
            "app/src/main/resources");
    private static final List<String> EXPECTED_BREAK_IDS = List.of(
            "HB-01", "HB-02", "HB-03", "HB-04", "HB-05", "HB-06",
            "HB-07", "HB-08", "HB-09", "HB-10", "HB-11", "HB-12");
    private static final Set<String> ALLOWED_SUBSYSTEMS = Set.of(
            "S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08");
    private static final Set<String> ALLOWED_MUTATION_OPERATIONS = Set.of(
            "REMOVE", "REPLACE_TEXT", "APPEND_DUPLICATE", "ADD_TOP_LEVEL");
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion",
            "contractId",
            "statuses",
            "activeSourceRoots",
            "requiredTraceKeys",
            "breaks",
            "parserMutationCorpus");
    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());

    private final String schemaVersion;
    private final String contractId;
    private final String verifiedStatus;
    private final String blockedStatus;
    private final String sha256;
    private final List<SourceRoot> activeSourceRoots;
    private final List<String> requiredTraceKeys;
    private final List<HarmonyBreakDefinition> breaks;
    private final Map<String, HarmonyBreakDefinition> breakById;
    private final List<ParserMutation> mutationCorpus;

    private HarmonyEvidenceContract(
            String schemaVersion,
            String contractId,
            String verifiedStatus,
            String blockedStatus,
            String sha256,
            List<SourceRoot> activeSourceRoots,
            List<String> requiredTraceKeys,
            List<HarmonyBreakDefinition> breaks,
            List<ParserMutation> mutationCorpus) {
        this.schemaVersion = schemaVersion;
        this.contractId = contractId;
        this.verifiedStatus = verifiedStatus;
        this.blockedStatus = blockedStatus;
        this.sha256 = sha256;
        this.activeSourceRoots = List.copyOf(activeSourceRoots);
        this.requiredTraceKeys = List.copyOf(requiredTraceKeys);
        this.breaks = List.copyOf(breaks);
        this.breakById = this.breaks.stream()
                .collect(Collectors.toUnmodifiableMap(
                        HarmonyBreakDefinition::id,
                        Function.identity()));
        this.mutationCorpus = List.copyOf(mutationCorpus);
    }

    public static HarmonyEvidenceContract loadClasspath() {
        return parse(readClasspathBytes(), EXPECTED_SHA256);
    }

    static byte[] readClasspathBytes() {
        ClassLoader loader = HarmonyEvidenceContract.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(RESOURCE_NAME)) {
            if (input == null) {
                throw new ContractException("contract_integrity_failed: resource_missing");
            }
            return input.readAllBytes();
        } catch (IOException error) {
            throw new ContractException("contract_integrity_failed: resource_unreadable", error);
        }
    }

    static String expectedSha256() {
        return EXPECTED_SHA256;
    }

    static HarmonyEvidenceContract parse(byte[] bytes, String expectedSha256) {
        if (bytes == null || bytes.length == 0) {
            throw new ContractException("contract_integrity_failed: resource_empty");
        }
        byte[] canonicalBytes = canonicalBytes(bytes);
        String actualSha256 = sha256(canonicalBytes);
        if (expectedSha256 == null || !MessageDigest.isEqual(
                actualSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expectedSha256.toLowerCase(java.util.Locale.ROOT)
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new ContractException("contract_integrity_failed: sha256_mismatch");
        }

        try {
            JsonNode root = JSON.readTree(canonicalBytes);
            requireObject(root, "root");
            requireExactFields(root, TOP_LEVEL_FIELDS, "root");
            requireText(root, "schemaVersion", SCHEMA_VERSION);
            requireText(root, "contractId", CONTRACT_ID);

            JsonNode statuses = requireObject(root.path("statuses"), "statuses");
            requireExactFields(statuses, Set.of("verified", "blocked"), "statuses");
            requireText(statuses, "verified", VERIFIED_STATUS);
            requireText(statuses, "blocked", BLOCKED_STATUS);

            List<SourceRoot> roots = parseRoots(root.path("activeSourceRoots"));
            List<String> traceKeys = parseUniqueStrings(
                    root.path("requiredTraceKeys"),
                    "requiredTraceKeys");
            List<HarmonyBreakDefinition> breaks = parseBreaks(root.path("breaks"), traceKeys);
            List<ParserMutation> mutations = parseMutations(root.path("parserMutationCorpus"));

            return new HarmonyEvidenceContract(
                    SCHEMA_VERSION,
                    CONTRACT_ID,
                    VERIFIED_STATUS,
                    BLOCKED_STATUS,
                    actualSha256,
                    roots,
                    traceKeys,
                    breaks,
                    mutations);
        } catch (ContractException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new ContractException("contract_schema_failed: invalid_json", error);
        }
    }

    private static List<SourceRoot> parseRoots(JsonNode node) {
        requireArraySize(node, EXPECTED_ROOT_PATHS.size(), "activeSourceRoots");
        List<SourceRoot> roots = new ArrayList<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = requireObject(node.get(index), "activeSourceRoots[" + index + "]");
            requireExactFields(item, Set.of("id", "path"), "activeSourceRoots[" + index + "]");
            String id = requireText(item, "id");
            String path = requireText(item, "path");
            if (!EXPECTED_ROOT_IDS.get(index).equals(id)
                    || !EXPECTED_ROOT_PATHS.get(index).equals(path)) {
                throw new ContractException("contract_schema_failed: active_source_root_mismatch");
            }
            roots.add(new SourceRoot(id, path));
        }
        return roots;
    }

    private static List<HarmonyBreakDefinition> parseBreaks(
            JsonNode node,
            List<String> traceKeys) {
        requireArraySize(node, EXPECTED_BREAK_IDS.size(), "breaks");
        Set<String> allowedTraceKeys = Set.copyOf(traceKeys);
        List<HarmonyBreakDefinition> breaks = new ArrayList<>();
        for (int index = 0; index < node.size(); index++) {
            String location = "breaks[" + index + "]";
            JsonNode item = requireObject(node.get(index), location);
            requireExactFields(item,
                    Set.of("id", "weight", "subsystem", "label", "runtimeRequirements"),
                    location);
            String id = requireText(item, "id");
            if (!EXPECTED_BREAK_IDS.get(index).equals(id)) {
                throw new ContractException("contract_schema_failed: break_id_mismatch");
            }
            JsonNode weightNode = item.path("weight");
            double weight = weightNode.isNumber() ? weightNode.doubleValue() : Double.NaN;
            if (!Double.isFinite(weight) || weight <= 0.0d) {
                throw new ContractException("contract_schema_failed: invalid_break_weight");
            }
            String subsystem = requireText(item, "subsystem");
            if (!ALLOWED_SUBSYSTEMS.contains(subsystem)) {
                throw new ContractException("contract_schema_failed: invalid_subsystem");
            }
            String label = requireText(item, "label");
            JsonNode requirementsNode = item.path("runtimeRequirements");
            if (!requirementsNode.isArray() || requirementsNode.isEmpty()) {
                throw new ContractException("contract_schema_failed: runtime_requirements_missing");
            }
            List<RuntimeRequirement> requirements = new ArrayList<>();
            Set<String> requirementKeys = new LinkedHashSet<>();
            for (int requirementIndex = 0;
                    requirementIndex < requirementsNode.size();
                    requirementIndex++) {
                String requirementLocation = location + ".runtimeRequirements[" + requirementIndex + "]";
                JsonNode requirement = requireObject(
                        requirementsNode.get(requirementIndex),
                        requirementLocation);
                requireExactFields(requirement, Set.of("traceKey", "rule"), requirementLocation);
                String traceKey = requireText(requirement, "traceKey");
                if (!allowedTraceKeys.contains(traceKey) || !requirementKeys.add(traceKey)) {
                    throw new ContractException("contract_schema_failed: invalid_runtime_trace_key");
                }
                String ruleText = requireText(requirement, "rule");
                EvidenceRule rule;
                try {
                    rule = EvidenceRule.valueOf(ruleText);
                } catch (IllegalArgumentException error) {
                    throw new ContractException("contract_schema_failed: unsupported_runtime_rule", error);
                }
                requirements.add(new RuntimeRequirement(traceKey, rule));
            }
            breaks.add(new HarmonyBreakDefinition(id, weight, subsystem, label, requirements));
        }
        return breaks;
    }

    private static List<ParserMutation> parseMutations(JsonNode node) {
        if (!node.isArray() || node.size() < 8) {
            throw new ContractException("contract_schema_failed: mutation_corpus_missing");
        }
        List<ParserMutation> mutations = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < node.size(); index++) {
            String location = "parserMutationCorpus[" + index + "]";
            JsonNode item = requireObject(node.get(index), location);
            requireExactFields(item,
                    Set.of("id", "operation", "path", "value", "expectedStatus"),
                    location);
            String id = requireText(item, "id");
            String operation = requireText(item, "operation");
            String path = requireText(item, "path");
            String value = textAllowEmpty(item, "value");
            String expectedStatus = requireText(item, "expectedStatus");
            if (!ids.add(id)
                    || !ALLOWED_MUTATION_OPERATIONS.contains(operation)
                    || !path.startsWith("/")
                    || !BLOCKED_STATUS.equals(expectedStatus)) {
                throw new ContractException("contract_schema_failed: invalid_mutation_corpus");
            }
            mutations.add(new ParserMutation(id, operation, path, value, expectedStatus));
        }
        return mutations;
    }

    private static List<String> parseUniqueStrings(JsonNode node, String location) {
        if (!node.isArray() || node.isEmpty()) {
            throw new ContractException("contract_schema_failed: " + location + "_missing");
        }
        List<String> values = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank() || !unique.add(item.textValue())) {
                throw new ContractException("contract_schema_failed: " + location + "_invalid");
            }
            values.add(item.textValue());
        }
        return values;
    }

    private static JsonNode requireObject(JsonNode node, String location) {
        if (node == null || !node.isObject()) {
            throw new ContractException("contract_schema_failed: " + location + "_object_required");
        }
        return node;
    }

    private static void requireArraySize(JsonNode node, int size, String location) {
        if (!node.isArray() || node.size() != size) {
            throw new ContractException("contract_schema_failed: " + location + "_size");
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> expected, String location) {
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new ContractException("contract_schema_failed: " + location + "_fields");
        }
    }

    private static void requireText(JsonNode node, String field, String expected) {
        if (!expected.equals(requireText(node, field))) {
            throw new ContractException("contract_schema_failed: " + field + "_mismatch");
        }
    }

    private static String requireText(JsonNode node, String field) {
        String value = textAllowEmpty(node, field);
        if (value.isBlank()) {
            throw new ContractException("contract_schema_failed: " + field + "_blank");
        }
        return value;
    }

    private static String textAllowEmpty(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            throw new ContractException("contract_schema_failed: " + field + "_text_required");
        }
        return value.textValue();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static byte[] canonicalBytes(byte[] bytes) {
        try {
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            return text.replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (CharacterCodingException error) {
            throw new ContractException("contract_schema_failed: invalid_utf8", error);
        }
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public String contractId() {
        return contractId;
    }

    public String verifiedStatus() {
        return verifiedStatus;
    }

    public String blockedStatus() {
        return blockedStatus;
    }

    public String sha256() {
        return sha256;
    }

    public List<SourceRoot> activeSourceRoots() {
        return activeSourceRoots;
    }

    public List<String> requiredTraceKeys() {
        return requiredTraceKeys;
    }

    public List<HarmonyBreakDefinition> breaks() {
        return breaks;
    }

    public List<ParserMutation> mutationCorpus() {
        return mutationCorpus;
    }

    public String subsystemFor(String breakId) {
        HarmonyBreakDefinition definition = breakById.get(breakId);
        return definition == null ? null : definition.subsystem();
    }

    public enum EvidenceRule {
        EMPTY_COLLECTION,
        NON_BLANK_STRING,
        FINITE_NUMBER,
        ZERO_NUMBER,
        NON_NEGATIVE_NUMBER,
        TRUE_BOOLEAN
    }

    public record SourceRoot(String id, String path) {
    }

    public record RuntimeRequirement(String traceKey, EvidenceRule rule) {
        public RuntimeRequirement {
            if (traceKey == null || rule == null) {
                throw new IllegalArgumentException("runtime requirement must be complete");
            }
        }
    }

    public record HarmonyBreakDefinition(
            String id,
            double weight,
            String subsystem,
            String label,
            List<RuntimeRequirement> runtimeRequirements) {
        public HarmonyBreakDefinition {
            runtimeRequirements = List.copyOf(runtimeRequirements);
        }
    }

    public record ParserMutation(
            String id,
            String operation,
            String path,
            String value,
            String expectedStatus) {
    }

    public static final class ContractException extends IllegalStateException {
        ContractException(String message) {
            super(message);
        }

        ContractException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
