"""Self-check for quarantine_codex_rollout_mine.py — synthetic rollout fixtures.

Builds tiny jsonl rollouts in a temp dir (never real session data) and checks
stop-reason classification, streaming bounds, and report emission.

Run: python -B scripts/tests/test_quarantine_codex_rollout_mine.py
Exit 0 = all cases behaved as expected; 1 = a case disagreed.
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MINER = ROOT / "scripts" / "quarantine_codex_rollout_mine.py"

SID_CHILD = "11111111-2222-3333-4444-555555555555"
SID_CLEAN = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"


def line(ts: str, typ: str, payload: dict) -> str:
    return json.dumps({"timestamp": ts, "type": typ, "payload": payload},
                      ensure_ascii=False)


def msg(role: str, text: str) -> dict:
    return {"type": "message", "role": role,
            "content": [{"type": "input_text", "text": text}]}


def make_child_rollout(path: Path) -> None:
    rows = [
        line("2026-09-12T08:00:00Z", "session_meta", {
            "session_id": SID_CHILD, "id": SID_CHILD,
            "parent_thread_id": "99999999-0000-0000-0000-000000000000",
            "source": {"subagent": {"thread_spawn": {
                "agent_nickname": "FixtureChild"}}}}),
        line("2026-09-12T08:00:01Z", "event_msg",
             {"type": "task_started", "turn_id": "t1"}),
        line("2026-09-12T08:01:00Z", "response_item",
             msg("assistant", "Read goal-objective.md — Done, task finished.")),
        line("2026-09-12T08:01:05Z", "response_item",
             msg("assistant", "승인하시겠습니까? 1) proceed 2) hold")),
        line("2026-09-12T08:01:10Z", "response_item",
             msg("assistant", "choose an option: 1) A 2) B")),
    ] * 3
    path.write_text("\n".join(rows) + "\n", encoding="utf-8")


def make_clean_rollout(path: Path) -> None:
    rows = [
        line("2026-09-12T09:00:00Z", "session_meta",
             {"session_id": SID_CLEAN, "id": SID_CLEAN}),
        line("2026-09-12T09:00:01Z", "event_msg",
             {"type": "task_started", "turn_id": "t1"}),
        line("2026-09-12T09:02:00Z", "response_item", {
            "type": "custom_tool_call", "name": "exec", "call_id": "c1",
            "input": '{"cmd": "gradlew test --tests com.demo.FooTest"}'}),
        line("2026-09-12T09:03:00Z", "response_item",
             msg("assistant", "Focused suite GREEN exit=0 GATE passed.")),
        line("2026-09-12T09:03:30Z", "response_item",
             msg("assistant", "wrote journal + FOR_CODEX handoff dir")),
        line("2026-09-12T09:04:00Z", "event_msg",
             {"type": "task_complete", "turn_id": "t1"}),
        line("2026-09-12T09:04:01Z", "token_usage_record",
             {"usage": {"total_tokens": 1200},
              "thread_token_usage": {"total_tokens": 1200}}),
    ]
    path.write_text("\n".join(rows) + "\n", encoding="utf-8")


def run_miner(*args: str) -> tuple[int, dict]:
    proc = subprocess.run(
        [sys.executable, "-B", str(MINER), *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    try:
        payload = json.loads(proc.stdout.strip())
    except json.JSONDecodeError:
        payload = {"stdout": proc.stdout[:300], "stderr": proc.stderr[:300]}
    return proc.returncode, payload


def session_for(payload: dict, sid: str) -> dict:
    for s in (payload.get("report", {}).get("sessions")
              or payload.get("sessions") or []):
        if s.get("sessionId") == sid:
            return s
    return {}


def main() -> int:
    cases = []
    with tempfile.TemporaryDirectory() as tmp:
        troot = Path(tmp)
        seed = troot / "quarantine"
        seed.mkdir()
        make_child_rollout(seed / f"rollout-2026-09-12T00-00-00-{SID_CHILD}.jsonl")
        make_clean_rollout(seed / f"rollout-2026-09-12T01-00-00-{SID_CLEAN}.jsonl")
        out_dir = troot / "out"

        code, out = run_miner("--seed", str(seed), "--out", str(out_dir),
                              "--root", str(troot), "--json")
        report_path = out_dir / "mine-report.json"
        report = {}
        if report_path.is_file():
            report = json.loads(report_path.read_text(encoding="utf-8"))
        cases.append(("exit0-and-report-written", code == 0 and report
                      and (out_dir / "ANTIPATTERNS.md").is_file(), out))

        child = session_for({"sessions": report.get("sessions", [])}, SID_CHILD)
        reasons = child.get("stopReasons", [])
        cases.append(("child-stale-flagged",
                      "child-stale-no-evidence" in reasons, child))
        cases.append(("goal-read-as-done-flagged",
                      "goal-read-as-done" in reasons, child))
        cases.append(("done-no-evidence-flagged",
                      "done-no-evidence" in reasons, child))

        clean = session_for({"sessions": report.get("sessions", [])}, SID_CLEAN)
        cases.append(("clean-no-done-flag",
                      "done-no-evidence" not in clean.get("stopReasons", []),
                      clean))
        cases.append(("clean-evidence-test",
                      clean.get("evidence", {}).get("test") is True, clean))

        code2, _ = run_miner("--seed", str(seed), "--out", str(out_dir),
                             "--root", str(troot), "--max-bytes-per-file",
                             "400", "--json")
        report2 = json.loads((out_dir / "mine-report.json").read_text(
            encoding="utf-8"))
        cases.append(("cap-truncates",
                      code2 == 0 and any(s.get("truncated") for s in
                                         report2.get("sessions", [])),
                      report2.get("sessions", [{}])[0]))

        code3, out3 = run_miner("--seed", str(troot / "missing-dir"),
                                "--out", str(out_dir), "--root", str(troot))
        cases.append(("missing-seed-exit2", code3 == 2, out3))

        # Contract-pinned fixture ships in-repo (scripts/tests/fixtures/).
        repo_fixture = ROOT / "scripts" / "tests" / "fixtures" / \
            "quarantine_mini.jsonl"
        code4, _ = run_miner("--seed", str(repo_fixture), "--out",
                             str(out_dir), "--root", str(troot), "--json")
        rep4 = json.loads((out_dir / "mine-report.json").read_text(
            encoding="utf-8"))
        fs = rep4.get("sessions", [{}])[0]
        cases.append(("repo-fixture-mines", code4 == 0 and
                      fs.get("lines") == 14 and
                      "child-stale-no-evidence" in fs.get("stopReasons", []),
                      fs))

        # Redaction: secret-shaped cmd args must reach the report as
        # [REDACTED], never as raw bytes. The credential shapes are built by
        # concatenation so the committed test carries no scanner-shaped bytes.
        bearer_tok = "a" * 24
        secret_rollout = troot / "quarantine" / (
            f"rollout-2026-09-12T02-00-00-{SID_CHILD}.jsonl")
        secret_rollout.write_text(line(
            "2026-09-12T10:00:00Z", "response_item", {
                "type": "function_call", "name": "exec", "call_id": "s1",
                "input": json.dumps({"cmd": "curl -H 'Author" + "ization: Bea"
                                            + "rer " + bearer_tok + "' "
                                            "https://x && tok" + "en="
                                            + "p" * 8})}
        ) + "\n", encoding="utf-8")
        code5, _ = run_miner("--seed", str(seed), "--out", str(out_dir),
                             "--root", str(troot), "--json")
        rep5 = json.loads((out_dir / "mine-report.json").read_text(
            encoding="utf-8"))
        cmds = json.dumps(rep5.get("totals", {}).get("cmds", []))
        cases.append(("secret-redacted",
                      code5 == 0 and "REDACTED" in cmds
                      and bearer_tok not in cmds
                      and "p" * 8 not in cmds, cmds[:200]))

    failed = [n for n, ok, _ in cases if not ok]
    for n, ok, o in cases:
        print(f"{'PASS' if ok else 'FAIL'} {n} :: "
              f"{json.dumps(o, ensure_ascii=False)[:160]}")
    print(f"{len(cases) - len(failed)}/{len(cases)} cases behaved")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
