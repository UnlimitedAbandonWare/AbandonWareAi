#!/usr/bin/env python3
"""Run a command whose stdout must be JSON, safely.

Captures stdout to --json-out <file> and stderr to a sibling .stderr file,
then prints exactly ONE summary line:

    {"ok":true|false,"exit":N,"json_path":"...","stderr_tail":"...<=300 chars"}

Code-mode rule of thumb: never JSON.parse a tool output that begins with
"Traceback" or "Warning: truncated". Re-run through this wrapper instead,
then parse the --json-out file.

Usage:
    python -B scripts/json_safe_run.py --json-out <path> [--cwd <dir>]
        [--timeout <sec>] [--require-json] -- <command...>

--require-json: also verifies the captured stdout starts with '{' or '['
and parses; ok=false when it does not.
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--json-out", required=True)
    ap.add_argument("--cwd", default=None)
    ap.add_argument("--timeout", type=int, default=600)
    ap.add_argument("--require-json", action="store_true")
    ap.add_argument("cmd", nargs=argparse.REMAINDER)
    args = ap.parse_args()

    cmd = args.cmd
    if cmd and cmd[0] == "--":
        cmd = cmd[1:]
    if not cmd:
        print(json.dumps({"ok": False, "exit": 2, "error": "no command after --"}))
        return 2

    out_path = Path(args.json_out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    err_path = out_path.with_suffix(out_path.suffix + ".stderr")

    try:
        proc = subprocess.run(cmd, cwd=args.cwd, capture_output=True,
                              timeout=args.timeout)
        code, stdout, stderr = proc.returncode, proc.stdout, proc.stderr
    except subprocess.TimeoutExpired as exc:
        code, stdout, stderr = 124, exc.stdout or b"", (exc.stderr or b"") + b" [timeout]"
    except OSError as exc:
        print(json.dumps({"ok": False, "exit": 2, "error": "spawn-failed",
                          "detail": str(exc)[:200]}))
        return 2

    out_path.write_bytes(stdout or b"")
    err_path.write_bytes(stderr or b"")

    json_ok = None
    if args.require_json:
        head = (stdout or b"").lstrip()[:1]
        json_ok = False
        if head in (b"{", b"["):
            try:
                json.loads(stdout.decode("utf-8", "replace"))
                json_ok = True
            except ValueError:
                json_ok = False

    tail = (stderr or b"").decode("utf-8", "replace")[-300:]
    summary = {"ok": code == 0 and json_ok is not False,
               "exit": code,
               "json_path": str(out_path),
               "stderr_tail": tail}
    if json_ok is not None:
        summary["json_valid"] = json_ok
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if code == 0 else (1 if code != 2 else 2)


if __name__ == "__main__":
    sys.exit(main())
