"""Self-check for common_verifier.py — fake-success injection fixtures.

Builds a synthetic repo in a temp dir with a controlled fake test runner and
verifies that each fake-success pattern from the P6 audit is blocked with the
right verdict, and that a genuine green run reaches VERIFIED_PENDING_APPROVAL.

Run: python -B scripts/test_common_verifier.py
Exit 0 = all cases behaved; 1 = a case disagreed (details printed).
"""
from __future__ import annotations

import json
import os
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

        # --- string "command" spec with a Windows backslash path -----------
        # nt-only: on POSIX the backslash path legitimately cannot resolve.
        if os.name == "nt":
            exe_cmd = (f'"{sys.executable}"' if " " in sys.executable
                       else sys.executable)
            cmd_spec = {"taskId": "t-fixture", "agent": "synthetic",
                        "commands": [{"id": "cmdstr", "required": True,
                                      "timeoutSec": 30,
                                      "command": f"{exe_cmd} -B "
                                                 r"scripts\fake_runner.py"
                                                 " green"}]}
            p = root / "spec_cmdstr.json"
            p.write_text(json.dumps(cmd_spec), encoding="utf-8")
            code, out = run_verifier("verify", "--root", str(root),
                                     "--spec", str(p))
            payload = parse_json_block(out)
            cases.append(("command-string-backslash-verified", code == 0
                          and payload.get("verdict")
                          == "VERIFIED_PENDING_APPROVAL"
                          and payload.get("testCount") == 12, out))

            # --- CLI --argv with a Windows backslash path -------------------
            code, out = run_verifier(
                "verify", "--root", str(root), "--argv",
                f"{exe_cmd} -B " + r"scripts\fake_runner.py" + " green")
            payload = parse_json_block(out)
            cases.append(("cli-argv-backslash-verified", code == 0
                          and payload.get("verdict")
                          == "VERIFIED_PENDING_APPROVAL"
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

        # Recorded green is valid only while every pinned file still matches.
        p = spec("pinned-replay", ["green"], testFiles=["scripts/test_thing.py"],
                 sourceFiles=["src/Thing.java"], testPreimageDir="preimage",
                 expectedMinTests=12)
        code, out = run_verifier("verify", "--root", str(root), "--spec", str(p))
        recorded = parse_json_block(out)
        result_file = root / "pinned_result.json"
        result_file.write_text(json.dumps(recorded), encoding="utf-8")
        receipt_bytes = result_file.read_bytes()
        receipt_mtime = result_file.stat().st_mtime_ns
        code, out = run_verifier("adjudicate", "--root", str(root),
                                 "--result", str(result_file))
        payload = parse_json_block(out)
        cases.append(("pinned-unchanged-replay-verified", code == 0
                      and payload.get("verdict") == "VERIFIED_PENDING_APPROVAL"
                      and recorded.get("expectedMinTests") == 12, out))
        source_bytes = src.read_bytes()
        src.write_bytes(source_bytes + b"// changed after verify\n")
        code, out = run_verifier("adjudicate", "--root", str(root),
                                 "--result", str(result_file))
        payload = parse_json_block(out)
        cases.append(("saved-green-current-source-changed-INVALIDATED", code == 2
                      and payload.get("verdict") == "INVALIDATED"
                      and any("current-digest-mismatch" in r for r in payload.get("reasons", []))
                      and result_file.read_bytes() == receipt_bytes
                      and result_file.stat().st_mtime_ns == receipt_mtime, out))
        src.unlink()
        code, out = run_verifier("adjudicate", "--root", str(root),
                                 "--result", str(result_file))
        payload = parse_json_block(out)
        cases.append(("saved-green-current-source-missing-INVALIDATED", code == 2
                      and payload.get("verdict") == "INVALIDATED"
                      and any("current-digest-missing" in r for r in payload.get("reasons", [])), out))
        src.write_bytes(source_bytes)

        for missing_map in ("pre", "post", "both"):
            missing = json.loads(json.dumps(recorded))
            missing["sourceFiles"] = ["src/Thing.java"]
            for key in (("pre", "post") if missing_map == "both" else (missing_map,)):
                missing["digests"][key].pop("src/Thing.java")
            missing_result = root / f"missing_{missing_map}_result.json"
            missing_result.write_text(json.dumps(missing), encoding="utf-8")
            code, out = run_verifier("adjudicate", "--root", str(root),
                                     "--result", str(missing_result))
            payload = parse_json_block(out)
            cases.append((f"missing-{missing_map}-pinned-digest-NOT_PROVEN", code == 2
                          and payload.get("verdict") == "REJECTED"
                          and any("pinned-digest-NOT_PROVEN" in r for r in payload.get("reasons", [])), out))

        # A recorded weakening rejection cannot be promoted by replay.
        test_file.write_text(removed, encoding="utf-8")
        p = spec("rejected-replay", ["green"], testFiles=["scripts/test_thing.py"],
                 testPreimageDir="preimage")
        code, out = run_verifier("verify", "--root", str(root), "--spec", str(p))
        rejected = parse_json_block(out)
        rejected_result = root / "rejected_result.json"
        rejected_result.write_text(json.dumps(rejected), encoding="utf-8")
        rejected_bytes = rejected_result.read_bytes()
        for restored in (False, True):
            if restored:
                test_file.write_text((pre_dir / "test_thing.py").read_text(encoding="utf-8"),
                                     encoding="utf-8")
            code, out = run_verifier("adjudicate", "--root", str(root),
                                     "--result", str(rejected_result))
            payload = parse_json_block(out)
            cases.append((f"saved-REJECTED-replay-restored-{restored}", code == 2
                          and payload.get("verdict") == "REJECTED"
                          and any("test-assertion-weakened" in r for r in payload.get("reasons", []))
                          and rejected_result.read_bytes() == rejected_bytes, out))

        for variant, changed in (("skip", weakened), ("assertion", removed)):
            test_file.write_text(changed, encoding="utf-8")
            code, out = run_verifier("adjudicate", "--root", str(root),
                                     "--result", str(result_file))
            payload = parse_json_block(out)
            cases.append((f"saved-green-current-test-{variant}-REJECTED", code == 2
                          and payload.get("verdict") == "REJECTED"
                          and any("test-disabled-added" in r or "test-assertion-weakened" in r
                                  for r in payload.get("reasons", [])), out))
        test_file.write_text((pre_dir / "test_thing.py").read_text(encoding="utf-8"),
                             encoding="utf-8")

        optional_spec = root / "optional_spec.json"
        optional_spec.write_text(json.dumps({
            "commands": [{"id": "required-green", "argv": [sys.executable, "-B", str(runner), "green"],
                          "required": True},
                         {"id": "optional-exit1", "argv": [sys.executable, "-B", str(runner), "exit1"],
                          "required": False}], "expectedMinTests": 13}), encoding="utf-8")
        code, out = run_verifier("verify", "--root", str(root), "--spec", str(optional_spec))
        optional_recorded = parse_json_block(out)
        optional_result = root / "optional_result.json"
        optional_result.write_text(json.dumps(optional_recorded), encoding="utf-8")
        code, out = run_verifier("adjudicate", "--root", str(root), "--result", str(optional_result))
        payload = parse_json_block(out)
        cases.append(("optional-command-required-metadata-preserved", code == 0
                      and payload.get("verdict") == "VERIFIED_PENDING_APPROVAL"
                      and optional_recorded.get("expectedMinTests") == 13
                      and any(c.get("id") == "optional-exit1" and c.get("required") is False
                              for c in optional_recorded.get("commands", [])), out))

        p = spec("minimum-rejected", ["green"], expectedMinTests=13)
        code, out = run_verifier("verify", "--root", str(root), "--spec", str(p))
        minimum_recorded = parse_json_block(out)
        minimum_result = root / "minimum_result.json"
        minimum_result.write_text(json.dumps(minimum_recorded), encoding="utf-8")
        code, out = run_verifier("adjudicate", "--root", str(root), "--result", str(minimum_result))
        payload = parse_json_block(out)
        cases.append(("saved-INCOMPLETE-minimum-replay-blocked", code == 2
                      and payload.get("verdict") == "INCOMPLETE"
                      and "no-tests-executed" in payload.get("reasons", []), out))

    failed = [name for name, ok, _ in cases if not ok]
    for name, ok, out in cases:
        print(f"{'PASS' if ok else 'FAIL'} {name} :: {out.strip()[:160]}")
    print(f"{len(cases) - len(failed)}/{len(cases)} cases behaved")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
