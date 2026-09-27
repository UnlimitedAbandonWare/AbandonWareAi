package com.example.lms.harmony;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarmonyEvidenceContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void loadsOneAuthoritativeHbBoardAndSixSourceRoots() {
        HarmonyEvidenceContract contract = HarmonyEvidenceContract.loadClasspath();

        assertEquals("awx.harmony.evidence-contract.v1", contract.schemaVersion());
        assertEquals("HB-01-12", contract.contractId());
        assertEquals(List.of(
                        "main/java",
                        "main/resources",
                        "src/test/java",
                        "src/test/resources",
                        "app/src/main/java_clean",
                        "app/src/main/resources"),
                contract.activeSourceRoots().stream()
                        .map(HarmonyEvidenceContract.SourceRoot::path)
                        .toList());
        assertEquals(List.of(
                        "HB-01", "HB-02", "HB-03", "HB-04", "HB-05", "HB-06",
                        "HB-07", "HB-08", "HB-09", "HB-10", "HB-11", "HB-12"),
                contract.breaks().stream()
                        .map(HarmonyEvidenceContract.HarmonyBreakDefinition::id)
                        .toList());
        assertEquals("BLOCKED_EVIDENCE", contract.blockedStatus());
        assertTrue(contract.mutationCorpus().size() >= 8);
    }

    @Test
    void rejectsTamperedContractDigest() throws Exception {
        byte[] raw = HarmonyEvidenceContract.readClasspathBytes();
        byte[] tampered = new String(raw, StandardCharsets.UTF_8)
                .replace("HB-01-12", "HB-01-11")
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(HarmonyEvidenceContract.ContractException.class,
                () -> HarmonyEvidenceContract.parse(
                        tampered,
                        HarmonyEvidenceContract.expectedSha256()));
    }

    @Test
    void acceptsWindowsLineEndingsWithoutWeakeningContractHash() {
        byte[] raw = HarmonyEvidenceContract.readClasspathBytes();
        byte[] crlf = new String(raw, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "\r\n")
                .getBytes(StandardCharsets.UTF_8);

        HarmonyEvidenceContract contract = HarmonyEvidenceContract.parse(
                crlf,
                HarmonyEvidenceContract.expectedSha256());

        assertEquals(HarmonyEvidenceContract.expectedSha256(), contract.sha256());
    }

    @Test
    void sharedMutationCorpusIsRejectedByStrictGrammar() throws Exception {
        byte[] raw = HarmonyEvidenceContract.readClasspathBytes();
        HarmonyEvidenceContract contract = HarmonyEvidenceContract.loadClasspath();

        for (HarmonyEvidenceContract.ParserMutation mutation : contract.mutationCorpus()) {
            ObjectNode mutated = (ObjectNode) JSON.readTree(raw).deepCopy();
            applyMutation(mutated, mutation);
            byte[] bytes = JSON.writeValueAsBytes(mutated);
            String digest = sha256(bytes);

            assertThrows(HarmonyEvidenceContract.ContractException.class,
                    () -> HarmonyEvidenceContract.parse(bytes, digest),
                    mutation.id());
        }
    }

    private static void applyMutation(
            ObjectNode root,
            HarmonyEvidenceContract.ParserMutation mutation) {
        String operation = mutation.operation();
        String path = mutation.path();
        if ("REMOVE".equals(operation)) {
            parent(root, path).remove(leaf(path));
            return;
        }
        if ("REPLACE_TEXT".equals(operation)) {
            parent(root, path).put(leaf(path), mutation.value());
            return;
        }
        if ("APPEND_DUPLICATE".equals(operation)) {
            JsonNode target = at(root, path);
            if (!(target instanceof ArrayNode array) || array.isEmpty()) {
                throw new IllegalArgumentException("mutation target is not a non-empty array: " + path);
            }
            array.add(array.get(0).deepCopy());
            return;
        }
        if ("ADD_TOP_LEVEL".equals(operation)) {
            root.put(leaf(path), mutation.value());
            return;
        }
        throw new IllegalArgumentException("unsupported mutation operation: " + operation);
    }

    private static ObjectNode parent(ObjectNode root, String pointer) {
        int split = pointer.lastIndexOf('/');
        JsonNode node = split <= 0 ? root : root.at(pointer.substring(0, split));
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException("mutation parent is not an object: " + pointer);
        }
        return object;
    }

    private static JsonNode at(ObjectNode root, String pointer) {
        JsonNode node = root.at(pointer);
        if (node.isMissingNode()) {
            throw new IllegalArgumentException("mutation target is missing: " + pointer);
        }
        return node;
    }

    private static String leaf(String pointer) {
        return pointer.substring(pointer.lastIndexOf('/') + 1);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
