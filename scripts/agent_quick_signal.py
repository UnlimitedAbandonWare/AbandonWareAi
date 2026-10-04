"""agent_quick_signal.py — one-stop quick ping-pong CLI over the orchestra layer.

Wraps the existing tools in a single call — never reimplements them:
  emit : orchestra_signal.py new + orchestra_route.py --apply
         + orchestra_paste.py (var/orchestra/outbox/PASTE_*.txt + 말로: line)
  inbox: waiting signals for one agent (inbox/<agent>/, done/dropped hidden)
  copy : copy a PASTE brief body to the Windows clipboard (clip.exe, then
         powershell Set-Clipboard) or print it with --print
  done : move a signal to archive with status=done
  counts: [Codex: N] [Devin: N] [Grok: N] header used by Agent-Signal.bat

All files stay local; nothing is sent to another agent automatically — the
user pastes. Network calls: 0. Paid calls: 0. Secret-looking content is
refused by the underlying signal tool (exit 2 surfaces unchanged).
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

SCHEMA = "awx.agent-quick-signal.v1"

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (OSError, ValueError):
        pass

DEFAULT_STORE = "data/agent-handoff/orchestra"
DEFAULT_OUTDIR = "var/orchestra/outbox"

AGENTS = ("user", "grokbot", "agy", "gptpro", "devin", "codex", "clean", "grokcli")
PASTE_AGENTS = ("codex", "devin", "gptpro", "agy", "grokbot", "clean", "user")
LANE_AGENT = {"DEVIN": "devin", "CODEX_DIRECT": "codex",
              "GPTPRO_THEN_CODEX": "gptpro", "AGY_RESEARCH": "agy",
              "GROKBOT_AMPLIFY": "grokbot", "AGY_AS_GROKBOT": "agy",
              "ASK_USER": "user"}
# Natural output kind per sender; --kind overrides.
DEFAULT_KIND = {"user": "idea", "grokbot": "amplified", "agy": "web-evidence",
                "gptpro": "gptpro-brief", "devin": "devin-signal",
                "codex": "patch-report", "clean": "patch-report",
                "grokcli": "amplified"}


def run_tool(root, script, argv):
    proc = subprocess.run(
        [sys.executable, "-B", str(Path("scripts") / script)] + argv,
        cwd=root, capture_output=True, text=True, encoding="utf-8",
        errors="replace")
    try:
        parsed = json.loads(proc.stdout.strip())
    except ValueError:
        parsed = {"raw": proc.stdout.strip()[:400]}
    return proc.returncode, parsed


def flat_files(values):
    out = []
    for item in values or []:
        out.extend(p.strip() for p in item.split(",") if p.strip())
    return out


def copy_clipboard(text):
    for cmd in (["clip.exe"],
                ["powershell", "-NoProfile", "-Command", "$input | Set-Clipboard"]):
        try:
            proc = subprocess.run(cmd, input=text, capture_output=True,
                                  text=True, encoding="utf-8", errors="replace",
                                  timeout=20)
            if proc.returncode == 0:
                return cmd[0]
        except (OSError, subprocess.TimeoutExpired):
            continue
    return None


def emit(args):
    files = flat_files(args.files)
    kind = args.kind or DEFAULT_KIND.get(args.sender, "idea")
    argv = ["new", "--store", args.store, "--root", args.root,
            "--from", args.sender, "--to", args.to, "--kind", kind,
            "--summary", args.summary, "--priority", args.priority]
    if files:
        argv += ["--files"] + files
    if args.notes:
        argv += ["--notes", args.notes]
    if args.parent:
        argv += ["--parent", args.parent]
    code, created = run_tool(args.root, "orchestra_signal.py", argv)
    if code != 0:
        return code, {"status": "error", "step": "signal-new", "detail": created}
    sid = created.get("id")

    argv = ["--root", args.root, "--store", args.store, "--signal", sid, "--apply"]
    if args.no_classify:
        argv.append("--no-classifiers")
    code, routed = run_tool(args.root, "orchestra_route.py", argv)
    if code != 0:
        return code, {"status": "error", "step": "route", "id": sid,
                      "detail": routed}

    agent = args.to if args.to in PASTE_AGENTS else \
        routed.get("nextAgent") if routed.get("nextAgent") in PASTE_AGENTS else "user"
    code, pasted = run_tool(args.root, "orchestra_paste.py",
                            ["--root", args.root, "--store", args.store,
                             "--outdir", args.outdir, "--id", sid,
                             "--agent", agent])
    if code != 0:
        return code, {"status": "error", "step": "paste", "id": sid,
                      "detail": pasted}

    clip = "skipped"
    if args.clip:
        body = Path(pasted["pasteFile"]).read_text(encoding="utf-8")
        clip = copy_clipboard(body) or "unavailable"
    result = {"schemaVersion": SCHEMA, "action": "emit", "id": sid,
              "stored": created.get("stored"), "from": args.sender,
              "to": args.to, "kind": kind, "lane": routed.get("lane"),
              "nextAgent": routed.get("nextAgent"),
              "overlapWarnings": routed.get("overlapWarnings") or [],
              "pasteFile": pasted.get("pasteFile"),
              "mallowLine": pasted.get("mallowLine"), "clipboard": clip,
              "autoSent": False}
    return 0, result


def inbox_rows(store, agent):
    base = Path(store) / "inbox" / agent
    rows = []
    if not base.is_dir():
        return rows
    for path in sorted(base.glob("*.json")):
        try:
            sig = json.loads(path.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError):
            continue
        if sig.get("status") in ("done", "dropped"):
            continue
        rows.append({"id": sig.get("id"), "from": sig.get("from"),
                     "kind": sig.get("kind"), "status": sig.get("status"),
                     "lane": sig.get("lane"), "priority": sig.get("priority"),
                     "atKst": sig.get("atKst"),
                     "summary": (sig.get("summary") or "")[:80],
                     "path": str(path)})
    return rows


def inbox(args):
    rows = inbox_rows(args.store, args.agent)
    return 0, {"schemaVersion": SCHEMA, "action": "inbox",
               "agent": args.agent, "count": len(rows), "signals": rows}


def resolve_paste_agent(store, sid):
    base = Path(store)
    for part in ("inbox", "outbox"):
        part_dir = base / part
        if part_dir.is_dir():
            hits = sorted(part_dir.glob("*/" + sid + ".json"))
            if hits:
                agent = hits[0].parent.name
                return agent if agent in PASTE_AGENTS else "user"
    arch = base / "archive" / (sid + ".json")
    if arch.is_file():
        try:
            sig = json.loads(arch.read_text(encoding="utf-8-sig"))
            return LANE_AGENT.get(sig.get("lane"), "user")
        except (OSError, ValueError):
            return "user"
    return None


def copy_cmd(args):
    path = None
    if args.id:
        agent = resolve_paste_agent(args.store, args.id)
        if agent is None:
            return 2, {"schemaVersion": SCHEMA, "action": "copy",
                       "status": "error", "reason": "signal-not-found:" + args.id}
        code, pasted = run_tool(args.root, "orchestra_paste.py",
                                ["--root", args.root, "--store", args.store,
                                 "--outdir", args.outdir, "--id", args.id,
                                 "--agent", agent])
        if code != 0:
            return code, {"status": "error", "step": "paste", "detail": pasted}
        path = Path(pasted["pasteFile"])
    else:
        pattern = "PASTE_" + args.agent.upper() + "_*.txt"
        cands = sorted(Path(args.root, args.outdir).glob(pattern),
                       key=lambda p: p.stat().st_mtime, reverse=True)
        if not cands:
            return 2, {"schemaVersion": SCHEMA, "action": "copy",
                       "status": "error",
                       "reason": "no-paste-file:" + pattern}
        path = cands[0]
    body = path.read_text(encoding="utf-8")
    if args.print_body:
        return 0, {"schemaVersion": SCHEMA, "action": "copy",
                   "pasteFile": str(path), "clipboard": "printed",
                   "body": body}
    clip = copy_clipboard(body)
    if clip is None:
        return 3, {"schemaVersion": SCHEMA, "action": "copy",
                   "status": "error", "pasteFile": str(path),
                   "reason": "clipboard-unavailable"}
    return 0, {"schemaVersion": SCHEMA, "action": "copy",
               "pasteFile": str(path), "clipboard": clip}


def done(args):
    code, moved = run_tool(args.root, "orchestra_signal.py",
                           ["move", "--store", args.store, "--root", args.root,
                            "--id", args.id, "--to", "archive",
                            "--status", "done"])
    if code != 0:
        return code, {"status": "error", "step": "move", "detail": moved}
    return 0, {"schemaVersion": SCHEMA, "action": "done", "id": moved.get("id"),
               "from": moved.get("from"), "to": moved.get("to"),
               "status": moved.get("status")}


def counts(args):
    parts = []
    detail = {}
    for name, label in (("codex", "Codex"), ("devin", "Devin"),
                        ("grokbot", "Grok")):
        n = len(inbox_rows(args.store, name))
        parts.append(f"[{label}: {n}]")
        detail[name] = n
    return 0, {"schemaVersion": SCHEMA, "action": "counts",
               "line": " ".join(parts), "waiting": detail}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--store", default=DEFAULT_STORE)
    parser.add_argument("--outdir", default=DEFAULT_OUTDIR)
    parser.add_argument("--json", action="store_true",
                        help="print the result as one JSON object")
    sub = parser.add_subparsers(dest="action", required=True)

    p = sub.add_parser("emit", help="signal new + route --apply + paste file")
    p.add_argument("--from", dest="sender", required=True, choices=AGENTS)
    p.add_argument("--to", required=True, choices=AGENTS)
    p.add_argument("--summary", required=True)
    p.add_argument("--files", action="append", default=[],
                   help="comma-separated paths; repeatable")
    p.add_argument("--kind", default=None)
    p.add_argument("--priority", default="P1", choices=("P0", "P1", "P2"))
    p.add_argument("--notes", default=None)
    p.add_argument("--parent", default=None, help="parent signal id (12hex)")
    p.add_argument("--clip", action="store_true",
                   help="copy the PASTE body to the Windows clipboard")
    p.add_argument("--no-classify", action="store_true",
                   help="route without the 3 classifier evidence calls")
    p.set_defaults(func=emit)

    p = sub.add_parser("inbox", help="list waiting signals for one agent")
    p.add_argument("--agent", required=True, choices=AGENTS)
    p.set_defaults(func=inbox)

    p = sub.add_parser("copy", help="copy latest PASTE body to the clipboard")
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--agent", choices=PASTE_AGENTS)
    src.add_argument("--id", help="signal id: re-render its PASTE then copy")
    p.add_argument("--print", dest="print_body", action="store_true",
                   help="print the body instead of touching the clipboard")
    p.set_defaults(func=copy_cmd)

    p = sub.add_parser("done", help="archive a signal with status=done")
    p.add_argument("--id", required=True)
    p.set_defaults(func=done)

    p = sub.add_parser("counts", help="[Codex: N] [Devin: N] [Grok: N] header")
    p.set_defaults(func=counts)

    args = parser.parse_args()
    args.store = str(Path(args.root, args.store).resolve())
    args.outdir = str(Path(args.root, args.outdir).resolve())
    code, result = args.func(args)

    if args.action == "counts" and not args.json:
        print(result["line"])
    elif args.action == "copy" and args.print_body and code == 0 \
            and not args.json:
        print(result["pasteFile"])
        print(result["body"])
    elif args.action == "emit" and not args.json and code == 0:
        print(f"signal={result['id']} {result['from']}->{result['to']} "
              f"kind={result['kind']} lane={result['lane']} "
              f"next={result['nextAgent']} stored={result['stored']}")
        for warn in result["overlapWarnings"]:
            print("overlap-warning: " + json.dumps(warn, ensure_ascii=False))
        print("paste=" + str(result["pasteFile"]))
        print(result["mallowLine"])
        print("clipboard=" + result["clipboard"])
    elif args.action == "inbox" and not args.json:
        print(f"inbox {result['agent']}: {result['count']} waiting")
        for row in result["signals"]:
            print(f"  [{row['id']}] {row['kind']} {row['status']} "
                  f"{row['lane'] or '-'} {row['from']} | {row['summary']}")
    elif args.action == "done" and not args.json and code == 0:
        print(f"done {result['id']} -> {result['to']} status={result['status']}")
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())
