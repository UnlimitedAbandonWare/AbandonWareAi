#!/usr/bin/env python3
"""baseline_sync_probe — is this tree on the expected git baseline?

Read-only: runs only `git rev-parse`, `git branch --show-current`,
`git worktree list --porcelain`, `git for-each-ref`, and
`git merge-base --is-ancestor`. Never fetches, checks out, resets, or writes.

Usage:
    python -B scripts/baseline_sync_probe.py --root . \
        --expect-head 4150b2822f2c --expect-branch codex/owned-runtime-browser-restart
    python -B scripts/baseline_sync_probe.py --root <other-worktree> --json

Git verdicts:
    MATCH           expected branch checked out and HEAD == expected sha
    AHEAD           expected branch, expected sha is a strict ancestor of HEAD
    BEHIND          expected branch, HEAD is a strict ancestor of expected sha
    DIVERGED        expected branch but unrelated tips, or a different branch
    DETACHED_OTHER  detached HEAD (headMatchesExpected reports sha equality)
    NO_GIT          root is not a git worktree / git unavailable
    EXPECTATION_MISSING  no --expect-head given; facts still reported

Any non-MATCH verdict prints the one-line summary BASELINE_MISMATCH with a
request for the user to confirm which tree is authoritative. Exit code: 0 on
MATCH, 3 otherwise (a gate, not an error), 2 on usage/internal failure.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

SCHEMA = "awx.baseline-sync-probe.v1"

DEFAULT_SYMBOLS = [
    "ChatRunRegistry", "JevGatewayClient", "JevEvaluationRuntime",
    "BraveSearchService", "LocalLlmProcessManager", "RagControl", "chat.js",
]

# Active source roots (SSOT: scripts/test_tree_contamination_report.py).
ACTIVE_PREFIXES = (
    "main/java/", "main/resources/",
    "app/src/main/java_clean/", "app/src/main/resources/",
    "src/test/java/", "src/chatUiTest/java/",
)
REFERENCE_PREFIXES = (
    "project/src/", "app/src/main/java/", "demo-1/", "lms-core/",
    "build/", "app/build/", "backups/", "archive/", "target/",
)
PRUNE_DIRS = {
    ".git", ".gradle", "build", "node_modules", "__pycache__", ".idea",
    ".next", "out", "dist", "data", "var", "__patch_drop__", ".secrets",
}


def run_git(root: Path, args: list[str], timeout: int = 30) -> tuple[int, str, str]:
    try:
        proc = subprocess.run(
            ["git", "-C", str(root), *args],
            capture_output=True, text=True, timeout=timeout,
        )
        return proc.returncode, proc.stdout, proc.stderr
    except (OSError, subprocess.TimeoutExpired) as exc:
        return 127, "", str(exc)


def normalize_sha(text: str) -> str:
    return text.strip().lower()


def sha_matches(head: str, expect: str) -> bool:
    """Prefix-safe compare: expected may be short (>=6 chars)."""
    return bool(expect) and head.startswith(normalize_sha(expect))


def _load_branch_context(root: Path) -> dict:
    """configs/git-branch-context.json — branch-role SSOT
    (docs/agents-rules/DEMO1-GIT-BRANCH-TOPOLOGY.md). Absent/bad -> {}."""
    try:
        cfg = root / "configs" / "git-branch-context.json"
        if cfg.is_file():
            data = json.loads(cfg.read_text(encoding="utf-8-sig"))
            if isinstance(data, dict):
                return data
    except (OSError, ValueError):
        pass
    return {}


def collect_git_facts(root: Path, runner=run_git) -> dict:
    facts: dict = {"gitAvailable": True}
    code, out, err = runner(root, ["rev-parse", "--show-toplevel"])
    if code != 0:
        return {"gitAvailable": False, "toplevel": None, "error": err.strip() or out.strip()}
    facts["toplevel"] = out.strip()

    code, out, _ = runner(root, ["rev-parse", "HEAD"])
    facts["head"] = out.strip() if code == 0 else None

    code, out, _ = runner(root, ["branch", "--show-current"])
    branch = out.strip() if code == 0 else ""
    facts["branch"] = branch or None
    facts["detached"] = not branch

    code, out, _ = runner(root, ["worktree", "list", "--porcelain"])
    worktrees = []
    if code == 0:
        current: dict = {}
        for line in out.splitlines():
            if line.startswith("worktree "):
                if current:
                    worktrees.append(current)
                current = {"path": line.split(" ", 1)[1]}
            elif line.startswith("HEAD "):
                current["head"] = line.split(" ", 1)[1]
            elif line.startswith("branch "):
                current["branch"] = line.split(" ", 1)[1].removeprefix("refs/heads/")
            elif line.strip() == "detached":
                current["detached"] = True
        if current:
            worktrees.append(current)
    facts["worktrees"] = worktrees

    code, out, _ = runner(
        root, ["for-each-ref", "refs/remotes/origin", "--format=%(refname:short) %(objectname)"]
    )
    remotes = {}
    if code == 0:
        for line in out.splitlines():
            parts = line.split()
            if len(parts) == 2:
                remotes[parts[0]] = parts[1]
    facts["originRefs"] = remotes
    facts["originMainSha"] = remotes.get("origin/main")
    # Branch topology (DEMO1-GIT-BRANCH-TOPOLOGY): the work branch tracks
    # origin/<workBranch>; origin/main is an unrelated public snapshot and is
    # never a diff/ahead-behind base. mainRelation only reports whether a
    # merge-base exists: ancestor | unrelated | unknown.
    ctx = _load_branch_context(root)
    work_ref = (f"{ctx.get('remote', 'origin')}/{ctx['workBranch']}"
                if ctx.get("workBranch") else None)
    facts["originWorkBranchSha"] = remotes.get(work_ref) if work_ref else None
    if not facts["originMainSha"]:
        facts["mainRelation"] = "unknown"
    else:
        code, out, _ = runner(root, ["merge-base", "HEAD", "origin/main"])
        facts["mainRelation"] = ("ancestor" if code == 0 and out.strip()
                                 else "unrelated" if code == 1 else "unknown")
    return facts


def classify_git(facts: dict, expect_head: str | None, expect_branch: str | None,
                 runner=run_git, root: Path | None = None) -> dict:
    result: dict = {"expectHead": expect_head, "expectBranch": expect_branch}
    if not facts.get("gitAvailable"):
        result["verdict"] = "NO_GIT"
        return result
    head = facts.get("head") or ""
    branch = facts.get("branch")
    result["headMatchesExpected"] = bool(expect_head) and sha_matches(head, expect_head)
    if not expect_head:
        result["verdict"] = "EXPECTATION_MISSING"
        return result

    # Can the expected sha be resolved as a commit in this repo?
    expected_resolvable = False
    if root is not None:
        code, _, _ = runner(root, ["rev-parse", "--verify", f"{expect_head}^{{commit}}"])
        expected_resolvable = code == 0
    result["expectedShaResolvable"] = expected_resolvable

    if facts.get("detached"):
        result["verdict"] = "DETACHED_OTHER"
        return result
    if expect_branch and branch != expect_branch:
        result["verdict"] = "DIVERGED"
        result["branchMismatch"] = True
        return result
    if sha_matches(head, expect_head):
        result["verdict"] = "MATCH"
        return result
    if not expected_resolvable:
        result["verdict"] = "DIVERGED"
        result["note"] = "expected sha not resolvable locally; ancestry unknown"
        return result
    code_a, _, _ = runner(root, ["merge-base", "--is-ancestor", expect_head, "HEAD"])
    code_b, _, _ = runner(root, ["merge-base", "--is-ancestor", "HEAD", expect_head])
    if code_a == 0:
        result["verdict"] = "AHEAD"
    elif code_b == 0:
        result["verdict"] = "BEHIND"
    else:
        result["verdict"] = "DIVERGED"
    return result


def classify_path(rel: str) -> str:
    rel = rel.replace("\\", "/")
    for prefix in ACTIVE_PREFIXES:
        if rel.startswith(prefix):
            return "active"
    for prefix in REFERENCE_PREFIXES:
        if rel.startswith(prefix):
            return "reference"
    return "other"


def scan_symbols(root: Path, symbols: list[str]) -> dict:
    wanted = {s: [] for s in symbols}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in PRUNE_DIRS]
        for name in filenames:
            for sym in symbols:
                if name == sym or name == sym + ".java":
                    full = Path(dirpath) / name
                    rel = os.path.relpath(full, root).replace("\\", "/")
                    wanted[sym].append({"path": rel, "location": classify_path(rel)})
    report = {}
    for sym, hits in wanted.items():
        locations = {h["location"] for h in hits}
        if not hits:
            status = "MISSING"
        elif "active" in locations:
            status = "ACTIVE"
        elif locations <= {"reference", "other"}:
            status = "REFERENCE_ONLY"
        else:
            status = "ACTIVE"
        report[sym] = {"status": status, "hits": hits[:20]}
    return report


def probe(root: Path, expect_head: str | None, expect_branch: str | None,
          symbols: list[str], runner=run_git) -> dict:
    facts = collect_git_facts(root, runner)
    verdict = classify_git(facts, expect_head, expect_branch, runner, root)
    symbol_report = scan_symbols(root, symbols) if root.is_dir() else {}
    match = verdict.get("verdict") == "MATCH"
    summary = "BASELINE_MATCH" if match else "BASELINE_MISMATCH"
    return {
        "schemaVersion": SCHEMA,
        "root": str(root),
        "git": facts,
        "verdict": verdict.get("verdict"),
        "verdictDetail": verdict,
        "symbols": symbol_report,
        "summary": summary,
        "oneLine": (
            "BASELINE_MATCH: tree is on the expected baseline."
            if match else
            f"BASELINE_MISMATCH: verdict={verdict.get('verdict')} - "
            "confirm with the user which tree is the authoritative baseline "
            "before patching (no checkout/reset/fetch performed)."
        ),
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--expect-head", default=None)
    ap.add_argument("--expect-branch", default=None)
    ap.add_argument("--symbol", dest="symbols", action="append", default=None,
                    help="symbol/file to locate; repeatable (default: R2 hotfix list)")
    ap.add_argument("--out", default=None, help="optional JSON output path")
    ap.add_argument("--json", action="store_true", help="JSON only, no summary line")
    args = ap.parse_args(argv)

    root = Path(os.path.abspath(args.root))
    symbols = args.symbols or DEFAULT_SYMBOLS
    result = probe(root, args.expect_head, args.expect_branch, symbols)
    text = json.dumps(result, ensure_ascii=True, indent=1)
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(text + "\n", encoding="utf-8")
    print(text)
    if not args.json:
        print(result["oneLine"], file=sys.stderr)
    return 0 if result["verdict"] == "MATCH" else 3


if __name__ == "__main__":
    raise SystemExit(main())
