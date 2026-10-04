#!/usr/bin/env python3
"""git_branch_context.py -- read-only branch topology report for this checkout.

SSOT:   docs/agents-rules/DEMO1-GIT-BRANCH-TOPOLOGY.md
Config: configs/git-branch-context.json (workBranch/remote/snapshotBranch).
        Missing config -> built-in defaults + a warning field, never a failure.

Git calls are rev-parse / rev-list / merge-base / symbolic-ref only, all with
--no-optional-locks and GIT_TERMINAL_PROMPT=0. Never fetch, checkout or write.

verdict:
  OK_WORK_BRANCH  on configs.workBranch and an upstream is set
  WRONG_BRANCH    on another branch (one-line guidance; nothing is checked out)
  NO_UPSTREAM     on workBranch but no upstream configured
  DETACHED        HEAD is not on any branch
  NO_GIT          git unavailable / root is not a worktree

mainRelation: SNAPSHOT_UNRELATED (normal -- snapshot branch, separate
  history), RELATED (a merge-base exists -- report it), UNKNOWN (cannot tell).
No ahead/behind vs main is ever computed: an unrelated snapshot is not a
comparison base.

Exit codes: 0 = OK_WORK_BRANCH; 2 = WRONG_BRANCH / DETACHED / NO_UPSTREAM;
1 = error (NO_GIT / config unreadable internal failure).
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

SCHEMA = "awx.git-branch-context.v1"

DEFAULT_CONTEXT = {
    "workBranch": "codex/owned-runtime-browser-restart",
    "remote": "origin",
    "snapshotBranch": "main",
}
CONFIG_REL = Path("configs") / "git-branch-context.json"

DEFAULT_GIT = os.environ.get("AWX_GIT_EXE", "git")
FALLBACK_GIT = r"F:\git\cmd\git.exe"
SAFE_ENV = {"GIT_TERMINAL_PROMPT": "0", "GIT_OPTIONAL_LOCKS": "0"}


def run_git(root: Path, args: list[str], timeout: int = 30) -> tuple[int, str, str]:
    for exe in dict.fromkeys([DEFAULT_GIT, FALLBACK_GIT] if DEFAULT_GIT == "git"
                            else [DEFAULT_GIT]):
        try:
            proc = subprocess.run(
                [exe, "--no-optional-locks", "-C", str(root), *args],
                capture_output=True, text=True, timeout=timeout,
                env={**os.environ, **SAFE_ENV})
            return proc.returncode, proc.stdout, proc.stderr
        except FileNotFoundError:
            continue
        except subprocess.TimeoutExpired:
            return 124, "", "git-timeout"
    return 127, "", "git-not-found"


def load_config(root: Path, toplevel: str | None) -> tuple[dict, str | None]:
    """Return (ctx, warning). Missing file -> defaults + warning, not an error."""
    seen: list[Path] = []
    for base in (root, Path(toplevel) if toplevel else None):
        if base is None or base in seen:
            continue
        seen.append(base)
        path = base / CONFIG_REL
        try:
            if path.is_file():
                data = json.loads(path.read_text(encoding="utf-8-sig"))
                return ({**DEFAULT_CONTEXT, **data}, None)
        except (OSError, ValueError):
            return (dict(DEFAULT_CONTEXT), "config-unreadable-defaults-used")
    return (dict(DEFAULT_CONTEXT), "config-missing-defaults-used")


def collect_facts(root: Path, ctx: dict, runner=run_git) -> dict:
    facts: dict = {"gitAvailable": True}
    code, out, err = runner(root, ["rev-parse", "--show-toplevel"])
    if code != 0:
        return {"gitAvailable": False, "error": (err or out).strip()[:200]}
    facts["toplevel"] = out.strip()

    code, out, _ = runner(root, ["rev-parse", "HEAD"])
    facts["head"] = out.strip() if code == 0 else None

    code, out, _ = runner(root, ["rev-parse", "--abbrev-ref", "HEAD"])
    branch = out.strip() if code == 0 else ""
    facts["detached"] = (not branch) or branch == "HEAD"
    facts["branch"] = None if facts["detached"] else branch

    code, out, _ = runner(
        root, ["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"])
    upstream = out.strip() if code == 0 and out.strip() else None
    facts["upstream"] = upstream

    ahead = behind = None
    if upstream:
        code, out, _ = runner(
            root, ["rev-list", "--left-right", "--count", f"{upstream}...HEAD"])
        if code == 0:
            parts = out.split()
            if len(parts) >= 2:
                behind, ahead = int(parts[0]), int(parts[1])
    facts["ahead"] = ahead
    facts["behind"] = behind

    remote = ctx.get("remote") or "origin"
    snap_branch = ctx.get("snapshotBranch") or "main"
    snap_ref = f"{remote}/{snap_branch}"
    facts["snapshotRef"] = snap_ref
    code, out, _ = runner(
        root, ["rev-parse", "--verify", "--quiet", f"{snap_ref}^{{commit}}"])
    facts["snapshotSha"] = out.strip() if code == 0 else None

    code, out, _ = runner(root, ["merge-base", "HEAD", snap_ref])
    if code == 0 and out.strip():
        facts["mainRelation"] = "RELATED"
        facts["mainMergeBase"] = out.strip()
    elif code == 1:
        facts["mainRelation"] = "SNAPSHOT_UNRELATED"
    else:
        facts["mainRelation"] = "UNKNOWN"
    return facts


def classify(facts: dict, ctx: dict) -> dict:
    result: dict = {}
    work = ctx.get("workBranch") or DEFAULT_CONTEXT["workBranch"]
    result["workBranch"] = work
    result["workBranchMatch"] = facts.get("branch") == work
    if not facts.get("gitAvailable"):
        result["verdict"] = "NO_GIT"
        return result
    if facts.get("detached"):
        result["verdict"] = "DETACHED"
        result["note"] = ("HEAD가 분리된 상태 — 어느 브랜치도 가리키지 않음; "
                          "checkout 하지 말고 상태를 보고")
        return result
    if not result["workBranchMatch"]:
        result["verdict"] = "WRONG_BRANCH"
        result["note"] = (f"현재 브랜치 '{facts.get('branch')}' — 작업 브랜치는 "
                          f"'{work}'; checkout하지 말고 어떤 세션이 바꿨는지·"
                          "사용자 지시인지 확인")
        return result
    if not facts.get("upstream"):
        result["verdict"] = "NO_UPSTREAM"
        result["note"] = (f"작업 브랜치 '{work}'인데 upstream이 없음; "
                          "push/pull 없이 upstream 설정만 검토")
        return result
    result["verdict"] = "OK_WORK_BRANCH"
    return result


def probe(root: Path, runner=run_git, config: dict | None = None) -> dict:
    code, out, _ = runner(root, ["rev-parse", "--show-toplevel"])
    toplevel = out.strip() if code == 0 else None
    if config is None:
        ctx, warning = load_config(root, toplevel)
    else:
        ctx, warning = {**DEFAULT_CONTEXT, **config}, (
            "config-missing-defaults-used" if not config else None)
    facts = collect_facts(root, ctx, runner)
    verdict = classify(facts, ctx)
    return {"schemaVersion": SCHEMA, "root": str(root), "context": ctx,
            "warning": warning, "git": facts, **verdict}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--quiet", action="store_true",
                    help="print only the verdict")
    args = ap.parse_args(argv)

    root = Path(os.path.abspath(args.root))
    try:
        result = probe(root)
    except Exception as exc:  # noqa: BLE001 - report, never crash-free
        result = {"schemaVersion": SCHEMA, "root": str(root),
                  "verdict": "NO_GIT", "error": str(exc)[:200]}

    verdict = result.get("verdict") or "NO_GIT"
    if args.quiet:
        print(verdict)
    elif args.json:
        print(json.dumps(result, ensure_ascii=False, indent=1))
    else:
        g = result.get("git", {})
        print(f"branch: {g.get('branch')}  head: {(g.get('head') or '')[:12]}")
        print(f"upstream: {g.get('upstream')}  ahead: {g.get('ahead')}  "
              f"behind: {g.get('behind')}")
        print(f"workBranch(config): {result.get('workBranch')}  "
              f"match: {result.get('workBranchMatch')}")
        print(f"mainRelation: {g.get('mainRelation')}  "
              f"({g.get('snapshotRef')} = 공개 스냅샷, 비교 기준 아님)")
        if result.get("warning"):
            print(f"warning: {result['warning']}")
        if result.get("note"):
            print(f"note: {result['note']}")
        print(f"verdict: {verdict}")
    if verdict == "OK_WORK_BRANCH":
        return 0
    if verdict in ("WRONG_BRANCH", "DETACHED", "NO_UPSTREAM"):
        return 2
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
