import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import awx_mcp_toolbox as toolbox


class HarmonyEvidenceContractTest(unittest.TestCase):
    def test_legacy_subproject_uses_root_plugin_authority(self):
        build = (Path(__file__).resolve().parents[1] / "demo-1" / "build.gradle.kts").read_text(
            encoding="utf-8"
        )

        self.assertIn('id("org.springframework.boot") version "3.3.4"', build)
        self.assertIn('id("io.spring.dependency-management")', build)
        self.assertNotIn(
            'id("io.spring.dependency-management") version',
            build,
        )

    def test_loads_one_authoritative_hb_board_and_six_source_roots(self):
        contract = toolbox.load_harmony_evidence_contract()

        self.assertEqual("awx.harmony.evidence-contract.v1", contract["schemaVersion"])
        self.assertEqual("HB-01-12", contract["contractId"])
        self.assertEqual(
            [
                "main/java",
                "main/resources",
                "src/test/java",
                "src/test/resources",
                "app/src/main/java_clean",
                "app/src/main/resources",
            ],
            [item["path"] for item in contract["activeSourceRoots"]],
        )
        self.assertEqual(
            [f"HB-{number:02d}" for number in range(1, 13)],
            [item["id"] for item in contract["breaks"]],
        )
        self.assertEqual("BLOCKED_EVIDENCE", contract["statuses"]["blocked"])
        self.assertGreaterEqual(len(contract["parserMutationCorpus"]), 8)

    def test_rejects_tampered_contract_digest(self):
        raw = toolbox.harmony_evidence_contract_path().read_bytes()
        tampered = raw.replace(b"HB-01-12", b"HB-01-11")

        with self.assertRaises(toolbox.HarmonyEvidenceContractError):
            toolbox.parse_harmony_evidence_contract_bytes(
                tampered,
                toolbox.HARMONY_EVIDENCE_CONTRACT_SHA256,
            )

    def test_accepts_windows_line_endings_with_same_contract_hash(self):
        raw = toolbox.harmony_evidence_contract_path().read_bytes()
        normalized = raw.decode("utf-8").replace("\r\n", "\n").replace("\r", "\n")
        crlf = normalized.replace("\n", "\r\n").encode("utf-8")

        contract = toolbox.parse_harmony_evidence_contract_bytes(
            crlf,
            toolbox.HARMONY_EVIDENCE_CONTRACT_SHA256,
        )

        self.assertEqual(toolbox.HARMONY_EVIDENCE_CONTRACT_SHA256, contract["sha256"])

    def test_shared_mutation_corpus_is_rejected_by_strict_grammar(self):
        raw = toolbox.harmony_evidence_contract_path().read_bytes()
        contract = toolbox.load_harmony_evidence_contract()

        for mutation in contract["parserMutationCorpus"]:
            with self.subTest(mutation=mutation["id"]):
                document = json.loads(raw)
                self._apply_mutation(document, mutation)
                mutated = json.dumps(
                    document,
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode("utf-8")
                digest = hashlib.sha256(mutated).hexdigest()
                with self.assertRaises(toolbox.HarmonyEvidenceContractError):
                    toolbox.parse_harmony_evidence_contract_bytes(mutated, digest)

    def test_fingerprints_every_regular_file_in_six_roots_deterministically(self):
        contract = toolbox.load_harmony_evidence_contract()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixtures = {
                "main/java/example/Runtime.java": "class Runtime {}",
                "main/resources/example.json": "{}",
                "src/test/java/example/fixture.txt": "test",
                "src/test/resources/example.bin": "binary-fixture",
                "app/src/main/java_clean/example/Adapter.kt": "class Adapter",
                "app/src/main/resources/example.properties": "enabled=true",
            }
            for relative, content in fixtures.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")

            first = toolbox.harmony_source_fingerprint(root, contract, 100)
            second = toolbox.harmony_source_fingerprint(root, contract, 100)

        self.assertEqual("DONE", first["status"])
        self.assertEqual(6, len(first["roots"]))
        self.assertEqual(6, first["fileCount"])
        self.assertEqual(0, first["errorCount"])
        self.assertEqual(first, second)

    def test_missing_optional_root_is_still_manifested(self):
        contract = toolbox.load_harmony_evidence_contract()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / "main/java/example/Runtime.java"
            path.parent.mkdir(parents=True)
            path.write_text("class Runtime {}", encoding="utf-8")

            result = toolbox.harmony_source_fingerprint(root, contract, 100)

        test_resources = next(item for item in result["roots"] if item["id"] == "testResources")
        self.assertEqual("DONE", result["status"])
        self.assertFalse(test_resources["exists"])
        self.assertEqual(0, test_resources["fileCount"])

    def test_incomplete_fingerprint_blocks_evidence(self):
        contract = toolbox.load_harmony_evidence_contract()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "main/java/example"
            source.mkdir(parents=True)
            (source / "One.java").write_text("class One {}", encoding="utf-8")
            (source / "Two.java").write_text("class Two {}", encoding="utf-8")

            result = toolbox.harmony_source_fingerprint(root, contract, 1)

        self.assertEqual("BLOCKED_EVIDENCE", result["status"])
        self.assertEqual(1, result["errorCount"])

    def test_hash_only_runtime_proof_cannot_promote(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = toolbox.harmony_scan(
                {
                    "root": tmp,
                    "runtimeProof": {
                        "traceStoreExportHash": "a" * 64,
                        "agentDbSnapshotHash": "b" * 64,
                    },
                }
            )

        self.assertEqual(0.0, report["harmonyScore"]["score"])
        self.assertFalse(report["harmonyScore"]["promotionAllowed"])
        self.assertEqual("BLOCKED_EVIDENCE", report["promotionDecision"])
        self.assertIn(
            "boot/live TraceStore export for runtime-only coverage",
            report["evidence_needed"],
        )

    @staticmethod
    def _apply_mutation(document, mutation):
        operation = mutation["operation"]
        parts = [part for part in mutation["path"].split("/") if part]
        if operation == "ADD_TOP_LEVEL":
            document[parts[-1]] = mutation.get("value", "unexpected")
            return
        target = document
        for part in parts[:-1]:
            target = target[int(part)] if isinstance(target, list) else target[part]
        leaf = parts[-1]
        if operation == "REMOVE":
            target.pop(int(leaf)) if isinstance(target, list) else target.pop(leaf)
        elif operation == "REPLACE_TEXT":
            if isinstance(target, list):
                target[int(leaf)] = mutation.get("value", "")
            else:
                target[leaf] = mutation.get("value", "")
        elif operation == "APPEND_DUPLICATE":
            array = target[int(leaf)] if isinstance(target, list) else target[leaf]
            array.append(copy.deepcopy(array[0]))
        else:
            raise AssertionError(f"unsupported mutation operation: {operation}")


if __name__ == "__main__":
    unittest.main()
