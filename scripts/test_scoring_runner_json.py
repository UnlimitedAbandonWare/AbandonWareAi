"""Machine-readable contract verification for ScoringRunner --format=json (Codex WP5).

Runs the product ScoringRunner in a hermetic javac/java lane (temp dirs only,
never gradle build outputs) and validates:
  - JSON parses; schemaVersion == 1; executionObserved is strictly false;
    evidenceProvenance == "local-artifact-consistency".
  - sourceIdentityHash and uiAssetHash are distinct 64-hex values.
  - A probe CSS file under the web asset roots changes uiAssetHash while
    sourceIdentityHash stays identical (UI-asset hash separation).
  - The 100-point scheme and structural penalty bound are not relaxed
    (spec points + silent-catch == 100; MAX_STRUCTURAL_PENALTY == 10;
    JSON totalScore/structuralPenalty inside bounds).

Exit 0 = every check passed. Exit 1 = at least one FAIL (a SKIP from a
missing prerequisite is reported as FAIL "not-implemented" because this
script is the gate for the Codex WP5 contract).
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "main" / "java" / "com" / "example" / "lms" / "tools" / "ScoringRunner.java"
PROBE = ROOT / "main" / "resources" / "static" / "assets" / "display" / "__jev_scoring_probe__.css"
JACKSON_ROOT = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1" / "com.fasterxml.jackson.core"
HEX64 = re.compile(r"[0-9a-f]{64}\Z")
EXPECTED_POINTS = {"sourceSetVersionPurity": 10, "cfvmBehavior": 15, "artPlateBehavior": 10,
                   "hypernovaBehavior": 20, "piiRedaction": 10, "citationOwnership": 10,
                   "promptBoundary": 10}
SILENT_CATCH_POINTS = 15
REQUIRED_JSON_FIELDS = ("schemaVersion", "sourceIdentityHash", "uiAssetHash",
                        "executionObserved", "evidenceProvenance", "totalScore",
                        "checks", "structuralPenalty")


def _javac() -> str | None:
    return shutil.which("javac")


def _java() -> str | None:
    return shutil.which("java")


def _jackson_jars() -> list[str]:
    jars: list[str] = []
    for artifact in ("jackson-databind", "jackson-core", "jackson-annotations"):
        base = JACKSON_ROOT / artifact
        if not base.is_dir():
            return []
        def _vkey(p: Path) -> tuple:
            return tuple(int(x) if x.isdigit() else 0 for x in re.split(r"[.\-_]", p.name))
        versions = sorted((p for p in base.iterdir() if p.is_dir()), key=_vkey)
        found = None
        for version in reversed(versions):
            candidates = list(version.glob(f"*/{artifact}-*.jar"))
            candidates = [c for c in candidates if "sources" not in c.name and "javadoc" not in c.name]
            if candidates:
                found = str(candidates[0])
                break
        if found is None:
            return []
        jars.append(found)
    return jars


def _compile(work: Path) -> tuple[bool, str]:
    javac = _javac()
    jars = _jackson_jars()
    if javac is None or _java() is None or not jars:
        return False, "toolchain-unavailable"
    classes = work / "classes"
    classes.mkdir()
    proc = subprocess.run(
        [javac, "-encoding", "UTF-8", "-d", str(classes), "-cp", os.pathsep.join(jars), str(RUNNER)],
        capture_output=True, text=True, timeout=180, cwd=str(ROOT))
    if proc.returncode != 0:
        return False, "compile-failed"
    return True, os.pathsep.join([str(classes), *jars])


def _run_json(classpath: str, work: Path, tag: str) -> dict:
    java = _java()
    out = work / f"report-{tag}.json"
    proc = subprocess.run(
        [java, "-cp", classpath, "com.example.lms.tools.ScoringRunner",
         "--root", str(ROOT), "--format=json", "--output", str(out)],
        capture_output=True, text=True, timeout=600, cwd=str(ROOT))
    candidates = []
    if out.is_file():
        candidates.append(out.read_text(encoding="utf-8", errors="replace"))
    candidates.append(proc.stdout)
    for text in candidates:
        text = text.strip()
        if text.startswith("{"):
            try:
                return json.loads(text)
            except json.JSONDecodeError:
                continue
    return {"__error__": "json-format-unavailable", "exit": proc.returncode}


def _report_problems(doc: dict) -> list[str]:
    problems: list[str] = []
    if doc.get("schemaVersion") != 1:
        problems.append("schemaVersion-not-1")
    if doc.get("executionObserved") is not False:
        problems.append("executionObserved-not-false")
    if doc.get("evidenceProvenance") != "local-artifact-consistency":
        problems.append("evidenceProvenance-mismatch")
    source_hash = doc.get("sourceIdentityHash")
    ui_hash = doc.get("uiAssetHash")
    if not (isinstance(source_hash, str) and HEX64.fullmatch(source_hash)):
        problems.append("sourceIdentityHash-not-64hex")
    if not (isinstance(ui_hash, str) and HEX64.fullmatch(ui_hash)):
        problems.append("uiAssetHash-not-64hex")
    if isinstance(source_hash, str) and source_hash == ui_hash:
        problems.append("hashes-not-separated")
    total = doc.get("totalScore")
    if not (isinstance(total, int) and not isinstance(total, bool) and 0 <= total <= 100):
        problems.append("totalScore-out-of-100")
    penalty = doc.get("structuralPenalty")
    if not (isinstance(penalty, int) and not isinstance(penalty, bool) and 0 <= penalty <= 10):
        problems.append("structuralPenalty-out-of-10")
    checks = doc.get("checks")
    if not checks or not isinstance(checks, (dict, list)):
        problems.append("checks-missing")
    return problems


def _static_contract() -> list[str]:
    problems: list[str] = []
    source = RUNNER.read_text(encoding="utf-8")
    if '"--format' not in source and "--format" not in source:
        problems.append("format-flag-absent")
    for token in ("uiAssetHash", "schemaVersion", "executionObserved", "evidenceProvenance"):
        if token not in source:
            problems.append(f"missing-{token}")
    points = [int(m) for m in re.findall(r"new EvidenceSpec\([^)]*?,\s*(\d+),", source, re.S)]
    if sum(points) + SILENT_CATCH_POINTS != 100 or len(points) != len(EXPECTED_POINTS):
        problems.append("points-not-100")
    if not re.search(r"MAX_STRUCTURAL_PENALTY\s*=\s*10\b", source):
        problems.append("structural-penalty-cap-changed")
    if "main/resources/static" not in source:
        problems.append("ui-asset-root-absent")
    return problems


class ScoringRunnerJsonTest(unittest.TestCase):
    maxDiff = None

    def test_static_source_contract(self) -> None:
        problems = _static_contract()
        self.assertEqual(problems, [], "ScoringRunner.java static contract: " + ",".join(problems))

    def test_validator_accepts_reference_shape(self) -> None:
        good = {"schemaVersion": 1, "sourceIdentityHash": "a" * 64, "uiAssetHash": "b" * 64,
                "executionObserved": False, "evidenceProvenance": "local-artifact-consistency",
                "totalScore": 85, "structuralPenalty": 10, "checks": {"x": {"ok": True}}}
        self.assertEqual(_report_problems(good), [])
        bad = dict(good, executionObserved=True)
        self.assertIn("executionObserved-not-false", _report_problems(bad))
        bad = dict(good, uiAssetHash=good["sourceIdentityHash"])
        self.assertIn("hashes-not-separated", _report_problems(bad))

    def test_json_output_contract(self) -> None:
        with tempfile.TemporaryDirectory(prefix="jev-score-") as tmp:
            work = Path(tmp)
            ok, classpath_or_reason = _compile(work)
            self.assertTrue(ok, f"hermetic compile failed: {classpath_or_reason}")
            doc = _run_json(classpath_or_reason, work, "base")
            self.assertNotIn("__error__", doc, f"no JSON output: {doc}")
            problems = _report_problems(doc)
            self.assertEqual(problems, [], ",".join(problems))

    def test_ui_asset_hash_separation(self) -> None:
        with tempfile.TemporaryDirectory(prefix="jev-score-") as tmp:
            work = Path(tmp)
            ok, classpath_or_reason = _compile(work)
            self.assertTrue(ok, f"hermetic compile failed: {classpath_or_reason}")
            try:
                base = _run_json(classpath_or_reason, work, "base")
                self.assertNotIn("__error__", base, f"no JSON output: {base}")
                PROBE.write_text("/* jev-scoring-uihash-probe */\n", encoding="utf-8")
                probed = _run_json(classpath_or_reason, work, "probe")
            finally:
                PROBE.unlink(missing_ok=True)
            self.assertNotIn("__error__", probed, f"no JSON output after probe: {probed}")
            base_src, probed_src = base.get("sourceIdentityHash"), probed.get("sourceIdentityHash")
            base_ui, probed_ui = base.get("uiAssetHash"), probed.get("uiAssetHash")
            if base_src != probed_src:
                self.fail("sourceIdentityHash moved with a CSS-only probe "
                          "(possible concurrent java edit or hash leak)")
            self.assertNotEqual(base_ui, probed_ui,
                                "uiAssetHash did not change when a display CSS file appeared")


if __name__ == "__main__":
    unittest.main(verbosity=2)
