#!/usr/bin/env python3
"""Isolated Git shadow snapshots for agents (awx.agent-git-snapshot.v1).

Shadow pre-image backup without touching the main index, staging area, HEAD,
or any branch: every plumbing call runs with GIT_INDEX_FILE pointed at a
fresh temp index, and snapshots publish to refs/agent-snapshots/<agent>/
<task-id>/<stage> only.

    python -B scripts/agent_git_snapshot.py take --task-id T --agent A \
        --path <repo-rel> [--path ...] [--stage pre|post]
    python -B scripts/agent_git_snapshot.py diff --task-id T [--agent A] \
        [--stage pre] [--path ...] [--stat] [--patch] [--verify-patch FILE]
    python -B scripts/agent_git_snapshot.py restore --task-id T [--agent A] \
        [--stage pre] [--path ...] [--force]
    python -B scripts/agent_git_snapshot.py make-patch --task-id T --out FILE \
        [--agent A] [--stage pre] [--path ...]
    python -B scripts/agent_git_snapshot.py list [--agent A] [--task-id T]
    python -B scripts/agent_git_snapshot.py prune [--days 14] [--dry-run] \
        [--agent A] [--task-id T]

A snapshot commit is a sparse commit: its tree contains exactly the declared
paths (git ls-tree -r --name-only <commit> is the manifest), parented on the
current HEAD for lineage. restore writes blob bytes directly (never
`git checkout`, which would stage into the main index). Files whose current
bytes match any recorded stage, the index, or HEAD restore without --force;
anything else defers `uncommitted-changes-detected`.

stdout is exactly one JSON line. Exit 0 ok, 2 deferred/usage, 3 repo/env.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import conditional_local_git as gate  # noqa: E402

SCHEMA = "awx.agent-git-snapshot.v1"
REF_PREFIX = "refs/agent-snapshots"
TOKEN_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}\Z")
STAGES = ("pre", "post")
MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024
GIT_TIMEOUT = 60
AUTHOR_ENV = {
    "GIT_AUTHOR_NAME": "awx-agent-snapshot",
    "GIT_AUTHOR_EMAIL": "agent-snapshot@localhost",
    "GIT_COMMITTER_NAME": "awx-agent-snapshot",
    "GIT_COMMITTER_EMAIL": "agent-snapshot@localhost",
}
# 절대 스냅샷하지 않는 비밀성 경로(값이 아니라 경로 형태로만 판정)
SECRET_PATH_RE = re.compile(
    r"(^|/)(\.secrets|\.env(\..*)?|[^/]*\.(pem|key|pfx|p12|jks))$", re.I)


class Deferred(Exception):
    def __init__(self, reason: str, code: int = 2, **extra):
        super().__init__(reason)
        self.reason, self.code, self.extra = reason, code, extra


def emit(payload: dict, code: int) -> int:
    payload.setdefault("schemaVersion", SCHEMA)
    print(json.dumps(payload, ensure_ascii=True, sort_keys=True))
    return code


def run_git(repo: Path, args: list[str], env_extra: dict | None = None,
            timeout: int = GIT_TIMEOUT) -> subprocess.CompletedProcess:
    """git --no-optional-locks -C <repo> <args>; env_extra overlays os.environ."""
    env = dict(os.environ)
    env["PATH"] = str(Path(gate.git_exe()).parent) + os.pathsep + env.get("PATH", "")
    if env_extra:
        env.update(env_extra)
    try:
        return subprocess.run(
            [gate.git_exe(), "--no-optional-locks", "-C", str(repo), *args],
            capture_output=True, check=False, env=env, timeout=timeout)
    except OSError:
        return subprocess.CompletedProcess(args, 127, b"", b"git-not-found")
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(args, 124, b"", b"git-timeout")


def check_token(name: str, value: str) -> str:
    """Bounded token that must also be a safe ref component and non-secret."""
    if not isinstance(value, str) or not TOKEN_RE.fullmatch(value) \
            or ".." in value or value.endswith(".lock"):
        raise Deferred(f"invalid-{name}", 2)
    blob = value.encode("utf-8", "replace")
    if any(pattern.search(blob) for _n, pattern in gate.SECRET_PATTERNS):
        raise Deferred(f"invalid-{name}", 2)
    return value


def resolve_repo(raw: str) -> tuple[Path, Path]:
    repo = Path(raw).resolve()
    if not gate.repo_allowed(repo):
        raise Deferred("repo-not-allowed", 3)
    if run_git(repo, ["version"]).returncode != 0:
        raise Deferred("git-not-found", 3)
    directory = gate.git_dir(repo)
    if directory is None or not (directory / "HEAD").exists():
        raise Deferred("not-a-git-repo", 3)
    return repo, directory


def normalize_paths(repo: Path, raw_paths: list[str],
                    must_exist: bool) -> list[str]:
    paths: list[str] = []
    for raw in raw_paths:
        norm = raw.strip().replace("\\", "/")
        while norm.startswith("./"):
            norm = norm[2:]
        if (not norm or norm.startswith("-") or norm.startswith("/")
                or ":" in norm or norm == "." or norm.startswith("../")
                or "/../" in norm or norm.endswith("/..")):
            raise Deferred("invalid-path", 2, badPath=raw)
        if gate.path_forbidden(norm) or SECRET_PATH_RE.search(norm):
            raise Deferred("forbidden-path", 2, badPath=norm)
        if norm not in paths:
            paths.append(norm)
    if not paths:
        raise Deferred("no-paths", 2)
    if must_exist:
        for rel in paths:
            target = repo / rel
            if not target.is_file():
                raise Deferred("path-missing", 2, badPath=rel)
            if target.stat().st_size > MAX_SNAPSHOT_BYTES:
                raise Deferred("file-too-large", 2, badPath=rel,
                               limit=MAX_SNAPSHOT_BYTES)
    return paths


def ref_name(agent: str, task_id: str, stage: str) -> str:
    ref = f"{REF_PREFIX}/{agent}/{task_id}/{stage}"
    if run_git(Path("."), ["check-ref-format", ref]).returncode != 0:
        raise Deferred("invalid-ref", 2)
    return ref


def resolve_snapshot(repo: Path, agent: str | None, task_id: str,
                     stage: str) -> tuple[str, str, str]:
    """Resolve (ref, agent, commit). Without --agent, search all agents."""
    check_token("task-id", task_id)
    if agent is not None:
        agent = check_token("agent", agent)
        ref = ref_name(agent, task_id, stage)
        proc = run_git(repo, ["rev-parse", "--verify", "-q", f"{ref}^{{commit}}"])
        if proc.returncode != 0:
            raise Deferred("snapshot-not-found", 2, ref=ref)
        return ref, agent, gate.text(proc.stdout)
    proc = run_git(repo, ["for-each-ref", REF_PREFIX,
                          "--format=%(refname)%00%(objectname)"])
    matches = []
    for line in gate.text(proc.stdout).splitlines():
        if "\0" not in line:
            continue
        ref, sha = line.split("\0", 1)
        tail = ref[len(REF_PREFIX) + 1:].split("/")
        if len(tail) == 3 and tail[1] == task_id and tail[2] == stage:
            matches.append((ref, tail[0], sha))
    if not matches:
        raise Deferred("snapshot-not-found", 2,
                       ref=f"{REF_PREFIX}/*/{task_id}/{stage}")
    if len(matches) > 1:
        raise Deferred("snapshot-ambiguous", 2,
                       refs=sorted(m[0] for m in matches))
    return matches[0]


def snapshot_paths(repo: Path, commit: str) -> list[str]:
    proc = run_git(repo, ["ls-tree", "-r", "--name-only", "-z", commit])
    if proc.returncode != 0:
        raise Deferred("snapshot-unreadable", 3,
                       detail=gate.git_stderr_tail(proc.stderr))
    return [p for p in proc.stdout.decode("utf-8", "replace").split("\0") if p]


def select_paths(repo: Path, commit: str, raw_paths: list[str] | None) -> list[str]:
    snap = snapshot_paths(repo, commit)
    if raw_paths:
        wanted = normalize_paths(repo, raw_paths, must_exist=False)
        missing = [p for p in wanted if p not in snap]
        if missing:
            raise Deferred("path-not-in-snapshot", 2, badPaths=missing)
        return wanted
    if not snap:
        raise Deferred("snapshot-empty", 2)
    return snap


def head_commit(repo: Path) -> str | None:
    proc = run_git(repo, ["rev-parse", "--verify", "-q", "HEAD"])
    return gate.text(proc.stdout) if proc.returncode == 0 else None


def snapshot_index_env(repo: Path, commit: str) -> tuple[dict, Path]:
    """Temp GIT_INDEX_FILE holding exactly the snapshot tree, so diff treats
    the recorded paths as tracked — including gitignored worktree files,
    which a bare `git diff <commit>` would otherwise report as deleted."""
    tmpdir = Path(tempfile.mkdtemp(prefix="awx-snap-diffidx-"))
    env = {"GIT_INDEX_FILE": str(tmpdir / "index")}
    proc = run_git(repo, ["read-tree", commit], env_extra=env)
    if proc.returncode != 0:
        raise Deferred("read-tree-failed", 3,
                       detail=gate.git_stderr_tail(proc.stderr))
    return env, tmpdir


def drop_tmpdir(tmpdir: Path) -> None:
    try:
        for child in tmpdir.glob("*"):
            child.unlink(missing_ok=True)
        tmpdir.rmdir()
    except OSError:
        pass


def snapshot_diff(repo: Path, commit: str, paths: list[str],
                  extra: list[str]) -> subprocess.CompletedProcess:
    env, tmpdir = snapshot_index_env(repo, commit)
    try:
        return run_git(repo, ["diff", "--no-renames", *extra, commit,
                              "--", *paths], env_extra=env)
    finally:
        drop_tmpdir(tmpdir)


def numstat(repo: Path, commit: str, paths: list[str]) -> dict:
    proc = snapshot_diff(repo, commit, paths, ["--numstat"])
    files, ins, dels = [], 0, 0
    for line in gate.text(proc.stdout).splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        a, d, path = parts
        try:
            ia, id_ = int(a), int(d)
        except ValueError:  # binary: '-\t-\tpath'
            ia = id_ = 0
        ins, dels = ins + ia, dels + id_
        files.append({"path": path, "insertions": ia, "deletions": id_})
    return {"filesChanged": len(files), "insertions": ins,
            "deletions": dels, "files": files}


def sha256_file(path: Path) -> str | None:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        return None


def worktree_blob_sha(repo: Path, rel: str) -> str | None:
    proc = run_git(repo, ["hash-object", "--", rel])
    return gate.text(proc.stdout) if proc.returncode == 0 else None


def cmd_take(args) -> dict:
    repo, directory = resolve_repo(args.repo)
    agent = check_token("agent", args.agent)
    task_id = check_token("task-id", args.task_id)
    paths = normalize_paths(repo, args.path or [], must_exist=True)
    ref = ref_name(agent, task_id, args.stage)
    index_before = gate.index_sha256(directory)

    tmpdir = Path(tempfile.mkdtemp(prefix="awx-snap-index-"))
    index_env = {"GIT_INDEX_FILE": str(tmpdir / "index")}
    try:
        file_meta = []
        for rel in paths:
            blob = run_git(repo, ["hash-object", "-w", "--", rel])
            if blob.returncode != 0:
                raise Deferred("hash-object-failed", 3, badPath=rel,
                               detail=gate.git_stderr_tail(blob.stderr))
            blob_sha = gate.text(blob.stdout)
            upd = run_git(repo, ["update-index", "--add", "--cacheinfo",
                                 "100644", blob_sha, rel], env_extra=index_env)
            if upd.returncode != 0:
                raise Deferred("update-index-failed", 3, badPath=rel,
                               detail=gate.git_stderr_tail(upd.stderr))
            file_meta.append({"path": rel, "blob": blob_sha,
                              "sha256": sha256_file(repo / rel),
                              "bytes": (repo / rel).stat().st_size})
        tree_p = run_git(repo, ["write-tree"], env_extra=index_env)
        if tree_p.returncode != 0:
            raise Deferred("write-tree-failed", 3,
                           detail=gate.git_stderr_tail(tree_p.stderr))
        tree = gate.text(tree_p.stdout)
    finally:
        drop_tmpdir(tmpdir)

    message = ("awx-snapshot: agent={} task={} stage={} files={}\n\n{}"
               .format(agent, task_id, args.stage, len(paths),
                       "\n".join(paths)))
    commit_env = dict(AUTHOR_ENV)
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    commit_env["GIT_AUTHOR_DATE"] = now
    commit_env["GIT_COMMITTER_DATE"] = now
    argv = ["commit-tree", tree]
    head = head_commit(repo)
    if head:
        argv += ["-p", head]
    argv += ["-m", message]
    commit_p = run_git(repo, argv, env_extra=commit_env)
    if commit_p.returncode != 0:
        raise Deferred("commit-tree-failed", 3,
                       detail=gate.git_stderr_tail(commit_p.stderr))
    commit = gate.text(commit_p.stdout)
    ref_p = run_git(repo, ["update-ref", ref, commit])
    if ref_p.returncode != 0:
        raise Deferred("update-ref-failed", 3,
                       detail=gate.git_stderr_tail(ref_p.stderr))
    index_after = gate.index_sha256(directory)
    return {"outcome": "snapshotted", "taskId": task_id, "agent": agent,
            "stage": args.stage, "commit": commit, "tree": tree, "ref": ref,
            "head": head, "files": paths, "fileDetail": file_meta,
            "mainIndexUnchanged": index_before == index_after,
            "indexSha256Before": index_before,
            "indexSha256After": index_after,
            "createdAt": now, "exit": 0}


def cmd_diff(args) -> dict:
    repo, _directory = resolve_repo(args.repo)
    ref, agent, commit = resolve_snapshot(repo, args.agent, args.task_id,
                                          args.stage)
    paths = select_paths(repo, commit, args.path)
    summary = numstat(repo, commit, paths)
    out = {"outcome": "diffed", "taskId": args.task_id, "agent": agent,
           "stage": args.stage, "commit": commit, "ref": ref,
           "paths": paths, **summary}
    if args.stat:
        stat_p = snapshot_diff(repo, commit, paths, ["--stat"])
        out["statText"] = gate.text(stat_p.stdout)[:4000]
    if args.patch:
        diff_p = snapshot_diff(repo, commit, paths, [])
        out["diffText"] = diff_p.stdout.decode("utf-8", "replace")
    if args.verify_patch:
        out["preflight"] = verify_vs_snapshot(repo, commit, args.verify_patch)
    out["exit"] = 0
    return out


def patch_targets(patch_file: Path) -> list[str]:
    targets = []
    try:
        for line in patch_file.read_text(encoding="utf-8",
                                         errors="replace").splitlines():
            if line.startswith("+++ "):
                rel = line[4:].strip().strip('"')
                for pref in ("a/", "b/"):
                    if rel.startswith(pref):
                        rel = rel[2:]
                if rel != "/dev/null" and rel not in targets:
                    targets.append(rel.replace("\\", "/"))
    except OSError:
        pass
    return targets


def materialize_snapshot(repo: Path, commit: str, paths: list[str]) -> Path:
    """Extract <commit>:<path> for each path into a fresh temp root."""
    tmp = Path(tempfile.mkdtemp(prefix="awx-snap-state-"))
    for rel in paths:
        blob = run_git(repo, ["rev-parse", "--verify", "-q", f"{commit}:{rel}"])
        if blob.returncode != 0:
            continue
        data = run_git(repo, ["cat-file", "blob", gate.text(blob.stdout)])
        if data.returncode != 0:
            continue
        dest = tmp / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data.stdout)
    return tmp


def verify_vs_snapshot(repo: Path, commit: str, patch_file: str) -> dict:
    """patch_preflight against files at snapshot state, not the live tree."""
    tool = Path(__file__).with_name("patch_preflight.py")
    patch = Path(patch_file)
    if not tool.is_file() or not patch.is_file():
        return {"ok": False, "reason": "preflight-unavailable"}
    tmp = materialize_snapshot(repo, commit, patch_targets(patch))
    try:
        proc = subprocess.run(
            [sys.executable, "-B", str(tool), str(patch.resolve()),
             "--root", str(tmp)],
            capture_output=True, timeout=120, check=False)
        try:
            report = json.loads(proc.stdout.decode("utf-8", "replace"))
        except ValueError:
            report = {"status": "error",
                      "reason": "preflight-output-unreadable"}
        return {"ok": proc.returncode == 0, "exit": proc.returncode,
                "root": "snapshot-state", "report": report}
    except (OSError, subprocess.TimeoutExpired):
        return {"ok": False, "reason": "preflight-exec-failed"}
    finally:
        for child in sorted(tmp.rglob("*"), reverse=True):
            if child.is_file():
                child.unlink(missing_ok=True)
            elif child.is_dir():
                child.rmdir() if not any(child.iterdir()) else None
        tmp.rmdir() if tmp.exists() else None


def known_blob_shas(repo: Path, agent: str, task_id: str, rel: str) -> set:
    """Blobs considered safe to overwrite: every recorded stage of this task
    plus the currently staged and HEAD blobs for the path."""
    known = set()
    proc = run_git(repo, ["for-each-ref", f"{REF_PREFIX}/{agent}/{task_id}",
                          "--format=%(objectname)"])
    commits = gate.text(proc.stdout).split()
    for spec in [f"{c}:{rel}" for c in commits] + [f":{rel}", f"HEAD:{rel}"]:
        blob = run_git(repo, ["rev-parse", "--verify", "-q", spec])
        if blob.returncode == 0:
            known.add(gate.text(blob.stdout))
    return known


def cmd_restore(args) -> dict:
    repo, _directory = resolve_repo(args.repo)
    ref, agent, commit = resolve_snapshot(repo, args.agent, args.task_id,
                                          args.stage)
    paths = select_paths(repo, commit, args.path)
    rows, blocked = [], []
    for rel in paths:
        target = repo / rel
        blob = run_git(repo, ["rev-parse", "--verify", "-q", f"{commit}:{rel}"])
        if blob.returncode != 0:
            raise Deferred("snapshot-path-unreadable", 3, badPath=rel)
        blob_sha = gate.text(blob.stdout)
        row = {"path": rel, "blob": blob_sha}
        if not target.exists():
            row["worktree"] = "missing"
        else:
            wt = worktree_blob_sha(repo, rel)
            if wt == blob_sha:
                row["worktree"] = "unchanged"
            elif wt in known_blob_shas(repo, agent, args.task_id, rel):
                row["worktree"] = "known-state"
            else:
                row["worktree"] = "unknown-changes"
                blocked.append(rel)
        rows.append(row)
    if blocked and not args.force:
        raise Deferred("uncommitted-changes-detected", 2,
                       hint="re-run with --force to overwrite",
                       blockedPaths=blocked, files=rows)
    restored, kept = [], []
    for row in rows:
        rel = row["path"]
        if row["worktree"] == "unchanged":
            kept.append(rel)
            continue
        # --filters emits the smudged (checkout-form) bytes, so EOL/ident
        # conversions match what the file originally looked like on disk.
        data = run_git(repo, ["cat-file", "--filters", f"{commit}:{rel}"])
        if data.returncode != 0:
            data = run_git(repo, ["cat-file", "blob", row["blob"]])
        if data.returncode != 0:
            raise Deferred("cat-file-failed", 3, badPath=rel,
                           detail=gate.git_stderr_tail(data.stderr))
        dest = repo / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data.stdout)
        row["restoredSha256"] = sha256_file(dest)
        row["restoredBytes"] = len(data.stdout)
        restored.append(rel)
    return {"outcome": "restored" if restored else "unchanged",
            "taskId": args.task_id, "agent": agent, "stage": args.stage,
            "commit": commit, "ref": ref, "restored": restored,
            "unchanged": kept, "forced": bool(args.force), "files": rows,
            "exit": 0}


def cmd_make_patch(args) -> dict:
    repo, _directory = resolve_repo(args.repo)
    ref, agent, commit = resolve_snapshot(repo, args.agent, args.task_id,
                                          args.stage)
    paths = select_paths(repo, commit, args.path)
    diff_p = snapshot_diff(repo, commit, paths, [])
    if diff_p.returncode != 0:
        raise Deferred("diff-failed", 3,
                       detail=gate.git_stderr_tail(diff_p.stderr))
    created = datetime.now(timezone.utc).isoformat(timespec="seconds")
    header = ("# AWX-PATCH: task_id={}, agent={}, stage={}, base_commit={}, "
              "created_at={}\n".format(args.task_id, agent, args.stage,
                                       commit, created)).encode("utf-8")
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(header + diff_p.stdout)
    summary = numstat(repo, commit, paths)
    tool = Path(__file__).with_name("patch_preflight.py")
    result = {"outcome": "patch-created", "taskId": args.task_id,
              "agent": agent, "stage": args.stage, "commit": commit,
              "ref": ref, "patchFile": str(out_path),
              "patchBytes": out_path.stat().st_size, "createdAt": created,
              "paths": paths, **summary}
    if tool.is_file():
        # Authoritative check: does the patch apply to the snapshot pre-image?
        result["preflightVsPreimage"] = verify_vs_snapshot(
            repo, commit, str(out_path))
        # Literal-spec check vs the live worktree; a patch that represents
        # already-applied work reports stale-context here by design.
        proc = subprocess.run(
            [sys.executable, "-B", str(tool), str(out_path.resolve()),
             "--root", str(repo)],
            capture_output=True, timeout=120, check=False)
        try:
            report = json.loads(proc.stdout.decode("utf-8", "replace"))
        except ValueError:
            report = {"status": "error",
                      "reason": "preflight-output-unreadable"}
        result["preflightVsWorktree"] = {"ok": proc.returncode == 0,
                                         "exit": proc.returncode,
                                         "report": report}
    else:
        result["preflightVsPreimage"] = {"ok": False,
                                         "reason": "preflight-unavailable"}
    result["commitGuide"] = (
        "to commit the owned paths locally: python -B "
        "scripts/agent_git_vibe_commit.py --repo . --path <path> [...] "
        "--message-file <file> --task-id " + args.task_id)
    result["exit"] = 0
    return result


def list_refs(repo: Path, agent: str | None, task_id: str | None) -> list[dict]:
    proc = run_git(repo, ["for-each-ref", REF_PREFIX,
                          "--format=%(refname)%00%(objectname)%00"
                          "%(committerdate:iso8601-strict)"])
    rows = []
    for line in gate.text(proc.stdout).splitlines():
        parts = line.split("\0")
        if len(parts) != 3:
            continue
        ref, sha, created = parts
        tail = ref[len(REF_PREFIX) + 1:].split("/")
        if len(tail) != 3:
            continue
        row = {"ref": ref, "agent": tail[0], "taskId": tail[1],
               "stage": tail[2], "commit": sha, "createdAt": created}
        if agent and row["agent"] != agent:
            continue
        if task_id and row["taskId"] != task_id:
            continue
        rows.append(row)
    return rows


def cmd_list(args) -> dict:
    repo, _directory = resolve_repo(args.repo)
    rows = list_refs(repo, args.agent, args.task_id)
    return {"outcome": "listed", "count": len(rows), "snapshots": rows,
            "exit": 0}


def parse_iso(text: str) -> datetime | None:
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def cmd_prune(args) -> dict:
    repo, _directory = resolve_repo(args.repo)
    cutoff = datetime.now(timezone.utc) - timedelta(days=args.days)
    pruned, kept, skipped = [], [], []
    for row in list_refs(repo, args.agent, args.task_id):
        created = parse_iso(row["createdAt"])
        if created is None:
            skipped.append({"ref": row["ref"], "reason": "date-unparseable"})
            continue
        if created >= cutoff:
            kept.append(row["ref"])
            continue
        if args.dry_run:
            pruned.append({"ref": row["ref"], "dryRun": True})
            continue
        delete = run_git(repo, ["update-ref", "-d", row["ref"]])
        if delete.returncode == 0:
            pruned.append({"ref": row["ref"], "dryRun": False})
        else:
            skipped.append({"ref": row["ref"],
                            "reason": gate.git_stderr_tail(delete.stderr)})
    return {"outcome": "pruned", "days": args.days,
            "cutoffUtc": cutoff.isoformat(timespec="seconds"),
            "dryRun": bool(args.dry_run), "pruned": pruned,
            "prunedCount": len(pruned), "keptCount": len(kept),
            "skipped": skipped, "exit": 0}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=".")
    sub = parser.add_subparsers(dest="cmd", required=True)

    def common(p, agent_required=False, with_paths=True):
        p.add_argument("--task-id", required=True)
        p.add_argument("--agent", required=agent_required, default=None)
        p.add_argument("--stage", choices=STAGES, default="pre")
        if with_paths:
            p.add_argument("--path", action="append", default=None)

    take = sub.add_parser("take")
    common(take, agent_required=True)
    take.set_defaults(func=cmd_take)

    diff = sub.add_parser("diff")
    common(diff)
    diff.add_argument("--stat", action="store_true")
    diff.add_argument("--patch", action="store_true",
                      help="include the unified diff text in the JSON output")
    diff.add_argument("--verify-patch", default=None,
                      help="patch_preflight this patch against snapshot state")
    diff.set_defaults(func=cmd_diff)

    restore = sub.add_parser("restore")
    common(restore)
    restore.add_argument("--force", action="store_true")
    restore.set_defaults(func=cmd_restore)

    make = sub.add_parser("make-patch")
    common(make)
    make.add_argument("--out", required=True)
    make.set_defaults(func=cmd_make_patch)

    listed = sub.add_parser("list")
    listed.add_argument("--agent", default=None)
    listed.add_argument("--task-id", default=None)
    listed.set_defaults(func=cmd_list)

    prune = sub.add_parser("prune")
    prune.add_argument("--days", type=float, default=14)
    prune.add_argument("--dry-run", action="store_true")
    prune.add_argument("--agent", default=None)
    prune.add_argument("--task-id", default=None)
    prune.set_defaults(func=cmd_prune)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        result = args.func(args)
    except Deferred as exc:
        result = {"outcome": "deferred", "deferred": exc.reason,
                  "reason": exc.reason, "exit": exc.code, **exc.extra}
    except Exception as exc:  # bounded diagnosis, never a raw traceback dump
        result = {"outcome": "error",
                  "reason": "unexpected:" + type(exc).__name__,
                  "detail": str(exc)[:240], "exit": 3}
    code = result.pop("exit", 2)
    return emit(result, code)


if __name__ == "__main__":
    sys.exit(main())
