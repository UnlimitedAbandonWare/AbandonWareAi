"""Self-check for agent_done_evidence_guard.py — synthetic journal fixtures in a temp dir.

Run: python -B scripts/test_agent_done_evidence_guard.py
Exit 0 = all cases behaved as expected; 1 = a case disagreed (details printed).
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GUARD = ROOT / "scripts" / "agent_done_evidence_guard.py"


def run_guard(*args: str) -> tuple[int, dict]:
    proc = subprocess.run(
        [sys.executable, "-B", str(GUARD), *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    try:
        payload = json.loads(proc.stdout.strip())
    except json.JSONDecodeError:
        payload = {"parse_error": proc.stdout[:200], "stderr": proc.stderr[:200]}
    return proc.returncode, payload


def make_journal(root: Path, task: str, events: list[dict], status="in_progress") -> Path:
    task_dir = root / "data" / "agent-handoff" / "codex-autonomy" / task
    task_dir.mkdir(parents=True, exist_ok=True)
    journal = {"schemaVersion": "awx.work_journal.v1", "taskId": task,
               "agent": "synthetic", "purpose": "fixture", "status": status,
               "events": events}
    (task_dir / "journal.json").write_text(json.dumps(journal), encoding="utf-8")
    return task_dir


def main() -> int:
    cases = []

    code, out = run_guard("--text", "Done: all verified")
    cases.append(("bare-done-claim-rejected", code == 2 and
                  "done-claim-no-evidence-token" in out.get("reasons", []), out))

    code, out = run_guard("--text", "Done: A1 GREEN 102/102, verify=gradlew exit=0")
    cases.append(("evidenced-done-allowed", code == 0, out))

    code, out = run_guard("--text", "full suite = Done")
    cases.append(("full-suite-phrase-blocked", code == 2 and
                  any(r.startswith("claim-text:") for r in out.get("reasons", [])), out))

    with tempfile.TemporaryDirectory() as tmp:
        troot = Path(tmp)
        make_journal(troot, "t-no-verify", [])
        code, out = run_guard("--root", str(troot), "--task", "t-no-verify",
                              "--text", "Done: exit=0")
        cases.append(("task-no-verify-event", code == 2 and
                      "no-verify-event" in out.get("reasons", []), out))

        task_dir = make_journal(troot, "t-verified", [
            {"kind": "verify", "text": "focused GREEN",
             "refs": ["data/agent-handoff/codex-autonomy/t-verified/v1/run.json"]}])
        run_dir = task_dir / "v1"
        run_dir.mkdir(parents=True, exist_ok=True)
        (run_dir / "run.json").write_text(json.dumps({"exitCode": 0, "status": "passed"}),
                                          encoding="utf-8")
        code, out = run_guard("--root", str(troot), "--task", "t-verified",
                              "--text", "Done: exit=0")
        cases.append(("task-verify-run-ok", code == 0 and
                      out.get("detail", {}).get("verifyEventCount") == 1, out))

        code, out = run_guard("--root", str(troot), "--task", "t-verified",
                              "--text", "Done: running live, exit=0")
        cases.append(("runtime-claim-without-runtime-evidence", code == 2 and
                      "runtime-not-observed" in out.get("reasons", []), out))

        make_journal(troot, "t-bad-ref", [
            {"kind": "verify", "text": "claimed",
             "refs": ["data/agent-handoff/codex-autonomy/t-bad-ref/v9/run.json"]}])
        code, out = run_guard("--root", str(troot), "--task", "t-bad-ref",
                              "--text", "Done: exit=0")
        cases.append(("verify-ref-missing-run", code == 2 and
                      any("verify-ref-missing-run" in r for r in out.get("reasons", [])), out))

        # Contract DEMO1-DEVIN-QUARANTINE-ANTIPATTERN-RAILS-20260929 §3:
        # evidence paths cited in the claim must exist under --root.
        code, out = run_guard(
            "--root", str(troot), "--text",
            "Done: exit=0, evidence docs/diagnostics/never-written.md")
        cases.append(("cited-evidence-path-missing", code == 2 and
                      any("evidence-path-missing" in r
                          for r in out.get("reasons", [])), out))

        cited = troot / "docs" / "diagnostics" / "wrote-this.md"
        cited.parent.mkdir(parents=True, exist_ok=True)
        cited.write_text("fixture", encoding="utf-8")
        code, out = run_guard(
            "--root", str(troot), "--text",
            "Done: exit=0, evidence docs/diagnostics/wrote-this.md")
        cases.append(("cited-evidence-path-exists", code == 0, out))

        code, out = run_guard(
            "--root", str(troot), "--text",
            "Done: exit=0; handoff data/agent-handoff/x/FOR_CODEX.md "
            "and test scripts/test_missing_file.py")
        cases.append(("multi-missing-paths-flagged", code == 2 and
                      sum(1 for r in out.get("reasons", [])
                          if r.startswith("evidence-path-missing")) == 2, out))

        code, out = run_guard(
            "--root", str(troot), "--text",
            "Done: exit=0, plain prose name notes.md not a path")
        cases.append(("bare-filename-not-flagged", code == 0, out))

    failed = [name for name, ok, _ in cases if not ok]
    for name, ok, out in cases:
        print(f"{'PASS' if ok else 'FAIL'} {name} :: {json.dumps(out, ensure_ascii=False)[:160]}")
    print(f"{len(cases) - len(failed)}/{len(cases)} cases behaved")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
