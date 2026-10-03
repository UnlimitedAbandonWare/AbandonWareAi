"""Self-check for quarantine_failure_episode_extract.py — synthetic fixtures.

Builds tiny rollout jsonl files in a temp dir (never real session data) and
checks episode shape, caps, stop-pattern classification, secret masking, and
the per-file episode cap. A committed micro-fixture is exercised too.

Run: python -B scripts/tests/test_quarantine_failure_episode_extract.py
Exit 0 = all cases behaved as expected; 1 = a case disagreed.
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "scripts" / "quarantine_failure_episode_extract.py"
FIXTURE = ROOT / "scripts" / "tests" / "fixtures" / \
    "quarantine_episode_fixture.jsonl"

SID_CHILD = "11111111-2222-3333-4444-555555555555"
SID_CLEAN = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
FAKE_SECRET = "sk-" + "FAKEKEY" * 3 + "1234"


def line(ts: str, typ: str, payload: dict) -> str:
    return json.dumps({"timestamp": ts, "type": typ, "payload": payload},
                      ensure_ascii=False)


def msg(role: str, text: str) -> dict:
    return {"type": "message", "role": role,
            "content": [{"type": "input_text", "text": text}]}


def make_child_rollout(path: Path) -> None:
    rows = [
        line("2026-09-12T08:00:00Z", "session_meta", {
            "session_id": "99999999-0000-0000-0000-000000000000",
            "id": SID_CHILD,
            "parent_thread_id": "99999999-0000-0000-0000-000000000000",
            "source": {"subagent": {"thread_spawn": {
                "agent_nickname": "FixtureChild",
                "agent_role": "worker"}}},
            "thread_source": "subagent"}),
        line("2026-09-12T08:00:01Z", "event_msg",
             {"type": "task_started", "turn_id": "t1"}),
        line("2026-09-12T08:01:00Z", "response_item", {
            "type": "custom_tool_call", "name": "exec", "call_id": "c1",
            "input": json.dumps({"cmd": "curl -H \"Author" + "ization: Bearer "
                                 + FAKE_SECRET + "\" http://localhost/x"})}),
        line("2026-09-12T08:01:05Z", "response_item",
             msg("assistant", "Read goal-objective.md — Done, finished.")),
        line("2026-09-12T08:02:00Z", "response_item",
             msg("assistant", "승인하시겠습니까? 1) proceed 2) hold")),
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
        line("2026-09-12T09:02:30Z", "response_item", {
            "type": "custom_tool_call_output", "call_id": "c1",
            "output": "BUILD SUCCESSFUL"}),
        line("2026-09-12T09:03:00Z", "response_item",
             msg("assistant", "Focused suite GREEN exit=0 GATE passed.")),
        line("2026-09-12T09:04:00Z", "event_msg",
             {"type": "task_complete", "turn_id": "t1"}),
    ]
    path.write_text("\n".join(rows) + "\n", encoding="utf-8")


def run_tool(*args: str) -> tuple[int, str]:
    proc = subprocess.run(
        [sys.executable, "-B", str(TOOL), *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    return proc.returncode, proc.stdout + proc.stderr


def load_episodes(out_dir: Path) -> list[dict]:
    eps = []
    for p in sorted(out_dir.glob("episodes-*.jsonl")):
        for ln in p.read_text(encoding="utf-8").splitlines():
            if ln.strip():
                eps.append(json.loads(ln))
    return eps


def main() -> int:
    cases = []
    with tempfile.TemporaryDirectory() as tmp:
        troot = Path(tmp)
        seed = troot / "quarantine"
        seed.mkdir()
        make_child_rollout(
            seed / f"rollout-2026-09-12T00-00-00-{SID_CHILD}.jsonl")
        make_clean_rollout(
            seed / f"rollout-2026-09-12T01-00-00-{SID_CLEAN}.jsonl")
        (seed / "apply-test.jsonl").write_text(json.dumps({
            "class": "child-stale-no-evidence", "id": SID_CHILD,
            "reason": "child-stale-no-evidence; updated_ms=1; title=",
            "pre_sha256": "a", "post_sha256": "a"}) + "\n",
            encoding="utf-8")
        out_dir = troot / "episodes"

        code, out = run_tool("--seed", str(seed), "--out", str(out_dir),
                             "--root", str(troot), "--catalog",
                             str(troot / "absent-catalog.yaml"))
        eps = load_episodes(out_dir)
        cases.append(("exit0-episodes-written",
                      code == 0 and len(eps) == 2
                      and (out_dir / "_index.json").is_file(), out[:200]))

        child = next((e for e in eps if e.get("session_id") == SID_CHILD),
                     {})
        cases.append(("child-class-from-manifest",
                      child.get("class") == "child-stale-no-evidence",
                      child.get("class")))
        cases.append(("child-stop-pattern",
                      "child-stale-no-evidence" in
                      (child.get("stop_patterns") or []),
                      child.get("stop_patterns")))
        cases.append(("child-title-empty-flagged",
                      child.get("titleEmpty") is True
                      and child.get("titleStatus") == "observed",
                      child.get("titleStatus")))
        cases.append(("child-rail-session-watch",
                      "agent_session_watch" in
                      str(child.get("recommended_rail")),
                      child.get("recommended_rail")))
        sig = child.get("signals") or {}
        cases.append(("signal-counts-present",
                      sig.get("custom_tool_call") == 3
                      and sig.get("askish") == 3
                      and sig.get("doneClaims") == 3
                      and sig.get("toolCalls") == 3, sig))
        cases.append(("child-flagged-child", (child.get("child") or {})
                      .get("isChild") is True, child.get("child")))

        clean = next((e for e in eps if e.get("session_id") == SID_CLEAN),
                     {})
        cases.append(("clean-evidence-test",
                      (clean.get("evidence") or {}).get("test") is True,
                      clean.get("evidence")))
        cases.append(("clean-no-done-no-evidence",
                      "done-no-evidence" not in
                      (clean.get("stop_patterns") or []),
                      clean.get("stop_patterns")))

        blob = out_dir.joinpath("episodes-0001.jsonl").read_text(
            encoding="utf-8")
        cases.append(("secret-masked", FAKE_SECRET not in blob
                      and "<redacted>" in blob,
                      blob[:160]))
        cases.append(("redact-flag", all(e.get("redact_secrets") for e in eps),
                      None))
        cases.append(("episode-size-cap", all(
            len(json.dumps(e, ensure_ascii=False)) <= 4096 for e in eps),
            None))
        cases.append(("no-raw-fields", all(
            "content" not in e and "text" not in e and "input" not in e
            for e in eps), None))

        # per-file cap splits output
        out2 = troot / "episodes2"
        code2, _ = run_tool("--seed", str(seed), "--out", str(out2),
                            "--root", str(troot), "--max-per-file", "1",
                            "--catalog", str(troot / "absent.yaml"))
        files2 = sorted(out2.glob("episodes-*.jsonl"))
        cases.append(("max-per-file-splits",
                      code2 == 0 and len(files2) == 2,
                      [f.name for f in files2]))

        # max-episodes sampling cap
        out3 = troot / "episodes3"
        run_tool("--seed", str(seed), "--out", str(out3),
                 "--root", str(troot), "--max-episodes", "1")
        cases.append(("max-episodes-cap",
                      len(load_episodes(out3)) == 1, None))

        # committed micro-fixture parses too
        if FIXTURE.is_file():
            out4 = troot / "episodes4"
            code4, _ = run_tool("--seed", str(FIXTURE), "--out", str(out4),
                                "--root", str(troot))
            fep = load_episodes(out4)
            cases.append(("committed-fixture",
                          code4 == 0 and len(fep) == 1
                          and fep[0].get("session_id"), fep[:1]))

        code5, out5 = run_tool("--seed", str(troot / "missing"),
                               "--out", str(out_dir), "--root", str(troot))
        cases.append(("missing-seed-exit2", code5 == 2, out5[:120]))

    failed = [n for n, ok, _ in cases if not ok]
    for n, ok, o in cases:
        print(f"{'PASS' if ok else 'FAIL'} {n} :: "
              f"{json.dumps(o, ensure_ascii=False)[:160]}")
    print(f"{len(cases) - len(failed)}/{len(cases)} cases behaved")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
