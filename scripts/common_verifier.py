#!/usr/bin/env python3
"""common_verifier.py — agent-independent completion adjudicator (P6 WP3 contract).

An agent's own "PASS"/success JSON is a claim, never evidence. This verifier
runs the declared verification commands itself and applies the five blocking
rules from the MA3212IN audit (2026-10-01) before any PASS is allowed:

  1. a required command exited non-zero (or failed to launch)      -> FAIL
  2. exit 0 but zero tests were actually executed                  -> INCOMPLETE
  3. all required tests skipped                                    -> INCOMPLETE
  4. test files gained @Disabled/@Ignore/skip markers or lost
     assertions versus their recorded preimage                     -> REJECTED
  5. a declared source/test file digest changed after the run      -> INVALIDATED

Only when every required command exits 0, at least one test really ran,
skipped < total, no test weakening is detected, and every pinned digest still
matches does it emit VERIFIED_PENDING_APPROVAL (verified=true). Approval
itself stays a separate human/agent step — this tool never approves.

Usage:
  python -B scripts/common_verifier.py verify --spec <spec.json> [--root .]
  python -B scripts/common_verifier.py verify --argv "python -B x.py" \
      [--argv ...] [--test-file f] [--source-file f] [--test-preimage-dir d]
  python -B scripts/common_verifier.py adjudicate --result <result.json>

Spec JSON (all paths repo-relative to --root):
  {"taskId":"...","agent":"...","baseRevision":"<sha|null>",
   "commands":[{"id":"focused","argv":["python","-B","scripts/test_x.py"],
                "required":true,"timeoutSec":300}],
   "testFiles":["scripts/test_x.py"],
   "sourceFiles":["main/java/.../Foo.java"],
   "testPreimageDir":"data/agent-handoff/<task>/preimage-tests",
   "expectedMinTests":1}

Result JSON (for adjudicate): the "commands" array of a prior verify output
plus "testFiles"/"sourceFiles"/"digests" as produced by verify --spec.

Exit: 0 = VERIFIED_PENDING_APPROVAL · 2 = blocked (FAIL/INCOMPLETE/REJECTED/
INVALIDATED, see verdict) · 3 = usage error. JSON on stdout plus a single
summary line `verified=.. verdict=.. testCount=.. failures=.. digestMatches=..`.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time

SCHEMA = "awx.common-verifier.v1"
POLICY_DIGEST = "sha256:" + hashlib.sha256(
    b"common-verifier-rules-v1:FAIL|INCOMPLETE|REJECTED|INVALIDATED|VERIFIED_PENDING_APPROVAL"
).hexdigest()[:16]

VERDICT_OK = "VERIFIED_PENDING_APPROVAL"
BLOCKING = {"FAIL", "INCOMPLETE", "REJECTED", "INVALIDATED"}

# Test-count parsers covering JUnit/Gradle text, pytest summary lines and the
# repo's own `N/M cases behaved` convention. First match per pattern wins.
RE_TESTS_RUN = [
    re.compile(r"Tests?\s+run\s*:\s*(\d+)", re.I),
    re.compile(r"(\d+)\s+tests?\s+(?:run|executed|found|discovered)", re.I),
    re.compile(r"(\d+)\s+passed", re.I),                      # pytest
    re.compile(r"(\d+)\s*/\s*(\d+)\s+cases\s+behaved"),       # repo convention
    re.compile(r'"testCount"\s*:\s*(\d+)'),
    re.compile(r"\btestCount\s*=\s*(\d+)"),
]
RE_SKIPPED = [
    re.compile(r"skipped[=:]?\s*(\d+)", re.I),
    re.compile(r"(\d+)\s+skipped", re.I),
    re.compile(r"(\d+)\s+disabled", re.I),
]
RE_FAILED = [
    re.compile(r"Failures?\s*:\s*(\d+)", re.I),
    re.compile(r"(\d+)\s+failed", re.I),
    re.compile(r"failures[=:]?\s*(\d+)", re.I),
    re.compile(r'"failures"\s*:\s*(\d+)'),
]

# Skip/disable markers whose *addition* to a test file means the agent lowered
# the grading bar. Multi-language on purpose: Java, Python, JS/TS.
RE_DISABLE_MARKER = re.compile(
    r"@Disabled\b|@Ignore\b|@Test\s*\([^)]*enabled\s*=\s*false"
    r"|pytest\.mark\.skip|pytest\.mark\.skipif|unittest\.skip|@unittest\.skip"
    r"|@skip(?:if)?\s*\(|@SkipTest\b"
    r"|\b(?:it|describe|test)\.skip\s*\(|\bx(?:it|describe|test)\s*\("
    r"|assumeTrue\s*\(\s*false|Assumptions\.assumeFalse\s*\(\s*true",
    re.I)
RE_ASSERTION = re.compile(
    r"\bassert\w*\s*\(|\bassert\s+\w|Assertions?\.\w+\s*\(|expect\s*\(")

MAX_OUTPUT_TAIL = 8192


def utcnow() -> str:
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).isoformat()


def sha256_file(path: Path):
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        return None


def rel_of(root: Path, value: str) -> str:
    p = Path(value)
    if not p.is_absolute():
        p = root / p
    return str(p.resolve().relative_to(root.resolve())).replace("\\", "/")


def digest_map(root: Path, rel_paths: list) -> dict:
    out = {}
    for rel in rel_paths:
        out[rel] = sha256_file(root / rel)
    return out


def parse_metrics(text: str) -> dict:
    """Best-effort test accounting from captured output."""
    tests = 0
    for line in text.splitlines():
        for pat in RE_TESTS_RUN:
            m = pat.search(line)
            if m:
                if pat.pattern.startswith("(\\d+)\\s*/"):
                    tests = max(tests, int(m.group(2)))
                else:
                    tests = max(tests, int(m.group(1)))
                break
    skipped = 0
    failed = 0
    for line in text.splitlines():
        for pat in RE_SKIPPED:
            m = pat.search(line)
            if m:
                skipped = max(skipped, int(m.group(1)))
                break
        for pat in RE_FAILED:
            m = pat.search(line)
            if m:
                failed = max(failed, int(m.group(1)))
                break
    return {"testCount": tests, "skipped": skipped, "failures": failed}


def split_command(command: str) -> list:
    """Split a shell-style command string without eating Windows path
    separators: posix mode treats ``\\`` as an escape (``scripts\\x.py`` ->
    ``scriptsx.py``), so on nt use posix=False — quote characters stay in
    tokens and pass through to the child's own argv parsing."""
    return shlex.split(str(command), posix=(os.name != "nt"))


def run_command(root: Path, spec: dict) -> dict:
    argv = spec.get("argv")
    if argv is None and spec.get("command"):
        argv = split_command(spec["command"])
    if not argv:
        return {"id": spec.get("id"), "launchError": "argv-missing",
                "exitCode": None, "metrics": {"testCount": 0, "skipped": 0,
                                              "failures": 0}}
    cwd = root / spec["cwd"] if spec.get("cwd") else root
    timeout = float(spec.get("timeoutSec", 300))
    started = time.monotonic()
    try:
        proc = subprocess.run(
            [str(a) for a in argv], cwd=str(cwd), capture_output=True,
            text=True, encoding="utf-8", errors="replace", timeout=timeout)
        output = (proc.stdout or "") + "\n" + (proc.stderr or "")
        return {"id": spec.get("id") or argv[-1], "argv": [str(a) for a in argv],
                "exitCode": proc.returncode,
                "durationMs": int((time.monotonic() - started) * 1000),
                "metrics": parse_metrics(output),
                "outputTail": output[-MAX_OUTPUT_TAIL:]}
    except subprocess.TimeoutExpired:
        return {"id": spec.get("id") or argv[-1], "argv": [str(a) for a in argv],
                "exitCode": None, "launchError": f"timeout>{int(timeout)}s",
                "metrics": {"testCount": 0, "skipped": 0, "failures": 0}}
    except OSError as exc:
        return {"id": spec.get("id") or argv[-1], "argv": [str(a) for a in argv],
                "exitCode": None, "launchError": str(exc)[:200],
                "metrics": {"testCount": 0, "skipped": 0, "failures": 0}}


def diff_test_weakening(preimage_dir: Path, root: Path, rel: str) -> list:
    """Compare a declared test file against its recorded preimage.

    REJECTED-worthy: new skip/disable markers added, or the assertion-call
    count decreased (assertion weakening/removal)."""
    reasons = []
    pre_path = preimage_dir / rel
    if not pre_path.is_file():
        # also try basename-only layout
        alt = preimage_dir / Path(rel).name
        pre_path = alt if alt.is_file() else None
    if pre_path is None or pre_path.is_file() is False:
        return reasons  # no preimage -> nothing to compare
    cur_path = root / rel
    if not cur_path.is_file():
        reasons.append(f"test-file-deleted:{rel}")
        return reasons
    try:
        before = pre_path.read_text(encoding="utf-8", errors="replace")
        after = cur_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return reasons
    before_lines = set(before.splitlines())
    added = [ln for ln in after.splitlines() if ln not in before_lines]
    added_disabled = [ln for ln in added if RE_DISABLE_MARKER.search(ln)]
    if added_disabled:
        reasons.append(f"test-disabled-added:{rel}")
    if len(RE_ASSERTION.findall(after)) < len(RE_ASSERTION.findall(before)):
        reasons.append(f"test-assertion-weakened:{rel}")
    return reasons


def adjudicate(commands: list, test_files: list, source_files: list,
               root: Path, preimage_dir: Path | None,
               digests_pre: dict, digests_post: dict,
               expected_min: int) -> tuple[str, list, dict]:
    """Apply the five blocking rules in order; return (verdict, reasons, detail)."""
    reasons: list[str] = []
    required = [c for c in commands if c.get("required", True)]

    # Rule 1: non-zero exit / launch failure on a required command.
    for c in required:
        if c.get("launchError"):
            reasons.append(f"command-launch-error:{c.get('id')}")
        elif c.get("exitCode") != 0:
            reasons.append(f"command-exit-nonzero:{c.get('id')}={c.get('exitCode')}")
    total = sum(c.get("metrics", {}).get("testCount", 0) for c in commands)
    skipped = sum(c.get("metrics", {}).get("skipped", 0) for c in commands)
    failures = sum(c.get("metrics", {}).get("failures", 0) for c in commands)
    if failures > 0:
        reasons.append(f"test-failures:{failures}")
    if reasons:
        return "FAIL", reasons, {"testCount": total, "skipped": skipped,
                                 "failures": failures}

    # Rule 2: zero executed tests is never a pass.
    if total < max(1, expected_min):
        return "INCOMPLETE", ["no-tests-executed"], {
            "testCount": total, "skipped": skipped, "failures": failures}

    # Rule 3: 100% skip of the required suite is incomplete, not green.
    if skipped >= total:
        return "INCOMPLETE", ["all-tests-skipped"], {
            "testCount": total, "skipped": skipped, "failures": failures}

    # Rule 4: test-file weakening (disable markers / assertion removal).
    if preimage_dir is not None:
        for rel in test_files:
            reasons += diff_test_weakening(preimage_dir, root, rel)
    if reasons:
        return "REJECTED", reasons, {"testCount": total, "skipped": skipped,
                                     "failures": failures}

    # Rule 5: recorded pins must be complete and still match the live files.
    if not isinstance(digests_pre, dict) or not isinstance(digests_post, dict):
        return "REJECTED", ["pinned-digest-NOT_PROVEN:invalid-map"], {
            "testCount": total, "skipped": skipped, "failures": failures}
    pinned = sorted(set(test_files) | set(source_files)
                    | set(digests_pre) | set(digests_post))
    try:
        if any(rel_of(root, rel) != rel for rel in pinned):
            raise ValueError("noncanonical-pinned-path")
    except (OSError, ValueError, TypeError):
        return "REJECTED", ["pinned-path-NOT_PROVEN"], {
            "testCount": total, "skipped": skipped, "failures": failures}
    unproven = [rel for rel in pinned
                if any(not isinstance(values.get(rel), str)
                       or re.fullmatch(r"[0-9a-fA-F]{64}", values[rel]) is None
                       for values in (digests_pre, digests_post))]
    if unproven:
        return "REJECTED", [f"pinned-digest-NOT_PROVEN:{rel}" for rel in unproven], {
            "testCount": total, "skipped": skipped, "failures": failures}
    digest_mismatch = []
    for rel in list(digests_pre):
        if digests_pre.get(rel) != digests_post.get(rel):
            digest_mismatch.append(rel)
    if digest_mismatch:
        return "INVALIDATED", [f"post-test-digest-mismatch:{r}"
                               for r in digest_mismatch], {
            "testCount": total, "skipped": skipped, "failures": failures}

    current = digest_map(root, pinned)
    current_mismatch = [rel for rel in pinned
                        if current[rel] != digests_post[rel].lower()]
    if current_mismatch:
        return "INVALIDATED", [f"current-digest-{'missing' if current[rel] is None else 'mismatch'}:{rel}"
                               for rel in current_mismatch], {
            "testCount": total, "skipped": skipped, "failures": failures}

    return VERDICT_OK, [], {"testCount": total, "skipped": skipped,
                            "failures": failures}


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def cmd_verify(args) -> int:
    root = Path(args.root).resolve()
    spec = {}
    if args.spec:
        try:
            spec = load_json(root / args.spec if not Path(args.spec).is_absolute()
                             else Path(args.spec))
        except (OSError, json.JSONDecodeError) as exc:
            print(json.dumps({"schemaVersion": SCHEMA, "verdict": None,
                              "reasons": [f"spec-unreadable:{exc}"]}))
            return 3
    if args.argv:
        spec.setdefault("commands", [])
        for i, raw in enumerate(args.argv):
            spec["commands"].append({"id": f"cli-{i}", "argv": split_command(raw),
                                     "required": True})
    for rel in args.test_file or []:
        spec.setdefault("testFiles", []).append(rel)
    for rel in args.source_file or []:
        spec.setdefault("sourceFiles", []).append(rel)
    if args.test_preimage_dir:
        spec["testPreimageDir"] = args.test_preimage_dir

    commands_spec = spec.get("commands") or []
    if not commands_spec:
        print(json.dumps({"schemaVersion": SCHEMA, "verdict": None,
                          "reasons": ["no-commands-declared"]}))
        return 3

    test_files = [rel_of(root, r) for r in spec.get("testFiles") or []]
    source_files = [rel_of(root, r) for r in spec.get("sourceFiles") or []]
    pinned = sorted(set(test_files) | set(source_files))
    digests_pre = digest_map(root, pinned)
    preimage_dir = (root / spec["testPreimageDir"]).resolve() \
        if spec.get("testPreimageDir") else None

    ran = [dict(run_command(root, c), required=c.get("required", True))
           for c in commands_spec]
    digests_post = digest_map(root, pinned)

    verdict, reasons, detail = adjudicate(
        ran, test_files, source_files, root, preimage_dir,
        digests_pre, digests_post, int(spec.get("expectedMinTests", 1)))

    digest_matches = digests_pre == digests_post
    verified = verdict == VERDICT_OK
    payload = {
        "schemaVersion": SCHEMA,
        "taskId": spec.get("taskId"),
        "agent": spec.get("agent"),
        "baseRevision": spec.get("baseRevision"),
        "policyDigest": POLICY_DIGEST,
        "specDigest": "sha256:" + hashlib.sha256(
            json.dumps(spec, sort_keys=True, ensure_ascii=False)
            .encode("utf-8")).hexdigest(),
        "verdict": verdict,
        "verified": verified,
        "testCount": detail["testCount"],
        "skipped": detail["skipped"],
        "failures": detail["failures"],
        "digestMatches": digest_matches,
        "reasons": reasons,
        "testFiles": test_files,
        "sourceFiles": source_files,
        "testPreimageDir": spec.get("testPreimageDir"),
        "expectedMinTests": int(spec.get("expectedMinTests", 1)),
        "commands": [{k: c.get(k) for k in
                      ("id", "required", "exitCode", "durationMs", "metrics", "launchError")}
                     for c in ran],
        "digests": {"pre": digests_pre, "post": digests_post},
        "checkedAtUtc": utcnow(),
    }
    print(json.dumps(payload, ensure_ascii=False))
    print(f"verified={'true' if verified else 'false'} verdict={verdict} "
          f"testCount={detail['testCount']} failures={detail['failures']} "
          f"digestMatches={'true' if digest_matches else 'false'}")
    return 0 if verified else 2


def cmd_adjudicate(args) -> int:
    """Re-judge a recorded verify result without re-running commands."""
    try:
        data = load_json(Path(args.result))
    except (OSError, json.JSONDecodeError) as exc:
        print(json.dumps({"schemaVersion": SCHEMA, "verdict": None,
                          "reasons": [f"result-unreadable:{exc}"]}))
        return 3
    root = Path(args.root).resolve()
    commands = data.get("commands") or []
    digests = data.get("digests") or {}
    preimage_dir = (root / data["testPreimageDir"]).resolve() \
        if data.get("testPreimageDir") else None
    verdict, reasons, detail = adjudicate(
        commands,
        [rel_of(root, r) for r in data.get("testFiles") or []],
        [rel_of(root, r) for r in data.get("sourceFiles") or []],
        root, preimage_dir,
        digests.get("pre") or {}, digests.get("post") or {},
        int(data.get("expectedMinTests", 1)))
    if data.get("verdict") in BLOCKING:
        verdict = data["verdict"]
        reasons = data.get("reasons") or [f"recorded-blocking-verdict:{verdict}"]
    verified = verdict == VERDICT_OK
    print(json.dumps({"schemaVersion": SCHEMA, "verdict": verdict,
                      "verified": verified, "reasons": reasons,
                      "testCount": detail["testCount"],
                      "failures": detail["failures"]}, ensure_ascii=False))
    print(f"verified={'true' if verified else 'false'} verdict={verdict} "
          f"testCount={detail['testCount']} failures={detail['failures']}")
    return 0 if verified else 2


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    v = sub.add_parser("verify")
    v.add_argument("--root", default=".")
    v.add_argument("--spec")
    v.add_argument("--argv", action="append")
    v.add_argument("--test-file", action="append")
    v.add_argument("--source-file", action="append")
    v.add_argument("--test-preimage-dir")
    a = sub.add_parser("adjudicate")
    a.add_argument("--root", default=".")
    a.add_argument("--result", required=True)
    args = parser.parse_args(argv)
    return cmd_verify(args) if args.action == "verify" else cmd_adjudicate(args)


if __name__ == "__main__":
    raise SystemExit(main())
