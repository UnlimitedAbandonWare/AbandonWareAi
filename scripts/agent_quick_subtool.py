"""Agent quick sub-tool backend for Sub-Tool.bat (P11/P7/P1 bottleneck relief).

Pure Python 3.11 stdlib. One-second-call helpers for agent sessions:
  hash        file size + line count + SHA12 table/JSON
  lint        instant syntax check for .py/.json/.yaml/.js/.mjs
  test        single Java FQCN (gradlew :test --tests) or Python unittest file
  precheck    pre-patch file metadata (SHA12/lines/mtime) against P1 context misses
  clean-stale stale lease reclaim + zombie journal report via existing tools (P7)
  scan-log    delegate to agent_fast_scan.mjs, Python streaming fallback (P11)

Read-only by default. clean-stale mutates only through agent_scope_lease.py /
work_journal.py and only with --execute (+--agent for journal close).
"""
import argparse
import datetime
import hashlib
import json
import os
import py_compile
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCAN_MJS = Path(__file__).resolve().with_name("agent_fast_scan.mjs")

LOG_PATTERNS = [
    ("p11-cannot-find-path", re.compile(r"Cannot find path", re.IGNORECASE)),
    ("p11-command-failed", re.compile(r"Command failed", re.IGNORECASE)),
    ("p7-goal-conflict", re.compile(r"goal-conflict", re.IGNORECASE)),
    ("exception", re.compile(r"\w*Exception\b")),
    ("error", re.compile(r"\w*Error\b")),
]

HELP_TEXT = """Sub-Tool.bat - agent quick sub-tool (1-second helpers)
  Sub-Tool.bat hash <files...>        file size + lines + SHA12 table
  Sub-Tool.bat lint <files...>        instant syntax check (.py/.json/.yaml/.js/.mjs)
  Sub-Tool.bat test <fqcn_or_file>    Java single test or Python unittest fast run
  Sub-Tool.bat precheck <files...>    pre-patch SHA12/lines/mtime check (P1 guard)
  Sub-Tool.bat clean-stale            reclaim stale leases / report zombie journals (P7)
  Sub-Tool.bat scan-log <log>         streaming error/warning summary (P11)
Options: --json (auto in AWX_AGENT/CI), clean-stale: --execute [--agent NAME] [--include-orphan]
         test: --timeout N (sec), scan-log: --limit N"""


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc)


def iso(ts):
    return datetime.datetime.fromtimestamp(ts, datetime.timezone.utc).isoformat()


def file_row(path_str):
    p = Path(path_str)
    row = {"path": path_str, "exists": p.is_file()}
    if not row["exists"]:
        return row
    data = p.read_bytes()
    row["bytes"] = len(data)
    row["lines"] = data.count(b"\n") + (1 if data and not data.endswith(b"\n") else 0)
    row["sha12"] = hashlib.sha256(data).hexdigest()[:12]
    row["mtimeUtc"] = iso(p.stat().st_mtime)
    return row


def anchor_lines(p):
    try:
        head = tail = ""
        with open(p, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if not head and line.strip():
                    head = line.strip()[:120]
                if line.strip():
                    tail = line.strip()[:120]
        return head, tail
    except OSError:
        return "", ""


def run_cmd(cmd, timeout, cwd=None):
    t0 = utc_now()
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              cwd=cwd or str(ROOT), timeout=timeout)
        out = (proc.stdout or "") + (proc.stderr or "")
        return proc.returncode, out, (utc_now() - t0).total_seconds()
    except subprocess.TimeoutExpired as exc:
        out = ""
        if exc.stdout:
            out += exc.stdout if isinstance(exc.stdout, str) else exc.stdout.decode("utf-8", "replace")
        if exc.stderr:
            out += exc.stderr if isinstance(exc.stderr, str) else exc.stderr.decode("utf-8", "replace")
        return None, out, (utc_now() - t0).total_seconds()
    except OSError as exc:
        return None, str(exc), (utc_now() - t0).total_seconds()


def last_json_line(text):
    for line in reversed(text.strip().splitlines()):
        line = line.strip()
        if line.startswith("{"):
            try:
                return json.loads(line)
            except ValueError:
                continue
    return None


def emit_json(payload):
    print(json.dumps(payload, ensure_ascii=True))


def emit_table(rows, columns):
    widths = [max(len(c[1]), *(len(str(r.get(c[0], ""))) for r in rows)) if rows else len(c[1])
              for c in columns]
    print("  ".join(c[1].ljust(w) for c, w in zip(columns, widths)))
    print("  ".join("-" * w for w in widths))
    for r in rows:
        print("  ".join(str(r.get(c[0], "")).ljust(w) for c, w in zip(columns, widths)))


# --- hash / precheck ---------------------------------------------------------

def cmd_hash(args):
    rows = [file_row(f) for f in args.files]
    missing = [r["path"] for r in rows if not r["exists"]]
    if args.json_out:
        emit_json({"action": "hash", "files": rows, "missing": missing})
    else:
        cols = [("path", "PATH"), ("bytes", "BYTES"), ("lines", "LINES"), ("sha12", "SHA12")]
        emit_table(rows, cols)
        for m in missing:
            print(f"MISSING {m}")
    return 1 if missing else 0


def cmd_precheck(args):
    rows = []
    for f in args.files:
        row = file_row(f)
        if row["exists"]:
            head, tail = anchor_lines(Path(f))
            row["firstLine"] = head
            row["lastLine"] = tail
        rows.append(row)
    missing = [r["path"] for r in rows if not r["exists"]]
    if args.json_out:
        emit_json({"action": "precheck", "files": rows, "missing": missing})
    else:
        cols = [("path", "PATH"), ("bytes", "BYTES"), ("lines", "LINES"),
                ("sha12", "SHA12"), ("mtimeUtc", "MTIME_UTC")]
        emit_table(rows, cols)
        for r in rows:
            if r["exists"]:
                print(f"  head| {r['path']}: {r['firstLine']}")
                print(f"  tail| {r['path']}: {r['lastLine']}")
        for m in missing:
            print(f"MISSING {m}")
    return 1 if missing else 0


# --- lint --------------------------------------------------------------------

def lint_python(path):
    try:
        with tempfile.TemporaryDirectory() as td:
            py_compile.compile(str(path), cfile=str(Path(td) / "x.pyc"), doraise=True)
        return "pass", None, ""
    except py_compile.PyCompileError as exc:
        msg = str(exc).replace("\n", " ").strip()
        m = re.search(r"line (\d+)", msg)
        return "fail", int(m.group(1)) if m else None, msg[:300]


def lint_json(path):
    try:
        json.loads(Path(path).read_text(encoding="utf-8-sig"))
        return "pass", None, ""
    except json.JSONDecodeError as exc:
        return "fail", exc.lineno, f"{exc.msg} (col {exc.colno})"[:300]
    except (OSError, ValueError) as exc:
        return "fail", None, str(exc)[:300]


def lint_yaml(path):
    try:
        import yaml
    except ImportError:
        return "skipped", None, "pyyaml-not-installed"
    try:
        yaml.safe_load(Path(path).read_text(encoding="utf-8-sig"))
        return "pass", None, ""
    except yaml.YAMLError as exc:
        mark = getattr(exc, "problem_mark", None)
        line = (mark.line + 1) if mark is not None else None
        return "fail", line, str(exc).replace("\n", " ")[:300]
    except OSError as exc:
        return "fail", None, str(exc)[:300]


def lint_js(path):
    if shutil.which("node") is None:
        return "skipped", None, "node-not-on-path"
    code, out, _ = run_cmd(["node", "--check", str(path)], timeout=30)
    if code == 0:
        return "pass", None, ""
    m = re.search(r":(\d+)\r?\n", out or "")
    return "fail", int(m.group(1)) if m else None, (out or "node --check failed").strip().replace("\n", " | ")[:300]


LINTERS = {".py": lint_python, ".json": lint_json,
           ".yaml": lint_yaml, ".yml": lint_yaml,
           ".js": lint_js, ".mjs": lint_js}


def cmd_lint(args):
    results = []
    for f in args.files:
        p = Path(f)
        if not p.is_file():
            results.append({"path": str(p), "kind": p.suffix.lower() or "?",
                            "status": "fail", "line": None, "message": "file-not-found"})
            continue
        linter = LINTERS.get(p.suffix.lower())
        if linter is None:
            results.append({"path": str(p), "kind": p.suffix.lower() or "?",
                            "status": "skipped", "line": None, "message": "no-linter-for-extension"})
            continue
        status, line, msg = linter(p)
        results.append({"path": str(p), "kind": p.suffix.lower(), "status": status,
                        "line": line, "message": msg})
    failed = [r for r in results if r["status"] == "fail"]
    if args.json_out:
        emit_json({"action": "lint", "results": results,
                   "pass": sum(1 for r in results if r["status"] == "pass"),
                   "fail": len(failed),
                   "skipped": sum(1 for r in results if r["status"] == "skipped")})
    else:
        for r in results:
            loc = f":{r['line']}" if r["line"] else ""
            msg = f" {r['message']}" if r["status"] != "pass" else ""
            print(f"{r['status'].upper():<7} {r['path']}{loc}{msg}")
    return 1 if failed else 0


# --- test --------------------------------------------------------------------

def java_fqcn(target):
    if target.endswith(".java") or "/" in target or "\\" in target:
        norm = target.replace("\\", "/")
        for anchor in ("src/test/java/", "main/java/"):
            idx = norm.find(anchor)
            if idx >= 0:
                return norm[idx + len(anchor):].removesuffix(".java").replace("/", ".")
        return Path(norm).stem
    return target


def python_module(target):
    p = Path(target).resolve()
    try:
        rel = p.relative_to(ROOT)
        return ".".join(rel.with_suffix("").parts)
    except ValueError:
        return None


def cmd_test(args):
    target = args.target
    is_java = target.endswith(".java") or (re.fullmatch(r"[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)+", target)
                                          and not target.endswith(".py"))
    if is_java:
        fqcn = java_fqcn(target)
        cmd = [str(ROOT / "gradlew.bat"), ":test", "--tests", fqcn,
               "--no-daemon", "--console=plain"]
        code, out, elapsed = run_cmd(cmd, timeout=args.timeout)
        verdict = "PASS" if code == 0 else ("TIMEOUT" if code is None else "FAIL")
        tail = "\n".join((out or "").strip().splitlines()[-40:])
        if args.json_out:
            emit_json({"action": "test", "kind": "java", "target": fqcn, "cmd": cmd,
                       "exit": code, "verdict": verdict, "elapsedSec": round(elapsed, 2),
                       "outputTail": tail})
        else:
            print(f"TEST {verdict} {fqcn} ({elapsed:.1f}s)")
            print(tail)
        return code if code is not None else 4

    p = Path(target)
    if not p.is_file() and not target.endswith(".py"):
        print(f"TEST ERROR target-not-found-or-unsupported: {target}", file=sys.stderr)
        return 2
    module = python_module(target)
    if module:
        cmd = [sys.executable, "-B", "-m", "unittest", "-v", module]
    elif p.is_file():
        cmd = [sys.executable, "-B", str(p)]
    else:
        print(f"TEST ERROR file-not-found: {target}", file=sys.stderr)
        return 2
    code, out, elapsed = run_cmd(cmd, timeout=args.timeout)
    verdict = "PASS" if code == 0 else ("TIMEOUT" if code is None else "FAIL")
    tail = "\n".join((out or "").strip().splitlines()[-40:])
    if args.json_out:
        emit_json({"action": "test", "kind": "python", "target": target,
                   "module": module, "cmd": cmd, "exit": code, "verdict": verdict,
                   "elapsedSec": round(elapsed, 2), "outputTail": tail})
    else:
        print(f"TEST {verdict} {target} ({elapsed:.1f}s)")
        print(tail)
    return code if code is not None else 4


# --- clean-stale -------------------------------------------------------------

def journal_idle_minutes(task):
    raw = task.get("updatedAtUtc") or ""
    try:
        dt = datetime.datetime.fromisoformat(raw.replace("Z", "+00:00"))
        return max(0.0, (utc_now() - dt).total_seconds() / 60.0)
    except ValueError:
        return None


def cmd_clean_stale(args):
    payload = {"action": "clean-stale", "dryRun": not args.execute,
               "staleMinutes": args.stale_minutes, "leases": {}, "journals": {}}

    lease_cmd = [sys.executable, "-B", str(ROOT / "scripts" / "agent_scope_lease.py"), "reclaim"]
    if args.include_orphan:
        lease_cmd.append("--include-orphan")
    if not args.execute:
        lease_cmd.append("--dry-run")
    code, out, _ = run_cmd(lease_cmd, timeout=120)
    payload["leases"] = {"exit": code, "result": last_json_line(out) or out.strip()[:500]}

    code, out, _ = run_cmd([sys.executable, "-B", str(ROOT / "scripts" / "work_journal.py"),
                            "list", "--active"], timeout=60)
    listing = last_json_line(out) or {}
    tasks = listing.get("tasks", [])
    stale, protected_recent, closable = [], [], []
    for t in tasks:
        idle = journal_idle_minutes(t)
        entry = {"taskId": t.get("taskId"), "agent": t.get("agent"),
                 "idleMinutes": round(idle, 1) if idle is not None else None}
        if idle is None:
            protected_recent.append(entry)
        elif idle >= args.stale_minutes:
            stale.append(entry)
        elif idle >= args.protect_minutes:
            protected_recent.append(entry)
            if args.agent and t.get("agent") == args.agent:
                closable.append(entry)
    payload["journals"] = {"activeCount": len(tasks), "stale": stale,
                           "idleBeyondProtectMinutes": protected_recent,
                           "closableOwned": closable if args.agent else [],
                           "closed": []}

    if args.execute and args.agent:
        for entry in closable:
            c, o, _ = run_cmd([sys.executable, "-B", str(ROOT / "scripts" / "work_journal.py"),
                               "close", "--task", entry["taskId"], "--result", "superseded",
                               "--summary", f"clean-stale: owned journal idle {entry['idleMinutes']}m"],
                              timeout=60)
            payload["journals"]["closed"].append({"taskId": entry["taskId"], "exit": c,
                                                  "ok": c == 0})
            if c != 0:
                payload["journals"]["closed"][-1]["output"] = o.strip()[:300]

    if args.json_out:
        emit_json(payload)
    else:
        mode = "EXECUTE" if args.execute else "DRY-RUN (add --execute to apply)"
        print(f"clean-stale [{mode}]")
        lease_res = payload["leases"]["result"]
        print(f"leases: exit={payload['leases']['exit']} "
              f"{json.dumps(lease_res, ensure_ascii=True)[:400] if isinstance(lease_res, dict) else lease_res}")
        j = payload["journals"]
        print(f"journals: active={j['activeCount']} stale(>={args.stale_minutes}m)={len(j['stale'])} "
              f"idle>={args.protect_minutes}m={len(j['idleBeyondProtectMinutes'])}")
        for e in j["stale"]:
            print(f"  STALE  {e['taskId']} agent={e['agent']} idle={e['idleMinutes']}m")
        for e in j["closed"]:
            print(f"  CLOSED {e['taskId']} exit={e['exit']}")
        if not args.execute:
            print("note: reclaim is stale-only (live leases never touched); "
                  "journal close needs --execute --agent <your-name>")
    return 0


# --- scan-log ----------------------------------------------------------------

def scan_log_python(path, limit):
    counts = {}
    samples = []
    total = matched = 0
    t0 = utc_now()
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            total += 1
            hit = [pid for pid, rx in LOG_PATTERNS if rx.search(line)]
            if hit:
                matched += 1
                for pid in hit:
                    counts[pid] = counts.get(pid, 0) + 1
                if len(samples) < limit:
                    samples.append({"line": total, "pattern": hit[0],
                                    "text": line.strip()[:240]})
    return {"engine": "python-fallback", "file": str(path), "totalLines": total,
            "matchedLines": matched, "elapsedMs": int((utc_now() - t0).total_seconds() * 1000),
            "counts": counts, "topMatches": samples}


def cmd_scan_log(args):
    path = Path(args.logfile)
    if not path.is_file():
        print(f"scan-log: file-not-found {path}", file=sys.stderr)
        return 2
    if SCAN_MJS.is_file() and shutil.which("node"):
        cmd = ["node", str(SCAN_MJS), "--file", str(path), "--limit", str(args.limit)]
        if args.json_out:
            cmd.append("--json")
        code, out, _ = run_cmd(cmd, timeout=120)
        sys.stdout.write(out if out.endswith("\n") or not out else out + "\n")
        return code if code is not None else 3
    result = scan_log_python(path, args.limit)
    if args.json_out:
        emit_json(result)
    else:
        print(f"scan-log [{result['engine']}] {result['file']}")
        print(f"lines={result['totalLines']} matched={result['matchedLines']} "
              f"elapsed={result['elapsedMs']}ms")
        for k, v in sorted(result["counts"].items()):
            print(f"  {k}: {v}")
        for s in result["topMatches"]:
            print(f"  L{s['line']} [{s['pattern']}] {s['text']}")
    return 0


# --- main --------------------------------------------------------------------

def build_parser():
    parser = argparse.ArgumentParser(prog="Sub-Tool.bat",
                                     description="Agent quick sub-tool (P11/P7/P1 helpers)")
    parser.add_argument("--json", action="store_true", help="JSON output (auto in AWX_AGENT/CI)")
    sub = parser.add_subparsers(dest="cmd")

    p = sub.add_parser("help", help="show usage")
    p.add_argument("--json", action="store_true")

    for name in ("hash", "precheck", "lint"):
        p = sub.add_parser(name)
        p.add_argument("files", nargs="+")
        p.add_argument("--json", action="store_true")

    p = sub.add_parser("test")
    p.add_argument("target")
    p.add_argument("--timeout", type=int, default=600)
    p.add_argument("--json", action="store_true")

    p = sub.add_parser("clean-stale")
    p.add_argument("--execute", action="store_true")
    p.add_argument("--agent", default=None)
    p.add_argument("--stale-minutes", type=int, default=1440)
    p.add_argument("--protect-minutes", type=int, default=30)
    p.add_argument("--include-orphan", action="store_true")
    p.add_argument("--json", action="store_true")

    p = sub.add_parser("scan-log")
    p.add_argument("logfile")
    p.add_argument("--limit", type=int, default=10)
    p.add_argument("--json", action="store_true")
    return parser


def main(argv=None):
    try:
        sys.stdout.reconfigure(errors="replace")
        sys.stderr.reconfigure(errors="replace")
    except (AttributeError, OSError):
        pass
    args = build_parser().parse_args(argv)
    args.json_out = bool(getattr(args, "json", False)
                         or os.environ.get("AWX_AGENT") or os.environ.get("CI"))
    if args.cmd in (None, "help"):
        if args.json_out:
            emit_json({"action": "help", "commands":
                       ["hash", "lint", "test", "precheck", "clean-stale", "scan-log"]})
        else:
            print(HELP_TEXT)
        return 0
    return {"hash": cmd_hash, "precheck": cmd_precheck, "lint": cmd_lint,
            "test": cmd_test, "clean-stale": cmd_clean_stale,
            "scan-log": cmd_scan_log}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
