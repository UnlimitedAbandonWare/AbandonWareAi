#!/usr/bin/env python3
"""One-call local commit orchestrator for agents (awx.agent-git-vibe-commit.v1).

Once an agent decides "this owned work may be committed" (goal met, or the
user said 커밋해/git 정리), this is the ONLY entry point it needs:

    python -B scripts/agent_git_vibe_commit.py --repo . \
        --path <owned-path> [--path ...] --message-file <file>

Flow: repo gate -> stale 0-byte index.lock soft-clear -> owned-path
add+scan+commit through scripts/conditional_local_git.py. The default staging
mode preserves foreign staged entries byte-identical (commit
--preserve-foreign-staged); --strict-staging switches to the exact-match
staged-set contract. --dry-run prints the add/scan/commit plan without
mutating the index.

stdout is exactly one JSON line:
  {"outcome":"committed","committed":"<sha>","deferred":null,...}
  {"outcome":"deferred","committed":null,"deferred":"<reason>",...}
  {"outcome":"plan","dryRun":true,"deferred":<reason|null>,...}

Never pushes, never unstages foreign paths, never deletes .git internals; a
non-empty or actively-written index.lock is preserved, not removed.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import conditional_local_git as gate  # noqa: E402

SCHEMA = "awx.agent-git-vibe-commit.v1"
DEFAULT_STALE_LOCK_DAYS = gate.DEFAULT_STALE_LOCK_DAYS  # TTL SSOT는 게이트 소유(6h)
TASK_ID_LABEL = "Task-Id"
TASK_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}\Z")
DOCTOR_TIMEOUT_SECONDS = 60
PUBLISH_REVIEW_TIMEOUT_SECONDS = 180


def emit(payload: dict, code: int) -> int:
    payload.setdefault("schemaVersion", SCHEMA)
    print(json.dumps(payload, ensure_ascii=True, sort_keys=True))
    return code


def deferred(reason: str, code: int = 2, **extra) -> dict:
    return {"outcome": "deferred", "committed": None, "deferred": reason,
            "reason": reason, "exit": code, **extra}


def normalize_paths(raw_paths: list[str]) -> tuple[list[str] | None, dict | None]:
    paths: list[str] = []
    for raw in raw_paths:
        norm = raw.strip().replace("\\", "/")
        while norm.startswith("./"):
            norm = norm[2:]
        if not norm or norm.startswith("-"):
            return None, deferred("invalid-selected-path", 2, badPath=raw)
        if gate.path_forbidden(norm):
            return None, deferred("forbidden-path", 2, badPath=norm)
        if norm not in paths:
            paths.append(norm)
    if not paths:
        return None, deferred("no-owned-paths", 2)
    return paths, None


def assess_lock(repo: Path, directory: Path, days: float) -> dict:
    """Read-only mirror of clear_stale_lock's conditions; never moves anything."""
    lock = directory / "index.lock"
    if not lock.exists():
        return {"action": "absent", "reason": "no-lock"}
    stat = lock.stat()
    info = {"lockSizeBytes": stat.st_size,
            "lockAgeSeconds": int(time.time() - stat.st_mtime)}
    marker = False
    if stat.st_size != 0:
        try:
            with lock.open("rb") as handle:
                head = handle.read(64)
        except OSError:
            return {"action": "preserved", "reason": "lock-unreadable", **info}
        if not head.startswith(gate.LOCK_MARKER_PREFIX):
            return {"action": "preserved", "reason": "lock-nonempty", **info}
        marker = True
    if not marker and info["lockAgeSeconds"] < days * 86400:
        return {"action": "preserved", "reason": "lock-fresh", **info}
    writers = gate.git_writers(repo)
    if writers is None:
        return {"action": "preserved", "reason": "writers-unobservable", **info}
    if writers:
        return {"action": "preserved", "reason": "git-writer-active",
                "writers": writers, **info}
    if marker:
        # 실제 회수는 게이트가 정착 창 뒤 수행; 여기선 index 안정만 한 번 확인.
        index_before = gate.index_sha256(directory)
        time.sleep(gate.MARKER_LOCK_SETTLE_SECONDS)
        try:
            again = lock.stat()
        except OSError:
            return {"action": "absent", "reason": "lock-vanished", **info}
        if again.st_size != stat.st_size or again.st_mtime != stat.st_mtime:
            return {"action": "preserved", "reason": "lock-changed", **info}
        if gate.index_sha256(directory) != index_before:
            return {"action": "preserved", "reason": "index-changed", **info}
        return {"action": "would-clear", "reason": "self-orphaned-marker-lock", **info}
    return {"action": "would-clear", "reason": "stale-lock", **info}


def journal_note(repo: Path, task_id: str, text: str) -> str:
    tool = Path(__file__).with_name("work_journal.py")
    if not tool.is_file():
        return "unavailable"
    try:
        proc = subprocess.run(
            [sys.executable, "-B", str(tool), "note", "--root", str(repo),
             "--task", task_id, "--kind", "info", "--text", text],
            capture_output=True, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return "failed:exec"
    return "ok" if proc.returncode == 0 else f"failed:{proc.returncode}"


def task_id_safe(task_id: str) -> bool:
    """Trailer id must be a bounded token and never match a secret pattern."""
    blob = task_id.encode("utf-8", "replace")
    return bool(TASK_ID_RE.fullmatch(task_id)) and not any(
        pattern.search(blob) for _name, pattern in gate.SECRET_PATTERNS)


def task_trailer_message(message_file: Path, task_id: str) -> Path:
    """Copy the message file and append `Task-Id: <id>` so `git log --grep=<id>`
    links the commit back to the work_journal task (journal logs the sha)."""
    body = message_file.read_text(encoding="utf-8")
    fd, name = tempfile.mkstemp(prefix="awx-vibe-msg-", suffix=".txt")
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(body.rstrip() + "\n\n" + TASK_ID_LABEL + ": " + task_id + "\n")
    return Path(name)


def _tool_json(tool_name: str, argv: list[str], timeout: int):
    """Run a sibling read-only tool; return (json-dict|None, exit-code|None)."""
    tool = Path(__file__).with_name(tool_name)
    if not tool.is_file():
        return None, None
    env = dict(os.environ)
    env["PATH"] = str(Path(gate.git_exe()).parent) + os.pathsep + env.get("PATH", "")
    try:
        proc = subprocess.run([sys.executable, "-B", str(tool), *argv],
                              capture_output=True, timeout=timeout, check=False,
                              env=env)
    except (OSError, subprocess.TimeoutExpired):
        return None, None
    try:
        data = json.loads(proc.stdout)
    except ValueError:
        return None, proc.returncode
    return (data if isinstance(data, dict) else None), proc.returncode


def doctor_summary(repo: Path, owned_paths: list[str]) -> dict:
    """Read-only git_doctor attach for deferred outcomes; soft-fails closed so a
    doctor failure never hides the original deferred reason."""
    argv = ["--root", str(repo), "--json"]
    for path in owned_paths:
        argv += ["--owned-path", path]
    data, code = _tool_json("git_doctor.py", argv, DOCTOR_TIMEOUT_SECONDS)
    if data is None:
        out = {"ok": False,
               "reason": "doctor-unavailable" if code is None
               else "doctor-output-unreadable"}
        if code is not None:
            out["exit"] = code
        return out
    issues = data.get("issues") if isinstance(data.get("issues"), list) else []
    return {"ok": True, "decision": data.get("decision"), "exit": code,
            "issueCount": len(issues),
            "issues": [{"code": str(item.get("code"))[:80],
                        "nextOwner": str(item.get("nextOwner"))[:40],
                        "detail": str(item.get("detail") or "")[:160]}
                       for item in issues[:8] if isinstance(item, dict)]}


def publish_review_summary(repo: Path) -> dict:
    """Opt-in read-only git_publish_review verdict attach; never pushes/fetches."""
    data, code = _tool_json("git_publish_review.py",
                            ["--root", str(repo), "--json"],
                            PUBLISH_REVIEW_TIMEOUT_SECONDS)
    if data is None:
        out = {"ok": False,
               "reason": "publish-review-unavailable" if code is None
               else "publish-review-output-unreadable"}
        if code is not None:
            out["exit"] = code
        return out
    reasons = data.get("reasons") if isinstance(data.get("reasons"), list) else []
    return {"ok": True, "verdict": data.get("verdict"), "exit": code,
            "reasons": [str(item)[:120] for item in reasons[:12]]}


def build_plan(repo: Path, directory: Path, paths: list[str], args,
               lock_report: dict) -> dict:
    mode = "strict" if args.strict_staging else "preserve-foreign-staged"
    message_file = Path(args.message_file)
    message_ok = gate.message_ok(message_file)
    deletions = sum(1 for p in paths if not (repo / p).exists())
    staged = gate.inspect_index(repo, None)
    staged_paths = staged.get("stagedPaths", [])
    plan: dict = {
        "mode": mode,
        "paths": paths,
        "lock": lock_report,
        "messageOk": message_ok,
        "candidatePathCount": len(paths),
        "candidateDeletionCount": deletions,
        "maxPaths": gate.MAX_COMMIT_PATHS,
        "maxDeletions": gate.MAX_COMMIT_DELETIONS,
        "remainingPathCapacity": max(0, gate.MAX_COMMIT_PATHS - len(paths)),
        "stagedPaths": staged_paths,
        "foreignStagedPaths": sorted(set(staged_paths) - set(paths)),
        "missingBlobPaths": staged.get("missingBlobPaths", []),
        "blockedPaths": staged.get("blockedPaths", []),
        "secretTotal": staged.get("secretTotal", 0),
        "worktreeCounts": staged.get("worktreeCounts", {}),
        "intendedRemote": staged.get("intendedRemote"),
        "forbiddenRemote": staged.get("forbiddenRemote"),
        "originMismatch": staged.get("originMismatch"),
        "steps": [],
    }
    plan["steps"].append({"step": "lock", "action": lock_report["action"],
                          "reason": lock_report["reason"]})
    if mode == "strict":
        plan["steps"].append({"step": "add", "argv": ["git", "add", "--", *paths]})
    else:
        plan["steps"].append({"step": "add", "via": "isolated-candidate-index"})
    plan["steps"].append({"step": "scan",
                          "via": "git_staged_guard.py on the candidate index"
                          if mode != "strict" else "inspect_index on shared index"})
    argv = ["conditional_local_git.py", "commit", "--repo", str(args.repo),
            "--message-file", args.message_file,
            "--preserve-foreign-staged" if mode != "strict" else "--strict-staging"]
    for path in paths:
        argv += ["--path", path]
    if args.task_id:
        plan["taskTrailer"] = f"{TASK_ID_LABEL}: {args.task_id}"
        plan["steps"].append({"step": "message-trailer",
                              "trailer": plan["taskTrailer"]})
    plan["steps"].append({"step": "commit", "argv": argv})

    if staged.get("forbiddenRemote"):
        plan["deferredReason"] = "forbidden-remote"
    elif staged.get("originMismatch"):
        plan["deferredReason"] = "origin-mismatch"
    elif lock_report["action"] not in ("absent", "would-clear"):
        plan["deferredReason"] = "index-lock"
    elif not message_ok:
        plan["deferredReason"] = "message-missing-reason-verify-constraint"
    elif len(paths) > gate.MAX_COMMIT_PATHS:
        plan["deferredReason"] = "blast-radius-paths"
    elif deletions > gate.MAX_COMMIT_DELETIONS:
        plan["deferredReason"] = "blast-radius-deletions"
    elif mode == "strict" and plan["foreignStagedPaths"]:
        # After `git add <paths>` the staged set still holds foreign entries,
        # so the exact-match contract cannot pass.
        plan["deferredReason"] = "foreign-or-mismatched-staging"
    elif mode == "strict" and not staged.get("ok") \
            and staged.get("reason") != "index-lock":
        plan["deferredReason"] = staged.get("reason", "scan-failed")
    else:
        plan["deferredReason"] = None
    return plan


def orchestrate(args) -> dict:
    repo = Path(args.repo).resolve()
    if not gate.repo_allowed(repo):
        return deferred("repo-not-allowed", 3)
    if gate.git(repo, ["version"]).returncode != 0:
        return deferred("git-not-found", 3)
    directory = gate.git_dir(repo)
    if directory is None or not (directory / "HEAD").exists():
        return deferred("not-a-git-repo", 3)
    remote = gate.remote_status(repo)
    if remote["forbiddenRemote"]:
        return deferred("forbidden-remote", 2, remote=remote)
    if remote["originMismatch"]:
        return deferred("origin-mismatch", 2, remote=remote)
    paths, failure = normalize_paths(args.path)
    if failure is not None:
        return failure
    if args.task_id is not None and not task_id_safe(args.task_id):
        return deferred("invalid-task-id", 2)

    backup_dir = (Path(args.backup_dir) if args.backup_dir
                  else repo / "data" / "agent-handoff" / "stale-index-lock")
    if args.dry_run:
        lock_report = assess_lock(repo, directory, args.stale_lock_days)
    elif (directory / "index.lock").exists():
        lock_report = gate.clear_stale_lock(repo, args.stale_lock_days, backup_dir)
    else:
        lock_report = {"action": "absent", "reason": "no-lock"}

    if args.dry_run:
        plan = build_plan(repo, directory, paths, args, lock_report)
        return {"outcome": "plan", "dryRun": True, "committed": None,
                "deferred": plan["deferredReason"], "plan": plan, "exit": 0}
    if lock_report["action"] == "preserved":
        return deferred("index-lock", 4, lock=lock_report)

    message_file = Path(args.message_file)
    trailer_message: Path | None = None
    if args.task_id:
        try:
            trailer_message = task_trailer_message(message_file, args.task_id)
        except OSError:
            return deferred("message-unreadable", 2)
        message_file = trailer_message
    try:
        if args.strict_staging:
            add = gate.git(repo, ["add", "--", *paths])
            if add.returncode != 0:
                return deferred("add-failed", 2, lock=lock_report)
            scan = gate.inspect_index(repo, paths)
            if not scan["ok"]:
                return deferred(scan["reason"], scan["exit"], scan=scan,
                                lock=lock_report)
            result = gate.do_commit(repo, message_file, paths)
        else:
            result = gate.commit_selected(repo, message_file, paths)
    finally:
        if trailer_message is not None:
            trailer_message.unlink(missing_ok=True)

    payload = {key: value for key, value in result.items() if key != "exit"}
    if result["ok"]:
        return {"outcome": "committed", "committed": result.get("commit"),
                "deferred": None, "exit": 0, "lock": lock_report,
                "taskId": args.task_id,
                "mode": "strict" if args.strict_staging
                else "preserve-foreign-staged", **payload}
    return deferred(result.get("reason", "commit-failed"), result["exit"],
                    lock=lock_report, detail=payload)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--path", action="append", required=True,
                        help="repo-relative path this session owns (repeatable)")
    parser.add_argument("--message-file", required=True,
                        help="commit message file carrying Reason/Verify/Constraint labels")
    parser.add_argument("--dry-run", action="store_true",
                        help="print the add/scan/commit plan without mutating")
    parser.add_argument("--preserve-foreign-staged", action="store_true",
                        help="default mode, may be stated explicitly: keep other "
                             "sessions' staged entries byte-identical")
    parser.add_argument("--strict-staging", action="store_true",
                        help="exact-match staged set instead of preserving foreign staging")
    parser.add_argument("--stale-lock-days", type=float,
                        default=DEFAULT_STALE_LOCK_DAYS,
                        help="min age for a 0-byte writerless index.lock to be archived")
    parser.add_argument("--backup-dir", default=None,
                        help="where a cleared stale lock is moved (never deleted)")
    parser.add_argument("--task-id", default=None,
                        help="work_journal taskId; records one AUTO: journal "
                             "line and appends a Task-Id trailer to the commit "
                             "message (git log --grep=<taskId>)")
    parser.add_argument("--attach-publish-review", action="store_true",
                        help="on a deferred outcome also attach a read-only "
                             "git_publish_review verdict summary (never pushes)")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.preserve_foreign_staged and args.strict_staging:
        return emit({"outcome": "deferred", "committed": None,
                     "deferred": "conflicting-staging-flags",
                     "reason": "conflicting-staging-flags"}, 2)
    result = orchestrate(args)
    code = result.pop("exit", 2)
    # Read-only diagnosis attach: deferred outcomes carry a bounded git_doctor
    # summary (soft-fail keeps the original reason). Dry-run never invokes it.
    if not args.dry_run and result.get("outcome") == "deferred":
        result["doctor"] = doctor_summary(Path(args.repo).resolve(),
                                          args.path or [])
        if args.attach_publish_review:
            result["publishReview"] = publish_review_summary(
                Path(args.repo).resolve())
    if args.task_id and not args.dry_run and result["outcome"] != "plan":
        if result["outcome"] == "committed":
            text = f"AUTO:committed={result['committed']}"
        else:
            text = f"AUTO:deferred={result['deferred']}"
        result["journalNote"] = journal_note(Path(args.repo).resolve(),
                                             args.task_id, text)
        lock = result.get("lock") or {}
        if lock.get("reason") == "self-orphaned-marker-lock-archived":
            name = Path(lock.get("backup", "")).name
            result["journalNoteLock"] = journal_note(
                Path(args.repo).resolve(), args.task_id,
                f"AUTO:self-orphaned-lock-cleared={name}")
    return emit(result, code)


if __name__ == "__main__":
    sys.exit(main())
