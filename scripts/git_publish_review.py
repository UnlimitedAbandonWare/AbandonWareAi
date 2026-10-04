#!/usr/bin/env python3
"""Read-only publish-review: candidate tree + outgoing history + push target.

Verifies the *actual* bytes a future approved push would send -- the candidate
tree AND the added blobs inside each outgoing commit -- plus the effective
push URL (host/owner/repo after url.*.insteadOf rewrites), the ref/OID pairs
from pre-push stdin when in hook mode, and ancestry (fast-forward) against
the recorded remote OID. Never fetches, never pushes, never prints secret
values: findings carry a path hash and rule id only.

Verdicts: CLEAN (all checks pass), BLOCKED (a hard check failed),
UNKNOWN (a required check could not complete -- never treated as pass).
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
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
from git_staged_guard import MAX_BYTES, PATTERNS, path_rule  # noqa: E402
from git_guard_fast import load_allow, is_allowed  # noqa: E402

SCHEMA = "awx.publish-review.v1"
GIT_TIMEOUT = 20
HISTORY_COMMIT_LIMIT = 500
ZERO_OID = "0" * 40
SAFE_ENV = {
    "GIT_OPTIONAL_LOCKS": "0",
    "GIT_TERMINAL_PROMPT": "0",
    "GIT_NO_LAZY_FETCH": "1",
}


def sanitize_stderr(data: bytes) -> str:
    text = re.sub(r"(://|//)[^/@\s]+@", r"\1***@",
                  data.decode("utf-8", errors="replace"))
    return " | ".join(ln for ln in text.splitlines() if ln.strip())[:200]


class ReviewError(Exception):
    pass


def git(root: Path, *args: str, timeout: int = GIT_TIMEOUT) -> bytes:
    env = dict(os.environ)
    env.update(SAFE_ENV)
    try:
        proc = subprocess.run(["git", "--no-optional-locks", *args],
                              cwd=root, capture_output=True,
                              timeout=timeout, env=env, check=False)
    except subprocess.TimeoutExpired:
        raise ReviewError("git-timeout") from None
    except OSError:
        raise ReviewError("git-unavailable") from None
    if proc.returncode:
        raise ReviewError(
            f"git-exit-{proc.returncode}:{sanitize_stderr(proc.stderr)}")
    return proc.stdout


def git_optional(root: Path, *args: str) -> bytes:
    """For commands where exit 1 is a normal answer (config --get-regexp)."""
    env = dict(os.environ)
    env.update(SAFE_ENV)
    try:
        proc = subprocess.run(["git", "--no-optional-locks", *args],
                              cwd=root, capture_output=True,
                              timeout=GIT_TIMEOUT, env=env, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return b""
    return proc.stdout if proc.returncode in (0, 1) else b""


def mask_url(url: str) -> str:
    return re.sub(r"(://|//)[^/@\s]+@", r"\1***@", url.strip())


def parse_target(url: str) -> dict:
    """Effective host/owner/repo from a push URL; credentials flagged."""
    url = url.strip()
    parsed = urlsplit(url if "://" in url else f"ssh://{url.replace(':', '/', 1)}")
    parts = [p for p in parsed.path.split("/") if p]
    repo = parts[-1][:-4] if parts and parts[-1].endswith(".git") else (parts[-1] if parts else "")
    owner = parts[-2] if len(parts) >= 2 else ""
    return {"urlMasked": mask_url(url),
            "scheme": parsed.scheme or "file",
            "host": (parsed.hostname or "").lower(),
            "owner": owner, "repo": repo,
            "hasCredentials": bool(parsed.username or parsed.password)}


def scan_blob(root: Path, oid: str, path: str, findings: list, scanned: set,
              allowed=None, allow_entries=None) -> int:
    identity = (path, oid)  # Allowances and path restrictions are path-specific.
    if identity in scanned or oid == ZERO_OID:
        return 0
    scanned.add(identity)
    def record(rule):
        finding = {"pathHash": hashlib.sha256(path.encode()).hexdigest(), "rule": rule}
        metadata = {"path": path, "rule": rule, "oid": oid}
        if allowed is not None and is_allowed(metadata, allow_entries or []):
            allowed.append(finding)
        else:
            findings.append(finding)
    rule = path_rule(path)
    if rule:
        # Existing private/path gates retain precedence and never read values.
        findings.append({"pathHash": hashlib.sha256(path.encode()).hexdigest(), "rule": rule})
        return 0
    size = int(git(root, "cat-file", "-s", oid))
    if size > MAX_BYTES:
        findings.append({"pathHash": hashlib.sha256(path.encode()).hexdigest(),
                         "rule": "blob-size-limit"})
        return 0
    blob = git(root, "cat-file", "blob", oid)
    if len(blob) != size:
        raise ReviewError("incomplete-blob-read")
    rules = [name for name, pattern in PATTERNS if re.search(pattern, blob)]
    if b"\0" in blob:
        rules.append("binary-scan-unavailable")
    if rules:
        record(",".join(rules))
    return 1


def scan_tree(root: Path, candidate: str) -> dict:
    raw = git(root, "ls-tree", "-r", "-z", "--full-tree", candidate)
    findings: list = []
    allowed: list = []
    allow_entries = load_allow(root)
    scanned: set = set()
    files = blobs = 0
    for row in raw.split(b"\0"):
        if not row:
            continue
        header, _, path_b = row.partition(b"\t")
        fields = header.decode("ascii").split()
        if len(fields) != 3 or fields[1] != "blob":
            continue
        path = path_b.decode("utf-8", errors="surrogateescape")
        files += 1
        blobs += scan_blob(root, fields[2], path, findings, scanned, allowed, allow_entries)
    return {"scope": "candidate-tree", "commit": candidate,
            "fileCount": files, "scannedBlobCount": blobs,
            "findings": findings, "allowed": allowed, "ok": not findings}


def scan_history(root: Path, base: str, candidate: str) -> dict:
    """Scan blobs *added* by each commit in base..candidate."""
    commits = [ln for ln in git(root, "rev-list", "--no-merges",
                                f"{base}..{candidate}").decode().splitlines() if ln]
    merge_commits = [ln for ln in git(root, "rev-list", "--merges",
                                      f"{base}..{candidate}").decode().splitlines() if ln]
    commits += merge_commits
    truncated = len(commits) > HISTORY_COMMIT_LIMIT
    commits = commits[:HISTORY_COMMIT_LIMIT]
    findings: list = []
    allowed: list = []
    allow_entries = load_allow(root)
    scanned: set = set()
    blobs = 0
    for oid in commits:
        raw = git(root, "diff-tree", "-r", "--root", "--diff-filter=A", "-z", oid)
        # -z layout: ':om nm ooid noid S\0path\0[:next...]' -- pairs of records.
        records = raw.split(b"\0")
        idx = 0
        while idx < len(records):
            rec = records[idx]
            idx += 1
            if not rec.startswith(b":"):
                continue
            fields = rec.decode("ascii", "replace").split()
            if len(fields) < 5:
                continue
            new_oid = fields[3]
            if idx < len(records):
                path = records[idx].decode("utf-8", "surrogateescape")
                idx += 1
            else:
                path = ""
            blobs += scan_blob(root, new_oid, path, findings, scanned, allowed, allow_entries)
    return {"scope": "outgoing-history", "base": base, "candidate": candidate,
            "commitCount": len(commits), "truncated": truncated,
            "scannedBlobCount": blobs, "findings": findings, "allowed": allowed,
            "ok": not findings and not truncated}


def review_remote(root: Path, name: str, allow_target: str) -> dict:
    fetch_url = git(root, "remote", "get-url", name).decode().strip()
    push_urls = [u.strip() for u in
                 git(root, "remote", "get-url", "--push", "--all", name)
                 .decode().splitlines() if u.strip()]
    rewrites = [ln for ln in
                git_optional(root, "config", "--get-regexp",
                             r"^url\..*\.(insteadof|pushinsteadof)")
                .decode().splitlines()]
    targets = [parse_target(u) for u in push_urls or [fetch_url]]
    reasons = []
    if len(push_urls) > 1:
        reasons.append("multiple-pushurls")
    for t in targets:
        if t["hasCredentials"]:
            reasons.append("url-has-credentials")
        actual = f"{t['host']}/{t['owner']}/{t['repo']}".lower()
        if not allow_target:
            reasons.append(f"target-unverified:{actual}")
        elif actual != allow_target.lower():
            reasons.append(f"push-target-mismatch:{actual}")
    if rewrites:
        reasons.append("url-rewrite-present")
    return {"remote": name, "targets": targets,
            "urlRewrites": [mask_url(r) for r in rewrites],
            "reasons": reasons, "ok": not reasons}


def review_refs(root: Path, lines: list[str], allow_ref: str) -> dict:
    rows = []
    reasons = []
    if len(lines) > 1:
        reasons.append("multi-ref-push")
    for line in lines:
        fields = line.split()
        if len(fields) != 4:
            reasons.append("hook-stdin-unparseable")
            continue
        local_ref, local_oid, remote_ref, remote_oid = fields
        row = {"localRef": local_ref, "localOid": local_oid,
               "remoteRef": remote_ref, "remoteOid": remote_oid}
        if local_oid == ZERO_OID:
            row["verdict"] = "push-delete-blocked"
            reasons.append("push-delete")
        elif allow_ref and remote_ref != allow_ref:
            row["verdict"] = "unexpected-ref"
            reasons.append(f"unexpected-ref:{remote_ref}")
        elif remote_oid != ZERO_OID:
            try:
                git(root, "cat-file", "-e", remote_oid)
                res = subprocess.run(
                    ["git", "--no-optional-locks", "merge-base", "--is-ancestor",
                     remote_oid, local_oid], cwd=root, capture_output=True,
                    timeout=GIT_TIMEOUT, env={**os.environ, **SAFE_ENV})
                if res.returncode == 0:
                    row["verdict"] = "fast-forward"
                elif res.returncode == 1:
                    row["verdict"] = "non-fast-forward"
                    reasons.append("non-fast-forward")
                else:
                    row["verdict"] = "ancestry-unknown"
                    reasons.append("ancestry-check-failed")
            except ReviewError:
                row["verdict"] = "remote-oid-unknown-locally"
                reasons.append("remote-oid-unknown")
        else:
            row["verdict"] = "new-remote-ref"
        rows.append(row)
    return {"refs": rows, "reasons": sorted(set(reasons)),
            "ok": not reasons}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--candidate", default="HEAD")
    parser.add_argument("--base", default=None,
                        help="remote tip OID the outgoing history starts from")
    parser.add_argument("--remote", default=None)
    parser.add_argument("--allow-target", default="",
                        help="expected host/owner/repo, e.g. github.com/org/repo")
    parser.add_argument("--allow-ref", default="",
                        help="only remote ref permitted, e.g. refs/heads/main")
    parser.add_argument("--hook-stdin", action="store_true",
                        help="read pre-push stdin ref lines and evaluate them")
    parser.add_argument("--approval-env", default="AWX_PUBLISH_APPROVED")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    root = Path(args.root).resolve()

    result: dict = {"schemaVersion": SCHEMA, "observedAtUtc":
                    time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                    "root": str(root), "candidate": args.candidate,
                    "reasons": []}
    incomplete = False
    try:
        stdin_lines: list[str] = []
        if args.hook_stdin:
            stdin_lines = [ln for ln in sys.stdin.read().splitlines() if ln.strip()]
            fields = stdin_lines[0].split() if len(stdin_lines) == 1 else []
            if len(fields) == 4 and fields[1] != ZERO_OID:
                args.candidate = fields[1]
                result["candidateSource"] = "hook-stdin"
            if len(fields) == 4 and not args.base and fields[3] != ZERO_OID:
                args.base = fields[3]
                result["baseSource"] = "hook-stdin-remote-oid"
        candidate_oid = git(root, "rev-parse", "--verify",
                            f"{args.candidate}^{{commit}}").decode().strip()
        result["candidateOid"] = candidate_oid
        result["treeScan"] = scan_tree(root, candidate_oid)
        if not result["treeScan"]["ok"]:
            result["reasons"].append("tree-secret-or-path-finding")
        if args.base:
            if subprocess.run(["git", "--no-optional-locks", "cat-file", "-e",
                               args.base], cwd=root, capture_output=True).returncode:
                result["historyScan"] = {"status": "not-run",
                                         "reason": "base-oid-not-in-object-store"}
                result["reasons"].append("history-not-verifiable")
                incomplete = True
            else:
                result["historyScan"] = scan_history(root, args.base, candidate_oid)
                if not result["historyScan"]["ok"]:
                    result["reasons"].append("history-secret-or-truncated")
        else:
            result["historyScan"] = {"status": "skipped", "reason": "no-base"}
            incomplete = True
        if args.remote:
            result["remoteCheck"] = review_remote(root, args.remote,
                                                 args.allow_target)
            result["reasons"] += result["remoteCheck"]["reasons"]
        if args.hook_stdin:
            result["refCheck"] = review_refs(root, stdin_lines, args.allow_ref)
            result["reasons"] += result["refCheck"]["reasons"]
            result["approvalEnvPresent"] = bool(os.environ.get(args.approval_env))
            if not result["approvalEnvPresent"]:
                result["reasons"].append("approval-env-absent")
    except ReviewError as failure:
        result["reasons"].append(str(failure))
        incomplete = True
    except (OSError, ValueError):
        result["reasons"].append("review-input-error")
        incomplete = True

    result["reasons"] = sorted(set(result["reasons"]))
    if result["reasons"]:
        result["verdict"] = "UNKNOWN" if incomplete and not any(
            r.startswith(("tree-secret", "history-secret", "push-",
                          "multi-ref", "unexpected-ref", "non-fast",
                          "url-", "approval-env"))
            for r in result["reasons"]) else "BLOCKED"
    else:
        result["verdict"] = "UNKNOWN" if incomplete else "CLEAN"
    if args.json:
        print(json.dumps(result, ensure_ascii=True, indent=1, sort_keys=True))
    else:
        print(f"[publish-review] verdict={result['verdict']} "
              f"candidate={result.get('candidateOid', args.candidate)[:12]}")
        for reason in result["reasons"]:
            print(f"[publish-review][reason] {reason}")
        for scope in ("treeScan", "historyScan"):
            scan = result.get(scope, {})
            for finding in scan.get("findings", []):
                print(f"[publish-review][finding] scope={scope} "
                      f"pathHash={finding['pathHash'][:16]} rule={finding['rule']}")
    return {"CLEAN": 0, "BLOCKED": 1}.get(result["verdict"], 3)


if __name__ == "__main__":
    sys.exit(main())
