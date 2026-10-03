#!/usr/bin/env python3
# scripts/grok_memory_recall.py
"""
GrokBot episodic recall engine (read-only, $0 local).

Keyword search over the synced GrokBot session index plus the brief registry:
  - data/agent-handoff/grokbot/sessions_index.json (sessions + recentPrompts)
  - data/agent-handoff/brief-registry/briefs.jsonl (directive ledger)

Returns the top-N hits (default 3) as one-line summaries ranked by weighted
keyword score with a small recency bonus.

Usage:
  python -B scripts/grok_memory_recall.py "<keyword>" [--limit 3] [--json]
"""
import argparse
import json
import os
import re
import sys
from datetime import datetime, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SESSIONS_INDEX = os.path.join(ROOT, "data", "agent-handoff", "grokbot", "sessions_index.json")
BRIEFS_JSONL = os.path.join(ROOT, "data", "agent-handoff", "brief-registry", "briefs.jsonl")


def _read_json(path):
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            return json.load(f)
    except Exception:
        return {}


def _iter_jsonl(path):
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        yield json.loads(line)
                    except Exception:
                        continue
    except Exception:
        return


def _to_dt(value):
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except Exception:
        return None


def _oneline(text, n=90):
    text = re.sub(r"\s+", " ", str(text or "")).strip()
    return text if len(text) <= n else text[: n - 1].rstrip() + "~"


def _weighted_score(weighted_fields, terms):
    score = 0.0
    for weight, field in weighted_fields:
        f = str(field or "").lower()
        if not f:
            continue
        for t in terms:
            if t in f:
                score += weight
    return score


def _recency_bonus(dt, now=None):
    if not dt:
        return 0.0
    now = now or datetime.now(timezone.utc)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    days = (now - dt).total_seconds() / 86400.0
    if days <= 1:
        return 2.0
    if days <= 7:
        return 1.0
    if days <= 30:
        return 0.5
    return 0.0


def _session_hit(s, terms, now):
    score = _weighted_score([
        (3.0, s.get("title")), (2.0, s.get("last_turn_summary")),
        (2.0, s.get("summary")), (1.5, s.get("first_prompt")),
        (1.0, s.get("last_user_prompt")), (1.0, s.get("last_assistant_resp")),
        (0.5, s.get("id")),
    ], terms)
    when = _to_dt(s.get("updated_at") or s.get("created_at"))
    score += _recency_bonus(when, now)
    return {
        "kind": "session",
        "ref": s.get("id") or "",
        "when": (s.get("updated_at") or "")[:16].replace("T", " "),
        "line": _oneline(s.get("title") or s.get("summary") or s.get("first_prompt")),
        "score": round(score, 2),
    }


def _prompt_hit(p, terms, now):
    score = _weighted_score([(2.0, p.get("prompt"))], terms)
    when = _to_dt(p.get("timestamp"))
    score += _recency_bonus(when, now)
    return {
        "kind": "prompt",
        "ref": (p.get("session_id") or "")[:12],
        "when": (p.get("timestamp") or "")[:16].replace("T", " "),
        "line": _oneline(p.get("prompt")),
        "score": round(score, 2),
    }


def _brief_hit(b, terms, now):
    score = _weighted_score([
        (3.0, b.get("topic")), (2.0, b.get("summaryKo")),
        (1.0, b.get("agent")), (0.5, b.get("downloadsPath")),
    ], terms)
    when = _to_dt(b.get("atKst"))
    score += _recency_bonus(when, now)
    return {
        "kind": "brief",
        "ref": b.get("downloadsPath") or b.get("repoPath") or "",
        "when": (b.get("atKst") or "")[:16].replace("T", " "),
        "line": _oneline(f"{b.get('agent','?')}/{b.get('topic','?')}: {b.get('summaryKo') or ''}"),
        "score": round(score, 2),
    }


def recall(query, limit=3, root=ROOT, now=None):
    """Top-limit recall hits across sessions_index + brief registry.

    Returns a list of dicts: {kind, ref, when, line, score}. Empty query or
    no matches -> []. Never raises on missing index files.
    """
    terms = [t for t in re.split(r"\s+", str(query or "").lower()) if t]
    if not terms:
        return []
    base = os.path.join(root, "data", "agent-handoff")
    index = _read_json(os.path.join(base, "grokbot", "sessions_index.json"))
    hits = []
    for s in index.get("sessions") or []:
        h = _session_hit(s, terms, now)
        if h["score"] > 0:
            hits.append(h)
    for p in index.get("recentPrompts") or []:
        h = _prompt_hit(p, terms, now)
        if h["score"] > 0:
            hits.append(h)
    for b in _iter_jsonl(os.path.join(base, "brief-registry", "briefs.jsonl")):
        h = _brief_hit(b, terms, now)
        if h["score"] > 0:
            hits.append(h)
    hits.sort(key=lambda h: (-h["score"], h["when"]))
    return hits[: max(1, int(limit))]


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")
        except Exception:
            pass
    parser = argparse.ArgumentParser(description="GrokBot episodic recall")
    parser.add_argument("query", help="keyword(s) to recall")
    parser.add_argument("--limit", type=int, default=3)
    parser.add_argument("--json", action="store_true", dest="as_json")
    args = parser.parse_args(argv)

    hits = recall(args.query, limit=args.limit)
    if args.as_json:
        print(json.dumps(hits, ensure_ascii=False, indent=1))
        return 0
    if not hits:
        print(f"(no recall hits for '{args.query}')")
        return 2
    for h in hits:
        print(f"[{h['kind']:<7}] {h['when']:<16} score={h['score']:<5} {h['line']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
