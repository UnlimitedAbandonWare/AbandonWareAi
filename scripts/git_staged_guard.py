"""Read-only staged-blob gate used by git_secret_guard.ps1; never prints content."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

MAX_BYTES = 2 * 1024 * 1024
PATTERNS = (
    ("provider-key", rb"(?<![A-Za-z0-9_-])(?:sk-|gsk_|pcsk_|AIza)[A-Za-z0-9_-]{20,}"),
    ("supabase-key", rb"(?<![A-Za-z0-9_-])(?:sb_secret_|sb_publishable_|sbp_)[A-Za-z0-9_-]{10,}"),
    ("private-key", rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    ("sensitive-assignment", rb"(?i)(?:api[-_]?key|client[-_]?secret|owner[-_]?token|password|authorization)\s*[:=]\s*['\x22]?(?!\$\{|dummy\b|test\b|changeme\b|sk-local\b)[A-Za-z0-9_./+=:-]{24,}"),
)


class GuardFailure(Exception):
    pass


def git(root, *args):
    try:
        result = subprocess.run(["git", "--no-optional-locks", *args], cwd=root,
                                capture_output=True, timeout=20)
    except (OSError, subprocess.TimeoutExpired):
        raise GuardFailure("git-unavailable-or-timeout") from None
    if result.returncode:
        raise GuardFailure("git-command-failed")
    return result.stdout


def path_rule(path):
    parts = PurePosixPath(path).parts
    lower = tuple(p.lower() for p in parts)
    leaf = lower[-1] if lower else ""
    if not parts or path.startswith("/") or "\\" in path or ":" in path or any(p in (".", "..", "") for p in parts):
        return "invalid-index-path"
    if any(p in (".git", ".secrets", ".gradle", "build", "logs", "node_modules", "__patch_drop__", "models") for p in lower):
        return "private-or-generated-path"
    if path.lower().startswith(("data/", "var/", "config/secrets/")):
        return "runtime-data-path"
    template = leaf.endswith((".example", ".sample", ".template")) or any(x in leaf for x in ("example.", "sample.", "template."))
    if (leaf.startswith((".env", "shared.env")) and not template) or leaf.startswith(("apikey", "api-key")):
        return "credential-path"
    if leaf.endswith((".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".der", ".db", ".sqlite", ".sqlite3")):
        return "credential-or-database-path"
    if re.match(r"application-(?:secrets?|local|dev|prod|machine|private).*\.(?:ya?ml|properties)$", leaf) and not template:
        return "private-profile"
    if leaf in ("application.properties", "bootstrap.properties"):
        return "private-profile"
    return None


def snapshot(root):
    raw = git(root, "ls-files", "--stage", "-z")
    entries = {}
    for row in raw.split(b"\0"):
        if not row:
            continue
        header, path = row.split(b"\t", 1)
        mode, oid, stage = header.decode("ascii").split()
        if stage != "0":
            raise GuardFailure("unmerged-index")
        name = path.decode("utf-8", errors="strict")
        if name in entries:
            raise GuardFailure("duplicate-index-path")
        entries[name] = (mode, oid)
    return hashlib.sha256(raw).hexdigest(), entries


def scan(root, expected_paths=None, expected_index=None):
    root = Path(root).resolve()
    actual = Path(git(root, "rev-parse", "--show-toplevel").decode("utf-8").strip()).resolve()
    if actual != root:
        raise GuardFailure("repository-root-mismatch")
    identity, entries = snapshot(root)
    if expected_index is not None and identity != expected_index:
        raise GuardFailure("index-changed-before-scan")
    changed = set(p.decode("utf-8") for p in git(root, "diff", "--cached", "--name-only", "-z").split(b"\0") if p)
    if expected_paths is not None and changed != set(expected_paths):
        raise GuardFailure("staged-scope-mismatch")
    findings = []
    scanned = 0
    for path in sorted(changed):
        rule = path_rule(path)
        entry = entries.get(path)  # A staged deletion has no blob to inspect.
        if rule:
            findings.append({"pathHash": hashlib.sha256(path.encode()).hexdigest(), "rule": rule})
            continue
        if entry is None:
            continue
        mode, oid = entry
        if mode not in ("100644", "100755"):
            rule = "symlink-or-submodule"
        else:
            size = int(git(root, "cat-file", "-s", oid))
            if size > MAX_BYTES:
                rule = "blob-size-limit"
            else:
                blob = git(root, "cat-file", "blob", oid)
                if len(blob) != size:
                    raise GuardFailure("incomplete-blob-read")
                scanned += 1
                rules = [name for name, pattern in PATTERNS if re.search(pattern, blob)]
                if b"\0" in blob:
                    rules.append("binary-scan-unavailable")
                if rules:
                    rule = ",".join(rules)
        if rule:
            findings.append({"pathHash": hashlib.sha256(path.encode()).hexdigest(), "rule": rule})
    after, _ = snapshot(root)
    if after != identity:
        raise GuardFailure("index-changed-during-scan")
    return {"schema": "awx.git-staged-scan.v1", "scanner": "builtin-patterns-v1",
            "scope": "changed-staged-full-blobs", "indexIdentity": identity,
            "changedCount": len(changed), "scannedBlobCount": scanned,
            "findings": findings, "ok": not findings}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--expected-paths-file")
    parser.add_argument("--expected-index")
    args = parser.parse_args()
    try:
        expected = json.loads(Path(args.expected_paths_file).read_text(encoding="utf-8")) if args.expected_paths_file else None
        if expected is not None and (not isinstance(expected, list) or not all(isinstance(p, str) for p in expected)):
            raise GuardFailure("invalid-expected-paths")
        result = scan(args.root, expected, args.expected_index)
    except (GuardFailure, OSError, ValueError) as failure:
        result = {"ok": False, "reason": str(failure) if isinstance(failure, GuardFailure) else "scan-input-unreadable"}
    print(json.dumps(result, ensure_ascii=True))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())

