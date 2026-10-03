#!/usr/bin/env python3
"""skill_signal_harvest.py — N-archive grafting of skill usage signals (UAW M06).

Grafts 4 archives into one per-skill signal row:
  1. codex_rollout  : ~/.codex/sessions/**/rollout-*.jsonl (mtime within --since-days)
  2. devin_log      : ~/AppData/Roaming/Devin/logs/<3 newest dirs>/*.log
  3. task_journal   : data/agent-handoff/**/journal.json (work_journal.py store)
  4. handoff_docs   : data/agent-handoff/**/*.md recent docs mentioning skill paths

Reads = tool/command invocations that open a SKILL.md (read verbs only).
Catalog mentions = developer/system/catalog payloads or docs listing the path.
Undecidable lines = unknown (reported, never silently folded into reads).

Stdlib only. Output: JSONL per skill + markdown summary.
"""
import argparse
import json
import os
import re
import sys
import time
from collections import Counter, defaultdict
from datetime import datetime, timezone

SKILL_REF_RE = re.compile(r"skills[/\\]([A-Za-z0-9][A-Za-z0-9._-]*)", re.I)
READ_VERB_RE = re.compile(
    r"(cat\b|type\b|more\b|head\b|tail\b|Get-Content|Select-String|findstr|"
    r"rg\b|python|py\b|sed\b|read_file|view_file|open\()", re.I)
TOOL_CALL_TYPES = {"function_call", "custom_tool_call", "local_shell_call"}
CATALOG_TYPES = {"session_meta", "turn_context"}
# Strict failure signals only — the bare word "error" appears in benign JSON
# keys ("error":null, "errors":[]) on nearly every line.
ERROR_RE = re.compile(
    r"\"exit_code\"\s*:\s*-[0-9]|\"exit_code\"\s*:\s*[1-9]|"
    r"\"is_error\"\s*:\s*true|\"success\"\s*:\s*false|"
    r"turn_aborted|task_failed|\"type\"\s*:\s*\"error\"|"
    r"Traceback \(most recent|FAILED \(|rate limit|timed out|"
    r"\"status\"\s*:\s*\"failed\"|panic\(", re.I)
NEAR_FAIL_WINDOW = 15  # ordinals after a read to look for failure signals
MAX_LOG_BYTES = 20 * 1024 * 1024


def norm(name):
    return name.strip().lower()


def load_skills(root):
    skills = {}
    base = os.path.join(root, ".agents", "skills")
    if not os.path.isdir(base):
        return skills
    for entry in sorted(os.listdir(base)):
        d = os.path.join(base, entry)
        if not os.path.isdir(d):
            continue
        for fn in ("SKILL.md", "skill.md", "Skill.md"):
            p = os.path.join(d, fn)
            if os.path.isfile(p):
                skills[norm(entry)] = {
                    "dir": entry,
                    "path": os.path.relpath(p, root).replace("\\", "/"),
                    "bytes": os.path.getsize(p),
                }
                break
    return skills


def find_refs(text):
    return {norm(m.group(1)) for m in SKILL_REF_RE.finditer(text)}


def classify_command(cmd):
    """Return 'read' if the command looks like it opens files, else 'unknown'."""
    return "read" if READ_VERB_RE.search(cmd) else "unknown"


def payload_text(p):
    parts = []
    if isinstance(p, dict):
        for key in ("arguments", "command", "text", "input"):
            v = p.get(key)
            if isinstance(v, str):
                parts.append(v)
            elif isinstance(v, list):
                parts.extend(str(x) for x in v)
        c = p.get("content")
        if isinstance(c, list):
            for item in c:
                if isinstance(item, dict) and isinstance(item.get("text"), str):
                    parts.append(item["text"])
        elif isinstance(c, str):
            parts.append(c)
    return "\n".join(parts)


def classify_rollout_line(line):
    """-> (kind, skills, ordinal, ts) kind in read|catalog|mention|unknown|none."""
    if b"skills" not in line.encode("utf-8", "replace") and "skills" not in line:
        return ("none", set(), None, None)
    try:
        obj = json.loads(line)
    except Exception:
        refs = find_refs(line)
        return ("unknown", refs, None, None) if refs else ("none", set(), None, None)
    payload = obj.get("payload") or {}
    ptype = payload.get("type") or obj.get("type") or ""
    text = payload_text(payload)
    refs = find_refs(text) if text else find_refs(line)
    if not refs:
        return ("none", set(), None, None)
    ordinal = obj.get("ordinal")
    ts = obj.get("timestamp")
    if ptype in TOOL_CALL_TYPES:
        name = str(payload.get("name") or "")
        kind = classify_command(name + " " + text)
        return (kind, refs, ordinal, ts)
    if ptype == "function_call_output":
        return ("unknown", refs, ordinal, ts)
    if obj.get("type") in CATALOG_TYPES or ptype in CATALOG_TYPES:
        return ("catalog", refs, ordinal, ts)
    if ptype == "message":
        role = payload.get("role") or ""
        return ("catalog" if role in ("developer", "system") else "mention",
                refs, ordinal, ts)
    if obj.get("type") == "event_msg":
        # exec/patch events carry command-ish payloads; message events are mentions
        if ptype in ("exec_command_begin", "exec_command_output",
                     "patch_apply_begin"):
            return (classify_command(text), refs, ordinal, ts)
        return ("mention", refs, ordinal, ts)
    return ("unknown", refs, ordinal, ts)


def scan_codex(root, skills, since_ts, counters, stats):
    base = os.path.expanduser(os.path.join("~", ".codex", "sessions"))
    files = []
    for dp, _, fns in os.walk(base):
        for fn in fns:
            if not fn.endswith(".jsonl"):
                continue
            p = os.path.join(dp, fn)
            try:
                if os.path.getmtime(p) < since_ts:
                    continue
            except OSError:
                continue
            files.append(p)
    for p in files:
        stats["codex_rollout"]["files"] += 1
        session_reads = set()
        reads_ord = []  # (ordinal_idx, skill)
        err_ord = []
        last_ts = None
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as fh:
                for idx, line in enumerate(fh):
                    stats["codex_rollout"]["lines"] += 1
                    if "skills" not in line and "SKILL" not in line:
                        if ERROR_RE.search(line):
                            err_ord.append(idx)
                        continue
                    kind, refs, _ord, ts = classify_rollout_line(line)
                    if ts:
                        last_ts = ts
                    if ERROR_RE.search(line):
                        err_ord.append(idx)
                    if kind == "none":
                        continue
                    stats["codex_rollout"][{"read": "reads",
                                            "catalog": "catalog",
                                            "mention": "mentions",
                                            "unknown": "unknown"}[kind]] += 1
                    for s in refs:
                        if s not in skills:
                            continue
                        c = counters[s]
                        if kind == "read":
                            c["reads"] += 1
                            session_reads.add(s)
                            reads_ord.append((idx, s))
                            c["sessions"].add("rollout:" + os.path.basename(p))
                            c["last_used"] = max(c["last_used"] or "", ts or "")
                        elif kind == "catalog":
                            c["catalog_mentions"] += 1
                        elif kind == "mention":
                            c["mentions"] += 1
                        else:
                            c["unknown"] += 1
                        c["by_source"]["codex_rollout"] += 1
        except OSError:
            stats["codex_rollout"]["errors"] += 1
            continue
        # near_fail: an error line within NEAR_FAIL_WINDOW ordinals after a read
        for idx, s in reads_ord:
            if any(idx < e <= idx + NEAR_FAIL_WINDOW for e in err_ord):
                counters[s]["near_fail"] += 1
        # co_reads within one rollout session
        rs = sorted(session_reads)
        for i in range(len(rs)):
            for j in range(i + 1, len(rs)):
                counters[rs[i]]["co"][rs[j]] += 1
                counters[rs[j]]["co"][rs[i]] += 1
    return stats


def scan_devin_logs(root, skills, since_ts, counters, stats):
    base = os.path.expanduser(os.path.join(
        "~", "AppData", "Roaming", "Devin", "logs"))
    if not os.path.isdir(base):
        stats["devin_log"]["note"] = "dir-missing"
        return stats
    dirs = sorted((d for d in os.listdir(base)
                   if os.path.isdir(os.path.join(base, d))), reverse=True)[:3]
    scanned = 0
    for d in dirs:
        for dp, _, fns in os.walk(os.path.join(base, d)):
            for fn in fns:
                if not fn.endswith(".log"):
                    continue
                p = os.path.join(dp, fn)
                try:
                    if os.path.getsize(p) > MAX_LOG_BYTES:
                        stats["devin_log"]["capped"] += 1
                        continue
                    if os.path.getmtime(p) < since_ts:
                        continue
                except OSError:
                    continue
                scanned += 1
                stats["devin_log"]["files"] += 1
                try:
                    with open(p, "r", encoding="utf-8", errors="replace") as fh:
                        for line in fh:
                            if "skills" not in line:
                                continue
                            stats["devin_log"]["lines"] += 1
                            refs = find_refs(line)
                            for s in refs:
                                if s in skills:
                                    counters[s]["mentions"] += 1
                                    counters[s]["by_source"]["devin_log"] += 1
                                    counters[s]["sessions"].add("devin:" + d)
                except OSError:
                    stats["devin_log"]["errors"] += 1
    if scanned == 0:
        stats["devin_log"]["note"] = "no-log-files-in-window"
    return stats


def iter_handoff_files(root, since_ts, want_journal):
    base = os.path.join(root, "data", "agent-handoff")
    for dp, _, fns in os.walk(base):
        for fn in fns:
            if want_journal and fn != "journal.json":
                continue
            if not want_journal and not fn.lower().endswith((".md", ".txt")):
                continue
            p = os.path.join(dp, fn)
            try:
                if os.path.getmtime(p) < since_ts:
                    continue
            except OSError:
                continue
            yield p


def scan_journals(root, skills, since_ts, counters, stats):
    for p in iter_handoff_files(root, since_ts, want_journal=True):
        stats["task_journal"]["files"] += 1
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as fh:
                obj = json.load(fh)
        except Exception:
            stats["task_journal"]["unparsed"] += 1
            continue
        task = obj.get("taskId") or os.path.basename(os.path.dirname(p))
        text = json.dumps(obj, ensure_ascii=False)
        refs = find_refs(text)
        # bare skill-dir names also count in journals (they name skills w/o path)
        for s in skills:
            if s not in refs and re.search(r"(?<![\w-])" + re.escape(s) +
                                         r"(?![\w-])", text, re.I):
                refs.add(s)
        for s in refs:
            if s in skills:
                counters[s]["mentions"] += 1
                counters[s]["by_source"]["task_journal"] += 1
                counters[s]["sessions"].add("journal:" + task)


def scan_handoff_docs(root, skills, since_ts, counters, stats):
    for p in iter_handoff_files(root, since_ts, want_journal=False):
        stats["handoff_docs"]["files"] += 1
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as fh:
                text = fh.read()
        except OSError:
            stats["handoff_docs"]["errors"] += 1
            continue
        for s in find_refs(text):
            if s in skills:
                counters[s]["mentions"] += 1
                counters[s]["by_source"]["handoff_docs"] += 1
                counters[s]["sessions"].add("doc:" + os.path.basename(
                    os.path.dirname(p)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--since-days", type=float, default=3.0)
    ap.add_argument("--out", default=None, help="jsonl output path")
    ap.add_argument("--md-out", default=None, help="markdown summary path")
    args = ap.parse_args()
    root = os.path.abspath(args.root)
    since_ts = time.time() - args.since_days * 86400
    skills = load_skills(root)
    counters = {s: {"reads": 0, "catalog_mentions": 0, "mentions": 0,
                    "unknown": 0, "near_fail": 0, "last_used": None,
                    "sessions": set(), "co": Counter(),
                    "by_source": Counter()} for s in skills}
    stats = {k: Counter() for k in
             ("codex_rollout", "devin_log", "task_journal", "handoff_docs")}

    scan_codex(root, skills, since_ts, counters, stats)
    scan_devin_logs(root, skills, since_ts, counters, stats)
    scan_journals(root, skills, since_ts, counters, stats)
    scan_handoff_docs(root, skills, since_ts, counters, stats)

    rows = []
    for s in sorted(skills):
        c = counters[s]
        co = [[o, n] for o, n in c["co"].most_common(3)]
        rows.append({
            "skill": s,
            "path": skills[s]["path"],
            "bytes": skills[s]["bytes"],
            "reads": c["reads"],
            "catalog_mentions": c["catalog_mentions"],
            "mentions": c["mentions"],
            "unknown": c["unknown"],
            "near_fail": c["near_fail"],
            "distinct_sessions": len(c["sessions"]),
            "co_reads": co,
            "last_used": c["last_used"],
            "by_source": dict(c["by_source"]),
        })

    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)),
                    exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as fh:
            for r in rows:
                fh.write(json.dumps(r, ensure_ascii=False) + "\n")

    zero = [r["skill"] for r in rows if r["reads"] == 0]
    top_reads = sorted(rows, key=lambda r: -r["reads"])[:10]
    top_fail = sorted(rows, key=lambda r: -r["near_fail"])[:10]
    bundles = Counter()
    for r in rows:
        for o, n in r["co_reads"]:
            pair = tuple(sorted((r["skill"], o)))
            bundles[pair] += n

    lines = ["# Skill signal harvest summary",
             "", f"- root: `{root}`",
             f"- since-days: {args.since_days}",
             f"- skills: {len(rows)}", "", "## Sources", ""]
    for k in ("codex_rollout", "devin_log", "task_journal", "handoff_docs"):
        st = dict(stats[k])
        note = st.pop("note", "")
        lines.append(f"- **{k}**: {st} {note}")
    lines += ["", f"## Zero-read skills ({len(zero)})", "",
              "skills with reads=0 in the window (mentions may still exist):", ""]
    lines += ["- " + s for s in zero]
    lines += ["", "## Top reads", ""]
    lines += [f"- {r['skill']}: reads={r['reads']} mentions="
              f"{r['mentions']} near_fail={r['near_fail']}"
              for r in top_reads if r["reads"] or r["mentions"]]
    lines += ["", "## Top near-fail", ""]
    lines += [f"- {r['skill']}: near_fail={r['near_fail']} reads={r['reads']}"
              for r in top_fail if r["near_fail"]]
    lines += ["", "## Co-read bundles (top10)", ""]
    lines += [f"- {a} + {b}: {n}" for (a, b), n in bundles.most_common(10)]
    md = "\n".join(lines) + "\n"
    if args.md_out:
        os.makedirs(os.path.dirname(os.path.abspath(args.md_out)),
                    exist_ok=True)
        with open(args.md_out, "w", encoding="utf-8") as fh:
            fh.write(md)
    print(json.dumps({"skills": len(rows), "zero_signal": len(zero),
                      "stats": {k: dict(v) for k, v in stats.items()}},
                     ensure_ascii=False))


if __name__ == "__main__":
    sys.exit(main())
