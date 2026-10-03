#!/usr/bin/env python3
"""selfask_triad.py — offline packet/judge for the Codex Self-Ask triad.

Contract DEMO1-DEVIN-CODEX-SELFASK-TRIAD-20261001 / skill
`.agents/skills/demo1-codex-selfask-triad/SKILL.md`. No model calls: `packet`
builds the three delegation prompts (definer/aliaser/challenger) with a
non-sensitive `deliveryMarker`; `judge` validates the three recorded answers
and emits one AUTO/ASK_ONCE/HOLD verdict — the same enum and exit codes as
`scripts/agent_vibe_auto_decision.py` (0/3/4; 2 = usage error).

Subcommands:
  packet --question "..." [--paths "a,b"] [--task ID] [--out DIR] [--json]
      writes <out>/packet-<ts>-<id>/{definer,aliaser,challenger}.prompt.md and
      prints a manifest JSON. marker = SELFASK-TRIAD-<sha8(question|utc)>.

  judge <definer.md> <aliaser.md> <challenger.md> [--root .] [--marker M]
        [--task ID] [--json]
      Literal `NOT_RUN` or a missing file = branch NOT_RUN (execution budget
      burned out — no retry, per Zero-100 rotation). Validation per branch:
      `task_received=` echo, the four sections (finding / evidence /
      uncertainty / recommended next check), `stance:` SUPPORT|OPPOSE|UNSURE,
      `evidence_tier:` T1..T4, and every `file:line` citation must resolve
      under --root with the line in range. Verdict precedence mirrors the
      NEUTRAL JUDGE: HOLD > ASK_ONCE > AUTO.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

SCHEMA = "awx.selfask-triad.v1"
BRANCHES = ("definer", "aliaser", "challenger")
ROLES = {
    "definer": (
        "selfask_definer",
        "UAW axis 1 — domain/definition (STRICT). Answer: what do the "
        "contract, spec, and source literally say? Cite source file:line, "
        "tests, AGENTS.md, docs/PROJECT_STATUS.md, official docs only."),
    "aliaser": (
        "selfask_aliaser",
        "UAW axis 2 — alias/synonym/typo (RELAXED). Answer: does the same "
        "concept exist under another name? Hunt duplicate lineage, renamed "
        "classes, doubled config keys, other callers, dormant code outside "
        "the scan boundary, stale docs."),
    "challenger": (
        "selfask_challenger",
        "UAW axis 3 — relation/hypothesis (EXPLORE). Answer: how does the "
        "claim/plan break? One causal hypothesis, one counterexample, the "
        "single smallest falsification test."),
}
TIER_WEIGHT = {"T1": 4, "T2": 3, "T3": 2, "T4": 1}
STANCE_POL = {"SUPPORT": 1, "UNSURE": 0, "OPPOSE": -1}
NOT_RUN = "NOT_RUN"

SECTION_RE = re.compile(
    r"(?im)^\s{0,3}(?:#{1,6}\s*)?(?:[1-4][.)]\s*)?"
    r"(finding|evidence|uncertainty|recommended[ \t]+next[ \t]+check)\s*$")
MARKER_RE = re.compile(r"(?m)^\s*task_received\s*=\s*(\S+)\s*$")
STANCE_RE = re.compile(r"(?im)^\s*stance\s*:\s*(SUPPORT|OPPOSE|UNSURE)\b")
TIER_RE = re.compile(r"(?im)^\s*evidence_tier\s*:\s*(T[1-4])\b")
DISAGREE_RE = re.compile(r"(?im)^\s*disagrees_with\s*:\s*(\w+)\s*$")
CITE_RE = re.compile(
    r"((?:[A-Za-z]:)?[\w.\-]+(?:[./\\][\w.\-]+)+\."
    r"(?:java|md|yml|yaml|toml|py|js|ts|cjs|mjs|properties|xml|gradle|kts|"
    r"txt|json|ps1|bat|html|css)):(\d+)")


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.casefold()).strip("-")[:40] or "q"


def marker_for(question: str) -> str:
    digest = hashlib.sha256(
        f"{question}|{utcnow()}".encode("utf-8")).hexdigest()[:8].upper()
    return f"SELFASK-TRIAD-{digest}"


# --- packet -----------------------------------------------------------------
def build_prompt(role: str, question: str, paths: list[str],
                 marker: str) -> str:
    agent, brief = ROLES[role]
    scope = "\n".join(f"- {p}" for p in paths) or "- (whole repo, read-only)"
    return f"""objective: {question}
agent_type: {agent}
deliveryMarker: {marker}
role-brief: {brief}
scope paths:
{scope}
constraints:
- read-only: no edits, no commits, no deletes, no paid/external calls
- cite `file:line` for every claim; repo-relative paths under the project root
- bounded: stop when the finding's evidence is cited; one question only
contract:
- first line under `finding` must be `task_received={marker}`
- sections, exactly: 1. finding / 2. evidence / 3. uncertainty /
  4. recommended next check
- trailer lines: `stance: SUPPORT|OPPOSE|UNSURE` then `evidence_tier: T1|T2|T3|T4`
  (T1=file:line+test, T2=file:line, T3=doc-only, T4=guess)
- on conflict with another axis you may add `disagrees_with: <role>`
stopCondition: the four sections are filled with cited evidence or a named gap
"""


def cmd_packet(args) -> int:
    paths = [p.strip() for p in args.paths.split(",") if p.strip()]
    marker = marker_for(args.question)
    out_dir = Path(args.out)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    packet_dir = (out_dir / f"packet-{stamp}-{slug(args.question)}")
    packet_dir.mkdir(parents=True, exist_ok=True)
    files = {}
    for role in BRANCHES:
        fp = packet_dir / f"{role}.prompt.md"
        fp.write_text(build_prompt(role, args.question, paths, marker),
                      encoding="utf-8")
        files[role] = str(fp.relative_to(Path.cwd())
                        if fp.is_relative_to(Path.cwd()) else fp)
    manifest = {"schemaVersion": SCHEMA, "action": "packet",
                "generatedAtUtc": utcnow(), "question": args.question,
                "paths": paths, "marker": marker, "packetDir": str(packet_dir),
                "files": files,
                "note": "spawn_agent once per role; zero-budget branches are "
                        "recorded NOT_RUN, never retried"}
    print(json.dumps(manifest, ensure_ascii=False))
    return 0


# --- judge ------------------------------------------------------------------
def _sections(text: str) -> list[str]:
    return [m.group(1).lower().replace("  ", " ")
            for m in SECTION_RE.finditer(text)]


def _citations(root: Path, text: str) -> tuple[list[dict], list[dict]]:
    ok, bad = [], []
    for m in CITE_RE.finditer(text):
        raw, line = m.group(1), int(m.group(2))
        rel = raw.replace("\\", "/")
        if ":" in rel.split("/", 1)[0]:  # strip windows drive letter
            rel = rel.split(":", 1)[1]
        rel = rel.lstrip("/").lstrip(".")
        fp = (root / rel)
        entry = {"cite": f"{rel}:{line}", "ok": True}
        try:
            if not fp.is_file():
                entry["ok"] = False
                entry["reason"] = "missing-file"
            else:
                n = sum(1 for _ in fp.open("r", encoding="utf-8",
                                           errors="replace"))
                if not 1 <= line <= n:
                    entry["ok"] = False
                    entry["reason"] = f"line-out-of-range(1..{n})"
        except OSError as exc:
            entry["ok"] = False
            entry["reason"] = type(exc).__name__
        (ok if entry["ok"] else bad).append(entry)
    return ok, bad


def _judge_branch(root: Path, role: str, arg: str,
                  want_marker: str | None) -> dict:
    res = {"role": role, "status": "OK", "stance": None, "tier": None,
           "weight": 0, "missing": [], "citation_failures": [],
           "citations": [], "disagrees_with": []}
    if arg.strip().upper() == NOT_RUN:
        res["status"] = NOT_RUN
        return res
    fp = Path(arg)
    if not fp.is_file():
        cand = root / arg
        fp = cand if cand.is_file() else fp
    if not fp.is_file():
        res["status"] = "INVALID"
        res["missing"].append(f"file-not-found:{arg}")
        return res
    text = fp.read_text(encoding="utf-8-sig", errors="replace")
    mark = MARKER_RE.search(text)
    if not mark:
        res["missing"].append("task_received marker")
    elif want_marker and mark.group(1) != want_marker:
        res["missing"].append(f"marker-mismatch:{mark.group(1)}")
    else:
        res["marker"] = mark.group(1)
    found = set(_sections(text))
    for sec in ("finding", "evidence", "uncertainty",
                "recommended next check"):
        if sec not in found:
            res["missing"].append(f"section:{sec}")
    st = STANCE_RE.search(text)
    if not st:
        res["missing"].append("stance")
    else:
        res["stance"] = st.group(1).upper()
    ti = TIER_RE.search(text)
    if not ti:
        res["missing"].append("evidence_tier")
    else:
        res["tier"] = ti.group(1)
        res["weight"] = TIER_WEIGHT[res["tier"]]
    ok_cites, bad_cites = _citations(root, text)
    res["citations"] = ok_cites
    res["citation_failures"] = bad_cites
    res["disagrees_with"] = [d.lower() for d in DISAGREE_RE.findall(text)]
    if res["missing"] or bad_cites:
        res["status"] = "INVALID"
    return res


def decide(branches: list[dict]) -> dict:
    ok = [b for b in branches if b["status"] == "OK"]
    invalid = [b["role"] for b in branches if b["status"] == "INVALID"]
    not_run = [b["role"] for b in branches if b["status"] == NOT_RUN]
    anchors: dict[str, dict[str, str]] = {}
    for b in ok:
        for c in b["citations"]:
            anchors.setdefault(c["cite"], {})[b["role"]] = b["stance"]
    conflicts, corroborations = [], []
    for anchor, st in anchors.items():
        if len(st) < 2:
            continue
        if "SUPPORT" in st.values() and "OPPOSE" in st.values():
            conflicts.append({"anchor": anchor, "stances": st})
        elif len(set(st.values())) == 1:
            corroborations.append({"anchor": anchor, "stance":
                                   next(iter(st.values())),
                                   "branches": sorted(st)})
    for b in ok:
        for other in b["disagrees_with"]:
            if other in [o["role"] for o in ok] and not any(
                    other in c["stances"] and b["role"] in c["stances"]
                    for c in conflicts):
                conflicts.append({"anchor": "explicit-disagree",
                                  "stances": {b["role"]: b["stance"],
                                              other: "?"}})
    score = sum(b["weight"] * STANCE_POL.get(b["stance"], 0) for b in ok)
    supporters = sum(1 for b in ok if b["stance"] == "SUPPORT")
    t1_opposers = [b["role"] for b in ok
                   if b["stance"] == "OPPOSE" and b["tier"] == "T1"]
    if not ok:
        verdict, reason = "HOLD", "no valid branches"
        question = None
    elif invalid:
        verdict = "HOLD"
        reason = "invalid-branches=" + ",".join(invalid)
        question = None
    elif len(ok) < 2:
        verdict = "HOLD"
        reason = f"insufficient-axes ok={len(ok)} not_run={not_run}"
        question = None
    elif conflicts:
        verdict = "ASK_ONCE"
        reason = "conflict=" + ",".join(c["anchor"] for c in conflicts)
        question = (f"Resolve the conflicting claims on "
                    f"{conflicts[0]['anchor']} before proceeding?")
    elif score <= -3 or t1_opposers:
        verdict = "HOLD"
        reason = (f"opposition score={score} t1={t1_opposers}")
        question = None
    elif score >= 2 and supporters >= 2:
        verdict = "AUTO"
        reason = f"supported score={score} supporters={supporters}"
        question = None
    else:
        verdict = "ASK_ONCE"
        reason = f"weak-or-split score={score} supporters={supporters}"
        question = "Evidence split/weak — proceed with the plan under review?"
    return {"verdict": verdict, "reason": reason, "question": question,
            "weighted_score": score, "supporters": supporters,
            "conflicts": conflicts, "corroborations": corroborations,
            "not_run": not_run, "invalid": invalid}


def journal_line(root: Path, task: str, verdict: str, reason: str,
                 slug_: str) -> str:
    text = f"SELFASK_TRIAD {verdict} | {reason} | {slug_}"
    try:
        proc = subprocess.run(
            [sys.executable, "-B", "scripts/work_journal.py", "note",
             "--root", ".", "--task", task, "--kind", "plan",
             "--text", text],
            cwd=str(root), capture_output=True, text=True, timeout=30)
        return f"journal-note exit={proc.returncode}"
    except (OSError, subprocess.TimeoutExpired) as exc:
        return f"journal-note skipped:{type(exc).__name__}"


def cmd_judge(args) -> int:
    root = Path(args.root).resolve()
    files = {"definer": args.definer, "aliaser": args.aliaser,
             "challenger": args.challenger}
    branches = [_judge_branch(root, role, files[role], args.marker)
                for role in BRANCHES]
    res = decide(branches)
    payload = {"schemaVersion": SCHEMA, "action": "judge",
               "generatedAtUtc": utcnow(), "root": str(root),
               "marker": args.marker, "branches": branches, **res}
    jnote = None
    if args.task:
        jnote = journal_line(root, args.task, res["verdict"], res["reason"],
                             args.slug or "-")
        payload["journal"] = jnote
    print(json.dumps(payload, ensure_ascii=False))
    print(f"SELFASK_TRIAD {res['verdict']} | {res['reason']} | "
          f"{args.slug or '-'}", file=sys.stderr)
    return {"AUTO": 0, "ASK_ONCE": 3, "HOLD": 4}[res["verdict"]]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    pk = sub.add_parser("packet", help="emit three delegation prompts")
    pk.add_argument("--question", required=True)
    pk.add_argument("--paths", default="")
    pk.add_argument("--task")
    pk.add_argument("--out", default="data/agent-handoff/selfask-triad")
    pk.set_defaults(fn=cmd_packet)
    jd = sub.add_parser("judge", help="validate + score three answers")
    jd.add_argument("definer")
    jd.add_argument("aliaser")
    jd.add_argument("challenger")
    jd.add_argument("--root", default=".")
    jd.add_argument("--marker")
    jd.add_argument("--task")
    jd.add_argument("--slug")
    jd.set_defaults(fn=cmd_judge)
    args = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    return args.fn(args)


if __name__ == "__main__":
    raise SystemExit(main())
