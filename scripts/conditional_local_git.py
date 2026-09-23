#!/usr/bin/env python3
"""Conditional local Git gate for the demo-1 canonical root.

The policy body is `.grok/rules/demo1-conditional-local-git.md`.
This tool classifies git commands and scans staged blobs before a local commit.
It does not push, rewrite history, unstage foreign paths, or delete `.git`.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

CANONICAL = Path(r"C:\AbandonWare\demo-1\demo-1\src")
POLICY_REL = Path(".grok/rules/demo1-conditional-local-git.md")
MAX_BLOB = 1_000_000
SCHEMA = "awx.conditional-local-git.v1"

READ_ONLY = {"status", "diff", "rev-parse", "ls-files", "show", "log", "version", "help"}
FORBIDDEN_CMDS = {
    "push", "pull", "fetch", "merge", "rebase", "cherry-pick", "revert",
    "clean", "stash", "init", "clone", "remote", "reset", "switch",
    "filter-branch", "filter-repo", "gc", "prune", "repack",
}
SECRET_PATTERNS = (
    ("private-key", re.compile(br"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----")),
    ("aws-access-key", re.compile(br"\bAKIA[0-9A-Z]{16}\b")),
    ("github-pat", re.compile(br"\bgithub_pat_[A-Za-z0-9_]{20,}\b")),
    ("slack-token", re.compile(br"\bxox[baprs]-[A-Za-z0-9-]{10,}\b")),
    ("openai-sk", re.compile(br"\bsk-(?!local\b)[A-Za-z0-9]{20,}\b")),
)
LABELS = (
    ("이유:", "Reason:"),
    ("검증:", "Verify:"),
    ("제약:", "Constraint:"),
)


def emit(payload: dict, code: int) -> int:
    payload.setdefault("schemaVersion", SCHEMA)
    print(json.dumps(payload, ensure_ascii=True, sort_keys=True))
    return code


def git(repo: Path, args: list[str]) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        capture_output=True,
        check=False,
    )


def text(data: bytes) -> str:
    return data.decode("utf-8", errors="replace").strip()


def policy_path() -> Path:
    return Path(__file__).resolve().parents[1] / POLICY_REL


def repo_allowed(repo: Path) -> bool:
    if repo.resolve() == CANONICAL.resolve():
        return True
    proc = git(repo, ["config", "--local", "--get", "demo1.gittest"])
    return proc.returncode == 0 and text(proc.stdout) == "fixture"


def git_dir(repo: Path) -> Path | None:
    proc = git(repo, ["rev-parse", "--absolute-git-dir"])
    if proc.returncode != 0:
        proc = git(repo, ["rev-parse", "--git-dir"])
        if proc.returncode != 0:
            return None
        path = Path(text(proc.stdout))
        return path if path.is_absolute() else (repo / path)
    return Path(text(proc.stdout))


def normalize_argv(argv: list[str]) -> list[str]:
    if not argv:
        return []
    if Path(argv[0]).name.lower() in {"git", "git.exe"}:
        argv = argv[1:]
    out: list[str] = []
    index = 0
    while index < len(argv):
        item = argv[index]
        if item in {"-C", "-c"} and index + 1 < len(argv):
            index += 2
            continue
        if item.startswith(("--git-dir", "--work-tree")) or (item.startswith("-C") and item != "-c"):
            index += 1
            continue
        out.append(item)
        index += 1
    return out


def classify(argv: list[str]) -> dict:
    args = normalize_argv(argv)
    if not args:
        return {"verdict": "forbid", "reason": "empty-command"}
    command = args[0]
    if command.startswith("-") or command in {"config", "credential"}:
        return {"verdict": "forbid", "reason": "forbidden-git-config"}
    if command in FORBIDDEN_CMDS:
        return {"verdict": "forbid", "reason": f"forbidden-{command}"}
    if command in {"checkout", "restore"}:
        return {"verdict": "forbid", "reason": "forbidden-worktree-restore"}
    if command in READ_ONLY:
        return {"verdict": "allow-local", "reason": "read-only"}
    if command == "add":
        return classify_add(args[1:])
    if command == "commit":
        return classify_commit(args[1:])
    return {"verdict": "forbid", "reason": "unlisted-command"}


def classify_add(args: list[str]) -> dict:
    flags: list[str] = []
    paths: list[str] = []
    after_dash = False
    for item in args:
        if item == "--":
            after_dash = True
            continue
        if not after_dash and item.startswith("-"):
            flags.append(item)
        else:
            paths.append(item)
    if flags or not paths or any(item in {".", "./"} for item in paths):
        return {"verdict": "forbid", "reason": "forbidden-broad-add"}
    if any(path_forbidden(item) for item in paths):
        return {"verdict": "forbid", "reason": "forbidden-path"}
    return {"verdict": "needs-scan", "reason": "selective-add"}


def classify_commit(args: list[str]) -> dict:
    blocked = {"-a", "--all", "--amend", "--no-verify", "-n"}
    if any(item in blocked for item in args):
        return {"verdict": "forbid", "reason": "forbidden-commit-bypass"}
    return {"verdict": "needs-scan", "reason": "local-commit"}


def path_forbidden(path: str) -> bool:
    normal = path.replace("\\", "/")
    if normal.startswith("../") or normal == ".." or Path(normal).is_absolute():
        return True
    lowered = normal.lower()
    while lowered.startswith("./"):
        lowered = lowered[2:]
    name = Path(lowered).name
    if lowered.startswith(".secrets/") or "/.secrets/" in f"/{lowered}":
        return True
    if name.startswith(".env") or name.startswith("apikey"):
        return True
    if name.endswith((".pem", ".key", ".pfx", ".p12", ".jks")):
        return True
    if "lmsdb" in name or lowered.startswith(".git/") or "/.git/" in f"/{lowered}":
        return True
    return False


def identity_present(repo: Path) -> bool:
    name = git(repo, ["config", "--get", "user.name"])
    email = git(repo, ["config", "--get", "user.email"])
    return (
        name.returncode == 0
        and bool(text(name.stdout))
        and email.returncode == 0
        and "@" in text(email.stdout)
    )


def staged_changes(repo: Path) -> tuple[list[dict], str | None]:
    head = git(repo, ["rev-parse", "--verify", "HEAD"])
    if head.returncode != 0:
        proc = git(repo, ["status", "--porcelain=v1", "-z", "--untracked-files=no"])
        if proc.returncode != 0:
            return [], "status-failed"
        return porcelain_records(repo, proc.stdout), None
    proc = git(repo, ["diff", "--cached", "--name-status", "-z"])
    if proc.returncode != 0:
        return [], "diff-cached-failed"
    return name_status_records(repo, proc.stdout), None


def porcelain_records(repo: Path, payload: bytes) -> list[dict]:
    parts = [item for item in payload.split(b"\0") if item]
    records: list[dict] = []
    index = 0
    while index < len(parts):
        rec = parts[index]
        index += 1
        if len(rec) < 4:
            continue
        status = rec[:2].decode("ascii", "replace")
        path = rec[3:].decode("utf-8", "surrogateescape")
        if status[0] in {"R", "C"} and index < len(parts):
            path = parts[index].decode("utf-8", "surrogateescape")
            index += 1
        if status[0] in {" ", "?"}:
            continue
        records.append(entry(repo, status[0], path))
    return records


def name_status_records(repo: Path, payload: bytes) -> list[dict]:
    parts = [item for item in payload.split(b"\0") if item]
    records: list[dict] = []
    index = 0
    while index < len(parts):
        status = parts[index].decode("utf-8", "surrogateescape")
        index += 1
        if index >= len(parts):
            break
        path = parts[index].decode("utf-8", "surrogateescape")
        index += 1
        code = status[:1]
        if code in {"R", "C"} and index < len(parts):
            path = parts[index].decode("utf-8", "surrogateescape")
            index += 1
        records.append(entry(repo, code, path))
    return records


def entry(repo: Path, code: str, path: str) -> dict:
    row = {
        "code": code,
        "path": path.replace("\\", "/"),
        "mode": "",
        "sha": "",
        "forbidden": path_forbidden(path),
    }
    if code == "D":
        return row
    listed = git(repo, ["ls-files", "--stage", "-z", "--", path])
    if listed.returncode != 0 or not listed.stdout:
        row["sha"] = "missing"
        return row
    stages = [item for item in listed.stdout.split(b"\0") if item]
    if len(stages) != 1:
        row["code"] = "U"
        return row
    meta, _found = stages[0].split(b"\t", 1)
    mode, sha, stage = meta.decode("ascii", "replace").split()
    if stage != "0":
        row["code"] = "U"
    row["mode"] = mode
    row["sha"] = sha
    return row


def snapshot(records: list[dict]) -> str:
    body = "\n".join(
        f"{row['code']} {row['mode']} {row['sha']} {row['path']}" for row in records
    )
    return hashlib.sha256(body.encode("utf-8")).hexdigest()


def scan_records(repo: Path, records: list[dict]) -> dict:
    counts = {name: 0 for name, _pattern in SECRET_PATTERNS}
    blocked: list[str] = []
    oversized: list[str] = []
    for row in records:
        if row["forbidden"] or row["code"] == "U":
            blocked.append(row["path"])
        if row["mode"].startswith("120") or row["mode"].startswith("160"):
            blocked.append(row["path"])
        if row["code"] == "D" or not row["sha"] or row["sha"] == "missing":
            continue
        blob = git(repo, ["cat-file", "blob", row["sha"]])
        if blob.returncode != 0:
            blocked.append(row["path"])
            continue
        if len(blob.stdout) > MAX_BLOB:
            oversized.append(row["path"])
            continue
        for name, pattern in SECRET_PATTERNS:
            counts[name] += len(pattern.findall(blob.stdout))
    return {
        "secretCounts": counts,
        "secretTotal": sum(counts.values()),
        "blockedPaths": sorted(set(blocked)),
        "oversizedPaths": oversized,
    }


def inspect_index(repo: Path, expected: list[str] | None) -> dict:
    if not repo_allowed(repo):
        return {"ok": False, "exit": 3, "reason": "repo-not-allowed"}
    directory = git_dir(repo)
    if directory is None or not (directory / "HEAD").exists():
        return {"ok": False, "exit": 3, "reason": "not-a-git-repo"}
    lock = (directory / "index.lock").exists()
    if not identity_present(repo):
        return {"ok": False, "exit": 3, "reason": "missing-git-identity", "indexLock": lock}
    records, error = staged_changes(repo)
    if error:
        return {"ok": False, "exit": 3, "reason": error, "indexLock": lock}
    scanned = scan_records(repo, records)
    paths = [row["path"] for row in records]
    wanted = None if expected is None else sorted(item.replace("\\", "/") for item in expected)
    foreign: list[str] = []
    if wanted is not None and sorted(paths) != wanted:
        foreign = sorted(set(paths) - set(wanted))
    reason = "ok"
    code = 0
    if lock:
        reason, code = "index-lock", 4
    elif scanned["blockedPaths"] or scanned["oversizedPaths"]:
        reason, code = "blocked-path", 3
    elif scanned["secretTotal"]:
        reason, code = "secret-found", 2
    elif wanted is not None and sorted(paths) != wanted:
        reason, code = "foreign-or-mismatched-staging", 2
    return {
        "ok": code == 0,
        "exit": code,
        "reason": reason,
        "indexLock": lock,
        "stagedPaths": paths,
        "foreignPaths": foreign,
        "snapshot": snapshot(records),
        "identityPresent": True,
        **scanned,
    }


def message_ok(path: Path) -> bool:
    if not path.is_file():
        return False
    body = path.read_text(encoding="utf-8")
    return all(any(label in body for label in group) for group in LABELS)


def do_commit(repo: Path, message_file: Path, paths: list[str]) -> dict:
    first = inspect_index(repo, paths)
    if not first["ok"]:
        return first
    if not message_ok(message_file):
        return {"ok": False, "exit": 2, "reason": "message-missing-reason-verify-constraint"}
    again = inspect_index(repo, paths)
    if again.get("snapshot") != first.get("snapshot"):
        return {"ok": False, "exit": 4, "reason": "index-changed-during-scan"}
    proc = git(repo, ["commit", "-F", str(message_file)])
    if proc.returncode != 0:
        return {"ok": False, "exit": 2, "reason": "commit-failed", "gitExit": proc.returncode}
    head = git(repo, ["rev-parse", "HEAD"])
    remaining = inspect_index(repo, [])
    if remaining.get("stagedPaths"):
        return {
            "ok": False,
            "exit": 4,
            "reason": "index-changed-after-commit",
            "commit": text(head.stdout),
        }
    return {
        "ok": True,
        "exit": 0,
        "reason": "committed",
        "commit": text(head.stdout),
        "snapshot": first["snapshot"],
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Conditional local Git gate")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("policy")
    check = sub.add_parser("check")
    check.add_argument("git_args", nargs=argparse.REMAINDER)
    scan = sub.add_parser("scan")
    scan.add_argument("--repo", required=True)
    scan.add_argument("--path", action="append", default=[])
    commit = sub.add_parser("commit")
    commit.add_argument("--repo", required=True)
    commit.add_argument("--message-file", required=True)
    commit.add_argument("--path", action="append", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.cmd == "policy":
        path = policy_path()
        if not path.is_file():
            return emit({"ok": False, "reason": "policy-missing"}, 3)
        sys.stdout.write(path.read_text(encoding="utf-8"))
        return 0
    if args.cmd == "check":
        git_args = list(args.git_args)
        if git_args[:1] == ["--"]:
            git_args = git_args[1:]
        result = classify(git_args)
        code = 2 if result["verdict"] == "forbid" else 0
        return emit({"ok": result["verdict"] != "forbid", **result}, code)
    repo = Path(args.repo)
    if args.cmd == "scan":
        result = inspect_index(repo, args.path or None)
        visible = {key: value for key, value in result.items() if key != "exit"}
        return emit(visible, result["exit"])
    result = do_commit(repo, Path(args.message_file), args.path)
    visible = {key: value for key, value in result.items() if key != "exit"}
    return emit(visible, result["exit"])


if __name__ == "__main__":
    sys.exit(main())
