#!/usr/bin/env python3
# scripts/grok_to_agy_memory_bridge.py
"""
GrokBot to Antigravity CLI Memory Bridge
Extracts GrokBot's sessions, topics, and memory into Antigravity CLI persistent memory.
Generates:
  - docs/GROKBOT_MEMORY_INDEX.md (comprehensive searchable human/agent doc)
  - .agents/rules/grokbot-session-memory.md (auto-loaded hierarchical project rule for agy)
  - data/agent-handoff/grokbot/sessions_index.json (structured registry for machine lookup)
"""
import argparse
import glob
import json
import os
import re
import sys
import time
from datetime import datetime
from urllib.parse import quote

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
_ROOT_SESSION_KEY = quote(ROOT, safe="")
GROK_DIR = os.path.expanduser("~/.grok")
SESSIONS_BASE = os.path.join(GROK_DIR, "sessions")
MEMORY_BASE = os.path.join(GROK_DIR, "memory-v2", "workspaces")
OUTPUT_JSON = os.path.join(ROOT, "data", "agent-handoff", "grokbot", "sessions_index.json")
OUTPUT_DOC = os.path.join(ROOT, "docs", "GROKBOT_MEMORY_INDEX.md")
OUTPUT_RULE = os.path.join(ROOT, ".agents", "rules", "grokbot-session-memory.md")

def get_demo1_session_dir():
    patterns = [
        os.path.join(SESSIONS_BASE, _ROOT_SESSION_KEY),
        os.path.join(SESSIONS_BASE, "*demo-1*src"),
    ]
    for p in patterns:
        matches = glob.glob(p)
        if matches:
            return matches[0]
    return os.path.join(SESSIONS_BASE, _ROOT_SESSION_KEY)

def extract_chat_excerpts(session_dir, max_lines=500):
    chat_file = os.path.join(session_dir, "chat_history.jsonl")
    first_prompt = ""
    last_user_prompt = ""
    last_assistant_resp = ""
    turns_count = 0

    if not os.path.exists(chat_file):
        return {"first_prompt": "", "last_user_prompt": "", "last_assistant_resp": "", "turn_count": 0}

    try:
        with open(chat_file, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                if not line.strip():
                    continue
                try:
                    msg = json.loads(line)
                    role = msg.get("role") or msg.get("sender") or ""
                    content = msg.get("content") or msg.get("text") or ""
                    if isinstance(content, list):
                        # extract text blocks
                        text_parts = [c.get("text", "") for c in content if isinstance(c, dict) and "text" in c]
                        content = " ".join(text_parts)
                    content_str = str(content).strip()

                    if role in ("user", "human"):
                        turns_count += 1
                        if not first_prompt and content_str:
                            first_prompt = content_str[:250].replace("\n", " ")
                        last_user_prompt = content_str[:250].replace("\n", " ")
                    elif role in ("assistant", "model", "grok"):
                        last_assistant_resp = content_str[:250].replace("\n", " ")
                except Exception:
                    continue
    except Exception:
        pass

    return {
        "first_prompt": first_prompt,
        "last_user_prompt": last_user_prompt,
        "last_assistant_resp": last_assistant_resp,
        "turn_count": turns_count,
    }

def _iso_to_epoch(value):
    if not value:
        return 0.0
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00")).timestamp()
    except Exception:
        return 0.0


def _session_src_mtime(d):
    """Newest mtime among the session dir and its source files."""
    mtimes = [os.path.getmtime(d)]
    for name in ("summary.json", "chat_history.jsonl"):
        p = os.path.join(d, name)
        if os.path.exists(p):
            mtimes.append(os.path.getmtime(p))
    return max(mtimes)


def collect_sessions(prev_index=None, recent_window_sec=0.0):
    """Collect session metadata.

    With `prev_index` (a prior sessions_index.json dict) unchanged sessions —
    source mtime <= prev generatedAtUtc AND outside the recent window — reuse
    their stored entry instead of re-scanning chat_history.jsonl.
    Returns (sessions, stats{"changed","reused"}).
    """
    session_root = get_demo1_session_dir()
    sessions = []
    stats = {"changed": 0, "reused": 0}
    if not os.path.exists(session_root):
        return sessions, stats

    prev_map = {}
    prev_epoch = 0.0
    if isinstance(prev_index, dict):
        prev_epoch = _iso_to_epoch(prev_index.get("generatedAtUtc"))
        prev_map = {s.get("id"): s for s in prev_index.get("sessions") or []}
    # sessions touched inside the recent window are always re-read; window=0
    # still means "now", so reuse stays enabled when a prior index exists.
    recent_after = time.time() - recent_window_sec

    for d in glob.glob(os.path.join(session_root, "*")):
        if not os.path.isdir(d):
            continue
        session_id = os.path.basename(d)
        src_mtime = _session_src_mtime(d)
        old = prev_map.get(session_id)
        if old is not None and src_mtime <= prev_epoch and src_mtime < recent_after:
            sessions.append(old)
            stats["reused"] += 1
            continue
        stats["changed"] += 1
        summary_path = os.path.join(d, "summary.json")
        meta = {
            "id": session_id,
            "path": d,
            "title": "Untitled Session",
            "summary": "",
            "last_turn_summary": "",
            "created_at": "",
            "updated_at": "",
            "last_active_at": "",
            "messages": 0,
            "chat_messages": 0,
            "head_branch": "",
            "head_commit": "",
            "model": "",
            "first_prompt": "",
            "last_user_prompt": "",
            "last_assistant_resp": "",
        }

        if os.path.exists(summary_path):
            try:
                with open(summary_path, "r", encoding="utf-8", errors="ignore") as f:
                    sdata = json.load(f)
                    meta["title"] = sdata.get("generated_title") or sdata.get("session_summary") or session_id
                    meta["summary"] = sdata.get("session_summary") or ""
                    meta["last_turn_summary"] = sdata.get("last_turn_summary") or ""
                    meta["created_at"] = sdata.get("created_at") or ""
                    meta["updated_at"] = sdata.get("updated_at") or ""
                    meta["last_active_at"] = sdata.get("last_active_at") or ""
                    meta["messages"] = sdata.get("num_messages") or 0
                    meta["chat_messages"] = sdata.get("num_chat_messages") or 0
                    meta["head_branch"] = sdata.get("head_branch") or ""
                    meta["head_commit"] = sdata.get("head_commit") or ""
                    meta["model"] = sdata.get("current_model_id") or ""
            except Exception:
                pass

        excerpts = extract_chat_excerpts(d)
        meta["first_prompt"] = excerpts["first_prompt"]
        meta["last_user_prompt"] = excerpts["last_user_prompt"]
        meta["last_assistant_resp"] = excerpts["last_assistant_resp"]
        if meta["title"] == session_id and meta["first_prompt"]:
            meta["title"] = meta["first_prompt"][:60]
        if not meta["summary"] and meta["first_prompt"]:
            meta["summary"] = meta["first_prompt"]

        sessions.append(meta)

    sessions.sort(key=lambda x: x["updated_at"] or x["created_at"] or "", reverse=True)
    return sessions, stats

def collect_memory_topics():
    topics = []
    # Primary workspace only — workspaces of other/older roots are not read.
    primary_ws = os.path.join(MEMORY_BASE, "abandonwareai-b3bbd780", "topics")

    seen = set()
    for ws_path, ws_type in [(primary_ws, "active")]:
        if not os.path.exists(ws_path):
            continue
        for f in glob.glob(os.path.join(ws_path, "*.md")):
            name = os.path.basename(f)
            if name in seen:
                continue
            seen.add(name)
            try:
                content = open(f, "r", encoding="utf-8", errors="ignore").read()
                # parse title (first line)
                lines = content.strip().splitlines()
                title = lines[0].lstrip("#").strip() if lines else name
                topics.append({
                    "name": name,
                    "title": title,
                    "type": ws_type,
                    "path": f,
                    "content": content,
                })
            except Exception:
                pass

    return topics

def collect_recent_prompts(limit=25):
    """Read prompt_history.jsonl in the demo-1 session store.

    Each line is {"timestamp","session_id","prompt","is_bash"} — the raw user
    asks GrokBot received. First line only, truncated; these are excerpts, never
    full paste bodies.
    """
    session_root = get_demo1_session_dir()
    ph = os.path.join(session_root, "prompt_history.jsonl")
    rows = []
    if not os.path.exists(ph):
        return rows
    try:
        with open(ph, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except Exception:
                    continue
                if row.get("is_bash"):
                    continue
                prompt = str(row.get("prompt") or "").strip()
                if not prompt:
                    continue
                first_line = prompt.splitlines()[0].strip()
                rows.append({
                    "timestamp": row.get("timestamp") or "",
                    "session_id": row.get("session_id") or "",
                    "prompt": first_line[:200],
                })
    except Exception:
        pass
    return rows[-limit:] if limit and len(rows) > limit else rows


def collect_observations():
    obs = []
    inbox = os.path.join(MEMORY_BASE, "abandonwareai-b3bbd780", "observations", "_inbox")
    if os.path.exists(inbox):
        for f in glob.glob(os.path.join(inbox, "*.md")):
            try:
                content = open(f, "r", encoding="utf-8", errors="ignore").read()
                name = os.path.basename(f)
                obs.append({
                    "name": name,
                    "path": f,
                    "content": content.strip()
                })
            except Exception:
                pass
    return obs

def build_sessions_index_json(sessions, topics, obs, prompts):
    return {
        "schemaVersion": "awx.grok-agy-memory-bridge.v1",
        "generatedAtUtc": datetime.utcnow().isoformat() + "Z",
        "totalSessions": len(sessions),
        "totalTopics": len(topics),
        "totalObservations": len(obs),
        "totalPromptsIndexed": len(prompts),
        "sessions": sessions,
        "topics": [{k: v for k, v in t.items() if k != "content"} for t in topics],
        "observations": obs,
        "recentPrompts": prompts,
    }

def build_memory_index_doc(sessions, topics, obs, prompts):
    now_str = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S UTC")
    lines = [
        "# GrokBot Sessions & Memory SSOT for Antigravity CLI",
        f"> Generated on: {now_str} via `scripts/grok_to_agy_memory_bridge.py`",
        "> Purpose: Durable memory recall of GrokBot's sessions, topics, and observations for Antigravity CLI (`agy`).",
        "",
        "## 1. Quick Stats",
        f"- Total GrokBot Sessions: **{len(sessions)}**",
        f"- Total Memory Topics: **{len(topics)}**",
        f"- Active Observations: **{len(obs)}**",
        "",
        "## 2. Active Standing Facts & Rules (from Grok standing contract)",
        f"- **Project Root**: `{ROOT}`",
        "- **Sole Remote**: `AbandonWareAi` (sole origin; no other remote is used).",
        "- **RTX 3090 Power Issue**: **RESOLVED 2026-09-24** → Local GPU lane is preferred first choice.",
        "- **Prototype Light**: Anonymous-first vibe coding (`demo.interview.enabled=false`), no auth friction.",
        "- **Conditional Local Git**: `status`/`diff`, selective `add` of owned paths, staged-blob scan, one local commit. Never unguided push/clean.",
        "- **Multi-Session Coexistence**: Only one live RAG/ForceRestart owner; shared work uses `agent_scope_lease.py` and `work_journal.py`.",
        "",
        "## 3. GrokBot Memory Topics",
        "| Topic File | Title | Scope | Summary |",
        "|---|---|---|---|",
    ]

    for t in topics:
        summary_line = ""
        c_lines = t["content"].splitlines()
        for cl in c_lines[1:5]:
            if cl.strip() and not cl.startswith("#"):
                summary_line = cl.strip().replace("|", "\\|")[:120]
                break
        lines.append(f"| `{t['name']}` | {t['title']} | {t['type']} | {summary_line} |")

    lines.extend([
        "",
        "## 4. GrokBot Recent & Key Sessions (Top 25)",
        "| Updated | Session Title / Goal | Turn Summary / First Prompt | Model | Messages | Session ID |",
        "|---|---|---|---|---|---|",
    ])

    for s in sessions[:25]:
        updated = s["updated_at"][:16].replace("T", " ") if s["updated_at"] else "N/A"
        title = s["title"].replace("|", "\\|")[:40]
        desc = (s["last_turn_summary"] or s["summary"] or s["first_prompt"] or "").replace("|", "\\|").replace("\n", " ")[:60]
        model = s["model"] or "grok"
        msg_count = s["messages"]
        sid = s["id"]
        lines.append(f"| {updated} | **{title}** | {desc} | `{model}` | {msg_count} | `{sid[:12]}...` |")

    lines.extend([
        "",
        "## 4b. Recent User Asks to GrokBot (prompt_history.jsonl excerpts)",
        "| Timestamp (UTC) | Ask (first line) | Session |",
        "|---|---|---|",
    ])
    for p in prompts[-15:]:
        ts = p["timestamp"][:16].replace("T", " ") if p["timestamp"] else "N/A"
        ask = p["prompt"].replace("|", "\\|")[:70]
        sid = p["session_id"][:12]
        lines.append(f"| {ts} | {ask} | `{sid}...` |")

    lines.extend([
        "",
        "## 5. Active Observations & Learnings",
    ])
    for o in obs:
        lines.append(f"### {o['name']}")
        lines.append("```")
        lines.append(o["content"][:400] + ("..." if len(o["content"]) > 400 else ""))
        lines.append("```")
        lines.append("")

    lines.extend([
        "## 6. How Antigravity CLI Recalls GrokBot Context",
        "- **List all sessions**: `python -B scripts/grok_to_agy_memory_bridge.py list`",
        "- **Inspect session turns**: `python -B scripts/grok_to_agy_memory_bridge.py show <session_id>`",
        "- **Search memory**: `python -B scripts/grok_to_agy_memory_bridge.py search <keyword>`",
        "- **Re-sync after GrokBot run**: `python -B scripts/grok_to_agy_memory_bridge.py sync`",
        "",
    ])

    return "\n".join(lines)

def build_rule_content(sessions, topics, obs, prompts):
    now_str = datetime.utcnow().strftime("%Y-%m-%d %H:%M UTC")
    lines = [
        "---",
        "trigger: always_on",
        "---",
        "",
        "<!-- BEGIN GROKBOT-SESSION-MEMORY-FOR-AGY -->",
        "# GrokBot Session Memory Bridge for Antigravity CLI",
        f"<!-- Synced: {now_str} | Sessions: {len(sessions)} | Topics: {len(topics)} -->",
        "",
        "## GrokBot Standing Context & Shared Memory",
        f"- GrokBot and Antigravity CLI share the Project Root: `{ROOT}`.",
        "- GrokBot sessions are indexed and accessible via `scripts/grok_to_agy_memory_bridge.py`.",
        "- Full memory catalog: `docs/GROKBOT_MEMORY_INDEX.md` and `data/agent-handoff/grokbot/sessions_index.json`.",
        "",
        "## Key Topic Knowledge (Inherited from GrokBot)",
        "- **ATT GraphRAG / CTX**: Codex owns ATT-0 through ATT-6 product code; assist tools produce handoffs only.",
        "- **AutoGrade B Assist**: B00 registered; Codex owns B02 search failure repair.",
        "- **Jev Gateway ZDR**: Default OFF; Hobby 403 plan gate handled via offline mock fixtures.",
        "- **ma312in / Adaptive Wait**: ChatWorkflow & timeout layers split; Codex owns product Java; Grok/agy assists via diagnostic probes.",
        "- **Clean MAX-PUSH Dynamic Rail**: Tools-only dynamic push rail; product Java stays with Codex.",
        "- **Agent Port Lease**: Dynamic port acquire/release (`demo1-agent-port-lease`).",
        "- **RTX 3090**: Hardware power resolved 2026-09-24. Local Ollama GPU lane is primary.",
        "",
        "## GrokBot Recent High-Impact Sessions",
    ]

    for s in sessions[:8]:
        updated = s["updated_at"][:10] if s["updated_at"] else ""
        lines.append(f"- `[{updated}]` **{s['title']}** (`{s['id']}`): {s['last_turn_summary'] or s['summary'][:80]}")

    if prompts:
        lines.extend([
            "",
            "## Latest user asks to GrokBot (excerpts)",
        ])
        for p in prompts[-5:]:
            ts = p["timestamp"][:10] if p["timestamp"] else ""
            lines.append(f"- `[{ts}]` {p['prompt'][:80]} (`{p['session_id'][:12]}...`)")

    lines.extend([
        "",
        "## Quick Retrieval Commands for agy",
        "- Search Grok sessions/topics/asks: `python -B scripts/grok_to_agy_memory_bridge.py search <query>`",
        "- Show session details: `python -B scripts/grok_to_agy_memory_bridge.py show <id>`",
        "- List recent user asks: `python -B scripts/grok_to_agy_memory_bridge.py prompts [--limit N]`",
        "- Sync fresh Grok turns: `python -B scripts/grok_to_agy_memory_bridge.py sync`",
        "<!-- END GROKBOT-SESSION-MEMORY-FOR-AGY -->",
        "",
    ])

    return "\n".join(lines)

def run_sync(incremental=False, window_hours=24):
    """Sync GrokBot memory -> index/doc/rule. `incremental` reuses index entries
    for sessions whose source files did not change since the previous sync;
    files touched inside `window_hours` are always re-read (clock-skew safety).
    Returns (sessions, topics, obs, stats)."""
    print("[bridge] Collecting GrokBot sessions...")
    t0 = time.time()
    stats = {"mode": "full", "changed": None, "reused": 0}
    prev_index = None
    if incremental:
        stats["mode"] = "incremental"
        stats["windowHours"] = window_hours
        if os.path.exists(OUTPUT_JSON):
            try:
                with open(OUTPUT_JSON, "r", encoding="utf-8") as f:
                    prev_index = json.load(f)
            except Exception:
                prev_index = None
        if not (prev_index or {}).get("sessions"):
            prev_index = None
            print("[bridge] no usable previous index -> full collect")
    sessions, cstats = collect_sessions(
        prev_index=prev_index,
        recent_window_sec=window_hours * 3600 if incremental else 0.0)
    stats.update(cstats)
    stats["elapsedSeconds"] = round(time.time() - t0, 3)
    if incremental and prev_index is not None:
        print(f"[bridge] incremental: {cstats['changed']} changed, "
              f"{cstats['reused']} reused in {stats['elapsedSeconds']}s")
    print(f"[bridge] Found {len(sessions)} sessions.")

    print("[bridge] Collecting GrokBot memory topics & observations...")
    topics = collect_memory_topics()
    obs = collect_observations()
    prompts = collect_recent_prompts()
    print(f"[bridge] Found {len(topics)} topics, {len(obs)} observations, {len(prompts)} recent prompts.")

    # 1. JSON
    os.makedirs(os.path.dirname(OUTPUT_JSON), exist_ok=True)
    data = build_sessions_index_json(sessions, topics, obs, prompts)
    with open(OUTPUT_JSON, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    print(f"[bridge] Wrote JSON index: {OUTPUT_JSON}")

    # 2. DOC
    os.makedirs(os.path.dirname(OUTPUT_DOC), exist_ok=True)
    doc_text = build_memory_index_doc(sessions, topics, obs, prompts)
    with open(OUTPUT_DOC, "w", encoding="utf-8") as f:
        f.write(doc_text)
    print(f"[bridge] Wrote Memory Doc: {OUTPUT_DOC}")

    # 3. RULE for agy
    os.makedirs(os.path.dirname(OUTPUT_RULE), exist_ok=True)
    rule_text = build_rule_content(sessions, topics, obs, prompts)
    with open(OUTPUT_RULE, "w", encoding="utf-8") as f:
        f.write(rule_text)
    print(f"[bridge] Wrote Antigravity CLI Rule: {OUTPUT_RULE}")

    return sessions, topics, obs, stats

def run_list(limit=20):
    sessions, _ = collect_sessions()
    print(f"\n=== GrokBot Sessions ({len(sessions)} total) ===")
    print(f"{'Updated':<17} | {'Messages':<8} | {'Title / Summary':<45} | {'Session ID'}")
    print("-" * 110)
    for s in sessions[:limit]:
        updated = s["updated_at"][:16].replace("T", " ") if s["updated_at"] else "N/A"
        title = (s["title"] or s["summary"] or "Untitled")[:45]
        print(f"{updated:<17} | {s['messages']:<8} | {title:<45} | {s['id']}")
    if len(sessions) > limit:
        print(f"\n... and {len(sessions) - limit} more. Use --limit {len(sessions)} to show all.")

def run_show(session_id):
    sessions, _ = collect_sessions()
    target = None
    for s in sessions:
        if s["id"].startswith(session_id) or session_id in s["id"]:
            target = s
            break
    if not target:
        print(f"[error] Session not found matching ID: {session_id}")
        sys.exit(1)

    print("\n" + "=" * 80)
    print(f"Session ID : {target['id']}")
    print(f"Title      : {target['title']}")
    print(f"Updated    : {target['updated_at']}")
    print(f"Created    : {target['created_at']}")
    print(f"Model      : {target['model']}")
    print(f"Branch     : {target['head_branch']} (commit: {target['head_commit']})")
    print(f"Messages   : {target['messages']} (chat messages: {target['chat_messages']})")
    print(f"Summary    : {target['summary']}")
    print(f"Last Turn  : {target['last_turn_summary']}")
    print("-" * 80)
    if target["first_prompt"]:
        print(f"Initial User Prompt:\n  {target['first_prompt']}\n")
    if target["last_user_prompt"]:
        print(f"Last User Prompt:\n  {target['last_user_prompt']}\n")
    if target["last_assistant_resp"]:
        print(f"Last Grok Response:\n  {target['last_assistant_resp']}\n")
    print(f"Path: {target['path']}")
    print("=" * 80)

def run_topics():
    topics = collect_memory_topics()
    print(f"\n=== GrokBot Memory Topics ({len(topics)} total) ===")
    for t in topics:
        print(f"\n[{t['type'].upper()}] {t['title']} ({t['name']})")
        lines = [l for l in t['content'].splitlines() if l.strip() and not l.startswith('#')]
        preview = " ".join(lines[:3])[:200]
        print(f"  {preview}...")

def run_prompts(limit=25):
    prompts = collect_recent_prompts(limit=limit)
    print(f"\n=== Recent User Asks to GrokBot ({len(prompts)} shown) ===")
    for p in prompts:
        ts = p["timestamp"][:16].replace("T", " ") if p["timestamp"] else "N/A"
        print(f"[{ts}] ({p['session_id'][:12]}) {p['prompt']}")

def run_search(query):
    query_lower = query.lower()
    sessions, _ = collect_sessions()
    topics = collect_memory_topics()
    prompts = collect_recent_prompts(limit=200)

    print(f"\n=== Search Results for '{query}' ===")
    print("\n--- Matching Topics ---")
    matched_topics = 0
    for t in topics:
        if query_lower in t["title"].lower() or query_lower in t["content"].lower():
            matched_topics += 1
            print(f"- [{t['type']}] {t['title']} ({t['name']})")
    if not matched_topics:
        print("  (None)")

    print("\n--- Matching Sessions ---")
    matched_sessions = 0
    for s in sessions:
        haystack = " ".join([
            s["id"], s["title"], s["summary"], s["last_turn_summary"],
            s["first_prompt"], s["last_user_prompt"], s["last_assistant_resp"]
        ]).lower()
        if query_lower in haystack:
            matched_sessions += 1
            updated = s["updated_at"][:10] if s["updated_at"] else ""
            print(f"- [{updated}] {s['title']} ({s['id']})")
            if s["last_turn_summary"]:
                print(f"    Summary: {s['last_turn_summary'][:100]}")
    if not matched_sessions:
        print("  (None)")

    print("\n--- Matching User Asks (prompt_history) ---")
    matched_prompts = 0
    for p in prompts:
        if query_lower in p["prompt"].lower():
            matched_prompts += 1
            ts = p["timestamp"][:10] if p["timestamp"] else ""
            print(f"- [{ts}] ({p['session_id'][:12]}) {p['prompt'][:100]}")
    if not matched_prompts:
        print("  (None)")

def main():
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")
        except Exception:
            pass
    parser = argparse.ArgumentParser(description="GrokBot to Antigravity CLI Memory Bridge")
    subparsers = parser.add_subparsers(dest="command", help="Subcommand")

    p_sync = subparsers.add_parser("sync", help="Sync Grok sessions & memory to agy docs/rules")
    p_sync.add_argument("--incremental", action="store_true",
                        help="reuse index entries for sessions unchanged since the last sync")
    p_sync.add_argument("--window-hours", type=float, default=24,
                        help="always re-read sessions touched within this many hours (default 24)")
    p_list = subparsers.add_parser("list", help="List all Grok sessions")
    p_list.add_argument("--limit", type=int, default=20, help="Max sessions to display")

    p_show = subparsers.add_parser("show", help="Show details of a specific Grok session")
    p_show.add_argument("session_id", help="Session ID (or prefix)")

    p_topics = subparsers.add_parser("topics", help="List all memory topics")

    p_search = subparsers.add_parser("search", help="Search sessions, topics, and user asks")
    p_search.add_argument("query", help="Keyword or phrase to search")

    p_prompts = subparsers.add_parser("prompts", help="List recent user asks sent to GrokBot")
    p_prompts.add_argument("--limit", type=int, default=25, help="Max prompts to display")

    args = parser.parse_args()

    if not args.command or args.command == "sync":
        run_sync(incremental=getattr(args, "incremental", False),
                 window_hours=getattr(args, "window_hours", 24))
    elif args.command == "list":
        run_list(limit=args.limit)
    elif args.command == "show":
        run_show(args.session_id)
    elif args.command == "topics":
        run_topics()
    elif args.command == "search":
        run_search(args.query)
    elif args.command == "prompts":
        run_prompts(limit=args.limit)

if __name__ == "__main__":
    main()
