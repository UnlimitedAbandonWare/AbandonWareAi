#!/usr/bin/env python3
"""git_guard_fast.py — fast read-only secret gate. Same findings as
git_staged_guard.scan but blob reads go through ONE `git cat-file --batch`
process instead of two processes per blob, and clean blob results are cached
under var/git-guard-cache/ (keyed by scanner version + blob oid).

Rule single-source: imports PATTERNS, MAX_BYTES, path_rule, snapshot,
GuardFailure from git_staged_guard — never copies them. Findings keep the
"awx.git-staged-scan.v1" schema (pathHash + rule only; no paths, no values).

Exit: 0 = ok, 1 = blocking findings, 2 = scan failure. A failed scan never
returns 0.
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import git_staged_guard as gsg  # noqa: E402  single rule source

CACHE_REL = "var/git-guard-cache/clean-oids.jsonl"
ALLOW_REL = "configs/git-guard-allow.json"
ALLOW_FILE_ENV = "GIT_GUARD_FAST_ALLOW_FILE"
CACHE_TRIM_LINES = 100_000

# Rules whose allow-list entries are honored only under test/fixture paths.
KEY_RULES = ("provider-key", "github-token", "slack-token", "aws-access-key",
             "private-key")
ALLOW_PATH_GLOBS = ("scripts/test_*", "*/fixtures/*", "fixtures/*",
                    "src/test/**", "src/test/*")

_GIT_PROCS = [0]
_orig_gsg_git = gsg.git


def _counted_gsg_git(root, *args):
    _GIT_PROCS[0] += 1
    return _orig_gsg_git(root, *args)


gsg.git = _counted_gsg_git


class FastFailure(gsg.GuardFailure):
    pass


def _git_exe():
    exe = os.environ.get("GIT_GUARD_GIT", "git")
    return exe


def _prep_git_path():
    """When GIT_GUARD_GIT points at an exe, prepend its dir to PATH so the
    literal 'git' inside git_staged_guard resolves to the same binary."""
    exe = _git_exe()
    if os.sep in exe or "/" in exe:
        d = str(Path(exe).resolve().parent)
        if d not in os.environ.get("PATH", ""):
            os.environ["PATH"] = d + os.pathsep + os.environ.get("PATH", "")


def _git(root, *args, timeout=60):
    _GIT_PROCS[0] += 1
    try:
        r = subprocess.run([_git_exe(), "--no-optional-locks", *args],
                           cwd=root, capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise FastFailure("git-unavailable-or-timeout") from None
    if r.returncode:
        raise FastFailure("git-command-failed")
    return r.stdout


def _cat_batch(root, oids, timeout=180):
    """One `cat-file --batch` for all oids. Returns {oid: (size, bytes)}.
    Missing objects raise; output order follows request order."""
    result = {}
    oids = list(oids)
    if not oids:
        return result
    _GIT_PROCS[0] += 1
    try:
        p = subprocess.Popen([_git_exe(), "--no-optional-locks", "cat-file", "--batch"],
                             cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        out, _ = p.communicate(b"".join(o.encode() + b"\n" for o in oids),
                               timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise FastFailure("git-unavailable-or-timeout") from None
    if p.returncode:
        raise FastFailure("git-command-failed")
    pos = 0
    for oid in oids:
        nl = out.find(b"\n", pos)
        if nl < 0:
            raise FastFailure("incomplete-batch-header")
        header = out[pos:nl].decode("ascii", errors="replace")
        pos = nl + 1
        parts = header.split()
        if len(parts) < 2 or (parts[0] != oid and not parts[0].startswith(oid)):
            raise FastFailure("batch-order-mismatch")
        if parts[1] == "missing" or parts[1] != "blob":
            raise FastFailure("batch-object-missing-or-nonblob")
        size = int(parts[2])
        blob = out[pos:pos + size]
        if len(blob) != size:
            raise FastFailure("incomplete-blob-read")
        pos += size
        if pos < len(out) and out[pos:pos + 1] == b"\n":
            pos += 1
        result[oid] = (size, blob)
    return result


def scanner_version():
    override = os.environ.get("GIT_GUARD_FAST_SCANNER_VERSION")
    if override:
        return override
    h = hashlib.sha256()
    h.update(repr(gsg.PATTERNS).encode("utf-8"))
    h.update(str(gsg.MAX_BYTES).encode())
    h.update(hashlib.sha256(Path(gsg.__file__).read_bytes()).hexdigest().encode())
    h.update(b"git-guard-fast-v1")
    return h.hexdigest()


def _cache_load(path):
    """Returns (set_of_clean_oids, raw_line_count). Any failure -> empty."""
    try:
        text = Path(path).read_text(encoding="utf-8")
    except (OSError, ValueError):
        return set(), 0
    ver = scanner_version()
    clean = set()
    try:
        for line in text.splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            if row.get("v") == ver and isinstance(row.get("oid"), str):
                clean.add(row["oid"])
    except (ValueError, TypeError, AttributeError):
        return set(), 0
    return clean, len(text.splitlines())


def _cache_save(path, newly_clean, prior_lines):
    try:
        p = Path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        ver = scanner_version()
        lines = [json.dumps({"v": ver, "oid": o}) for o in sorted(newly_clean)]
        if not lines:
            return
        if prior_lines and prior_lines > CACHE_TRIM_LINES:
            p.write_text("\n".join(lines) + "\n", encoding="utf-8")
        else:
            with p.open("a", encoding="utf-8", newline="\n") as f:
                f.write("\n".join(lines) + "\n")
    except OSError:
        pass  # cache is best-effort; a write failure never changes results


def _blob_oid(repo_format, data):
    try:
        h = hashlib.new(repo_format)
    except ValueError:
        return None
    h.update(b"blob %d\0" % len(data) + data)
    return h.hexdigest()


def _allow_path_ok(path):
    p = path.replace("\\", "/")
    return any(fnmatch.fnmatch(p, g) for g in ALLOW_PATH_GLOBS)


def _load_allow(root, allow_file=None):
    src = Path(allow_file) if allow_file else Path(root) / ALLOW_REL
    try:
        doc = json.loads(src.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    entries = doc.get("entries", []) if isinstance(doc, dict) else []
    return [e for e in entries if isinstance(e, dict)] if isinstance(entries, list) else []


def _is_allowed(finding, allow_entries):
    """finding has internal keys path/rule/oid. Entry must match all three
    and carry a non-empty reason. Key rules additionally require a
    test/fixture path."""
    for e in allow_entries:
        if not isinstance(e.get("reason"), str) or not e["reason"].strip():
            continue
        if e.get("path") != finding["path"] or e.get("rule") != finding["rule"]:
            continue
        e_oid, f_oid = e.get("oid"), finding.get("oid")
        if not isinstance(e_oid, str) or not re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", e_oid) or e_oid != f_oid:
            continue
        if set(finding["rule"].split(",")) & set(KEY_RULES) and not _allow_path_ok(finding["path"]):
            continue
        return True
    return False


# Public entry points keep every consumer on this exact allowance contract.
load_allow = _load_allow
is_allowed = _is_allowed


def _content_rules(blob):
    rules = [name for name, pattern in gsg.PATTERNS if re.search(pattern, blob)]
    if b"\0" in blob:
        rules.append("binary-scan-unavailable")
    return rules


def _finalize(result, changed, scanned, fast, findings, allowed, started, procs0):
    fast["elapsedMs"] = int((time.perf_counter() - started) * 1000)
    fast["gitProcesses"] = _GIT_PROCS[0] - procs0
    result.update({"changedCount": len(changed), "scannedBlobCount": scanned,
                   "findings": findings, "allowed": allowed, "fast": fast,
                   "ok": not findings})
    return result


def scan_staged(root, allow_entries=None, cache_file=None, expected_paths=None,
                expected_index=None, _before_second_snapshot=None):
    started = time.perf_counter()
    procs0 = _GIT_PROCS[0]
    root = Path(root).resolve()
    actual = Path(_git(root, "rev-parse", "--show-toplevel").decode("utf-8").strip()).resolve()
    if actual != root:
        raise FastFailure("repository-root-mismatch")
    identity, entries = gsg.snapshot(root)
    if expected_index is not None and identity != expected_index:
        raise FastFailure("index-changed-before-scan")
    changed = set(p.decode("utf-8") for p in
                  _git(root, "diff", "--cached", "--name-only", "-z").split(b"\0") if p)
    if expected_paths is not None and changed != set(expected_paths):
        raise FastFailure("staged-scope-mismatch")
    clean_cache, prior_lines = _cache_load(cache_file or Path(root) / CACHE_REL)
    fast = {"cacheHit": 0, "cacheMiss": 0, "cacheEnabled": True}
    need, plan = [], []
    for path in sorted(changed):
        rule = gsg.path_rule(path)
        if rule:
            plan.append((path, rule, None))
            continue
        entry = entries.get(path)
        if entry is None:
            continue  # staged deletion
        mode, oid = entry
        if mode not in ("100644", "100755"):
            plan.append((path, "symlink-or-submodule", oid))
            continue
        plan.append((path, None, oid))
        if oid in clean_cache:
            fast["cacheHit"] += 1
        else:
            fast["cacheMiss"] += 1
            need.append(oid)
    blobs = _cat_batch(root, dict.fromkeys(need))
    raw_findings, scanned, newly_clean = [], 0, set()
    for path, rule, oid in plan:
        if rule is None:
            if oid in clean_cache:
                continue  # clean by cache; not re-scanned, produces no finding
            size, blob = blobs[oid]
            if size > gsg.MAX_BYTES:
                rule = "blob-size-limit"
            else:
                scanned += 1
                rules = _content_rules(blob)
                if rules:
                    rule = ",".join(rules)
                else:
                    newly_clean.add(oid)
            if rule is None:
                continue
        raw_findings.append({"path": path, "rule": rule, "oid": oid,
                             "pathHash": hashlib.sha256(path.encode()).hexdigest()})
    if _before_second_snapshot:
        _before_second_snapshot()
    after, _ = gsg.snapshot(root)
    if after != identity:
        raise FastFailure("index-changed-during-scan")
    _cache_save(cache_file or Path(root) / CACHE_REL, newly_clean, prior_lines)
    findings, allowed = _split_allowed(raw_findings, allow_entries)
    result = {"schema": "awx.git-staged-scan.v1", "scanner": "fast-batch-v1",
              "scope": "changed-staged-full-blobs", "indexIdentity": identity}
    return _finalize(result, changed, scanned, fast, findings, allowed, started, procs0)


def _split_allowed(raw_findings, allow_entries):
    findings, allowed = [], []
    for f in raw_findings:
        pub = {"pathHash": f["pathHash"], "rule": f["rule"]}
        if _is_allowed(f, allow_entries or []):
            allowed.append(pub)
        else:
            findings.append(pub)
    return findings, allowed


def _diff_raw(root, a, b):
    raw = _git(root, "diff", "--raw", "-z", "--no-renames", "--abbrev=40", a, b)
    out, i = [], 0
    parts = raw.split(b"\0")
    for part in parts:
        if not part:
            continue
        if part.startswith(b":"):
            header = part[1:].decode("ascii", errors="replace").split()
            out.append({"header": header})
        else:
            if not out:
                raise FastFailure("diff-parse-error")
            out[-1].setdefault("paths", []).append(part.decode("utf-8", errors="strict"))
    entries = []
    for rec in out:
        h = rec["header"]
        if len(h) < 5 or not rec.get("paths"):
            raise FastFailure("diff-parse-error")
        old_mode, new_mode, old_oid, new_oid, status = h[0], h[1], h[2], h[3], h[4]
        path = rec["paths"][-1]
        if status.startswith("D") or set(new_oid) == {"0"}:
            continue  # deleted on the B side: nothing to scan
        entries.append((path, new_mode, new_oid))
    return entries


def scan_diff(root, a, b, allow_entries=None, cache_file=None):
    started = time.perf_counter()
    procs0 = _GIT_PROCS[0]
    root = Path(root).resolve()
    actual = Path(_git(root, "rev-parse", "--show-toplevel").decode("utf-8").strip()).resolve()
    if actual != root:
        raise FastFailure("repository-root-mismatch")
    entries = _diff_raw(root, a, b)
    clean_cache, prior_lines = _cache_load(cache_file or Path(root) / CACHE_REL)
    fast = {"cacheHit": 0, "cacheMiss": 0, "cacheEnabled": True}
    need, plan = [], []
    for path, mode, oid in sorted(entries):
        rule = gsg.path_rule(path)
        if rule:
            plan.append((path, rule, oid))
            continue
        if mode not in ("100644", "100755"):
            plan.append((path, "symlink-or-submodule", oid))
            continue
        plan.append((path, None, oid))
        if oid in clean_cache:
            fast["cacheHit"] += 1
        else:
            fast["cacheMiss"] += 1
            need.append(oid)
    blobs = _cat_batch(root, dict.fromkeys(need))
    raw_findings, scanned, newly_clean = [], 0, set()
    for path, rule, oid in plan:
        if rule is None:
            if oid in clean_cache:
                continue
            size, blob = blobs[oid]
            if size > gsg.MAX_BYTES:
                rule = "blob-size-limit"
            else:
                scanned += 1
                rules = _content_rules(blob)
                if rules:
                    rule = ",".join(rules)
                else:
                    newly_clean.add(oid)
            if rule is None:
                continue
        raw_findings.append({"path": path, "rule": rule, "oid": oid,
                             "pathHash": hashlib.sha256(path.encode()).hexdigest()})
    _cache_save(cache_file or Path(root) / CACHE_REL, newly_clean, prior_lines)
    findings, allowed = _split_allowed(raw_findings, allow_entries)
    result = {"schema": "awx.git-staged-scan.v1", "scanner": "fast-batch-v1",
              "scope": "changed-diff-full-blobs", "diffRange": f"{a}..{b}"}
    return _finalize(result, entries, scanned, fast, findings, allowed, started, procs0)


def scan_paths(root, paths, allow_entries=None):
    started = time.perf_counter()
    procs0 = _GIT_PROCS[0]
    root = Path(root).resolve()
    try:
        repo_format = _git(root, "rev-parse", "--show-object-format").decode().strip()
    except gsg.GuardFailure:
        repo_format = "sha1"
    fast = {"cacheHit": 0, "cacheMiss": 0, "cacheEnabled": False}
    raw_findings, scanned, missing = [], 0, 0
    for path in sorted(set(p.replace("\\", "/").strip("/") for p in paths if p.strip())):
        rule = gsg.path_rule(path)
        oid = None
        if rule is None:
            fp = root / path
            try:
                blob = fp.read_bytes()
            except OSError:
                missing += 1
                continue
            oid = _blob_oid(repo_format, blob)
            if len(blob) > gsg.MAX_BYTES:
                rule = "blob-size-limit"
            else:
                scanned += 1
                rules = _content_rules(blob)
                if rules:
                    rule = ",".join(rules)
            if rule is None:
                continue
        else:
            fp = root / path
            try:
                oid = _blob_oid(repo_format, fp.read_bytes())
            except OSError:
                pass
        raw_findings.append({"path": path, "rule": rule, "oid": oid,
                             "pathHash": hashlib.sha256(path.encode()).hexdigest()})
    findings, allowed = _split_allowed(raw_findings, allow_entries)
    result = {"schema": "awx.git-staged-scan.v1", "scanner": "fast-batch-v1",
              "scope": "paths-file-full-blobs", "missingCount": missing}
    return _finalize(result, paths, scanned, fast, findings, allowed, started, procs0)


def _reference_diff(root, entries):
    """Old-style reference for --diff: two git processes per blob, identical
    rule semantics. Used only for --reference parity checks."""
    fast0 = _GIT_PROCS[0]
    t0 = time.perf_counter()
    findings, scanned = [], 0
    for path, mode, oid in sorted(entries):
        rule = gsg.path_rule(path)
        if rule is None:
            if mode not in ("100644", "100755"):
                rule = "symlink-or-submodule"
            else:
                size = int(_git(root, "cat-file", "-s", oid))
                if size > gsg.MAX_BYTES:
                    rule = "blob-size-limit"
                else:
                    blob = _git(root, "cat-file", "blob", oid)
                    if len(blob) != size:
                        raise FastFailure("incomplete-blob-read")
                    scanned += 1
                    rules = _content_rules(blob)
                    if rules:
                        rule = ",".join(rules)
        if rule:
            findings.append({"pathHash": hashlib.sha256(path.encode()).hexdigest(),
                             "rule": rule})
    return findings, {"elapsedMs": int((time.perf_counter() - t0) * 1000),
                      "gitProcesses": _GIT_PROCS[0] - fast0}


def _findings_set(findings):
    return {(f["pathHash"], f["rule"]) for f in findings}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default=".")
    ap.add_argument("--staged", action="store_true")
    ap.add_argument("--check-allow-stdin", action="store_true",
                    help="partition metadata-only path/rule/oid records")
    ap.add_argument("--diff", nargs=2, metavar=("A", "B"))
    ap.add_argument("--paths-file")
    ap.add_argument("--reference", action="store_true")
    ap.add_argument("--allow-file")
    ap.add_argument("--cache-file")
    ap.add_argument("--no-cache", action="store_true")
    ap.add_argument("--expected-paths-file")
    ap.add_argument("--expected-index")
    ap.add_argument("--json", action="store_true", help="accepted for compatibility; output is always JSON")
    args = ap.parse_args()
    _prep_git_path()
    root = Path(args.root).resolve()
    cache_file = Path(args.cache_file) if args.cache_file else None
    if args.no_cache:
        cache_file = Path(os.devnull)
    allow_entries = _load_allow(root, args.allow_file)
    if args.check_allow_stdin:
        try:
            records = json.load(sys.stdin)
            if not isinstance(records, list) or not all(
                    isinstance(f, dict) and all(isinstance(f.get(k), str)
                                                for k in ("path", "rule", "oid"))
                    for f in records):
                raise ValueError("invalid-allowance-metadata")
            print(json.dumps([is_allowed(f, allow_entries) for f in records]))
            return 0
        except (OSError, ValueError):
            print(json.dumps({"ok": False, "reason": "invalid-allowance-metadata"}))
            return 2
    try:
        expected = None
        if args.expected_paths_file:
            expected = json.loads(Path(args.expected_paths_file).read_text(encoding="utf-8"))
            if not isinstance(expected, list) or not all(isinstance(p, str) for p in expected):
                raise FastFailure("invalid-expected-paths")
        if args.diff:
            a, b = args.diff
            result = scan_diff(root, a, b, allow_entries, cache_file)
            if args.reference:
                ref_findings, ref_meta = _reference_diff(root, _diff_raw(root, a, b))
                result["reference"] = dict(ref_meta)
                result["reference"]["findingsEqual"] = (
                    _findings_set(ref_findings) ==
                    _findings_set(result["findings"]) | _findings_set(result["allowed"]))
                result["reference"]["mismatch"] = sorted(
                    [{"pathHash": h, "rule": r} for h, r in
                     (_findings_set(ref_findings) ^
                      (_findings_set(result["findings"]) | _findings_set(result["allowed"])))],
                    key=lambda d: (d["pathHash"], d["rule"]))
        elif args.paths_file:
            paths = [l for l in Path(args.paths_file).read_text(encoding="utf-8").splitlines()
                     if l.strip() and not l.lstrip().startswith("#")]
            result = scan_paths(root, paths, allow_entries)
        else:
            result = scan_staged(root, allow_entries, cache_file, expected,
                                 args.expected_index)
            if args.reference:
                ref0 = _GIT_PROCS[0]
                t0 = time.perf_counter()
                ref = gsg.scan(root)
                result["reference"] = {"elapsedMs": int((time.perf_counter() - t0) * 1000),
                                       "gitProcesses": _GIT_PROCS[0] - ref0}
                result["reference"]["findingsEqual"] = (
                    _findings_set(ref["findings"]) ==
                    _findings_set(result["findings"]) | _findings_set(result["allowed"]))
                result["reference"]["mismatch"] = sorted(
                    [{"pathHash": h, "rule": r} for h, r in
                     (_findings_set(ref["findings"]) ^
                      (_findings_set(result["findings"]) | _findings_set(result["allowed"])))],
                    key=lambda d: (d["pathHash"], d["rule"]))
    except (gsg.GuardFailure, OSError, ValueError) as failure:
        result = {"schema": "awx.git-staged-scan.v1", "ok": False,
                  "reason": str(failure) if isinstance(failure, gsg.GuardFailure)
                            else "scan-input-unreadable"}
        print(json.dumps(result, ensure_ascii=True))
        return 2
    print(json.dumps(result, ensure_ascii=True))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
