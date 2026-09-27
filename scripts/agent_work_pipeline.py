#!/usr/bin/env python3
"""agent_work_pipeline.py — turn a pasted brief into a phased work plan
with a turn budget and a human-review summary (0913g0/0924b0 lineage,
weekly directive P8).

Phase sequencing is delegated to devin_task_orchestrate.plan (playbook
SSOT); this adds the scheduling layer on top:
  - phase class: read-only | write | verify (from the phase's write list)
  - turn budget per phase: 2 + tool count, capped at 12
  - single JSON plan + markdown summary for human review

Actions:
  plan --brief-file <path> | --brief "<text>"
       [--out plan.json] [--summary plan.md]
  update --plan <plan.json> --done <phaseId>[,<phaseId>...]
      Recomputes remaining phases and budget after completed phases.

Exit codes: 0 ok, 2 usage/io. Read-only over the repo; --out/--summary
write only where asked.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

SCRIPTS = Path(__file__).resolve().parent
ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))
import devin_task_orchestrate as orch  # noqa: E402

SCHEMA = "awx.agent-work-pipeline.v1"
BASE_TURNS = 2
MAX_PHASE_TURNS = 12


def _phase_class(phase: dict) -> str:
    if phase.get("write"):
        return "write"
    if phase.get("id", "").startswith(("verify", "capture")):
        return "verify"
    return "read-only"


def _budget(phase: dict) -> int:
    return min(MAX_PHASE_TURNS, BASE_TURNS + len(phase.get("tools") or []))


def build_plan(brief: str) -> dict:
    doc = orch.plan(brief)
    phases = []
    for phase in doc.get("phases", []):
        phases.append({
            "id": phase.get("id"),
            "skill": phase.get("skill"),
            "guard": phase.get("guard"),
            "class": _phase_class(phase),
            "budgetTurns": _budget(phase),
            "tools": phase.get("tools") or [],
            "write": phase.get("write") or [],
            "skip": phase.get("skip") or [],
            "stop": phase.get("stop"),
        })
    return {
        "schemaVersion": SCHEMA,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "matchedPlaybooks": doc.get("matchedPlaybooks"),
        "forbidden": doc.get("forbidden"),
        "phases": phases,
        "totalBudgetTurns": sum(p["budgetTurns"] for p in phases),
        "humanSummary": _summary(phases),
    }


def _summary(phases: list[dict]) -> str:
    lines = ["# 작업 계획표 (자동 생성)", ""]
    for i, p in enumerate(phases, 1):
        lines.append(f"{i}. `{p['id']}` [{p['class']}] "
                     f"skill={p['skill']} budget={p['budgetTurns']}턴")
        if p.get("stop"):
            lines.append(f"   - stop: {p['stop']}")
    lines.append("")
    lines.append(f"총 예산: {sum(p['budgetTurns'] for p in phases)}턴 / "
                 f"{len(phases)}단계")
    return "\n".join(lines)


def cmd_plan(args) -> tuple[dict, int]:
    if args.brief_file:
        brief = Path(args.brief_file).read_text(encoding="utf-8-sig")
    elif args.brief:
        brief = args.brief
    else:
        return {"schemaVersion": SCHEMA, "status": "error",
                "reason": "brief-required"}, 2
    plan_doc = build_plan(brief)
    if args.out:
        Path(args.out).write_text(
            json.dumps(plan_doc, ensure_ascii=True, indent=2),
            encoding="utf-8")
        plan_doc["planPath"] = args.out
    if args.summary:
        Path(args.summary).write_text(plan_doc["humanSummary"],
                                      encoding="utf-8")
        plan_doc["summaryPath"] = args.summary
    return plan_doc, 0


def cmd_update(args) -> tuple[dict, int]:
    doc = json.loads(Path(args.plan).read_text(encoding="utf-8-sig"))
    done = set()
    for item in args.done or []:
        done.update(part for part in item.split(",") if part)
    remaining = [p for p in doc.get("phases", [])
                 if p.get("id") not in done]
    return {
        "schemaVersion": SCHEMA,
        "done": sorted(done),
        "remaining": [p["id"] for p in remaining],
        "remainingBudgetTurns": sum(p["budgetTurns"] for p in remaining),
        "nextPhase": remaining[0] if remaining else None,
        "complete": not remaining,
    }, 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    p = sub.add_parser("plan")
    p.add_argument("--brief-file")
    p.add_argument("--brief")
    p.add_argument("--out")
    p.add_argument("--summary")
    p = sub.add_parser("update")
    p.add_argument("--plan", required=True)
    p.add_argument("--done", action="append", default=[])
    args = parser.parse_args(argv)

    try:
        result, code = (cmd_plan(args) if args.action == "plan"
                        else cmd_update(args))
    except (OSError, ValueError, KeyError, TypeError) as error:
        result, code = {"schemaVersion": SCHEMA, "status": "error",
                        "reason": str(error)}, 2
    print(json.dumps(result, ensure_ascii=True))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
