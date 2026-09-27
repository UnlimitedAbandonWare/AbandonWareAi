import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("random_probe_candidate_ledger.py")
SPEC = importlib.util.spec_from_file_location("random_probe_candidate_ledger", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("unable to load random_probe_candidate_ledger module")
ledger = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = ledger
SPEC.loader.exec_module(ledger)


def write_java(root: Path, relative: str, body: str) -> None:
    path = root / Path(relative)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")


def make_status(*lines: str) -> str:
    return "\n".join(lines) + ("\n" if lines else "")


def deterministic_manifest(manifest: dict) -> dict:
    copy = json.loads(json.dumps(manifest))
    copy.pop("generatedAt", None)
    return copy


class RandomProbeCandidateLedgerTest(unittest.TestCase):
    def test_same_snapshot_and_seed_are_byte_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            write_java(
                root,
                "main/java/com/example/Alpha.java",
                "class Alpha { String f(String v) { return v.toLowerCase(); } }\n",
            )
            write_java(
                root,
                "app/src/main/java_clean/com/example/Beta.java",
                "class Beta { void f() { try { run(); } catch (Exception e) { } } }\n",
            )

            rows_a, manifest_a = ledger.build_ledger(root, "20260830", 1000, "")
            rows_b, manifest_b = ledger.build_ledger(root, "20260830", 1000, "")

            self.assertEqual(
                ledger.canonical_ndjson_bytes(rows_a),
                ledger.canonical_ndjson_bytes(rows_b),
            )
            self.assertEqual(
                deterministic_manifest(manifest_a),
                deterministic_manifest(manifest_b),
            )

    def test_filesystem_enumeration_order_does_not_change_rows(self) -> None:
        bodies = {
            "main/java/com/example/Alpha.java": "class Alpha {}\n",
            "main/java/com/example/Beta.java": "class Beta { String f(String v) { return v.toUpperCase(); } }\n",
            "app/src/main/java_clean/com/example/Gamma.java": "class Gamma {}\n",
        }
        with tempfile.TemporaryDirectory() as raw_a, tempfile.TemporaryDirectory() as raw_b:
            root_a = Path(raw_a)
            root_b = Path(raw_b)
            for relative in bodies:
                write_java(root_a, relative, bodies[relative])
            for relative in reversed(tuple(bodies)):
                write_java(root_b, relative, bodies[relative])

            rows_a, manifest_a = ledger.build_ledger(root_a, "20260830", 1000, "")
            rows_b, manifest_b = ledger.build_ledger(root_b, "20260830", 1000, "")

            self.assertEqual(rows_a, rows_b)
            self.assertEqual(manifest_a["pathOrderHash"], manifest_b["pathOrderHash"])
            self.assertEqual(
                deterministic_manifest(manifest_a),
                deterministic_manifest(manifest_b),
            )

    def test_seed_changes_rank_not_identity_universe(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            for index in range(12):
                write_java(
                    root,
                    f"main/java/com/example/Probe{index:02d}.java",
                    f"class Probe{index:02d} {{}}\n",
                )

            rows_a, _ = ledger.build_ledger(root, "seed-a", 1000, "")
            rows_b, _ = ledger.build_ledger(root, "seed-b", 1000, "")

            self.assertEqual(
                {row["candidateId"] for row in rows_a},
                {row["candidateId"] for row in rows_b},
            )
            self.assertNotEqual(
                [row["candidateId"] for row in rows_a],
                [row["candidateId"] for row in rows_b],
            )

    def test_non_source_status_rows_do_not_change_active_source_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            write_java(root, "main/java/com/example/Active.java", "class Active {}\n")

            rows_a, manifest_a = ledger.build_ledger(root, "20260830", 1000, "")
            rows_b, manifest_b = ledger.build_ledger(
                root,
                "20260830",
                1000,
                make_status("?? var/codex-smoke/probe/generated.ndjson"),
            )

            self.assertEqual(rows_a, rows_b)
            self.assertEqual(
                manifest_a["sourceSnapshot"]["statusSnapshotHash"],
                manifest_b["sourceSnapshot"]["statusSnapshotHash"],
            )

    def test_repeated_matches_collapse_to_one_identity_with_bounded_line_hints(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            lines = ["class LocaleCase {"]
            lines.extend(
                f"  String f{index}(String v) {{ return v.toLowerCase(); }}"
                for index in range(12)
            )
            lines.append("}")
            write_java(root, "main/java/com/example/LocaleCase.java", "\n".join(lines) + "\n")

            rows, _ = ledger.build_ledger(root, "20260830", 1000, "")
            matches = [
                row
                for row in rows
                if row["probeFamily"] == "locale_sensitive_case_normalization"
            ]

            self.assertEqual(1, len(matches))
            row = matches[0]
            self.assertEqual(12, row["staticEvidence"]["matchCount"])
            self.assertEqual(list(range(2, 10)), row["lineHints"])
            self.assertEqual(len({item["identityHash"] for item in matches}), 1)

    def test_inactive_generated_and_test_roots_never_emit_rows(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            write_java(root, "main/java/com/example/Active.java", "class Active {}\n")
            write_java(root, "app/src/main/java/com/example/Inactive.java", "class Inactive {}\n")
            write_java(root, "build/generated/Generated.java", "class Generated {}\n")
            write_java(root, "src/test/java/com/example/TestOnly.java", "class TestOnly {}\n")
            write_java(root, "archive/main/java/com/example/Archived.java", "class Archived {}\n")

            rows, manifest = ledger.build_ledger(root, "20260830", 1000, "")

            self.assertEqual(
                {"main/java/com/example/Active.java"},
                {row["repoPath"] for row in rows},
            )
            self.assertEqual(["main/java", "app/src/main/java_clean"], manifest["candidateRoots"])

    def test_dirty_and_untracked_rows_are_excluded_dirty(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            write_java(root, "main/java/com/example/Clean.java", "class Clean {}\n")
            write_java(root, "main/java/com/example/Modified.java", "class Modified {}\n")
            write_java(
                root,
                "app/src/main/java_clean/com/example/Untracked.java",
                "class Untracked {}\n",
            )
            status = make_status(
                " M main/java/com/example/Modified.java",
                "?? app/src/main/java_clean/com/example/Untracked.java",
            )

            rows, manifest = ledger.build_ledger(root, "20260830", 1000, status)
            by_path = {row["repoPath"]: row for row in rows}

            self.assertEqual("candidate", by_path["main/java/com/example/Clean.java"]["eligibility"])
            self.assertEqual("clean_tracked", by_path["main/java/com/example/Clean.java"]["trackingState"])
            self.assertEqual("excluded_dirty", by_path["main/java/com/example/Modified.java"]["eligibility"])
            self.assertEqual("modified_tracked", by_path["main/java/com/example/Modified.java"]["trackingState"])
            self.assertEqual(
                "excluded_dirty",
                by_path["app/src/main/java_clean/com/example/Untracked.java"]["eligibility"],
            )
            self.assertEqual(
                "untracked",
                by_path["app/src/main/java_clean/com/example/Untracked.java"]["trackingState"],
            )
            self.assertEqual(1, manifest["recordCounts"]["candidate"])
            self.assertEqual(2, manifest["recordCounts"]["excludedDirty"])

    def test_output_paths_under_source_or_tool_roots_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            rejected = (
                "main/java/out.ndjson",
                "app/src/main/java_clean/out.ndjson",
                "src/test/out.ndjson",
                "scripts/out.ndjson",
                "build/out.ndjson",
                ".git/out.ndjson",
            )
            for relative in rejected:
                with self.subTest(relative=relative):
                    with self.assertRaises(ledger.LedgerContractError) as caught:
                        ledger.validate_output_path(root, root / relative)
                    self.assertEqual("output-path-forbidden", caught.exception.reason_code)

            accepted = ledger.validate_output_path(
                root,
                root / "var/codex-smoke/probe/out.ndjson",
            )
            self.assertEqual((root / "var/codex-smoke/probe/out.ndjson").resolve(), accepted)

    def test_seed_and_record_bounds_are_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            write_java(root, "main/java/com/example/Active.java", "class Active {}\n")
            cases = (("bad seed", 10, "seed-invalid"), ("ok", 0, "max-records-invalid"), ("ok", 1001, "max-records-invalid"))
            for seed, maximum, reason in cases:
                with self.subTest(seed=seed, maximum=maximum):
                    with self.assertRaises(ledger.LedgerContractError) as caught:
                        ledger.build_ledger(root, seed, maximum, "")
                    self.assertEqual(reason, caught.exception.reason_code)

    def test_file_publication_refuses_overwrite_and_writes_sidecars(self) -> None:
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            write_java(root, "main/java/com/example/Active.java", "class Active {}\n")
            rows, manifest = ledger.build_ledger(root, "20260830", 1000, "")
            output = root / "var/codex-smoke/probe/candidates.ndjson"
            manifest_output = root / "var/codex-smoke/probe/candidates.manifest.json"

            ledger.publish_artifacts(output, manifest_output, rows, manifest)

            self.assertEqual(ledger.canonical_ndjson_bytes(rows), output.read_bytes())
            published_manifest = json.loads(manifest_output.read_text(encoding="utf-8"))
            self.assertEqual("var/codex-smoke/probe/candidates.ndjson", published_manifest["ndjsonPath"])
            self.assertEqual(ledger.sha256_hex(output.read_bytes()), published_manifest["ndjsonSha256"])
            self.assertTrue(Path(f"{output}.sha256").is_file())
            self.assertTrue(Path(f"{output}.ready").is_file())

            with self.assertRaises(ledger.LedgerContractError) as caught:
                ledger.publish_artifacts(output, manifest_output, rows, manifest)
            self.assertEqual("output-exists", caught.exception.reason_code)


if __name__ == "__main__":
    unittest.main()
