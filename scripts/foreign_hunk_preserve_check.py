#!/usr/bin/env python3
"""Snapshot foreign git-diff line hashes and check that those lines remain.

snapshot records SHA-256 of added and deleted unified-diff lines for the
named paths. check exits 0 when every snapshotted hash is still present in
the current diff. Lines added after the snapshot are ignored. Exit 4 prints
the lost file and hunk header only. File bodies are never printed.

This is not a merge tool. codex_work_checkpoint.py apply/restore refuses a
whole-file hash mismatch; it does not test whether an earlier foreign hunk
is still inside a later diff.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from collections import Counter
from pathlib import Path

from awx_paths import resolve as _awx_resolve

SCHEMA = "awx.foreign-hunk-preserve.v1"


def git_bin() -> str:
    override = os.environ.get("AWX_GIT") or ""
    for candidate in (override, str(_awx_resolve("git.exe")), "git"):
        if not candidate:
            continue
        if candidate == "git" or Path(candidate).is_file():
            return candidate
    return "git"


def line_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def run_git(root: Path, args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [git_bin(), "--no-optional-locks", "-C", str(root), *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace")


def hunk_header(line: str) -> str:
    parts = line.split("@@")
    if len(parts) >= 3:
        return "@@" + parts[1] + "@@"
    return line


def parse_unified(diff_text: str) -> list[dict]:
    hunks: list[dict] = []
    header = None
    added: list[str] = []
    deleted: list[str] = []

    def flush() -> None:
        nonlocal header, added, deleted
        if header is None:
            return
        hunks.append({"header": header, "added": added, "deleted": deleted})
        header, added, deleted = None, [], []

    for line in diff_text.splitlines():
        if line.startswith("diff --git ") or line.startswith("index "):
            continue
        if line.startswith("--- a/") or line.startswith("+++ b/") or line.startswith("--- /dev/null") or line.startswith("+++ /dev/null"):
            continue
        if line.startswith("@@"):
            flush()
            header = hunk_header(line)
            added, deleted = [], []
            continue
        if line.startswith("\\"):
            continue
        if header is None:
            continue
        if line.startswith("+"):
            added.append(line_hash(line[1:]))
        elif line.startswith("-"):
            deleted.append(line_hash(line[1:]))
    flush()
    return hunks


def porcelain_untracked(root: Path, path: str) -> bool:
    status = run_git(root, ["status", "--porcelain", "--", path])
    if status.returncode != 0:
        raise RuntimeError("git-status-failed")
    for row in status.stdout.splitlines():
        if row.startswith("??"):
            return True
    return False


def load_diff(root: Path, path: str) -> str:
    if porcelain_untracked(root, path):
        file_path = root / path
        if not file_path.is_file():
            return ""
        lines = file_path.read_text(encoding="utf-8", errors="replace").splitlines()
        return "@@ untracked @@\n" + "\n".join("+" + line for line in lines)
    diff = run_git(root, ["diff", "--no-ext-diff", "-U0", "HEAD", "--", path])
    if diff.returncode != 0:
        raise RuntimeError("git-diff-failed")
    return diff.stdout


def snapshot_files(root: Path, paths: list[str]) -> dict:
    files = []
    for path in paths:
        text = load_diff(root, path)
        files.append({"path": path, "hunks": parse_unified(text)})
    return {"schemaVersion": SCHEMA, "files": files}


def lost_hunks(root: Path, snap: dict) -> list[dict]:
    lost = []
    for item in snap.get("files") or []:
        path = str(item.get("path") or "")
        current = parse_unified(load_diff(root, path))
        have_added: Counter[str] = Counter()
        have_deleted: Counter[str] = Counter()
        for hunk in current:
            have_added.update(hunk["added"])
            have_deleted.update(hunk["deleted"])
        for hunk in item.get("hunks") or []:
            missing_added = 0
            missing_deleted = 0
            for digest, count in Counter(hunk.get("added") or []).items():
                got = have_added[digest]
                if got < count:
                    missing_added += count - got
                have_added[digest] = max(0, got - count)
            for digest, count in Counter(hunk.get("deleted") or []).items():
                got = have_deleted[digest]
                if got < count:
                    missing_deleted += count - got
                have_deleted[digest] = max(0, got - count)
            if missing_added or missing_deleted:
                lost.append({
                    "path": path,
                    "header": hunk.get("header") or "@@",
                    "missingAdded": missing_added,
                    "missingDeleted": missing_deleted,
                })
    return lost


def emit(payload: dict, as_json: bool) -> None:
    if as_json:
        print(json.dumps(payload, ensure_ascii=True))
        return
    lost = payload.get("lost") or []
    if lost:
        for row in lost:
            print(
                f"LOST path={row['path']} header={row['header']} "
                f"missingAdded={row['missingAdded']} missingDeleted={row['missingDeleted']}"
            )
        return
    print(f"PRESERVED files={payload.get('files', 0)}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Check that snapshotted foreign diff hunks are still present. "
                    "Later added lines are ignored. Line text is not printed.")
    parser.add_argument("--root", default=".")
    sub = parser.add_subparsers(dest="action", required=True)

    snap = sub.add_parser("snapshot", help="Record added/deleted line hashes")
    snap.add_argument("--out", required=True)
    snap.add_argument("--path", action="append", required=True)

    check = sub.add_parser("check", help="Exit 0 if snapshot hunks remain, else 4")
    check.add_argument("--snapshot", required=True)
    check.add_argument("--json", action="store_true")

    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    try:
        if args.action == "snapshot":
            payload = snapshot_files(root, list(args.path))
            out = Path(args.out)
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(json.dumps(payload, ensure_ascii=True, indent=2) + "\n",
                           encoding="utf-8")
            print(f"SNAPSHOT files={len(payload['files'])} out={out}")
            return 0
        snap_payload = json.loads(Path(args.snapshot).read_text(encoding="utf-8"))
        lost = lost_hunks(root, snap_payload)
        emit({
            "schemaVersion": SCHEMA,
            "files": len(snap_payload.get("files") or []),
            "lost": lost,
        }, args.json)
        return 4 if lost else 0
    except (OSError, RuntimeError, json.JSONDecodeError) as failure:
        print(f"ERROR {failure.__class__.__name__}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
