"""Self-check for common_verifier.py — fake-success injection fixtures.

Builds a synthetic repo in a temp dir with a controlled fake test runner and
verifies that each fake-success pattern from the P6 audit is blocked with the
right verdict, and that a genuine green run reaches VERIFIED_PENDING_APPROVAL.

Run: python -B scripts/test_common_verifier.py
Exit 0 = all cases behaved; 1 = a case disagreed (details printed).
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERIFIER = ROOT / "scripts" / "common_verifier.py"

FAKE_RUNNER = '''import pathlib, sys
mode = sys.argv[1] if len(sys.argv) > 1 else "green"
if mode == "green":
    print("12/12 cases behaved"); sys.exit(0)
if mode == "exit1":
    print("1/12 cases behaved"); sys.exit(1)
if mode == "zero":
    print("0/0 cases behaved"); sys.exit(0)
if mode == "allskip":
    print("Tests run: 6, skipped=6"); sys.exit(0)
if mode == "mutate":
    target = pathlib.Path(sys.argv[2])
    target.write_text(target.read_text() + "// drifted\\n")
    print("9/9 cases behaved"); sys.exit(0)
if mode == "failures":
    print("Tests run: 8, Failures: 2"); sys.exit(0)
sys.exit(3)
'''


def run_verifier(*args: str) -> tuple[int, str]:
    proc = subprocess.run(
        [sys.executable, "-B", str(VERIFIER), *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    return proc.returncode, proc.stdout


def parse_json_block(stdout: str) -> dict:
    # first stdout line is the JSON payload; second is the summary line
    line = stdout.strip().splitlines()[0] if stdout.strip() else "{}"
    return json.loads(line)


def main() -> int:
    cases = []
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "scripts").mkdir()
        runner = root / "scripts" / "fake_runner.py"
        runner.write_text(FAKE_RUNNER, encoding="utf-8")
        src = root / "src" / "Thing.java"
        src.parent.mkdir(parents=True)
        src.write_text("class Thing {}\n", encoding="utf-8")
        test_file = root / "scripts" / "test_thing.py"
        test_file.write_text(
            "import unittest\nclass T(unittest.TestCase):\n"
            "    def test_a(self):\n        assert True\n", encoding="utf-8")
        pre_dir = root / "preimage"
        pre_dir.mkdir()
        (pre_dir / "test_thing.py").write_text(
            test_file.read_text(encoding="utf-8"), encoding="utf-8")

        def spec(name, argv, **extra):
            s = {"taskId": "t-fixture", "agent": "synthetic",
                 "commands": [{"id": name,
                               "argv": [sys.executable, "-B", str(runner), *argv],
                               "required": True, "timeoutSec": 30}]}
            s.update(extra)
            p = root / f"spec_{name}.json"
            p.write_text(json.dumps(s), encoding="utf-8")
            return p

        # --- genuine green -------------------------------------------------
        p = spec("green", ["green"], testFiles=["scripts/test_thing.py"],
                 sourceFiles=["src/Thing.java"],
                 testPreimageDir="preimage")
        code, out = run_verifier("verify", "--root", str(root), "--spec", str(p))
        payload = parse_json_block(out)
        cases.append(("green-verified-pending-approval",
                      code == 0 and payload.get("verdict")
                      == "VERIFIED_PENDING_APPROVAL"
                      and payload.get("verified") is True
                      and payload.get("testCount") == 12, out))

        # --- scenario 1: non-zero exit -------------------------------------
        p = spec("exit1", ["exit1"])
        code, out = run_verifier("verify", "--root", str(root), "--spec", str(p))
        payload = parse_json_block(out)
        cases.append(("nonzero-exit-FAIL", code == 2
                      and payload.get("verdict") == "FAIL"
                      and any("command-exit-nonzero" in r
                              for r in payload.get("reasons", [])), out))

        # --- scenario 2: exit 0, zero tests --------------------------------
        p = spec("zero", ["zero"])
        code, out = run_verifier("verify", "--root", str(root), "--spec", str(p))
        payload = parse_json_block(out)
        cases.append(("zero-tests-INCOMPLETE", code == 2
                      and payload.get("verdict") == "INCOMPLETE"
                      and "no-tests-executed" in payload.get("reasons", []), out))

        # --- scenario 3: 100% skipped --------------------------------------
        p = spec("allskip", ["allskip"])
        code, out = run_verifier("verify", "--root", str(root), "--spec", str(p))
        payload = parse_json_block(out)
        cases.append(("all-skipped-INCOMPLETE", code == 2
                      and payload.get("verdict") == "INCOMPLETE"
                      and "all-tests-skipped" in payload.get("reasons", []), out))

        # --- scenario 4: @Disabled added to test file ----------------------
        weakened = test_file.read_text(encoding="utf-8").replace(
            "import unittest\n", "import unittest\nfrom unittest import skip\n")
        weakened = weakened.replace("    def test_a",
                                    "    @skip('nope')\n    def test_a")
        test_file.write_text(weakened, encoding="utf-8")
        p = spec("weakened", ["green"], testFiles=["scripts/test_thing.py"],
                 testPreimageDir="preimage")
        code, out = run_verifier("verify", "--root", str(root), "--spec", str(p))
        payload = parse_json_block(out)
        cases.append(("test-weakened-REJECTED", code == 2
                      and payload.get("verdict") == "REJECTED"
                      and any("test-disabled-added" in r
                              for r in payload.get("reasons", [])), out))
        # restore for later cases
        test_file.write_text(
            (pre_dir / "test_thing.py").read_text(encoding="utf-8"),
            encoding="utf-8")

        # assertion removal variant
        removed = test_file.read_text(encoding="utf-8").replace(
            "        assert True\n", "        pass\n")
        test_file.write_text(removed, encoding="utf-8")
        p = spec("assertremoved", ["green"], testFiles=["scripts/test_thing.py"],
                 testPreimageDir="preimage")
        code, out = run_verifier("verify", "--root", str(root), "--spec", str(p))
        payload = parse_json_block(out)
        cases.append(("assertion-removed-REJECTED", code == 2
                      and payload.get("verdict") == "REJECTED"
                      and any("test-assertion-weakened" in r
                              for r in payload.get("reasons", [])), out))
        test_file.write_text(
            (pre_dir / "test_thing.py").read_text(encoding="utf-8"),
            encoding="utf-8")

        # --- scenario 5: source digest drifts during the run ---------------
        p = spec("mutate", ["mutate", str(src)],
                 sourceFiles=["src/Thing.java"])
        code, out = run_verifier("verify", "--root", str(root), "--spec", str(p))
        payload = parse_json_block(out)
        cases.append(("post-test-drift-INVALIDATED", code == 2
                      and payload.get("verdict") == "INVALIDATED"
                      and payload.get("digestMatches") is False
                      and any("post-test-digest-mismatch" in r
                              for r in payload.get("reasons", [])), out))

        # --- explicit failure counts are FAIL even at exit 0 ---------------
        p = spec("failures", ["failures"])
        code, out = run_verifier("verify", "--root", str(root), "--spec", str(p))
        payload = parse_json_block(out)
        cases.append(("exit0-with-failures-FAIL", code == 2
                      and payload.get("verdict") == "FAIL"
                      and any("test-failures" in r
                              for r in payload.get("reasons", [])), out))

        # --- adjudicate replays a recorded result --------------------------
        p = spec("green2", ["green"])
        code, out = run_verifier("verify", "--root", str(root), "--spec", str(p))
        payload = parse_json_block(out)
        payload["expectedMinTests"] = 1
        result_file = root / "recorded_result.json"
        result_file.write_text(json.dumps(payload), encoding="utf-8")
        code, out = run_verifier("adjudicate", "--root", str(root),
                                 "--result", str(result_file))
        payload = parse_json_block(out)
        cases.append(("adjudicate-replay-verified", code == 0
                      and payload.get("verdict") == "VERIFIED_PENDING_APPROVAL",
                      out))

    failed = [name for name, ok, _ in cases if not ok]
    for name, ok, out in cases:
        print(f"{'PASS' if ok else 'FAIL'} {name} :: {out.strip()[:160]}")
    print(f"{len(cases) - len(failed)}/{len(cases)} cases behaved")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
