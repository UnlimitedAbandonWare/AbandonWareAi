#!/usr/bin/env python3
"""agent_vibe_auto_decision.py — CLI mirror of $demo1-vibe-selfask-judge-auto.

Contract DEMO1-CLEAN-QUARANTINE-VIBE-AUTO-OPT-20260929 §C. Encodes the skill's
POSITIVE/NEGATIVE/COUNTEREXAMPLE + NEUTRAL JUDGE loop as a deterministic
classifier so any agent (Codex/Devin/Grok/Cline) can run the pre-ASK gate from
a shell without a quiz card:

  1. POSITIVE      = the proposed action as declared (--action/--paths).
  2. NEGATIVE      = stop/ASK_ONCE keyword gates (irreversible, forbidden,
                     paid, secret, remote, prod/shared writes, banned flags).
  3. COUNTEREXAMPLE= live checks: foreign in-progress leases on the declared
                     paths, paid-provider env, unverifiable evidence claims.
  4. NEUTRAL JUDGE = precedence: HOLD (foreign scope) > ASK_ONCE (hard gate)
                     > AUTO (local/reversible/evidence-backed).

Usage:
  python -B scripts/agent_vibe_auto_decision.py --action "<what you intend>"
      [--paths "scripts/x.py,docs/y.md"] [--agent devin] [--root .]
      [--task <taskId>] [--json] [--question "<single ask text>"]

Exit codes: 0 = AUTO, 3 = ASK_ONCE, 4 = HOLD, 2 = usage error.
Every verdict prints one line `SELFASK_JUDGE <verdict> | <reason> | <paths>`;
with --task it is also journaled via work_journal.py note --kind plan.
This tool never relaxes hard constraints — it only classifies the ask.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

SCHEMA = "awx.agent-vibe-auto-decision.v1"
LOCKS_GLOB = "__patch_drop__/source-edit-locks"
SKILL_REF = ".agents/skills/demo1-vibe-selfask-judge-auto/SKILL.md"

# --- NEGATIVE gates (never AUTO) --------------------------------------------
# (reason code, regex, suggested single question)
ASK_GATES = [
    ("git-remote-mutate",
     re.compile(r"\bgit\s+(commit|push|add\s+-A|add\s+\.|fetch|pull|merge|"
                r"rebase|reset|clean|remote\s+(add|set-url|remove|rename|rm))"
                r"\b|--no-verify|force-push", re.I),
     "Run the conditional-local-git entry point "
     "scripts/agent_git_vibe_commit.py for owned paths only?"),
    ("destructive-data",
     re.compile(r"\b(DELETE\s+FROM|TRUNCATE|DROP\s+(TABLE|COLUMN|INDEX)|"
                r"rm\s+-rf|Remove-Item\s+-Recurse|bulk.?delete|"
                r"delete\s+user|delete\s+row)", re.I),
     "This is destructive on real data — proceed with the exact target only?"),
    ("prod-or-shared-write",
     re.compile(r"\b(prod|production|shared)\b[^\n]{0,40}\b(write|db|sql|"
                r"migration|apply)\b|\b(write|sql|migration)[^\n]{0,40}\b"
                r"(prod|production|shared)\b", re.I),
     "Target is prod/shared — is the local-dev-only variant acceptable?"),
    ("secret-emission",
     re.compile(r"(secret|credential|api[_-]?key|token|password)[^\n]{0,60}"
                r"(print|log|emit|paste|commit|show|output)|"
                r"(print|log|emit|paste|commit|show)[^\n]{0,60}"
                r"(secret|credential|api[_-]?key|token|password)", re.I),
     "Secrets stay env-only — keep names only and never emit values?"),
    ("banned-feature",
     re.compile(r"task_ask|external\s+callback|InMemory[^\n]{0,20}queue|"
                r"n8n[^\n]{0,20}callback", re.I),
     "Enabling a banned product feature needs an explicit contract — enable?"),
    ("reopen-locked-scope",
     re.compile(r"autograde\s*b|\bf02\b[^\n]{0,20}(scanner|reopen)", re.I),
     "Scope is contract-locked — is there an explicit new contract?"),
]

PAID_RE = re.compile(r"\b(paid|openai|anthropic|gemini|gpt-5|claude)[^\n]{0,40}"
                     r"(api|provider|generation|fanout)\b|provider[^\n]{0,20}"
                     r"generation", re.I)

# Sole valid remote: origin must point at this URL; anything else warns.
INTENDED_ORIGIN_URL = "https://github.com/unlimitedabandonware/abandonwareai"


def _env_credit_budget() -> int:
    raw = os.environ.get("AWX_CREDIT_BUDGET")
    if raw is None:
        return 0
    try:
        return int(str(raw).strip() or "0")
    except ValueError:
        return 0


def _yaml_credit_budget(root: Path) -> int:
    """policy.authorized_credit_budget from configs/agent-api-spend-guard.yaml;
    line-parsed like apikit.common.blocked_models — no yaml dependency."""
    try:
        text = (root / "configs" / "agent-api-spend-guard.yaml").read_text(
            encoding="utf-8")
    except OSError:
        return 0
    m = re.search(r"(?m)^\s*authorized_credit_budget\s*:\s*([0-9]+)", text)
    return int(m.group(1)) if m else 0


_PAID_KILL_SWITCH = ("0", "false", "no", "off")


def paid_generation_authorized(root: Path) -> bool:
    """2026-10-03 kill-switch semantics: paid lanes are ON by default for
    agent work (agent_spend_order codex_credits -> external_paid_api ->
    free_tier -> local_ollama). AWX_AGENT_ALLOW_PAID_MODELS=0/false/no/off
    blocks even with a positive budget; otherwise authorized by env flag,
    positive AWX_CREDIT_BUDGET, or guard-yaml authorized_credit_budget."""
    if (os.environ.get("AWX_AGENT_ALLOW_PAID_MODELS", "").strip().lower()
            in _PAID_KILL_SWITCH):
        return False
    return (os.environ.get("AWX_AGENT_ALLOW_PAID_MODELS") == "1"
            or _env_credit_budget() > 0 or _yaml_credit_budget(root) > 0)

# --- COUNTEREXAMPLE ----------------------------------------------------------
LOCAL_SAFE_HINT = re.compile(
    r"local|dev|additive|read.?only|probe|snapshot|dry.?run|docs?|skill|"
    r"diagnostics|agent-handoff|scripts?/|test|journal", re.I)


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def foreign_leases(root: Path, agent: str) -> list[dict]:
    locks = root / LOCKS_GLOB
    out = []
    if not locks.is_dir():
        return out
    now = dt.datetime.now(dt.timezone.utc)
    for lease_file in sorted(locks.glob("*.lock/lease.json")):
        try:
            lease = json.loads(lease_file.read_text(encoding="utf-8-sig"))
        except (json.JSONDecodeError, OSError):
            out.append({"topic": lease_file.parent.name,
                        "state": "corrupt", "targetPaths": []})
            continue
        exp = lease.get("expiresAtUtc", "")
        try:
            expires = dt.datetime.fromisoformat(exp.replace("Z", "+00:00"))
            live = expires.tzinfo is not None and expires > now
        except ValueError:
            live = False
        owner = str(lease.get("ownerId") or lease.get("taskId") or "")
        if live and owner and owner != agent:
            out.append({"topic": lease_file.parent.name,
                        "owner": owner, "state": "live",
                        "targetPaths": lease.get("targetPaths") or []})
    return out


def sole_origin_url(root: Path) -> str | None:
    """Normalized fetch URL of `origin`; None when unobservable or absent."""
    try:
        proc = subprocess.run(
            [shutil.which("git") or "git", "-C", str(root),
             "remote", "get-url", "origin"],
            capture_output=True, timeout=15)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if proc.returncode != 0:
        return None
    url = proc.stdout.decode("utf-8", errors="replace").strip().rstrip("/").lower()
    if url.endswith(".git"):
        url = url[:-4]
    return url or None


def norm(p: str) -> str:
    return str(p).replace("\\", "/").strip("/").casefold()


def path_overlap(declared: list[str], lease_paths: list[str]) -> list[str]:
    want = {norm(p) for p in declared}
    have = {norm(p) for p in lease_paths}
    return sorted(want & have)


def decide(action: str, paths: list[str], agent: str, root: Path) -> dict:
    hits, counter, notes = [], [], []
    # NEGATIVE: hard gates
    for code, pat, question in ASK_GATES:
        if pat.search(action):
            hits.append({"code": code, "question": question})
    if PAID_RE.search(action):
        if paid_generation_authorized(root):
            notes.append("paid-generation-authorized")
        else:
            hits.append({"code": "paid-provider",
                         "question": "Paid provider generation is off "
                                     "(AWX_AGENT_ALLOW_PAID_MODELS kill "
                                     "switch set to 0/false/no/off, or no "
                                     "authorized credit budget) — proceed?"})
    # COUNTEREXAMPLE: live foreign-scope checks
    if paths:
        for lease in foreign_leases(root, agent):
            hit = path_overlap(paths, lease["targetPaths"])
            if hit:
                counter.append({"code": "foreign-lease",
                                "topic": lease["topic"],
                                "owner": lease["owner"],
                                "paths": hit})
        for p in paths:
            pp = (root / p)
            if not str(pp.resolve()).startswith(str(root)):
                counter.append({"code": "outside-root", "path": p})
    origin = sole_origin_url(root)
    if origin is not None and origin != INTENDED_ORIGIN_URL:
        hits.append({"code": "origin-mismatch",
                     "question": "origin이 유일한 remote(AbandonWareAi)가 "
                                 "아니다 — remote 변경 없이 보고만 할까?"})
    # NEUTRAL JUDGE precedence
    if counter and any(c.get("code") in ("foreign-lease",) for c in counter):
        fl = [c for c in counter if c["code"] == "foreign-lease"][0]
        verdict = "HOLD"
        reason = (f"foreign live lease {fl['topic']} owns {fl['paths']}")
        resume = "lease release or expiry; re-run this decision"
    elif hits:
        verdict = "ASK_ONCE"
        reason = "hard gate: " + ",".join(h["code"] for h in hits)
        resume = None
    elif counter:
        verdict = "HOLD"
        reason = "counterexample alive: " + ",".join(
            c["code"] for c in counter)
        resume = "resolve counterexamples then re-run"
    else:
        verdict = "AUTO"
        localish = bool(LOCAL_SAFE_HINT.search(action)) or not action.strip()
        reason = ("reversible local scope; no hard gate; no foreign lease"
                  + ("" if localish else "; (advisory: action text lacks "
                     "local/reversible markers)"))
        resume = None
    return {"verdict": verdict, "reason": reason,
            "question": hits[0]["question"] if verdict == "ASK_ONCE" else None,
            "resume": resume, "gates": [h["code"] for h in hits],
            "counterexamples": counter, "notes": notes}


def journal_line(root: Path, task: str, verdict: str, reason: str,
                 paths: list[str]) -> str:
    text = f"SELFASK_JUDGE {verdict} | {reason} | {','.join(paths) or '-'}"
    try:
        proc = subprocess.run(
            [sys.executable, "-B", "scripts/work_journal.py", "note",
             "--root", ".", "--task", task, "--kind", "plan",
             "--text", text],
            cwd=str(root), capture_output=True, text=True, timeout=30)
        return f"journal-note exit={proc.returncode}"
    except (OSError, subprocess.TimeoutExpired) as exc:
        return f"journal-note skipped:{type(exc).__name__}"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="Self-Ask judge CLI: AUTO | ASK_ONCE | HOLD before any "
                    "user-facing approval quiz")
    ap.add_argument("--action", required=True,
                    help="what you intend to do, one line")
    ap.add_argument("--paths", default="",
                    help="comma-separated repo-relative paths you would touch")
    ap.add_argument("--agent", default="devin",
                    help="caller agent id for foreign-lease exclusion")
    ap.add_argument("--root", default=".")
    ap.add_argument("--task", help="also journal the verdict to this taskId")
    ap.add_argument("--question",
                    help="override the single ASK_ONCE question text")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass

    root = Path(args.root).resolve()
    paths = [p.strip() for p in args.paths.split(",") if p.strip()]
    res = decide(args.action, paths, args.agent, root)
    if args.question and res["verdict"] == "ASK_ONCE":
        res["question"] = args.question
    payload = {"schemaVersion": SCHEMA, "generatedAtUtc": utcnow(),
                   "action": args.action[:200], "paths": paths,
                   "skillMirror": SKILL_REF, **res}
    jnote = None
    if args.task:
        jnote = journal_line(root, args.task, res["verdict"],
                             res["reason"], paths)
        payload["journal"] = jnote
    line = (f"SELFASK_JUDGE {res['verdict']} | {res['reason']} | "
            f"{','.join(paths) or '-'}")
    if args.json:
        print(json.dumps(payload, ensure_ascii=False))
    else:
        print(line)
        if res["verdict"] == "ASK_ONCE":
            print("ASK_ONCE question:", res["question"])
        if res["verdict"] == "HOLD":
            print("resume:", res["resume"])
        if jnote:
            print(jnote)
    return {"AUTO": 0, "ASK_ONCE": 3, "HOLD": 4}[res["verdict"]]


if __name__ == "__main__":
    raise SystemExit(main())
