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

from awx_paths import resolve as _awx_resolve

CANONICAL = _awx_resolve("repo.root")
POLICY_REL = Path(".grok/rules/demo1-conditional-local-git.md")
MAX_BLOB = 1_000_000
MAX_COMMIT_PATHS = 40
MAX_COMMIT_DELETIONS = 15
INTENDED_REMOTE = "AbandonWareAi"
INTENDED_REMOTE_URL = "https://github.com/UnlimitedAbandonWare/AbandonWareAi"
ALLOWED_REMOTE_NAME = "origin"
SCHEMA = "awx.conditional-local-git.v1"
LOCK_MARKER_PREFIX = b"conditional-local-git:"  # commit_selected가 index.lock에 쓰는 소유 marker
MARKER_LOCK_SETTLE_SECONDS = 1.0  # marker lock 회수 전 관찰 정착 창(진행 중 publish 노출용)
DEFAULT_STALE_LOCK_DAYS = 0.25  # 6h: lock 서브커맨드와 오케스트레이터가 공유하는 TTL SSOT

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


GIT_FALLBACKS = (
    str(_awx_resolve("git.exe")),
    r"C:\Program Files\Git\cmd\git.exe",
    r"C:\Program Files (x86)\Git\cmd\git.exe",
)
_GIT_EXE: str | None = None


def git_exe() -> str:
    """Resolve git without asking: PATH first, then known install locations."""
    global _GIT_EXE
    if _GIT_EXE:
        return _GIT_EXE
    import shutil

    found = shutil.which("git")
    if not found:
        for candidate in GIT_FALLBACKS:
            if Path(candidate).is_file():
                found = candidate
                break
    _GIT_EXE = found or "git"
    return _GIT_EXE


def git(repo: Path, args: list[str]) -> subprocess.CompletedProcess[bytes]:
    try:
        return subprocess.run(
            [git_exe(), "-C", str(repo), *args],
            capture_output=True,
            check=False,
        )
    except OSError:
        return subprocess.CompletedProcess(args, 127, b"", b"git-not-found")


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
    parts = lowered.split("/")
    if "__pycache__" in parts:
        return True
    if name.endswith(".pyc") or (name.endswith(".class") and "build" in parts):
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
    missing: list[str] = []
    for row in records:
        if row["forbidden"] or row["code"] == "U":
            blocked.append(row["path"])
        if row["mode"].startswith("120") or row["mode"].startswith("160"):
            blocked.append(row["path"])
        if row["code"] == "D":
            continue
        if not row["sha"] or row["sha"] == "missing":
            missing.append(row["path"])
            continue
        blob = git(repo, ["cat-file", "blob", row["sha"]])
        if blob.returncode != 0:
            missing.append(row["path"])
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
        "missingBlobPaths": sorted(set(missing)),
        "oversizedPaths": oversized,
    }


def worktree_counts(repo: Path) -> dict:
    """Whole-worktree dirty counts; informational only, never a hard gate."""
    counts = {"total": 0, "staged": 0, "unstaged": 0, "untracked": 0,
              "deleted": 0, "conflicted": 0}
    proc = git(repo, ["status", "--porcelain=v1", "-z"])
    if proc.returncode != 0:
        return {"error": "status-failed"}
    parts = [item for item in proc.stdout.split(b"\0") if item]
    index = 0
    while index < len(parts):
        rec = parts[index]
        index += 1
        if len(rec) < 4:
            continue
        status = rec[:2].decode("ascii", "replace")
        if status[0] in {"R", "C"} and index < len(parts):
            index += 1
        counts["total"] += 1
        if status == "??":
            counts["untracked"] += 1
            continue
        if "U" in status or status in {"AA", "DD"}:
            counts["conflicted"] += 1
        if status[0] != " ":
            counts["staged"] += 1
        if status[1] != " ":
            counts["unstaged"] += 1
        if "D" in status:
            counts["deleted"] += 1
    return counts


def remote_status(repo: Path) -> dict:
    """Remote observation feeding the commit gate; emits flags only, never URLs.

    Sole-remote allowlist: the only permitted remote is `origin` pointing at
    the intended upstream URL. Any other remote (any name, fetch or push)
    sets `forbiddenRemote`; an `origin` whose fetch or push URL differs from
    the sole intended remote sets `originMismatch`. Observation only —
    never mutates config.
    """
    proc = git(repo, ["remote"])
    names = ([line.strip() for line in text(proc.stdout).splitlines() if line.strip()]
             if proc.returncode == 0 else [])
    origin_urls: list[str] = []
    if ALLOWED_REMOTE_NAME in names:
        for extra in ([], ["--push"]):
            got = git(repo, ["remote", "get-url", *extra, ALLOWED_REMOTE_NAME])
            if got.returncode != 0:
                continue
            url = text(got.stdout).rstrip("/").lower()
            if url.endswith(".git"):
                url = url[:-4]
            origin_urls.append(url)
    return {
        "intendedRemote": INTENDED_REMOTE,
        "forbiddenRemote": any(name != ALLOWED_REMOTE_NAME for name in names),
        "originMismatch": bool(origin_urls) and any(
            url != INTENDED_REMOTE_URL.lower() for url in origin_urls),
    }


def inspect_index(repo: Path, expected: list[str] | None) -> dict:
    if not repo_allowed(repo):
        return {"ok": False, "exit": 3, "reason": "repo-not-allowed"}
    if git(repo, ["version"]).returncode != 0:
        return {"ok": False, "exit": 3, "reason": "git-not-found"}
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
    deletions = sum(1 for row in records if row["code"] == "D")
    wanted = None if expected is None else sorted(item.replace("\\", "/") for item in expected)
    foreign: list[str] = []
    if wanted is not None and sorted(paths) != wanted:
        foreign = sorted(set(paths) - set(wanted))
    remote = remote_status(repo)
    reason = "ok"
    code = 0
    if remote["forbiddenRemote"]:
        reason, code = "forbidden-remote", 2
    elif remote["originMismatch"]:
        reason, code = "origin-mismatch", 2
    elif lock:
        reason, code = "index-lock", 4
    elif scanned["missingBlobPaths"]:
        reason, code = "missing-blob", 3
    elif scanned["blockedPaths"] or scanned["oversizedPaths"]:
        reason, code = "blocked-path", 3
    elif scanned["secretTotal"]:
        reason, code = "secret-found", 2
    elif len(paths) > MAX_COMMIT_PATHS:
        reason, code = "blast-radius-paths", 2
    elif deletions > MAX_COMMIT_DELETIONS:
        reason, code = "blast-radius-deletions", 2
    elif wanted is not None and sorted(paths) != wanted:
        reason, code = "foreign-or-mismatched-staging", 2
    return {
        "ok": code == 0,
        "exit": code,
        "reason": reason,
        "indexLock": lock,
        "stagedPaths": paths,
        "foreignPaths": foreign,
        "candidatePathCount": len(paths),
        "candidateDeletionCount": deletions,
        "worktreeCounts": worktree_counts(repo),
        **remote,
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
        return {"ok": False, "exit": 2, "reason": "commit-failed",
                "gitExit": proc.returncode,
                "errorDetail": git_stderr_tail(proc.stderr)}
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
        "candidatePathCount": first["candidatePathCount"],
        "candidateDeletionCount": first["candidateDeletionCount"],
        "worktreeCounts": first["worktreeCounts"],
        "intendedRemote": first["intendedRemote"],
        "originMismatch": first["originMismatch"],
    }



def redact_snippet(data: bytes, limit: int = 300) -> str:
    """짧은 진단 스니펫: 시크릿 패턴 치환 + 공백 압축 + 길이 상한."""
    for _name, pattern in SECRET_PATTERNS:
        data = pattern.sub(b"<redacted>", data)
    return " ".join(data.decode("utf-8", errors="replace").split())[:limit]


def index_sha256(directory: Path) -> str | None:
    index = directory / "index"
    return hashlib.sha256(index.read_bytes()).hexdigest() if index.exists() else None


def git_stderr_tail(data: bytes, limit: int = 240) -> str:
    """git stderr 꼬리 redact 스니펫(진단 필드용). 미기록 외부 편집이 참조하는 이름."""
    return redact_snippet(data[-4096:] if data else b"", limit)


def commit_selected(repo: Path, message_file: Path, paths: list[str]) -> dict:
    """Commit an isolated candidate and publish only owned entries to the shared index."""
    import os
    import tempfile
    import uuid

    def fail(reason: str, code: int = 4, **extra) -> dict:
        return {"ok": False, "exit": code, "reason": reason, **extra}

    # Never let ambient routing change which index/repository is protected.
    if any(os.environ.get(k) for k in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE",
                                      "GIT_COMMON_DIR", "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES")):
        return fail("ambient-git-routing")
    repo = repo.resolve()
    if not repo_allowed(repo):
        return fail("repo-not-allowed", 3)
    if git(repo, ["version"]).returncode != 0:
        return fail("git-not-found", 3)
    directory = git_dir(repo)
    if directory is None or not identity_present(repo):
        return fail("missing-repository-or-identity", 3)
    remote = remote_status(repo)
    if remote["forbiddenRemote"]:
        return fail("forbidden-remote", 2)
    if remote["originMismatch"]:
        return fail("origin-mismatch", 2)
    if not message_ok(message_file):
        return fail("message-missing-reason-verify-constraint", 2)
    wanted = set(paths)
    if len(wanted) != len(paths) or not wanted:
        return fail("invalid-selected-paths", 2)
    for name in wanted:
        p = Path(name)
        if (path_forbidden(name) or "\\" in name or ":" in name or p.as_posix() != name
                or any(x in ("", ".", "..") for x in name.split("/"))
                or (repo / p).is_dir() or not (repo / p).resolve().is_relative_to(repo)):
            return fail("invalid-selected-path", 2)
    candidate_paths = sorted(wanted)
    candidate_deletions = sum(1 for name in candidate_paths if not (repo / name).exists())
    blast = {"candidatePathCount": len(candidate_paths),
             "candidateDeletionCount": candidate_deletions,
             "worktreeCounts": worktree_counts(repo)}
    if len(candidate_paths) > MAX_COMMIT_PATHS:
        return fail("blast-radius-paths", 2, **blast)
    if candidate_deletions > MAX_COMMIT_DELETIONS:
        return fail("blast-radius-deletions", 2, **blast)
    environment = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    environment["GIT_LITERAL_PATHSPECS"] = "1"

    def run(args, index=None, data=None):
        env = dict(environment)
        if index is not None:
            env["GIT_INDEX_FILE"] = str(index)
        # A private index omits foreign-only staged objects: automatic GC must never prune them.
        proc = subprocess.run([git_exe(), "-c", "gc.auto=0", "-c", "maintenance.auto=false", "-C", str(repo), *args], input=data, env=env,
                              capture_output=True, timeout=120, check=False)
        if proc.returncode:
            detail = git_stderr_tail(proc.stderr)
            raise RuntimeError("selected-git-command-failed(exit="
                               + str(proc.returncode) + ")"
                               + (":" + detail if detail else ""))
        return proc.stdout

    def entries(index=None):
        rows = {}
        for row in run(["ls-files", "--stage", "-z"], index).split(b"\0"):
            if not row:
                continue
            meta, name = row.split(b"\t", 1)
            if meta.split()[2] != b"0":
                raise RuntimeError("unmerged-index")
            rows[name.decode("utf-8")] = row
        return rows

    shared = directory / "index"
    lock = directory / "index.lock"
    marker = ("conditional-local-git:" + uuid.uuid4().hex).encode()
    acquired = False
    committed = None
    try:
        if lock.exists():
            return fail("index-lock")
        original = shared.read_bytes() if shared.exists() else None
        before = entries()
        head_result = git(repo, ["rev-parse", "--verify", "HEAD"])
        old_head = text(head_result.stdout) if head_result.returncode == 0 else None
        foreign = {p: row for p, row in before.items() if p not in wanted}
        with tempfile.TemporaryDirectory(prefix="awx-selected-commit-") as tmp:
            candidate = Path(tmp) / "candidate-index"
            publication = Path(tmp) / "publication-index"
            run(["read-tree", old_head] if old_head else ["read-tree", "--empty"], candidate)
            run(["add", "--", *sorted(wanted)], candidate)
            expected = Path(tmp) / "paths.json"
            expected.write_text(json.dumps(sorted(wanted)), encoding="utf-8")
            scan_env = dict(environment, GIT_INDEX_FILE=str(candidate))
            scanner = Path(__file__).with_name("git_staged_guard.py")
            scanned = subprocess.run([sys.executable, "-B", str(scanner), "--root", str(repo),
                                      "--expected-paths-file", str(expected)],
                                     env=scan_env, capture_output=True, timeout=120, check=False)
            try:
                scan_result = json.loads(scanned.stdout)
            except ValueError:
                scan_result = None
            if scanned.returncode or not (scan_result or {}).get("ok"):
                # 불투명 실패 방지: reason/findings(경로는 sha256 해시만) + redact된 stderr 꼬리만 싣는다.
                detail: dict = {}
                if scanned.returncode:
                    detail["scanExit"] = scanned.returncode
                if isinstance(scan_result, dict):
                    detail["scanReason"] = scan_result.get("reason") or "guard-findings"
                    detail["scanFindings"] = scan_result.get("findings", [])[:20]
                    if scan_result.get("changedCount") is not None:
                        detail["scanChangedCount"] = scan_result.get("changedCount")
                else:
                    detail["scanReason"] = "scanner-output-unreadable"
                hint = redact_snippet(scanned.stderr)
                if hint:
                    detail["scanStderr"] = hint
                return fail("selected-staged-scan-failed", 2, **detail)
            candidate_tree = text(run(["write-tree"], candidate))
            selected = entries(candidate)
            if original is None:
                run(["read-tree", "--empty"], publication)
            else:
                publication.write_bytes(original)
            # Keep every foreign entry (including partial staging and flags); update only selected entries.
            updates = b""
            for name in sorted(wanted):
                row = selected.get(name)
                updates += (row if row else b"0 " + b"0" * 40 + b"\t" + name.encode("utf-8")) + b"\0"
            run(["update-index", "-z", "--index-info"], publication, updates)
            if {p: row for p, row in entries(publication).items() if p not in wanted} != foreign:
                return fail("foreign-index-publication-drift")
            # Standard index lock: never remove a pre-existing lock or steal another writer.
            with lock.open("xb") as owned_lock:
                acquired = True
                owned_lock.write(marker)
            if (shared.read_bytes() if shared.exists() else None) != original or entries() != before:
                return fail("shared-index-changed-before-commit")
            head_now = git(repo, ["rev-parse", "--verify", "HEAD"])
            if (text(head_now.stdout) if head_now.returncode == 0 else None) != old_head:
                return fail("head-changed-before-commit")
            run(["commit", "-F", str(message_file.resolve())], candidate)
            committed = text(run(["rev-parse", "HEAD"]))
            # Hooks remain enabled. A hook that unexpectedly changes the candidate is never hidden.
            if text(run(["rev-parse", "HEAD^{tree}"])) != candidate_tree:
                return fail("committed-tree-changed-by-hook", commit=committed)
            parents = text(run(["rev-list", "--parents", "-n", "1", "HEAD"])).split()[1:]
            if parents != ([] if old_head is None else [old_head]):
                return fail("head-parent-changed", commit=committed)
            if (shared.read_bytes() if shared.exists() else None) != original or lock.read_bytes() != marker:
                return fail("shared-index-changed-after-commit", commit=committed)
            with lock.open("wb") as owned_lock:
                owned_lock.write(publication.read_bytes())
                owned_lock.flush()
                os.fsync(owned_lock.fileno())
            os.replace(lock, shared)
            acquired = False
            after = entries()
            if {p: row for p, row in after.items() if p not in wanted} != foreign:
                return fail("foreign-index-changed-after-commit", commit=committed)
            if any(after.get(p) != selected.get(p) for p in wanted):
                return fail("owned-index-publication-mismatch", commit=committed)
            return {"ok": True, "exit": 0, "reason": "committed-selected", "commit": committed,
                    "foreignStagingPreserved": True, "foreignEntryCount": len(foreign),
                    "selectedPathCount": len(wanted), "snapshot": scan_result["indexIdentity"],
                    "candidatePathCount": len(candidate_paths),
                    "candidateDeletionCount": candidate_deletions,
                    "worktreeCounts": worktree_counts(repo)}
    except FileExistsError:
        return fail("index-lock")
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        return fail("selected-commit-failed", commit=committed,
                    errorType=type(error).__name__,
                    errorDetail=redact_snippet(str(error).encode("utf-8", "replace")))
    finally:
        if acquired and lock.exists() and lock.read_bytes() == marker:
            lock.unlink()  # This invocation's lock only; never a stale/foreign lock.


def git_writer_pids(repo: Path) -> set[int] | None:
    """git.exe PIDs that may write this repo; None when process listing is unobservable."""
    try:
        proc = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "Get-CimInstance Win32_Process -Filter \"Name='git.exe'\""
             " | Select-Object -Property ProcessId,CommandLine | ConvertTo-Json -Compress"],
            capture_output=True, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if proc.returncode != 0:
        return None
    raw = proc.stdout.decode("utf-8", "replace").strip()
    if not raw:
        return set()
    try:
        data = json.loads(raw)
    except ValueError:
        return None
    rows = data if isinstance(data, list) else ([data] if data else [])
    target = str(repo.resolve()).lower()
    pids: set[int] = set()
    for row in rows:
        cmd = str(row.get("CommandLine") or "").lower().replace("/", "\\")
        # An unreadable command line is treated as a possible writer (fail-safe).
        if not cmd or target in cmd:
            pid = row.get("ProcessId")
            if isinstance(pid, int):
                pids.add(pid)
    return pids


def git_writers(repo: Path) -> int | None:
    """Confirmed git writers: a PID must survive two samples ~0.6s apart.

    Freshly-exited git children can linger in the process list with an empty
    command line; requiring the same PID in both samples drops those zombies
    while a real writer (a `git add`/`commit` still running) persists.
    """
    import time

    first = git_writer_pids(repo)
    if first is None:
        return None
    if not first:
        return 0
    time.sleep(0.6)
    second = git_writer_pids(repo)
    if second is None:
        return None
    return len(first & second)


def clear_stale_lock(repo: Path, days: float, backup_dir: Path) -> dict:
    """Move a proven-stale index.lock into backup_dir; preserve otherwise.

    회수 가능한 형태는 두 가지: 나이가 `days`를 넘은 0-byte lock, 그리고
    본 도구의 `conditional-local-git:` marker를 담은 nonempty lock(작성자가
    commit 도중 죽은 자가고아)으로 writer=0 + index 해시 안정이 확인된 경우.
    그 외 nonempty lock은 기존대로 preserved.
    """
    import shutil
    import time

    if not repo_allowed(repo):
        return {"ok": False, "exit": 3, "action": "preserved", "reason": "repo-not-allowed"}
    if git(repo, ["version"]).returncode != 0:
        return {"ok": False, "exit": 3, "action": "preserved", "reason": "git-not-found"}
    directory = git_dir(repo)
    if directory is None:
        return {"ok": False, "exit": 3, "action": "preserved", "reason": "not-a-git-repo"}
    lock = directory / "index.lock"
    if not lock.exists():
        return {"ok": True, "exit": 0, "action": "absent", "reason": "no-lock"}
    stat = lock.stat()
    marker = False
    if stat.st_size != 0:
        try:
            with lock.open("rb") as handle:
                head = handle.read(64)
        except OSError:
            return {"ok": False, "exit": 4, "action": "preserved", "reason": "lock-unreadable"}
        if not head.startswith(LOCK_MARKER_PREFIX):
            return {"ok": False, "exit": 4, "action": "preserved", "reason": "lock-nonempty"}
        marker = True
    age_seconds = time.time() - stat.st_mtime
    if not marker and age_seconds < days * 86400:
        return {"ok": False, "exit": 4, "action": "preserved", "reason": "lock-fresh",
                "ageSeconds": int(age_seconds)}
    writers = git_writers(repo)
    if writers is None:
        return {"ok": False, "exit": 4, "action": "preserved", "reason": "writers-unobservable"}
    if writers:
        return {"ok": False, "exit": 4, "action": "preserved", "reason": "git-writer-active",
                "writers": writers}
    index_before = index_sha256(directory)
    if marker:
        # 진행 중인 selected-commit은 이 lock으로 index를 republish한다.
        # 정착 창 동안 index/lock 변화가 보이면 살아있는 writer로 간주해 보존.
        time.sleep(MARKER_LOCK_SETTLE_SECONDS)
    try:
        again = lock.stat()
    except OSError:
        return {"ok": False, "exit": 4, "action": "preserved", "reason": "lock-vanished"}
    if again.st_size != stat.st_size or again.st_mtime != stat.st_mtime:
        return {"ok": False, "exit": 4, "action": "preserved", "reason": "lock-changed"}
    if index_sha256(directory) != index_before:
        return {"ok": False, "exit": 4, "action": "preserved", "reason": "index-changed"}
    stamp = time.strftime("%Y%m%d", time.gmtime())
    backup_dir.mkdir(parents=True, exist_ok=True)
    dest = backup_dir / f"index.lock.bak-{stamp}"
    if dest.exists():
        dest = backup_dir / f"index.lock.bak-{time.strftime('%Y%m%d-%H%M%S', time.gmtime())}"
    try:
        shutil.move(str(lock), str(dest))
    except OSError:
        return {"ok": False, "exit": 3, "action": "preserved", "reason": "move-failed"}
    index_after = index_sha256(directory)
    return {"ok": True, "exit": 0, "action": "moved",
            "reason": "self-orphaned-marker-lock-archived" if marker else "stale-lock-archived",
            "backup": str(dest), "indexUnchanged": index_before == index_after}


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
    commit.add_argument("--preserve-foreign-staged", action="store_true",
                        help="add/scan/commit only selected paths; preserve all other staging")
    commit.add_argument("--strict-staging", action="store_true",
                        help="explicit exact-match staged-set contract (the default mode)")
    lock = sub.add_parser("lock")
    lock.add_argument("--repo", required=True)
    lock.add_argument("--days", type=float, default=DEFAULT_STALE_LOCK_DAYS)
    lock.add_argument("--backup-dir", default=None)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.cmd == "policy":
        path = policy_path()
        if not path.is_file():
            return emit({"ok": False, "reason": "policy-missing"}, 3)
        sys.stdout.buffer.write(path.read_bytes())
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
    if args.cmd == "lock":
        backup = (Path(args.backup_dir) if args.backup_dir
                  else repo / "data" / "agent-handoff" / "stale-index-lock")
        result = clear_stale_lock(repo, args.days, backup)
        visible = {key: value for key, value in result.items() if key != "exit"}
        return emit(visible, result["exit"])
    if args.preserve_foreign_staged and args.strict_staging:
        return emit({"ok": False, "reason": "conflicting-staging-flags"}, 2)
    result = (commit_selected(repo, Path(args.message_file), args.path) if args.preserve_foreign_staged
              else do_commit(repo, Path(args.message_file), args.path))
    visible = {key: value for key, value in result.items() if key != "exit"}
    return emit(visible, result["exit"])


if __name__ == "__main__":
    sys.exit(main())
