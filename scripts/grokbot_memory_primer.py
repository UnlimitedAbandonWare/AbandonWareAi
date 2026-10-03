#!/usr/bin/env python3
# scripts/grokbot_memory_primer.py
"""
GrokBot 3-Tier Memory Primer (read-only, $0 local).

Builds a bounded primer to paste at GrokBot session start so working context
does not get lost-in-the-middle:

  Tier 1 Working Memory   : active source-edit leases, in-progress journals,
                            git dirty-path count.
  Tier 2 Episodic Memory  : latest brief-registry entries + most recent
                            GrokBot sessions from sessions_index.json.
  Tier 3 Semantic / Core  : Project Root, primary surface, key operating
                            rules, GrokBot tone (`한 줄:`, `말로: 「…」`).

Usage:
  python -B scripts/grokbot_memory_primer.py
      [--format markdown|text|json] [--max-chars 2500] [--copy] [--quiet]

`--copy` pipes the primer to PowerShell Set-Clipboard (Windows). Clipboard
failure is non-fatal: the primer is still printed to stdout.
"""
import argparse
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone, timedelta

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
LEASE_GLOB = os.path.join(ROOT, "__patch_drop__", "source-edit-locks", "*.lock", "lease.json")
JOURNAL_GLOB = os.path.join(ROOT, "data", "agent-handoff", "codex-autonomy", "*", "journal.json")
BRIEFS_JSONL = os.path.join(ROOT, "data", "agent-handoff", "brief-registry", "briefs.jsonl")
SESSIONS_INDEX = os.path.join(ROOT, "data", "agent-handoff", "grokbot", "sessions_index.json")

KST = timezone(timedelta(hours=9))
DEFAULT_MAX_CHARS = 2500

# --- secret masking (defense in depth: sources are titles/topics only, but
# paste-target text still gets a cheap scrub before leaving the tool) ---
_KV_SECRET = re.compile(
    r"(?i)\b(api[_-]?key|token|secret|password|passwd|authorization|bearer)"
    r"(\s*[:=]\s*|\s+)['\"]?[^\s'\"]{6,}['\"]?")
_TOKEN_SECRET = re.compile(
    r"\b(sk-[A-Za-z0-9_-]{8,}|xox[baprs]-[A-Za-z0-9-]{8,}|ghp_[A-Za-z0-9]{8,}|"
    r"glpat-[A-Za-z0-9_-]{8,}|AKIA[0-9A-Z]{16})\b")


def mask_secrets(text):
    """Return text with secret-shaped values replaced by `***` (key kept)."""
    if not text:
        return text
    text = _KV_SECRET.sub(lambda m: m.group(1) + m.group(2) + "***", text)
    return _TOKEN_SECRET.sub("***", text)


def _clip(text, n):
    text = mask_secrets(str(text or "").replace("\n", " ").replace("|", "/").strip())
    return text if len(text) <= n else text[: max(0, n - 1)].rstrip() + "~"


def _brief_summary(row):
    """Human-ish one-liner: prefer summaryKo; drop HTML comments and @skill lines."""
    raw = str(row.get("summaryKo") or "").strip()
    if not raw or raw.startswith("(") and "미열람" in raw:
        raw = str(row.get("topic") or "")
    raw = re.sub(r"<!--.*?-->", " ", raw)
    parts = [p.strip() for p in re.split(r"[\n\r]+", raw)
             if p.strip() and not p.strip().startswith(("@", "<!--", "#"))]
    return _clip(" ".join(parts) or str(row.get("topic") or ""), 60)


def _iso_to_dt(value):
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except Exception:
        return None


def _read_json(path):
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            return json.load(f)
    except Exception:
        return None


# ---------------- Tier 1: working memory ----------------

def collect_active_leases(now=None):
    """Active (not expired) source-edit leases: topic + target count only."""
    now = now or datetime.now(timezone.utc)
    leases = []
    for path in glob.glob(LEASE_GLOB):
        data = _read_json(path) or {}
        topic = data.get("topic") or os.path.basename(os.path.dirname(path)).replace(".lock", "")
        exp = _iso_to_dt(data.get("expiresAtUtc") or data.get("expiresAt"))
        status = "expired" if (exp and exp < now) else "active"
        leases.append({
            "topic": topic,
            "status": status,
            "targets": len(data.get("targetPaths") or []),
            "expiresAtUtc": data.get("expiresAtUtc") or "",
        })
    leases.sort(key=lambda x: (x["status"] != "active", x["topic"]))
    return leases


def collect_active_journals():
    """In-progress work journals: taskId + agent + short purpose."""
    journals = []
    for path in glob.glob(JOURNAL_GLOB):
        data = _read_json(path) or {}
        if data.get("status") != "in_progress":
            continue
        journals.append({
            "taskId": data.get("taskId") or os.path.basename(os.path.dirname(path)),
            "agent": data.get("agent") or "",
            "purpose": _clip(data.get("purpose") or "", 60),
            "updatedAtUtc": data.get("updatedAtUtc") or "",
        })
    journals.sort(key=lambda x: x["updatedAtUtc"], reverse=True)
    return journals


def git_dirty_count(root=ROOT):
    """Count of `git status --porcelain` paths; None when git is unavailable."""
    try:
        env = dict(os.environ, PYTHONIOENCODING="utf-8")
        out = subprocess.run(
            ["git", "-C", root, "status", "--porcelain"],
            capture_output=True, text=True, timeout=15, env=env)
        if out.returncode != 0:
            return None
        return sum(1 for line in out.stdout.splitlines() if line.strip())
    except Exception:
        return None


def tier1_working(now=None):
    return {
        "leases": collect_active_leases(now=now),
        "journals": collect_active_journals(),
        "gitDirty": git_dirty_count(),
    }


# ---------------- Tier 2: episodic memory ----------------

def recent_briefs(limit=3, path=BRIEFS_JSONL):
    rows = []
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except Exception:
                    continue
    except Exception:
        return []
    rows.sort(key=lambda r: r.get("atKst") or "", reverse=True)
    return [{
        "atKst": r.get("atKst") or "",
        "agent": r.get("agent") or "",
        "topic": r.get("topic") or "",
        "summary": _brief_summary(r),
    } for r in rows[:limit]]


def recent_sessions(limit=3, path=SESSIONS_INDEX):
    data = _read_json(path) or {}
    sessions = data.get("sessions") or []
    sessions = sorted(sessions, key=lambda s: s.get("updated_at") or s.get("created_at") or "",
                      reverse=True)
    return [{
        "id": (s.get("id") or "")[:12],
        "title": _clip(s.get("title") or s.get("summary") or "untitled", 48),
        "updated": (s.get("updated_at") or "")[:16].replace("T", " "),
        "model": s.get("model") or "",
    } for s in sessions[:limit]]


def tier2_episodic():
    return {"briefs": recent_briefs(), "sessions": recent_sessions()}


# ---------------- Tier 3: semantic / core rules ----------------

def tier3_semantic():
    return {
        "projectRoot": ROOT,
        "primarySurface": "/chat (local http://127.0.0.1:18180/chat, public https://abandonwareai.kro.kr/chat)",
        "rules": [
            "secrets = env names only; never print values",
            "no push/pull/commit/add -A; conditional local git via agent_git_vibe_commit.py",
            "other agents' leases/journals/staging = hands off",
            "verification target = main /chat; interview page is debug-only",
            "reversible local = Self-Ask AUTO; irreversible = ASK_ONCE once",
        ],
        "tone": [
            "첫 줄 결론(예/아니오/판정/추천), 서두·반복 질문 금지",
            "답 끝에 `한 줄:` 요약; 지시서엔 `말로: 「…」` 한 줄",
            "한국어 쉬운 존댓말, 답은 2~3덩어리",
        ],
    }


# ---------------- assembly ----------------

def _fmt_md(data):
    t1, t2, t3 = data["t1"], data["t2"], data["t3"]
    leases_a = [l for l in t1["leases"] if l["status"] == "active"]
    lease_txt = ", ".join(f"{_clip(l['topic'], 28)}({l['targets']})" for l in leases_a[:4]) or "none"
    dirty = t1["gitDirty"]
    lines = [
        f"[GROKBOT-MEMORY-PRIMER] {data['generatedAtKst']} (<= {data['maxChars']} chars)",
        "",
        "## T1 Working",
        f"- leases active={len(leases_a)}: {lease_txt}",
        f"- journals in_progress={len(t1['journals'])}"
        + (f" (newest: {_clip(t1['journals'][0]['taskId'], 36)})" if t1["journals"] else ""),
        f"- git dirty paths: {dirty if dirty is not None else 'not_observed'}",
        "",
        "## T2 Episodic (latest)",
    ]
    for b in t2["briefs"]:
        lines.append(f"- brief {(b['atKst'] or '')[:16]} {b['agent']}/{_clip(b['topic'], 24)}: {b['summary']}")
    for s in t2["sessions"]:
        lines.append(f"- session {s['updated']}: {s['title']} ({s['id']})")
    lines += [
        "",
        "## T3 Core Rules",
        f"- root: {t3['projectRoot']}",
        f"- primary surface: {t3['primarySurface']}",
    ]
    lines += [f"- {r}" for r in t3["rules"]]
    lines += ["- tone: " + " / ".join(t3["tone"])]
    lines += ["", f"recall: scripts/grok_memory_recall.py | recipes: scripts/grokbot_skill_catalog.py match <kw>"]
    return "\n".join(lines)


def _fmt_text(data):
    return re.sub(r"^#+\s*", "", _fmt_md(data), flags=re.M)


def build_primer(fmt="markdown", max_chars=DEFAULT_MAX_CHARS):
    """Return the primer text, always <= max_chars (hard cap, marker-appended)."""
    data = {
        "generatedAtKst": datetime.now(KST).strftime("%Y-%m-%d %H:%M KST"),
        "maxChars": max_chars,
        "t1": tier1_working(),
        "t2": tier2_episodic(),
        "t3": tier3_semantic(),
    }
    def render():
        if fmt == "json":
            return json.dumps(data, ensure_ascii=False, indent=1)
        return _fmt_md(data) if fmt == "markdown" else _fmt_text(data)

    out = render()
    if len(out) > max_chars:
        # shrink episodic first (keep T1/T3)
        data["t2"]["sessions"] = data["t2"]["sessions"][:1]
        data["t2"]["briefs"] = [dict(b, summary=_clip(b["summary"], 36)) for b in data["t2"]["briefs"][:2]]
        out = render()
    if len(out) > max_chars and fmt == "json":
        # keep JSON valid: reduce episodic/journal detail to counts
        data["t2"] = {"briefs": len(data["t2"]["briefs"]), "sessions": len(data["t2"]["sessions"]),
                      "omitted": True}
        data["t1"]["journals"] = len(data["t1"]["journals"])
        out = render()
    if len(out) > max_chars:
        out = out[: max_chars - 16].rstrip() + "\n…[truncated]"
    return out


def copy_to_clipboard(text):
    """Set-Clipboard via PowerShell; returns True on success. A temp file is
    used because `$input` is stripped when piped through `powershell -Command`."""
    tmp = None
    try:
        fd, tmp = tempfile.mkstemp(prefix="grokbot-primer-", suffix=".txt")
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(text)
        proc = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "Get-Content -Raw -Encoding UTF8 -LiteralPath "
             f"'{tmp}' | Set-Clipboard"],
            capture_output=True, text=True, timeout=10)
        return proc.returncode == 0
    except Exception:
        return False
    finally:
        if tmp:
            try:
                os.remove(tmp)
            except OSError:
                pass


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")
        except Exception:
            pass
    parser = argparse.ArgumentParser(description="GrokBot 3-Tier Memory Primer")
    parser.add_argument("--format", choices=("markdown", "text", "json"), default="markdown")
    parser.add_argument("--max-chars", type=int, default=DEFAULT_MAX_CHARS)
    parser.add_argument("--copy", action="store_true", help="also copy to clipboard (Set-Clipboard)")
    parser.add_argument("--quiet", action="store_true", help="suppress stdout print (useful with --copy)")
    args = parser.parse_args(argv)

    out = build_primer(fmt=args.format, max_chars=args.max_chars)
    copied = None
    if args.copy:
        copied = copy_to_clipboard(out)
        print(f"[primer] clipboard copy: {'ok' if copied else 'FAILED (console output only)'}",
              file=sys.stderr)
    if not args.quiet:
        print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
