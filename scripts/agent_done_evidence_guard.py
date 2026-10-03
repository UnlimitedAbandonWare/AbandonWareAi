"""Reject Done/PARTIAL completion claims that carry no recorded evidence.

For the W2/W6 failure class: progress accumulated while runtime stayed
not_observed, and quarantined child sessions claimed completion with no command
evidence. Composes max_push_done_guard.claim_blocked for forbidden claim
phrasing; adds journal/run.json cross-checks, and (since contract
DEMO1-DEVIN-QUARANTINE-ANTIPATTERN-RAILS-20260929) verifies that repo-relative
evidence paths cited in the claim text (tests, GATE results, handoff files)
actually exist under --root. Read-only: journal.json and referenced paths are
inspected, never written.

  python -B scripts/agent_done_evidence_guard.py --text "<claim>"
  python -B scripts/agent_done_evidence_guard.py --task <taskId> [--root .]
  python -B scripts/agent_done_evidence_guard.py --task <taskId> --text "<claim>"

Exit 0 = claim carries evidence; 2 = blocked (reasons in JSON); 3 = usage error.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import max_push_done_guard  # noqa: E402

SCHEMA = "awx.agent-harmony.done-evidence-guard.v1"
JOURNAL_ROOT = Path("data/agent-handoff/codex-autonomy")

CLAIM_RE = re.compile(r"\b(Done|PARTIAL|verified|GREEN|완료|통과)\b", re.I)
EVIDENCE_TOKEN_RE = re.compile(
    r"exit\s*=?-?\d+|run\.json|cycle-\d+|verify=|\d+\s*/\s*\d+|\bPASS\b|\bRED\b.*\bGREEN\b",
    re.I)
RUNTIME_CLAIM_RE = re.compile(r"\b(running|runtime|live|onGlasses|current\s+PID|freshness)\b", re.I)
RUNTIME_EVIDENCE_RE = re.compile(r"runtime|freshness|PID\s*\d+|runId|18180|wear|dev\b", re.I)

# DEMO1-DEVIN-QUARANTINE-ANTIPATTERN-RAILS-20260929 §3: a Done claim that cites
# an evidence *path* (test file, GATE result, handoff artifact) must point at a
# path that exists under --root. Quarantined rollouts repeatedly claimed
# completion with proof paths that were never written. Only tokens that look
# like repo-relative paths (known top dir or explicit extension) are checked —
# prose filenames without a separator never trigger this.
EVIDENCE_PATH_RE = re.compile(
    r"(?<![\w.-])((?:data|docs|scripts|var|main|__patch_drop__|\.agents)"
    r"[/\\][\w.\\/-]{2,200}|[\w.-]+[/\\][\w.\\/-]+\."
    r"(?:py|ps1|js|bat|sh|md|json|jsonl|txt|log))(?![\w.-])")


def lint_text(text: str, root: Path | None = None) -> list[str]:
    reasons = []
    blocked = max_push_done_guard.claim_blocked(text)
    reasons += [f"claim-text:{b}" for b in blocked]
    lines = [ln for ln in text.splitlines() if CLAIM_RE.search(ln)]
    for ln in lines:
        if not EVIDENCE_TOKEN_RE.search(ln):
            reasons.append("done-claim-no-evidence-token")
            break
    if root is not None:
        for m in EVIDENCE_PATH_RE.finditer(text):
            rel = m.group(1).rstrip(".,;:)\"'")
            if not (root / rel).exists():
                reasons.append(f"evidence-path-missing:{rel}")
    return reasons


def journal_reasons(root: Path, task_id: str, claim_text: str | None) -> tuple[list[str], dict]:
    journal_path = root / JOURNAL_ROOT / task_id / "journal.json"
    if not journal_path.is_file():
        return ["journal-missing"], {"journal": str(journal_path)}
    try:
        journal = json.loads(journal_path.read_text(encoding="utf-8-sig"))
    except (json.JSONDecodeError, OSError) as exc:
        return ["journal-unreadable"], {"error": str(exc)}
    events = journal.get("events") or []
    verify_events = [e for e in events if e.get("kind") == "verify"]
    reasons: list[str] = []
    refs_checked = []
    if not verify_events:
        reasons.append("no-verify-event")
    for event in verify_events:
        for ref in event.get("refs") or []:
            ref_path = root / ref
            if ref_path.name == "run.json":
                refs_checked.append(ref)
                if not ref_path.is_file():
                    reasons.append(f"verify-ref-missing-run:{ref}")
                    continue
                try:
                    run = json.loads(ref_path.read_text(encoding="utf-8-sig"))
                except (json.JSONDecodeError, OSError):
                    reasons.append(f"verify-ref-unreadable-run:{ref}")
                    continue
                if not isinstance(run.get("exitCode"), int) and not isinstance(
                        run.get("verificationExitCode"), int):
                    reasons.append(f"verify-ref-no-exit:{ref}")
    if claim_text and RUNTIME_CLAIM_RE.search(claim_text):
        blob = json.dumps(events, ensure_ascii=False) + json.dumps(
            journal.get("purpose", ""), ensure_ascii=False)
        if not RUNTIME_EVIDENCE_RE.search(blob):
            reasons.append("runtime-not-observed")
    detail = {"taskId": task_id,
              "journalStatus": journal.get("status"),
              "verifyEventCount": len(verify_events),
              "runRefsChecked": refs_checked}
    return list(dict.fromkeys(reasons)), detail


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--task")
    parser.add_argument("--text")
    args = parser.parse_args(argv)
    if not args.task and not args.text:
        print(json.dumps({"schemaVersion": SCHEMA, "ok": False,
                          "reasons": ["task-or-text-required"]}))
        return 3
    reasons: list[str] = []
    detail: dict = {}
    root = Path(args.root).resolve()
    if args.text:
        reasons += lint_text(args.text, root)
        detail["textLinted"] = True
        detail["evidencePathsChecked"] = True
    if args.task:
        jreasons, jdetail = journal_reasons(root, args.task, args.text)
        reasons += jreasons
        detail.update(jdetail)
    reasons = list(dict.fromkeys(reasons))
    print(json.dumps({"schemaVersion": SCHEMA, "ok": not reasons,
                      "reasons": reasons, "detail": detail}, ensure_ascii=False))
    return 2 if reasons else 0


if __name__ == "__main__":
    raise SystemExit(main())
