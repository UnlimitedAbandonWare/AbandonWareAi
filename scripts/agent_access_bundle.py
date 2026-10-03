#!/usr/bin/env python3
"""agent_access_bundle.py — F01B + TRACE 접근 프로브 한 명령 실행.

Contract DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929 §4.7.

하위 스크립트를 subprocess 로 호출하고 Codex 용 증거 경로를 인쇄한다.
트랙 병합 금지: 판정·출력 JSON 은 트랙별 분리.

실행 순서:
  1) f01b_schema_gate.py   (--mode file when --skip-live-db, else both)
  2) f01b_tm_probe.py
  3) f01b_admission_key_demo.py --demo
  4) f01b_evidence_pack.py (missing scaffold 만 채움)
  5) trace_dock_cost_guard.py
  6) trace_dock_a11y_scan.py

--tracks vibe (DEMO1-CLEAN-QUARANTINE-VIBE-AUTO-OPT-20260929 §D):
  7) agent_done_evidence_guard.py --text <evidenced claim>   (0 pass / 2 block)
  8) agent_vibe_auto_decision.py --action <local probe>     (0 AUTO/3 ASK/4 HOLD)
  9) quarantine_codex_rollout_mine.py --sample-lines 300    (0 / 2 no-rollouts)

overall exit = worst of children (모두 0/3 예상 PARTIAL 이면 0; 어느 하나 2면 2;
4 safety 는 항상 4; 1 usage/IO 는 1). 자식 verdict 는 그 자식 JSON에서 확인.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import subprocess
import sys

CONTRACT_ID = "DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929"
SCHEMA = "awx.agent-access-bundle.v1"
DEFAULT_OUT_DIR = "data/diagnostics/f01b-trace-access-0929"
FOR_CODEX_MD = "docs/diagnostics/f01b-narrow-jdbc-0929/FOR_CODEX.md"
FOR_CODEX_SCRIPTS_MD = ("docs/diagnostics/"
                        "devin-scripts-f01b-trace-access-0929/"
                        "FOR_CODEX_SCRIPTS.md")

CHILDREN = {
    "f01b": [
        ("F01B_SCHEMA", "scripts/f01b_schema_gate.py"),
        ("F01B_TM", "scripts/f01b_tm_probe.py"),
        ("F01B_KEYS", "scripts/f01b_admission_key_demo.py"),
        ("F01B_PACK", "scripts/f01b_evidence_pack.py"),
    ],
    "trace": [
        ("TRACE_COST", "scripts/trace_dock_cost_guard.py"),
        ("TRACE_A11Y", "scripts/trace_dock_a11y_scan.py"),
    ],
    "vibe": [
        ("VIBE_DONE_GUARD", "scripts/agent_done_evidence_guard.py"),
        ("VIBE_AUTO_DECISION", "scripts/agent_vibe_auto_decision.py"),
        ("VIBE_MINER", "scripts/quarantine_codex_rollout_mine.py"),
    ],
}


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def child_args(name: str, out_dir: str, skip_live_db: bool) -> list[str]:
    # vibe track children have their own output contracts (no --json-out)
    if name.endswith("agent_done_evidence_guard.py"):
        return ["--text", "bundle selfcheck Done verified 1/1 exit=0"]
    if name.endswith("agent_vibe_auto_decision.py"):
        return ["--action", "bundle selfcheck read-only local probe"]
    if name.endswith("quarantine_codex_rollout_mine.py"):
        return ["--sample-lines", "300", "--out", f"{out_dir}/vibe-mine"]
    json_out = f"{out_dir}/{name.split('/')[-1].replace('.py', '')}.json"
    args = ["--json-out", json_out]
    if name.endswith("f01b_schema_gate.py"):
        args += ["--mode", "file" if skip_live_db else "both"]
    elif name.endswith("f01b_admission_key_demo.py"):
        args += ["--demo"]
    return args


def run_child(root: Path, script: str, extra: list[str],
              timeout: int) -> dict:
    cmd = [sys.executable, "-B", script, "--root", str(root)] + extra
    try:
        proc = subprocess.run(cmd, cwd=str(root), capture_output=True,
                              text=True, timeout=timeout)
        stdout_tail = (proc.stdout or "").strip().splitlines()
        return {"script": script, "exit": proc.returncode,
                "stdoutTail": stdout_tail[-1] if stdout_tail else ""}
    except subprocess.TimeoutExpired:
        return {"script": script, "exit": 1, "error": "timeout"}
    except OSError as exc:
        return {"script": script, "exit": 1, "error": type(exc).__name__}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="Run all F01B+TRACE access probes in one command")
    ap.add_argument("--root", default=".")
    ap.add_argument("--tracks", default="f01b,trace",
                    help="comma-separated: f01b,trace,vibe")
    ap.add_argument("--skip-live-db", action="store_true",
                    help="schema_gate 를 --mode file 로 실행")
    ap.add_argument("--timeout", type=int, default=120,
                    help="per-child subprocess timeout seconds")
    ap.add_argument("--json-out",
                    default=f"{DEFAULT_OUT_DIR}/agent_access_bundle.json")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    tracks = [t.strip() for t in args.tracks.split(",") if t.strip()]
    unknown = [t for t in tracks if t not in CHILDREN]
    if unknown or not tracks:
        print(f"unknown tracks: {unknown}", file=sys.stderr)
        return 1

    results = {}
    for track in tracks:
        for label, script in CHILDREN[track]:
            extra = child_args(script, DEFAULT_OUT_DIR, args.skip_live_db)
            res = run_child(root, script, extra, args.timeout)
            res["track"] = track
            res["label"] = label
            res["jsonOut"] = (extra[extra.index("--json-out") + 1]
                              if "--json-out" in extra else None)
            results[label] = res

    exits = [r["exit"] for r in results.values()]
    if any(e == 4 for e in exits):
        overall = 4
    elif any(e == 2 for e in exits):
        overall = 2
    elif any(e == 1 for e in exits):
        overall = 1
    else:  # 전부 0 또는 3 (expected partial)
        overall = 0

    for_codex_paths = [r["jsonOut"] for r in results.values() if r["jsonOut"]]
    for_codex_paths += [FOR_CODEX_MD, FOR_CODEX_SCRIPTS_MD]

    payload = {
        "schemaVersion": SCHEMA,
        "contractId": CONTRACT_ID,
        "generatedAtUtc": utcnow(),
        "root": str(root),
        "tracks": tracks,
        "skipLiveDb": bool(args.skip_live_db),
        "overallExit": overall,
        "children": results,
        "forCodex": for_codex_paths,
        "note": "per-track verdicts stay separate; TRACE PASS is never F01-B "
                "approval; stay product flags OFF",
    }
    out_path = root / args.json_out
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                        encoding="utf-8")

    summary = "  ".join(
        f"{label}={res['exit']}" for label, res in results.items())
    if args.json:
        print(json.dumps(payload, ensure_ascii=False))
    else:
        print(summary)
        print(f"overallExit={overall} json={out_path}")
        print("Codex: read " + FOR_CODEX_SCRIPTS_MD +
              " ; stay product flags OFF")
    return overall


if __name__ == "__main__":
    raise SystemExit(main())
