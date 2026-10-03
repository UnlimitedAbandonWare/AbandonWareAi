"""Read-only counts of executor and pool lines in launcher logs.

Prints counts and file names. Matching line text stays out of the output.
Does not modify logs and does not start a server.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SCHEMA = "awx.max-push.executor-log-scan.v1"
PATTERNS = (
    "RejectedExecutionException",
    "SearchExecutor",
    "ThreadPoolExecutor",
    "queue capacity",
)
EXTS = {".log", ".out", ".err", ".txt"}


def scan_text(text: str, patterns: tuple[str, ...] = PATTERNS) -> dict[str, int]:
    counts = {p: 0 for p in patterns}
    for line in text.splitlines():
        for pattern in patterns:
            if pattern in line:
                counts[pattern] += 1
    return counts


def select_files(log_dir: Path, limit: int) -> list[Path]:
    if not log_dir.is_dir():
        return []
    files = [p for p in log_dir.rglob("*") if p.is_file() and p.suffix.lower() in EXTS]
    files.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    return files[:limit]


def scan_dir(log_dir: Path, limit: int, root: Path) -> dict:
    files = select_files(log_dir, limit)
    if not log_dir.is_dir():
        return {
            "schemaVersion": SCHEMA,
            "status": "not_observed",
            "filesScanned": 0,
            "counts": {p: 0 for p in PATTERNS},
            "files": [],
        }
    totals = {p: 0 for p in PATTERNS}
    names = []
    for path in files:
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        counts = scan_text(text)
        for key, value in counts.items():
            totals[key] += value
        try:
            rel = path.relative_to(root).as_posix()
        except ValueError:
            rel = path.name
        names.append(rel)
    return {
        "schemaVersion": SCHEMA,
        "status": "observed",
        "filesScanned": len(names),
        "counts": totals,
        "files": names,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--log-dir", default="var/rag-launcher")
    parser.add_argument("--limit", type=int, default=20)
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    report = scan_dir(root / args.log_dir, max(1, args.limit), root)
    json.dump(report, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
