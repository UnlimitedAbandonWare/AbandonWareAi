#!/usr/bin/env python3
"""demo1_skill_quality_audit.py — skill quality + router-index coverage audit.

Ported from the 2026-10-02 Grok audit script with fixes:
  * frontmatter parser handles folded YAML scalars (description: >- / > / |- / |)
    and continuation lines, quoted scalars.
  * adds index coverage: every on-disk .agents/skills/*/SKILL.md vs the skills
    referenced by .agents/skills-intent-index.yaml (intents, families, fallback).
  * adds --since-days for the usage window.
  * masks anything that looks like a secret before it reaches any output.

Stdlib only. Usage:
    python -B scripts/demo1_skill_quality_audit.py --json-out <path> [--md-out <path>]
        [--root .] [--index .agents/skills-intent-index.yaml] [--since-days 10]
        [--no-usage] [--no-md]
"""
from __future__ import annotations

import argparse
import glob
import json
import os
from pathlib import Path
import re
import sys
import time
from collections import defaultdict

SCHEMA = "awx.skill-quality-audit.v1"
SKILLS_REL = os.path.join(".agents", "skills")
DEFAULT_INDEX = ".agents/skills-intent-index.yaml"
DEFAULT_MD_OUT = "docs/agent-tooling/skill-quality-report.md"

# Core @skills pinned by the Grok Bot handover template
# (demo1-agy-directive-writer/references/grokbot-current/HANDOVER.md).
# They ship in every directive's first line, so a zero-use window verdict
# must never list them as archive candidates again (cf. 2026-10-04
# devin-skill-slim restore).
CORE_KEEP_SKILLS = frozenset({
    "demo1-project-root",
    "agent-scope-lease",
    "demo1-lease-conflict-autoflow",
    "regression-check",
    "positive-negative-neutral-judge",
    "demo1-vibe-selfask-judge-auto",
    "demo1-work-ledger",
    "demo1-superpowers-repo-evidence-guard",
    "demo1-agent-code-evidence-gate",
})

SECRET_PATTERNS = [
    re.compile(r"(?i)(api[_-]?key|secret|token|passwd|password|authorization|"
               r"bearer|credential|private[_-]?key)\s*[:=]\s*['\"]?[^\s'\",}]+"),
    re.compile(r"\bsk-[A-Za-z0-9_\-]{8,}\b"),
    re.compile(r"\b(?:xox[baprs]|ghp|gho|github_pat|glpat|AKIA|AIza)"
               r"[A-Za-z0-9_\-]{6,}\b"),
    re.compile(r"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----"),
    re.compile(r"(?i)\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{5,}\b"),
]


def mask(text):
    """Redact secret-looking fragments from any string that may reach output."""
    if not isinstance(text, str):
        return text
    for pat in SECRET_PATTERNS:
        text = pat.sub("<masked>", text)
    return text


# --- SKILL.md parsing -------------------------------------------------------

_FM_RE = re.compile(r"^\ufeff?---[ \t]*\r?\n(.*?)\r?\n---[ \t]*\r?\n?", re.S)


def split_frontmatter(txt):
    """Return (frontmatter_text_or_None, body_text)."""
    m = _FM_RE.match(txt)
    if not m:
        return None, txt
    return m.group(1), txt[m.end():]


def _fold_scalar(lines, start, base_indent, folded):
    """Collect YAML block-scalar/continuation lines more indented than the key."""
    parts = []
    for nxt in lines[start:]:
        if not nxt.strip():
            continue
        ind = len(nxt) - len(nxt.lstrip())
        if ind <= base_indent:
            break
        parts.append(nxt.strip())
    sep = " " if folded else "\n"
    return sep.join(parts)


def extract_field(fm, field):
    """Extract a top-level scalar field from a frontmatter block.

    Handles `field: value`, `field: "quoted"`, folded/literal block scalars
    (`field: >-`, `field: |`), and continuation lines indented under the key.
    """
    lines = fm.splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^(\s*)" + re.escape(field) + r"\s*:\s*(.*)$", line)
        if not m:
            continue
        base_indent = len(m.group(1))
        rest = m.group(2).strip()
        if rest in (">", ">-", ">+", "|", "|-", "|+"):
            return _fold_scalar(lines, i + 1, base_indent, rest.startswith(">"))
        if not rest:
            return _fold_scalar(lines, i + 1, base_indent, True)
        # plain/quoted scalar, optionally with continuation lines
        extra = _fold_scalar(lines, i + 1, base_indent, True)
        value = (rest + " " + extra).strip() if extra else rest
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        elif value[:1] in ("'", '"'):
            value = value[1:]
        return value.strip()
    return ""


def score_skill(name, folder, root_tag, path, txt, src_root):
    """Quality score = 100 minus penalties; returns (row_dict)."""
    fm, body = split_frontmatter(txt)
    desc = extract_field(fm or "", "description")
    raw = txt.encode("utf-8", "replace")
    d = os.path.dirname(path)
    extra = [f for f in os.listdir(d) if f != "SKILL.md"]
    heads = re.findall(r"^#{1,4}\s+(.+)$", body, re.M)
    hl = " ".join(heads).lower() + " " + body.lower()[:20000]
    refs = set(re.findall(
        r"`((?:scripts|\.agents|docs|tools|\.codex)[/\\][^`\s]+?"
        r"\.(?:py|ps1|md|json|bat|cmd|sh|yaml|yml))`", body))
    missing = [r_ for r_ in refs
               if not os.path.exists(os.path.join(src_root, r_.replace("/", os.sep)))
               and not os.path.exists(os.path.join(d, r_.replace("/", os.sep)))]
    missing = [x for x in missing if "*" not in x]

    score = 100
    why = []

    def pen(n, w):
        nonlocal score
        score -= n
        why.append(w)

    if fm is None:
        pen(15, "no-frontmatter")
    if name != folder and name.replace("_", "-").replace(".", "-") != folder:
        pen(5, f"name!=folder({name})")
    if len(desc) < 40:
        pen(10, "desc<40")
    if not re.search(r"(?i)\buse (this )?(when|for|before|after|only)\b|"
                     r"\buse when\b|\bwhen\b|사용", desc):
        pen(8, "desc-no-trigger")
    bl = len(body.encode("utf-8"))
    if bl < 600:
        pen(25, f"body{bl}B")
    elif bl < 1500:
        pen(12, f"body{bl}B")
    elif bl > 25000:
        pen(8, f"body{bl}B-bloated")
    if not re.search(r"(?i)(step|workflow|procedure|절차|순서|^\s*\d+\.)",
                     hl, re.M):
        pen(12, "no-steps")
    if not re.search(r"(?i)(verif|acceptance|check|검증|done when|pass)", hl):
        pen(12, "no-verify")
    if not re.search(r"(?i)(forbid|never|do not|don't|stop|금지|hold|ask)", hl):
        pen(8, "no-guardrail")
    if not re.search(r"(?i)(report|output|format|보고|출력)", hl):
        pen(6, "no-output-format")
    if not re.search(r"(?i)(example|e\.g\.|예시|```)", hl):
        pen(5, "no-example")
    if root_tag == "repo" and not os.path.exists(
            os.path.join(d, "agents", "openai.yaml")) and not os.path.exists(
            os.path.join(d, "openai.yaml")):
        why.append("no-openai.yaml")
    if missing:
        pen(min(20, 7 * len(missing)),
            "missing-refs:" + ",".join(sorted(mask(x) for x in missing)[:4]))
    if "\ufffd" in txt or "\x00" in txt:
        pen(10, "mojibake/NUL")
    if re.search(r"(?i)\b(todo|tbd|fixme|placeholder)\b", body):
        pen(5, "todo")
    return {
        "name": name, "folder": folder, "root": root_tag, "path": path,
        "bytes": len(raw), "body": bl, "desc_len": len(desc),
        "desc": mask(desc[:300]), "score": max(0, score),
        "why": [mask(w) for w in why], "extra_files": len(extra),
        "mtime": time.strftime("%Y-%m-%d", time.localtime(os.path.getmtime(path))),
    }


def scan_skills(src_root, user_home):
    roots = {"repo": os.path.join(src_root, SKILLS_REL),
             "codex_user": os.path.join(user_home, ".codex", "skills"),
             "agents_user": os.path.join(user_home, ".agents", "skills")}
    skills = {}
    for tag, r in roots.items():
        for p in glob.glob(os.path.join(r, "**", "SKILL.md"), recursive=True):
            folder = os.path.basename(os.path.dirname(p))
            raw = Path(p).read_bytes()
            txt = raw.decode("utf-8", "replace")
            fm, _ = split_frontmatter(txt)
            nm = extract_field(fm or "", "name") or folder
            name = nm.strip().strip("\"'") or folder
            skills[name] = score_skill(name, folder, tag, p, txt, src_root)
    return skills


# --- usage scan --------------------------------------------------------------

def _iter_recent(globpat, cut):
    for p in glob.glob(globpat, recursive=True):
        try:
            if os.path.getmtime(p) >= cut and os.path.isfile(p):
                yield p
        except OSError:
            pass


LISTING_MARKERS = ("<skills_instructions>", "available skills",
                   "available_skills", "agents.md instructions",
                   "the following skills can be invoked")


def is_listing_line(line):
    low = line.lower()
    return any(mk in low for mk in LISTING_MARKERS)


def count_usage(skills, src_root, user_home, since_days, agent_filter=None):
    """Session counts per skill. A line counts only when it names 1-4 skills;
    injected skill-list lines are excluded via is_listing_line.
    agent_filter (e.g. {"codex"}) limits which stores are scanned."""
    names = sorted(skills, key=len, reverse=True)
    alt = "|".join(re.escape(n) for n in names)
    fold2name = {skills[n]["folder"]: n for n in names}
    pat_read = re.compile(r"skills[/\\]{1,2}(" + "|".join(
        re.escape(skills[n]["folder"]) for n in names) + r")[/\\]{1,2}SKILL\.md")
    pat_inv = re.compile(r"[\$@](" + alt + r")(?![\w-])")
    use = {a: defaultdict(lambda: [0, 0])
           for a in ("codex", "grok", "devin", "brief")}
    cut = time.time() - since_days * 86400

    def scan(path, agent, line_filter=None):
        seen = defaultdict(int)
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    if len(line) > 200000:
                        line = line[:200000]
                    if "SKILL.md" not in line and "$" not in line and "@" not in line:
                        continue
                    if is_listing_line(line):
                        continue
                    hits = set()
                    for m in pat_read.finditer(line):
                        hits.add(fold2name.get(m.group(1)))
                    if line_filter is None or line_filter(line):
                        for m in pat_inv.finditer(line):
                            hits.add(m.group(1))
                    hits.discard(None)
                    if 0 < len(hits) <= 4:
                        for h in hits:
                            seen[h] += 1
        except OSError:
            return
        for h, c in seen.items():
            use[agent][h][0] += 1
            use[agent][h][1] += c

    codex_filter = lambda l: ('"user_message"' in l) or ('"function_call"' in l) \
        or ('custom_tool_call' in l)
    if agent_filter is None or "codex" in agent_filter:
        for p in _iter_recent(os.path.join(user_home, ".codex", "sessions",
                                           "**", "*.jsonl"), cut):
            scan(p, "codex", codex_filter)
    if agent_filter is None or "grok" in agent_filter:
        for p in _iter_recent(os.path.join(user_home, ".grok", "sessions",
                                           "**", "*.jsonl"), cut):
            scan(p, "grok")
    appdata = os.environ.get("APPDATA", "")
    if appdata and (agent_filter is None or "devin" in agent_filter):
        for p in _iter_recent(os.path.join(appdata, "Devin", "logs", "**",
                                           "*devin-cli*.log"), cut):
            scan(p, "devin",
                 lambda l: "Reading file" in l or "user" in l.lower())
        for p in _iter_recent(os.path.join(appdata, "Devin", "summaries",
                                           "**", "*"), cut):
            scan(p, "devin")
    if agent_filter is None or "brief" in agent_filter:
        dl = os.path.join(user_home, "Downloads")
        for p in list(_iter_recent(os.path.join(src_root, "agent-prompts",
                                                "**", "*.txt"), cut)) + \
                list(_iter_recent(os.path.join(dl, "PASTE_*.txt"), cut)):
            scan(p, "brief")
    return use


# --- index coverage -----------------------------------------------------------

def load_index(root, index_rel):
    """Reuse the router's loader so YAML dialect stays identical."""
    sys.path.insert(0, os.path.join(os.path.abspath(root), "scripts"))
    try:
        import demo1_vibe_skill_router as vibe
        return vibe.load_index(root, index_rel)
    finally:
        try:
            sys.path.remove(os.path.join(os.path.abspath(root), "scripts"))
        except ValueError:
            pass


def referenced_skills(index):
    refs = set()
    for entry in index.get("intents") or []:
        if not isinstance(entry, dict):
            continue
        for key in ("primary_skill", "optional_skill"):
            if entry.get(key):
                refs.add(entry[key])
    for fam in (index.get("families") or {}).values():
        for skill in (fam or {}).get("skills") or []:
            refs.add(skill)
    fb = (index.get("fallback") or {}).get("primary_skill")
    if fb:
        refs.add(fb)
    for name in index.get("direct_call_only") or []:
        if isinstance(name, str):
            refs.add(name.split("#")[0].split()[0])
    return refs


def index_coverage(skills, index):
    repo = sorted(n for n, s in skills.items() if s["root"] == "repo")
    refs = referenced_skills(index)
    covered = [n for n in repo if n in refs
               or skills[n]["folder"] in refs]
    unindexed = [n for n in repo if n not in covered]
    return {"repo_skills": len(repo), "indexed": len(covered),
            "unindexed": unindexed, "indexed_names": covered}


# --- report -------------------------------------------------------------------

def render_markdown(report):
    s = report["summary"]
    lines = [
        "# Skill quality audit",
        "",
        f"- generatedAtUtc: {report['generatedAtUtc']}",
        f"- sinceDays: {report['sinceDays']}",
        f"- skills total: {s['skills_total']} (repo {s['repo_skills']}, "
        f"codex_user {s['codex_user']}, agents_user {s['agents_user']})",
        f"- index coverage: {s['indexed']}/{s['repo_skills']} "
        f"({s['unindexed']} unindexed)",
        f"- zero-use (window): {s['zero_use']}",
        f"- zero-use archive candidates (core-keep 제외): "
        f"{s['zero_use_archive_candidates']} "
        f"(core keep {s['core_keep_protected']}개 보호)",
        "",
        "## Weakest high-traffic skills",
        "",
        "| sessions | score | skill | penalties |",
        "|---|---|---|---|",
    ]
    rows = [r for r in report["skills"]
            if r["use_total"] >= 3 and r["score"] < 90]
    rows.sort(key=lambda r: (-r["use_total"], r["score"]))
    for r in rows[:40]:
        lines.append(f"| {r['use_total']} | {r['score']} | {r['name']} "
                     f"[{r['root']}] | {';'.join(r['why'])} |")
    lines += ["", "## Unindexed repo skills", ""]
    for n in report["index_coverage"]["unindexed"]:
        lines.append(f"- {n}")
    lines.append("")
    return "\n".join(lines)


def build_report(src_root, index_rel, since_days, do_usage, user_home=None):
    src_root = os.path.abspath(src_root)
    user_home = user_home or os.path.expanduser("~")
    skills = scan_skills(src_root, user_home)
    use = count_usage(skills, src_root, user_home, since_days) if do_usage \
        else {a: defaultdict(lambda: [0, 0])
              for a in ("codex", "grok", "devin", "brief")}
    try:
        index = load_index(src_root, index_rel)
        coverage = index_coverage(skills, index)
    except Exception as exc:  # index unreadable -> coverage reports not_observed
        coverage = {"repo_skills": sum(1 for s in skills.values()
                                       if s["root"] == "repo"),
                    "indexed": 0, "unindexed": [], "indexed_names": [],
                    "error": mask(str(exc) or "index-load-failed")}
    rows = []
    for n, s in skills.items():
        u = {a: use[a][n][0] for a in use}
        s["use_sessions"] = u
        s["use_total"] = u["codex"] + u["grok"] + u["devin"]
        s["indexed"] = coverage["indexed_names"].__contains__(n) \
            if "indexed_names" in coverage else None
        s["core_keep"] = n in CORE_KEEP_SKILLS
        rows.append(s)
    rows.sort(key=lambda r: (-r["use_total"], r["score"]))
    roots_count = defaultdict(int)
    for r in rows:
        roots_count[r["root"]] += 1
    report = {
        "schemaVersion": SCHEMA,
        "generatedAtUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "sinceDays": since_days,
        "root": src_root,
        "summary": {
            "skills_total": len(rows),
            "repo_skills": roots_count["repo"],
            "codex_user": roots_count["codex_user"],
            "agents_user": roots_count["agents_user"],
            "indexed": coverage["indexed"],
            "unindexed": len(coverage["unindexed"]),
            "zero_use": sum(1 for r in rows
                            if r["root"] == "repo" and r["use_total"] == 0),
            "zero_use_archive_candidates": sum(
                1 for r in rows
                if r["root"] == "repo" and r["use_total"] == 0
                and not r["core_keep"]),
            "core_keep_protected": sum(1 for r in rows if r["core_keep"]),
            "avg_score": round(sum(r["score"] for r in rows)
                               / max(1, len(rows)), 1),
            "usage_scanned": bool(do_usage),
        },
        "index_coverage": {k: v for k, v in coverage.items()
                           if k != "indexed_names"},
        "skills": rows,
    }
    return report


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--index", default=DEFAULT_INDEX)
    ap.add_argument("--json-out", default=None,
                    help="write full JSON report to this path")
    ap.add_argument("--md-out", default=DEFAULT_MD_OUT,
                    help="markdown report path relative to --root")
    ap.add_argument("--no-md", action="store_true")
    ap.add_argument("--no-usage", action="store_true",
                    help="skip session-store usage scan (fast)")
    ap.add_argument("--since-days", type=int, default=10)
    args = ap.parse_args(argv)

    report = build_report(args.root, args.index, args.since_days,
                          not args.no_usage)
    blob = json.dumps(report, ensure_ascii=False, indent=1)
    if args.json_out:
        out = Path(args.json_out)
        if not out.is_absolute():
            out = Path(args.root) / out
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(mask(blob), encoding="utf-8")
    if not args.no_md:
        md_path = Path(args.root) / args.md_out
        md_path.parent.mkdir(parents=True, exist_ok=True)
        md_path.write_text(render_markdown(report), encoding="utf-8")
    s = report["summary"]
    print(mask(json.dumps({"schemaVersion": SCHEMA, "ok": True, "summary": s},
                          ensure_ascii=False)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
