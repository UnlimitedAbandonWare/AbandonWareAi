#!/usr/bin/env python3
"""Read-only Git environment doctor for the demo-1 canonical root.

Reports what is blocked, what still works, and who owns the next action.
Never mutates: no deletes, no staging changes, no fetch. Network access only
via the explicit --probe-remote option (bounded ls-remote HEAD). Git reads run
with GIT_OPTIONAL_LOCKS=0 / GIT_TERMINAL_PROMPT=0 / GIT_NO_LAZY_FETCH=1 and
--no-optional-locks so diagnosis itself cannot take locks or hang on auth.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

SCHEMA = "awx.git-doctor.v1"
GIT_TIMEOUT = 20
PROBE_TIMEOUT = 15

SAFE_ENV = {
    "GIT_OPTIONAL_LOCKS": "0",
    "GIT_TERMINAL_PROMPT": "0",
    "GIT_NO_LAZY_FETCH": "1",
    "GIT_LITERAL_PATHSPECS": "1",
}

IN_PROGRESS_MARKERS = (
    ("MERGE_HEAD", "merge-in-progress"),
    ("CHERRY_PICK_HEAD", "cherry-pick-in-progress"),
    ("REVERT_HEAD", "revert-in-progress"),
    ("REBASE_HEAD", "rebase-in-progress"),
    ("rebase-merge", "rebase-in-progress"),
    ("rebase-apply", "rebase-in-progress"),
    ("sequencer", "sequencer-in-progress"),
)

POLICY_FILES = (
    ".grok/rules/demo1-conditional-local-git.md",
    "AGENTS.md",
    ".windsurf/rules/demo1-conditional-local-git.md",
    ".windsurf/rules/demo1-hard-constraints.md",
)


def sanitize_stderr(data: bytes) -> str:
    """First lines only, userinfo stripped, capped -- never a dump."""
    text = data.decode("utf-8", errors="replace")
    text = re.sub(r"(://|//)[^/@\s]+@", r"\1***@", text)
    lines = [ln for ln in text.splitlines() if ln.strip()][:2]
    return " | ".join(lines)[:200]


def run_git(root: Path | None, args: list[str], timeout: int = GIT_TIMEOUT) -> dict:
    env = dict(os.environ)
    env.update(SAFE_ENV)
    argv = ["git", "--no-optional-locks"]
    if root is not None:
        argv += ["-C", str(root)]
    argv += args
    try:
        proc = subprocess.run(argv, capture_output=True, timeout=timeout,
                              env=env, check=False)
    except FileNotFoundError:
        return {"ok": False, "error": "git-not-found", "code": None}
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "git-timeout", "code": None}
    except OSError:
        return {"ok": False, "error": "git-exec-failed", "code": None}
    return {
        "ok": proc.returncode == 0,
        "code": proc.returncode,
        "stdout": proc.stdout,
        "stderrSummary": sanitize_stderr(proc.stderr),
    }


def git_text(root: Path, args: list[str]) -> tuple[str, dict]:
    res = run_git(root, args)
    if not res["ok"]:
        return "", res
    return res["stdout"].decode("utf-8", errors="replace").strip(), res


def sha256_file(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()[:16]
    except OSError:
        return ""


def mask_url(url: str) -> str:
    return re.sub(r"(://|//)[^/@\s]+@", r"\1***@", url.strip())


def issue(code: str, blocked: list[str], continuable: list[str], owner: str,
          detail: str = "") -> dict:
    return {"code": code, "blockedActions": blocked,
            "continuableActions": continuable, "nextOwner": owner,
            "detail": detail}


def collect(root_arg: str, owned: list[str], probe_remote: str | None) -> dict:
    out: dict = {"schemaVersion": SCHEMA, "observedAtUtc":
                 time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                 "rootInput": root_arg, "issues": []}
    issues: list[dict] = out["issues"]

    ver = run_git(None, ["--version"])
    out["gitVersion"] = ver["stdout"].decode("utf-8", "replace").strip() if ver["ok"] else ""
    out["pythonVersion"] = sys.version.split()[0]
    if not ver["ok"]:
        issues.append(issue("git-unavailable", ["all-git"],
                            ["file-edits", "work-ledger-checkpoint", "handoff-report"],
                            "needs-decision", ver.get("error", "")))
        out["decision"] = "ERROR"
        return out

    root = Path(root_arg).resolve()
    top, res = git_text(root, ["rev-parse", "--show-toplevel"])
    if not res["ok"]:
        issues.append(issue("not-a-repo", ["all-git"],
                            ["file-edits", "work-ledger-checkpoint"],
                            "needs-decision", res.get("stderrSummary") or res.get("error", "")))
        out["decision"] = "ERROR"
        return out
    root = Path(top).resolve()
    out["rootResolved"] = str(root)

    gitdir_raw, _ = git_text(root, ["rev-parse", "--absolute-git-dir"])
    common_raw, _ = git_text(root, ["rev-parse", "--git-common-dir"])
    gitdir = Path(gitdir_raw) if gitdir_raw else None
    common = Path(common_raw) if common_raw else gitdir
    # --git-common-dir prints a path relative to the cwd (worktree root).
    if gitdir is not None and not gitdir.is_absolute():
        gitdir = (root / gitdir).resolve()
    if common is not None and not common.is_absolute():
        common = (root / common).resolve()
    out["gitDir"] = str(gitdir) if gitdir else ""
    out["gitCommonDir"] = str(common) if common else ""
    out["isLinkedWorktree"] = bool(gitdir and common and gitdir != common)

    index_raw, _ = git_text(root, ["rev-parse", "--git-path", "index"])
    lock_raw, _ = git_text(root, ["rev-parse", "--git-path", "index.lock"])
    index_path = Path(index_raw) if index_raw else None
    lock_path = Path(lock_raw) if lock_raw else None
    if index_path is not None and not index_path.is_absolute():
        index_path = (root / index_path).resolve()
    if lock_path is not None and not lock_path.is_absolute():
        lock_path = (root / lock_path).resolve()
    index_lock_present = bool(lock_path and lock_path.exists())
    lock_info = {"indexPath": str(index_path) if index_path else "",
                 "lockPath": str(lock_path) if lock_path else "",
                 "lockPresent": index_lock_present}
    if index_lock_present:
        stat = lock_path.stat()
        lock_info["lockSizeBytes"] = stat.st_size
        lock_info["lockAgeSeconds"] = int(time.time() - stat.st_mtime)
    out["index"] = lock_info
    if index_lock_present:
        issues.append(issue(
            "index-lock-present",
            ["git-add", "git-commit", "git-stage-anything"],
            ["git-status", "git-diff", "file-edits", "work-ledger-checkpoint",
             "fixture-repo-work"],
            "user",
            "lock age/size recorded; agents never delete index.lock"))

    head_ref, _ = git_text(root, ["symbolic-ref", "-q", "HEAD"])
    head_oid, head_res = git_text(root, ["rev-parse", "--verify", "HEAD"])
    out["head"] = {"ref": head_ref, "oid": head_oid, "detached": not head_ref}
    if not head_res["ok"]:
        issues.append(issue("unborn-head", ["git-commit-on-shared-index"],
                            ["reads", "file-edits"], "needs-decision"))

    in_progress = []
    if gitdir:
        for name, code in IN_PROGRESS_MARKERS:
            if (gitdir / name).exists():
                in_progress.append(code)
    out["inProgress"] = sorted(set(in_progress))
    for code in set(in_progress):
        issues.append(issue(code, ["git-add", "git-commit", "git-merge-flow"],
                            ["git-status", "git-log", "reads", "file-edits"],
                            "foreign-session",
                            "a Git operation is mid-flight; do not interleave"))

    staged, staged_res = git_text(root, ["diff", "--cached", "--name-only", "-z"])
    if not staged_res["ok"]:
        staged, staged_res = git_text(
            root, ["status", "--porcelain=v1", "-z", "--untracked-files=no"])
    staged_paths = []
    if staged_res["ok"] and staged:
        raw = run_git(root, ["diff", "--cached", "--name-only", "-z"])
        if raw["ok"]:
            staged_paths = sorted(p for p in
                                  raw["stdout"].decode("utf-8", "surrogateescape").split("\0") if p)
    unmerged_raw = run_git(root, ["ls-files", "-u", "-z"])
    unmerged = [p for p in
                (unmerged_raw["stdout"].decode("utf-8", "surrogateescape").split("\0")
                 if unmerged_raw["ok"] else []) if p]
    owned_set = {p.replace("\\", "/") for p in owned}
    foreign = [p for p in staged_paths if p not in owned_set] if owned_set else staged_paths
    out["staging"] = {"stagedPaths": staged_paths, "stagedCount": len(staged_paths),
                      "foreignStagedPaths": foreign if owned_set else [],
                      "unmergedCount": len({r.split("\t")[-1] for r in unmerged})}
    if unmerged:
        issues.append(issue("unmerged-paths", ["git-add", "git-commit"],
                            ["reads", "conflict-report"], "foreign-session"))
    if owned_set and foreign:
        issues.append(issue(
            "foreign-staged-paths",
            ["shared-index-commit"],
            ["work-ledger-checkpoint", "handoff-report",
             "conditional-commit-preserve-foreign-staged (needs its own authorization)"],
            "foreign-session",
            f"{len(foreign)} staged path(s) not in this session's owned set"))

    status_raw = run_git(root, ["status", "--porcelain=v1", "-z"])
    counts: dict[str, int] = {}
    deleted_top: dict[str, int] = {}
    if status_raw["ok"]:
        parts = [p for p in status_raw["stdout"].split(b"\0") if p]
        i = 0
        while i < len(parts):
            rec = parts[i]
            i += 1
            if len(rec) < 4:
                continue
            xy = rec[:2].decode("ascii", "replace")
            counts[xy] = counts.get(xy, 0) + 1
            if xy[0] in {"R", "C"} and i < len(parts):
                i += 1
            if "D" in xy:
                name = rec[3:].decode("utf-8", "surrogateescape")
                deleted_top[name.split("/")[0]] = deleted_top.get(name.split("/")[0], 0) + 1
    out["worktreeCounts"] = dict(sorted(counts.items()))
    out["deletedTopDirs"] = dict(sorted(deleted_top.items(),
                                        key=lambda kv: -kv[1])[:10])
    if counts.get(" D", 0) + counts.get("D ", 0) + counts.get("D", 0) > 500:
        issues.append(issue(
            "mass-deletion-uncommitted",
            ["git-add-bulk", "commit-of-deletions"],
            ["review-deleted-paths", "compare-with-publish-snapshot",
             "user-decision"],
            "user",
            "large tracked-path deletions not committed; intent must be confirmed"))

    wt_raw = run_git(root, ["worktree", "list", "--porcelain", "-z"])
    worktrees = []
    if wt_raw["ok"]:
        for block in wt_raw["stdout"].decode("utf-8", "surrogateescape").split("\0\0"):
            entry: dict = {}
            for line in block.replace("\n", "\0").split("\0"):
                if not line.strip():
                    continue
                if line.startswith("worktree "):
                    entry["path"] = line[9:]
                elif line.startswith("HEAD "):
                    entry["head"] = line[5:12]
                elif line.startswith("branch "):
                    entry["branch"] = line[7:].rsplit("/", 1)[-1]
                elif line == "detached":
                    entry["detached"] = True
                elif line.startswith("locked"):
                    entry["locked"] = True
                elif line.startswith("prunable"):
                    entry["prunable"] = True
            if entry.get("path"):
                entry["present"] = Path(entry["path"]).exists()
                worktrees.append(entry)
    out["worktrees"] = {"count": len(worktrees), "entries": worktrees}
    offline = [w for w in worktrees if not w.get("present", True)]
    prunable = [w for w in worktrees if w.get("prunable")]
    if offline:
        issues.append(issue("offline-worktrees", ["worktree-prune", "worktree-remove"],
                            ["record-inventory", "owner-check"], "user",
                            f"{len(offline)} worktree path(s) not reachable -- SMB/external drive may be offline"))
    elif prunable:
        issues.append(issue("prunable-worktrees", ["worktree-prune (needs user approval)"],
                            ["record-inventory"], "user",
                            f"{len(prunable)} prunable entr(ies) -- observation only"))

    remotes = []
    remote_names, _ = git_text(root, ["remote"])
    rewrites_raw = run_git(root, ["config", "--get-regexp", r"^url\..*\.(insteadof|pushinsteadof)"])
    rewrites = [mask_url(ln) for ln in
                rewrites_raw["stdout"].decode("utf-8", "replace").splitlines()] if rewrites_raw["ok"] else []
    for name in remote_names.splitlines():
        name = name.strip()
        if not name:
            continue
        fetch_url, _ = git_text(root, ["remote", "get-url", name])
        push_res = run_git(root, ["remote", "get-url", "--push", "--all", name])
        push_urls = [mask_url(u) for u in
                     push_res["stdout"].decode("utf-8", "replace").splitlines() if u.strip()] if push_res["ok"] else []
        remotes.append({"name": name, "fetchUrl": mask_url(fetch_url),
                        "pushUrls": push_urls})
    out["remotes"] = remotes
    out["urlRewrites"] = rewrites
    if rewrites:
        issues.append(issue("url-rewrite-present", ["trust-remote-name-only"],
                            ["verify-effective-url"], "agent",
                            "url.*.insteadOf/pushInsteadOf rules exist -- effective push target differs from remote name"))

    policy = {}
    for rel in POLICY_FILES:
        path = root / rel
        entry = {"present": path.is_file()}
        if path.is_file():
            body = path.read_text(encoding="utf-8", errors="replace")
            entry["sha256"] = sha256_file(path)
            if rel.endswith("demo1-conditional-local-git.md") and rel.startswith(".grok"):
                entry["staleSelfReference"] = "still say Git is read-only" in body
            if rel == "AGENTS.md":
                entry["hasLocalFirstBlock"] = "DEMO1-GIT-LOCAL-FIRST" in body
        policy[rel] = entry
    out["policy"] = policy
    grok = policy.get(".grok/rules/demo1-conditional-local-git.md", {})
    agents = policy.get("AGENTS.md", {})
    if grok.get("staleSelfReference") or (agents.get("present") and not agents.get("hasLocalFirstBlock")):
        issues.append(issue("policy-drift", ["git-commit-until-resolved"],
                            ["reads", "report-drift"], "agent",
                            "policy documents disagree or carry stale self-reference"))

    if probe_remote:
        match = next((r for r in remotes if r["name"] == probe_remote), None)
        url = (match["pushUrls"][0] if match and match["pushUrls"]
               else (match["fetchUrl"] if match else ""))
        probe: dict = {"remote": probe_remote, "attempted": bool(url)}
        if url:
            res = run_git(root, ["ls-remote", url, "HEAD"], timeout=PROBE_TIMEOUT)
            if res["ok"]:
                head_line = res["stdout"].decode("utf-8", "replace").split()
                probe.update({"ok": True,
                              "headOid": head_line[0] if head_line else ""})
            else:
                err = (res.get("stderrSummary") or res.get("error") or "").lower()
                kind = ("remote-not-found" if "not found" in err
                        else "remote-auth-blocked" if "auth" in err or "username" in err
                        else "remote-timeout" if res.get("error") == "git-timeout"
                        else "remote-error")
                probe.update({"ok": False, "errorKind": kind})
                issues.append(issue(kind, ["publish-review-remote-checks"],
                                    ["local-work", "user-remote-check"], "user",
                                    "bounded ls-remote probe failed"))
        out["probe"] = probe

    out["decision"] = "BLOCKED-ITEMS-PRESENT" if issues else "CLEAR"
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--owned-path", action="append", default=[])
    parser.add_argument("--probe-remote", default=None,
                        help="opt-in bounded ls-remote HEAD probe of this remote")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    result = collect(args.root, args.owned_path, args.probe_remote)
    if args.json:
        print(json.dumps(result, ensure_ascii=True, indent=1, sort_keys=True,
                         default=str))
    else:
        print(f"[git-doctor] root={result.get('rootResolved', result['rootInput'])} "
              f"decision={result.get('decision')}")
        head = result.get("head", {})
        if head:
            print(f"[git-doctor] head={head.get('ref') or 'DETACHED'} "
                  f"oid={head.get('oid', '')[:12]}")
        idx = result.get("index", {})
        print(f"[git-doctor] indexLock={idx.get('lockPresent', False)} "
              f"staged={result.get('staging', {}).get('stagedCount', 0)} "
              f"worktrees={result.get('worktrees', {}).get('count', 0)}")
        for item in result.get("issues", []):
            print(f"[git-doctor][issue] {item['code']} nextOwner={item['nextOwner']}")
            print(f"    blocked: {', '.join(item['blockedActions'])}")
            print(f"    continuable: {', '.join(item['continuableActions'])}")
            if item.get("detail"):
                print(f"    note: {item['detail']}")
    return 0 if result.get("decision") != "ERROR" else 3


if __name__ == "__main__":
    sys.exit(main())
