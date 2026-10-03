"""Multi-writer status-doc append/update helper: CAS rebase loop for shared markdown rows.

status_doc.py already refuses a stale --expect-sha256 (conflict, exit 2). This
wrapper adds the W1 recovery path seen in live journals (foreign writer landed
between read and append): read fresh, re-apply the row, bounded retries, and a
machine-readable rebase guide other agents can follow.

  python -B scripts/agent_harmony_status_cas.py check  --file docs/PROJECT_STATUS.md --key <k>
  python -B scripts/agent_harmony_status_cas.py append --file docs/PROJECT_STATUS.md --after-key <anchor> --line-file <f> [--tries 3]
  python -B scripts/agent_harmony_status_cas.py update --file docs/PROJECT_STATUS.md --key <k> --line-file <f> [--tries 3]

check: current sha256 + matching rows + mtime. Exit 0.
append/update: fresh read -> status_doc row mutate -> on conflict re-read and
retry up to --tries. Exit 0 applied; 2 still-conflicting or error.
Read/check are free of side effects; only append/update touch the file.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import status_doc  # noqa: E402

SCHEMA = "awx.agent-harmony.status-cas.v1"
REBASE_GUIDE = ("status doc changed between read and write: re-run `status_doc.py read` "
                "for a fresh sha256, merge your row against the newest lines, then retry "
                "with --expect-sha256. Never overwrite the foreign delta.")


def emit(payload: dict, code: int) -> int:
    print(json.dumps({"schemaVersion": SCHEMA, **payload}, ensure_ascii=False))
    return code


def do_check(file: str, key: str | None) -> int:
    path, data, _ = status_doc.read_doc(file)
    payload = {"action": "check", "file": str(path), "sha256": status_doc.sha(data),
               "mtimeUtc": __import__("datetime").datetime.fromtimestamp(
                   path.stat().st_mtime, __import__("datetime").timezone.utc).isoformat()}
    if key:
        payload.update(status_doc.read(file, key))
    return emit(payload, 0)


def do_mutate(action: str, file: str, key: str | None, after_key: str | None,
              line: str, tries: int) -> int:
    attempts = 0
    conflicts = 0
    last = None
    while attempts < tries:
        attempts += 1
        fresh = status_doc.read_doc(file)
        sha = status_doc.sha(fresh[1])
        if action == "update":
            result = status_doc.update_row(file, key, line, sha)
        else:
            result = status_doc.append_row(file, after_key, line, sha)
        last = result
        if result["status"] == "applied":
            return emit({"action": action, "status": "applied", "attempts": attempts,
                         "conflictCount": conflicts, **result}, 0)
        conflicts += 1
    return emit({"action": action, "status": "conflict", "attempts": attempts,
                 "conflictCount": conflicts, "last": last, "rebaseGuide": REBASE_GUIDE}, 2)


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "append", "update"))
    parser.add_argument("--file", required=True)
    parser.add_argument("--key")
    parser.add_argument("--after-key")
    parser.add_argument("--line")
    parser.add_argument("--line-file")
    parser.add_argument("--tries", type=int, default=3)
    args = parser.parse_args(argv)
    try:
        if args.action == "check":
            return do_check(args.file, args.key)
        if args.line_file:
            line = Path(args.line_file).read_text(encoding="utf-8-sig").rstrip("\r\n")
        elif args.line == "-":
            line = sys.stdin.read().rstrip("\r\n")
        elif args.line:
            line = args.line
        else:
            return emit({"status": "error", "reason": "line-or-line-file-required"}, 2)
        if args.action == "update" and not args.key:
            return emit({"status": "error", "reason": "key-required"}, 2)
        if args.action == "append" and not args.after_key:
            return emit({"status": "error", "reason": "after-key-required"}, 2)
        return do_mutate(args.action, args.file, args.key, args.after_key, line,
                         max(1, args.tries))
    except (ValueError, OSError, KeyError) as failure:
        return emit({"status": "error", "reason": str(failure), "rebaseGuide": REBASE_GUIDE}, 2)


if __name__ == "__main__":
    sys.exit(main())
