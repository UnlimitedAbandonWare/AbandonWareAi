import copy
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


def load_module(name: str, filename: str):
    path = Path(__file__).with_name(filename)
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"unable to load {name}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


producer = load_module("random_probe_candidate_ledger", "random_probe_candidate_ledger.py")
validator = load_module(
    "validate_random_probe_candidate_ledger",
    "validate_random_probe_candidate_ledger.py",
)


def write_java(root: Path, relative: str, body: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")


def valid_fixture(root: Path) -> tuple[Path, Path, list[dict], dict]:
    for index in range(4):
        write_java(
            root,
            f"main/java/com/example/Probe{index}.java",
            f"class Probe{index} {{}}\n",
        )
    rows, manifest = producer.build_ledger(root, "20260830", 1000, "")
    output = root / "var/codex-smoke/probe/candidates.ndjson"
    manifest_path = root / "var/codex-smoke/probe/candidates.manifest.json"
    write_fixture_artifacts(root, output, manifest_path, rows, manifest)
    return output, manifest_path, rows, manifest


def write_fixture_artifacts(
    root: Path,
    output: Path,
    manifest_path: Path,
    rows: list[dict],
    manifest: dict,
    *,
    refresh_ndjson_hash: bool = True,
) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    payload = producer.canonical_ndjson_bytes(rows)
    manifest["ndjsonPath"] = output.relative_to(root).as_posix()
    if refresh_ndjson_hash:
        manifest["ndjsonSha256"] = producer.sha256_hex(payload)
    published = copy.deepcopy(manifest)
    output.write_bytes(payload)
    manifest_path.write_bytes(producer.canonical_json_bytes(published) + b"\n")


class ValidateRandomProbeCandidateLedgerTest(unittest.TestCase):
    def assert_rejected(
        self,
        mutate,
        reason_code: str,
        *,
        refresh_ndjson_hash: bool = True,
    ) -> validator.LedgerValidationError:
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            output, manifest_path, rows, manifest = valid_fixture(root)
            rows = copy.deepcopy(rows)
            manifest = copy.deepcopy(manifest)
            mutate(rows, manifest)
            write_fixture_artifacts(
                root,
                output,
                manifest_path,
                rows,
                manifest,
                refresh_ndjson_hash=refresh_ndjson_hash,
            )
            with self.assertRaises(validator.LedgerValidationError) as caught:
                validator.validate_v1(output, manifest_path, 1000)
            self.assertEqual(reason_code, caught.exception.reason_code)
            return caught.exception

    def test_valid_v1_ledger_recomputes_all_counts_and_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            output, manifest_path, rows, _ = valid_fixture(root)

            result = validator.validate_v1(output, manifest_path, 1000)

            self.assertEqual("awx.random-probe-candidate-validation.v1", result["schemaVersion"])
            self.assertEqual("VALID", result["verdict"])
            self.assertEqual(len(rows), result["recordCounts"]["emitted"])
            self.assertEqual(0, result["secretPatternHits"])
            self.assertFalse(result["rawContentStored"])

    def test_duplicate_candidate_id_is_rejected(self) -> None:
        def mutate(rows, _manifest):
            rows[1]["candidateId"] = rows[0]["candidateId"]

        self.assert_rejected(mutate, "duplicate-id")

    def test_duplicate_identity_pair_is_rejected(self) -> None:
        def mutate(rows, _manifest):
            rows[1]["repoPath"] = rows[0]["repoPath"]
            rows[1]["probeFamily"] = rows[0]["probeFamily"]

        self.assert_rejected(mutate, "duplicate-identity")

    def test_noncontiguous_ordinal_is_rejected(self) -> None:
        def mutate(rows, _manifest):
            rows[1]["ordinal"] = 99

        self.assert_rejected(mutate, "ordinal-gap")

    def test_rank_order_or_rank_hash_corruption_is_rejected(self) -> None:
        def mutate(rows, _manifest):
            rows[0]["rankKey"] = "f" * 64

        self.assert_rejected(mutate, "rank-hash-invalid")

    def test_path_traversal_and_inactive_root_are_rejected(self) -> None:
        for bad_path in ("../Escape.java", "app/src/main/java/com/example/Inactive.java"):
            with self.subTest(bad_path=bad_path):
                def mutate(rows, _manifest, value=bad_path):
                    rows[0]["repoPath"] = value

                self.assert_rejected(mutate, "path-invalid")

    def test_dirty_row_marked_candidate_is_rejected(self) -> None:
        def mutate(rows, _manifest):
            rows[0]["trackingState"] = "modified_tracked"
            rows[0]["eligibility"] = "candidate"

        self.assert_rejected(mutate, "dirty-eligible")

    def test_non_untriaged_status_is_rejected(self) -> None:
        def mutate(rows, _manifest):
            rows[0]["status"] = "FIXED"

        self.assert_rejected(mutate, "status-claim-invalid")

    def test_manifest_count_or_ndjson_hash_mismatch_is_rejected(self) -> None:
        def count_mutation(_rows, manifest):
            manifest["recordCounts"]["emitted"] += 1

        self.assert_rejected(count_mutation, "manifest-count-mismatch")

        def payload_mutation(rows, _manifest):
            rows[0]["lineHints"] = [1]

        self.assert_rejected(
            payload_mutation,
            "ndjson-hash-mismatch",
            refresh_ndjson_hash=False,
        )

    def test_prohibited_secret_pattern_reports_count_only(self) -> None:
        marker = "Authorization:" + " synthetic"

        def mutate(rows, _manifest):
            rows[0]["nextAction"] = marker

        error = self.assert_rejected(mutate, "secret-pattern-hit")
        self.assertEqual(1, error.secret_pattern_hits)
        self.assertNotIn(marker, str(error))

    def test_unknown_row_field_is_rejected(self) -> None:
        def mutate(rows, _manifest):
            rows[0]["unexpected"] = True

        self.assert_rejected(mutate, "field-set-invalid")

    def test_current_legacy_markdown_is_evidence_needed_not_valid(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            path = Path(raw_root) / "legacy.md"
            path.write_text(
                "# Historical Random Probe\n\n"
                "## Candidate Rows\n\n"
                "| ID | Status |\n"
                "| --- | --- |\n"
                "| RP-001 | CANDIDATE |\n"
                "| RP-002 | FIXED |\n\n"
                "## 99-row historical closure\n\n"
                "Later continuation supersedes earlier closure counts.\n",
                encoding="utf-8",
            )

            result = validator.inspect_legacy_markdown(path)

            self.assertEqual("awx.random-probe-legacy-inspection.v1", result["schemaVersion"])
            self.assertEqual("EVIDENCE_NEEDED", result["verdict"])
            self.assertEqual(2, result["primaryCandidateTableRowCount"])
            self.assertTrue(result["historicalSectionAmbiguity"])
            self.assertNotEqual("VALID", result["verdict"])


if __name__ == "__main__":
    unittest.main()
