#!/usr/bin/env python3
"""Offline probe: a fail-soft (indeterminate) verifier outcome must release the
draft body, not HOLD it.

Contract under test (Codex owns the Java change; directive
PASTE_CODEX_verifier-failsoft-release_20261005.txt, sha12 8b6e0b617ddb):

  OLD (bug): ChatWorkflow.applyFinalVerificationReleaseGate -- the
    `!outcomeKnown` branch replaced content with an "evidence_needed: ..."
    notice, status=HOLD, reasonCode="verification_outcome_unknown",
    releaseAllowed=false; hardGuardHeld then swallowed the body as
    "existing_release_guard_hold". markFailSoft() (judge empty/exception/
    malformed/budget exhausted) is infrastructure indeterminate, not a
    negative verdict.
  NEW: unknown/fail-soft keeps the draft body, releaseAllowed=true,
    reasonCode="verification_unknown_release", knowledgeWriteAllowed=false.

Exit codes:
  0 = new contract present (new marker found; old !outcomeKnown->HOLD branch
      absent from the release gate)
  3 = old contract still present (Codex patch not landed yet, or regression)
  2 = indeterminate (file/gate missing or unrecognized shape)
  1 = usage error

Test-file scan: any src/test/java file still asserting HOLD /
"verification_outcome_unknown" for an unknown outcome is reported as a
warning. With --strict-tests (policy: after the Codex landing) warnings
escalate to exit 3.

Read-only and offline. Output carries only paths, line numbers, and marker
names -- never file contents beyond the matched markers. --json supported.
"""

import argparse
import json
import re
import sys
from pathlib import Path

NEW_MARKER = "verification_unknown_release"
OLD_REASON = "verification_outcome_unknown"
GATE_DECL = re.compile(
    r"FinalVerificationReleaseDecision\s+applyFinalVerificationReleaseGate\s*\(")
OLD_BRANCH_HEAD = re.compile(r"!\s*outcomeKnown")


def _brace_span(text, open_idx):
    """Return (start, end) slice bounds for the brace block whose first '{'
    is at or after open_idx. Naive brace counting; returns None when the
    block never balances (caller treats as indeterminate for that span)."""
    start = text.find("{", open_idx)
    if start < 0:
        return None
    depth = 0
    for i in range(start, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return (start, i + 1)
    return None


def _line_of(text, idx):
    return text.count("\n", 0, idx) + 1


def analyze_workflow(text):
    """Return dict describing the release-gate contract found in the file."""
    m = GATE_DECL.search(text)
    if not m:
        return {"gateFound": False}
    span = _brace_span(text, m.end())
    if span is None:
        return {"gateFound": True, "gateBalanced": False}
    body = text[span[0]:span[1]]
    gate_line = _line_of(text, m.start())
    result = {
        "gateFound": True,
        "gateBalanced": True,
        "gateLine": gate_line,
        "newMarkerInGate": NEW_MARKER in body,
        "newMarkerInFile": NEW_MARKER in text,
    }
    branch = OLD_BRANCH_HEAD.search(body)
    if branch:
        bspan = _brace_span(body, branch.end())
        if bspan is not None:
            block = body[bspan[0]:bspan[1]]
            result["oldBranch"] = {
                "line": _line_of(text, span[0] + branch.start()),
                "hold": '"HOLD"' in block,
                "oldReasonCode": OLD_REASON in block,
                "bodyReplaced": "evidence_needed" in block,
                "releaseDenied": re.search(
                    r"\bfalse\s*,\s*false\s*,\s*false\s*\)", block) is not None,
            }
        else:
            result["oldBranch"] = {"line": _line_of(text, span[0] + branch.start()),
                                   "unbalanced": True}
    else:
        result["oldBranch"] = None
    return result


def scan_tests(root):
    """Return list of stale test claims: files under src/test/java that still
    pin the old contract (unknown outcome -> HOLD / verification_outcome_unknown)."""
    warnings = []
    test_root = Path(root) / "src" / "test" / "java"
    if not test_root.is_dir():
        return warnings
    for path in sorted(test_root.rglob("*.java")):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        rel = path.relative_to(root).as_posix() if path.is_relative_to(root) else str(path)
        gate_test = re.search(r"Release(Gate|Boundary)", path.name) is not None
        for mm in re.finditer(re.escape(OLD_REASON), text):
            warnings.append({
                "file": rel,
                "line": _line_of(text, mm.start()),
                "marker": OLD_REASON,
                "staleClaim": gate_test,
            })
        # unknown-outcome call shape + HOLD assertion in the same method is a
        # stale claim even if the reasonCode literal was renamed.
        for meth in re.finditer(r"(?s)void\s+(\w*[Uu]nknown\w*)\s*\([^)]*\)\s*\{",
                                text):
            mspan = _brace_span(text, meth.end() - 1)
            if mspan is None:
                continue
            mbody = text[mspan[0]:mspan[1]]
            if '"HOLD"' in mbody and re.search(
                    r",\s*false\s*,\s*false\s*\)", mbody):
                warnings.append({
                    "file": rel,
                    "line": _line_of(text, meth.start()),
                    "marker": "unknown-outcome-hold-assertion",
                    "method": meth.group(1),
                    "staleClaim": True,
                })
    return warnings


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    ap.add_argument("--chat-workflow", help="override path to ChatWorkflow.java")
    ap.add_argument("--test-root", help="override dir scanned instead of <root>/src")
    ap.add_argument("--strict-tests", action="store_true",
                    help="stale unknown->HOLD test claims become exit 3")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    wf = Path(args.chat_workflow).resolve() if args.chat_workflow else (
        root / "main" / "java" / "com" / "example" / "lms" / "service"
        / "ChatWorkflow.java")
    if not wf.is_file():
        out = {"contract": "indeterminate", "reason": "chatworkflow-missing",
               "file": str(wf)}
        print(json.dumps(out) if args.json else
              "INDETERMINATE chatworkflow-missing %s" % wf)
        return 2
    text = wf.read_text(encoding="utf-8", errors="replace")
    info = analyze_workflow(text)
    test_warnings = scan_tests(args.test_root if args.test_root else root)

    old_present = bool(info.get("oldBranch")) and (
        info["oldBranch"].get("hold") and info["oldBranch"].get("oldReasonCode"))
    new_present = info.get("newMarkerInGate") or info.get("newMarkerInFile")

    if old_present:
        contract, code = "old", 3
    elif new_present:
        contract, code = "new", 0
    else:
        contract, code = "indeterminate", 2
    if args.strict_tests and any(w.get("staleClaim") for w in test_warnings) \
            and code == 0:
        code = 3

    out = {
        "contract": contract,
        "file": str(wf),
        "gate": info,
        "oldContractPresent": old_present,
        "newContractPresent": new_present,
        "testWarnings": test_warnings,
        "strictTests": bool(args.strict_tests),
        "exit": code,
    }
    if args.json:
        print(json.dumps(out, indent=2, sort_keys=True))
    else:
        label = {0: "NEW-CONTRACT", 3: "OLD-CONTRACT", 2: "INDETERMINATE"}[code]
        print("%s file=%s gateLine=%s" % (
            label, wf, info.get("gateLine")))
        if info.get("oldBranch"):
            print("  oldBranch=%s" % info["oldBranch"])
        for w in test_warnings:
            print("  WARN stale-test %s:%s %s" % (
                w["file"], w["line"], w["marker"]))
        if code == 3 and not old_present:
            print("  strict-tests: stale test claims escalated to exit 3")
        elif code == 3:
            print("  meaning: Codex patch not landed yet OR regression reintroduced")
        elif code == 0:
            print("  meaning: fail-soft/unknown releases the draft body")
    return code


if __name__ == "__main__":
    sys.exit(main())
