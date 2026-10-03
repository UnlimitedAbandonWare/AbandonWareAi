"""orchestra_board.py — one-screen status board for the orchestra layer.

Merges `agent_signal_digest.py --json` (leases, journals, handoffs) with the
awx.orchestra-signal.v1 store into <=30 lines: per-agent current work,
waiting signals, last signal, file overlaps (active leases x signal files),
per-signal live-call budget n/cap, cost order, and the next paste line.

Read-only. Network calls: 0. Paid calls: 0.
  python -B scripts/orchestra_board.py [--md] [--json] [--hours 24]
"""
import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone, timedelta
from pathlib import Path

SCHEMA = "awx.orchestra-board.v1"

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (OSError, ValueError):
        pass
DEFAULT_STORE = "data/agent-handoff/orchestra"
DEFAULT_OUT = "var/orchestra/BOARD.md"
LEASE_GLOB = "__patch_drop__/source-edit-locks/*.lock/lease.json"
COST_ORDER = ["codex-credit", "external-paid", "free", "ollama-local"]
AGENTS = ("user", "grokbot", "agy", "gptpro", "devin", "codex", "clean", "grokcli")
MAX_LINES = 30


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def run_digest(root, hours):
    try:
        proc = subprocess.run(
            [sys.executable, "-B", "scripts/agent_signal_digest.py", "--json",
             "--hours", str(hours)],
            cwd=root, capture_output=True, text=True, timeout=60,
            encoding="utf-8", errors="replace")
        if proc.returncode == 0 and proc.stdout.strip():
            return json.loads(proc.stdout), None
        return None, "digest-exit-" + str(proc.returncode)
    except (OSError, subprocess.TimeoutExpired, ValueError) as exc:
        return None, "digest-error:" + str(exc)[:120]


def load_signals(root, store):
    base = Path(root) / store
    out = []
    for part in ("inbox", "outbox", "archive"):
        part_dir = base / part
        if not part_dir.is_dir():
            continue
        for path in sorted(part_dir.rglob("*.json")):
            try:
                sig = load_json(path)
            except (OSError, ValueError):
                continue
            sig["_part"] = part
            sig["_agentDir"] = path.parent.name if part != "archive" else "archive"
            sig["_path"] = str(path)
            out.append(sig)
    return out


def lease_rows(root):
    rows = []
    for lease_file in sorted(Path(root).glob(LEASE_GLOB)):
        try:
            lease = load_json(lease_file)
        except (OSError, ValueError):
            rows.append({"topic": lease_file.parent.name[:-5], "status": "corrupt",
                         "targets": []})
            continue
        status = str(lease.get("status") or "").lower()
        if status in ("released", "ended", "expired"):
            live = False
        else:
            exp = lease.get("expiresAtUtc")
            live = False
            if exp:
                try:
                    live = datetime.fromisoformat(str(exp).replace("Z", "+00:00")) > \
                        datetime.now(timezone.utc)
                except ValueError:
                    live = False
            elif lease.get("mutationAllowed"):
                live = True
        rows.append({"topic": lease.get("topic") or lease_file.parent.name[:-5],
                     "status": "active" if live else "ended",
                     "targets": lease.get("targetPaths") or []})
    return rows


def build(root, store, hours):
    digest, digest_err = run_digest(root, hours)
    signals = load_signals(root, store)
    leases = lease_rows(root)
    active_leases = [l for l in leases if l["status"] == "active"]

    # Per-agent rollup.
    agents = {}
    for name in AGENTS:
        inbox = [s for s in signals if s["_part"] == "inbox" and s["_agentDir"] == name]
        outbox = [s for s in signals if s["_part"] == "outbox" and s["_agentDir"] == name]
        # "working" = signal sitting in this agent's inbox marked in-progress;
        # the signal's `from` is its author, not its holder.
        working = [s for s in inbox if s.get("status") == "in-progress"]
        inbox = [s for s in inbox if s.get("status") != "in-progress"]
        last = max((s for s in signals if s.get("from") == name),
                   key=lambda s: s.get("atUtc", ""), default=None)
        agents[name] = {"waiting": len(inbox), "outbox": len(outbox),
                        "working": len(working),
                        "lastSignal": (last or {}).get("id"),
                        "lastSummary": ((last or {}).get("summary") or "")[:46]}

    # Overlaps: active lease targets x live signal files.
    overlaps = []
    live = [s for s in signals
            if s.get("status") in ("new", "routed", "in-progress")]
    for lease in active_leases:
        leased = {t.replace("\\", "/").casefold() for t in lease["targets"]}
        for sig in live:
            hits = sorted(leased & {f.replace("\\", "/").casefold()
                                    for f in (sig.get("files") or [])})
            if hits:
                overlaps.append({"lease": lease["topic"], "signal": sig.get("id"),
                                 "files": hits})

    # Budget use per live signal.
    budget_rows = []
    for sig in live:
        cap = sig.get("budget") or {}
        used = sig.get("budgetUsed") or {}
        if cap or used:
            budget_rows.append({"id": sig.get("id"),
                                "liveCalls": f"{used.get('liveCalls', 0)}/{cap.get('liveCalls', 0)}",
                                "restarts": f"{used.get('restarts', 0)}/{cap.get('restarts', 0)}"})

    # Next paste suggestion: newest routed-but-not-started signal.
    routed = [s for s in live if s.get("status") == "routed" and s.get("lane")]
    routed.sort(key=lambda s: s.get("atUtc", ""), reverse=True)
    next_paste = None
    if routed:
        s = routed[0]
        agent = {"DEVIN": "devin", "CODEX_DIRECT": "codex",
                 "GPTPRO_THEN_CODEX": "gptpro", "AGY_RESEARCH": "agy",
                 "GROKBOT_AMPLIFY": "grokbot", "AGY_AS_GROKBOT": "agy",
                 "ASK_USER": "user"}.get(s.get("lane"), "orchestra")
        next_paste = {"signalId": s.get("id"), "agent": agent,
                      "command": f"python -B scripts/orchestra_paste.py --id {s.get('id')} --agent {agent}"}

    journals = (digest or {}).get("journals") or []
    return {
        "schemaVersion": SCHEMA,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "generatedAtKst": datetime.now(timezone(timedelta(hours=9))).strftime("%Y-%m-%d %H:%M KST"),
        "digestError": digest_err,
        "signalCount": len(signals),
        "agents": agents,
        "activeLeases": [{"topic": l["topic"], "targets": len(l["targets"])}
                         for l in active_leases],
        "overlaps": overlaps,
        "budgets": budget_rows,
        "costOrder": COST_ORDER,
        "nextPaste": next_paste,
        "activeJournals": [{"taskId": j.get("taskId"), "agent": j.get("agent"),
                            "purpose": (j.get("purpose") or "")[:60]}
                           for j in journals][:8],
        "errors": ([digest_err] if digest_err else []),
    }


def render_md(board):
    lines = []
    lines.append(f"# Orchestra Board — {board['generatedAtKst']}")
    lines.append(f"signals={board['signalCount']}  activeLeases={len(board['activeLeases'])}"
                 + (f"  digestError={board['digestError']}" if board["digestError"] else ""))
    lines.append("")
    lines.append("## agents")
    lines.append("| agent | waiting(inbox) | working | out | last signal |")
    lines.append("|---|---|---|---|---|")
    for name, a in board["agents"].items():
        if a["waiting"] or a["working"] or a["outbox"] or a["lastSignal"]:
            lines.append(f"| {name} | {a['waiting']} | {a['working']} | {a['outbox']} | "
                         f"{a['lastSignal'] or '-'} {(a['lastSummary'] or '')} |")
    lines.append("")
    lines.append("## overlaps (lease x signal files)")
    if board["overlaps"]:
        for o in board["overlaps"][:6]:
            lines.append(f"- {o['lease']} vs {o['signal']}: {', '.join(o['files'][:4])}")
    else:
        lines.append("- none")
    lines.append("")
    lines.append("## budgets (liveCalls/restarts used/cap)")
    if board["budgets"]:
        for b in board["budgets"][:8]:
            lines.append(f"- {b['id']}: live {b['liveCalls']} restart {b['restarts']}")
    else:
        lines.append("- none (default 0/0)")
    lines.append("")
    lines.append("cost order: " + " -> ".join(board["costOrder"]))
    if board["activeJournals"]:
        lines.append("")
        lines.append("## active journals")
        for j in board["activeJournals"][:5]:
            lines.append(f"- {j['taskId']} ({j['agent']}): {j['purpose']}")
    lines.append("")
    if board["nextPaste"]:
        lines.append(f"next paste: `{board['nextPaste']['command']}`")
    else:
        lines.append("next paste: none routed")
    return lines[:MAX_LINES]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--store", default=DEFAULT_STORE)
    parser.add_argument("--hours", type=int, default=24)
    parser.add_argument("--md", action="store_true", help="write var/orchestra/BOARD.md")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--out", default=DEFAULT_OUT)
    args = parser.parse_args()

    board = build(args.root, args.store, args.hours)
    md_lines = render_md(board)
    if args.md:
        out_path = Path(args.root) / args.out
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text("\n".join(md_lines) + "\n", encoding="utf-8")
        board["boardPath"] = str(out_path)
    if args.json or not args.md:
        print(json.dumps(board, ensure_ascii=False, indent=2))
    if args.md:
        print("\n".join(md_lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
