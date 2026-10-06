#!/usr/bin/env python3
"""Offline probe: an `insufficient` final-verification verdict must release the
draft body with a parametric-knowledge warning, not a one-line HOLD notice.

Contract under test (Codex owns the Java change; directive
PASTE_DEVIN_EVIDENCE_ZERO_PARAMETRIC_RELEASE_20261006):

  OLD (defect): ChatWorkflow.applyFinalVerificationReleaseGate -- the
    `"insufficient".equals(normalizedStatus)` branch replaces the body with
    "evidence_needed: ...", status=HOLD, reasonCode="verification_insufficient",
    releaseAllowed=false. Downstream, `hardGuardHeld` (release guard) and
    `verificationFinding` (HOLD `verification_rejected`) keep the stop plan, so
    RagControlProjectionRenderer swaps the body for the held notice.
  NEW: insufficient keeps the draft body prefixed with the standard parametric
    warning, releaseStatus="UNVERIFIED",
    reasonCode="verification_insufficient_parametric_release",
    releaseAllowed=true, knowledgeWriteAllowed=false; the release is carried
    past shouldStop() via the verification-unknown-release lane
    (isVerificationUnknownRelease accepts the new reason) or an equivalent
    parametric flag, so verificationFinding emits DEGRADE instead of HOLD.

Verdicts:
  PATCH_READY     = gate emits the parametric release AND the carry seam routes
                    it off the stop plan (renderer body-drop then unreachable
                    for insufficient)
  DEFECT_PRESENT  = gate still HOLDs `insufficient`, or the gate is patched but
                    the carry seam still funnels it into HOLD
  INDETERMINATE   = required file/gate missing or unrecognized shape

Exit codes (default): 0 = probe ran and produced a verdict -- the verdict
field carries DEFECT_PRESENT vs PATCH_READY (defects are a diagnosis, not a
probe failure). 2 = indeterminate input (file missing/unrecognized). 1 = usage
error. `--strict` maps DEFECT_PRESENT to exit 3 for post-landing gates.

Read-only and offline. Output carries only paths, line numbers, and marker
names -- never file contents beyond the matched markers. --json supported.
"""

import argparse
import json
import re
import sys
from pathlib import Path

NEW_REASON = "verification_insufficient_parametric_release"
OLD_REASON = "verification_insufficient"
DOC = Path("docs/agents-rules/DEMO1-EVIDENCE-ZERO-RELEASE.md")
WORKFLOW = Path("main/java/com/example/lms/service/ChatWorkflow.java")
RENDERER = Path("main/java/com/example/lms/orchestration/control/RagControlProjectionRenderer.java")
ADAPTER = Path("main/java/com/example/lms/orchestration/control/RagControlRuntimeAdapter.java")

GATE_DECL = re.compile(
    r"FinalVerificationReleaseDecision\s+applyFinalVerificationReleaseGate\s*\(")
INSUFF_GUARD = re.compile(r'"insufficient"\s*\.equals\(\s*normalizedStatus\s*\)')
# The actual branch: `if ("insufficient".equals(normalizedStatus))` -- the
# equals must BE the if-condition, not a negated member of a compound guard
# (the unknown-release guard uses `!"insufficient".equals(...)`).
INSUFF_IF = re.compile(
    r'if\s*\(\s*"insufficient"\s*\.equals\(\s*normalizedStatus\s*\)\s*\)')
DENIED_TAIL = re.compile(r"\bfalse\s*,\s*false\s*,\s*false\s*\)")
PRED_DECL = re.compile(r"boolean\s+isVerificationUnknownRelease\s*\(")
BODY_DROP = re.compile(r"plan\.shouldStop\(\)\s*\?\s*heldNoticeFor\(plan\)\s*:\s*semanticAnswer")
HELD_INSUFF_CASE = re.compile(r'case\s+"verification_insufficient"')
VERIF_REJECTED_HOLD = re.compile(r'"verification_rejected"')
OLD_HOLD_WORDING = "기존 HOLD/거절을 유지하며"


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


def _read(path):
    try:
        return Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None


def analyze_policy_doc(text):
    """Policy doc must state the release-with-warning contract."""
    if text is None:
        return {"filePresent": False}
    return {
        "filePresent": True,
        "parametricKnowledgeStated": "사전학습 지식" in text,
        "warningStated": "주의" in text,
        "newReasonCited": NEW_REASON in text,
        "knowledgeWriteDenyKept": "knowledgeWriteAllowed=false" in text,
        "oldHoldWordingPresent": OLD_HOLD_WORDING in text,
    }


def analyze_gate(text):
    """Inspect the applyFinalVerificationReleaseGate `insufficient` branch."""
    if text is None:
        return {"filePresent": False}
    # applyFinalVerificationReleaseGate has overloads; pick the declaration
    # whose body actually contains the insufficient guard.
    body = None
    gate_line = None
    decls = list(GATE_DECL.finditer(text))
    if not decls:
        return {"filePresent": True, "gateFound": False}
    for m in decls:
        span = _brace_span(text, m.end())
        if span is None:
            continue
        candidate = text[span[0]:span[1]]
        if INSUFF_GUARD.search(candidate):
            body = candidate
            gate_line = _line_of(text, m.start())
            break
    if body is None:
        span = _brace_span(text, decls[0].end())
        if span is not None:
            body = text[span[0]:span[1]]
            gate_line = _line_of(text, decls[0].start())
    if body is None:
        return {"filePresent": True, "gateFound": True, "gateBalanced": False}
    out = {"filePresent": True, "gateFound": True, "gateBalanced": True,
           "gateLine": gate_line}
    guard = INSUFF_IF.search(body)
    if not guard:
        out["insufficientBranch"] = None
        return out
    bspan = _brace_span(body, guard.end())
    out["insufficientBranch"] = {"line": _line_of(text, span[0] + guard.start())}
    if bspan is None:
        out["insufficientBranch"]["unbalanced"] = True
        return out
    block = body[bspan[0]:bspan[1]]
    out["insufficientBranch"].update({
        "hold": '"HOLD"' in block,
        "oldReasonCode": OLD_REASON in block and NEW_REASON not in block,
        "newReasonCode": NEW_REASON in block,
        "unverified": '"UNVERIFIED"' in block,
        "bodyReplaced": "evidence_needed" in block,
        "releaseDenied": DENIED_TAIL.search(block) is not None,
        "releaseAllowed": re.search(r"\btrue\s*,\s*false\s*,\s*false\s*\)",
                                    block) is not None,
        "warningPrefix": "사전학습" in block or "PARAMETRIC" in block,
    })
    # Carry seam: does isVerificationUnknownRelease accept the new reason?
    pm = PRED_DECL.search(text)
    if pm:
        pspan = _brace_span(text, pm.end())
        pbody = text[pspan[0]:pspan[1]] if pspan else ""
        out["carryPredicate"] = {"line": _line_of(text, pm.start()),
                                 "acceptsNewReason": NEW_REASON in pbody}
    else:
        out["carryPredicate"] = None
    return out


def analyze_renderer(text):
    """Renderer seam: does a stop plan still swap the body for the notice?"""
    if text is None:
        return {"filePresent": False}
    out = {"filePresent": True}
    drop = BODY_DROP.search(text)
    out["shouldStopSwapsBody"] = bool(drop)
    if drop:
        out["bodyDropLine"] = _line_of(text, drop.start())
    out["heldNoticeInsufficientCase"] = bool(HELD_INSUFF_CASE.search(text))
    out["parametricEscape"] = NEW_REASON in text or "parametric" in text.lower()
    return out


def analyze_adapter(text):
    """Adapter seam: !verificationAccepted still produces HOLD
    verification_rejected (fires whenever the gate does not carry the
    parametric release onto the unknown-release lane)."""
    if text is None:
        return {"filePresent": False}
    out = {"filePresent": True}
    hit = VERIF_REJECTED_HOLD.search(text)
    out["rejectedHoldPresent"] = bool(hit)
    if hit:
        out["rejectedHoldLine"] = _line_of(text, hit.start())
    out["parametricFlagPresent"] = "parametric" in text.lower() or NEW_REASON in text
    return out


def scan_tests(root):
    """src/test/java pins that still assert HOLD / denied release for an
    `insufficient` verdict. Warnings; --strict-tests escalates."""
    warnings = []
    test_root = Path(root) / "src" / "test" / "java"
    if not test_root.is_dir():
        return warnings
    for path in sorted(test_root.rglob("*.java")):
        text = _read(path)
        if text is None:
            continue
        rel = path.relative_to(root).as_posix() if path.is_relative_to(root) else str(path)
        for mm in re.finditer(r'"insufficient"', text):
            line_start = text.rfind("\n", 0, mm.start()) + 1
            line = text[line_start:text.find("\n", mm.start())]
            pins_denial = (
                "ValueSource" in line or "CsvSource" in line
                or "VerificationCase" in line or "List.of(" in line)
            if pins_denial:
                warnings.append({
                    "file": rel,
                    "line": _line_of(text, mm.start()),
                    "marker": "insufficient-in-denial-set",
                    "staleClaim": True,
                })
        for mm in re.finditer(r'"' + OLD_REASON + r'"', text):
            warnings.append({
                "file": rel,
                "line": _line_of(text, mm.start()),
                "marker": OLD_REASON,
                "staleClaim": True,
            })
        for meth in re.finditer(r"void\s+(\w*[Ii]nsufficient\w*)\s*\([^)]*\)\s*\{",
                                text):
            mspan = _brace_span(text, meth.end() - 1)
            if mspan is None:
                continue
            mbody = text[mspan[0]:mspan[1]]
            if '"HOLD"' in mbody and re.search(
                    r"assertFalse\([^)]*releaseAllowed\(\)", mbody):
                warnings.append({
                    "file": rel,
                    "line": _line_of(text, meth.start()),
                    "marker": "insufficient-hold-assertion",
                    "method": meth.group(1),
                    "staleClaim": True,
                })
    return warnings


def verdict(gate, renderer, adapter):
    """Combine seam findings into DEFECT_PRESENT / PATCH_READY / INDETERMINATE
    plus the concrete defect points."""
    defects = []
    if not gate.get("gateFound"):
        return "INDETERMINATE", defects
    branch = gate.get("insufficientBranch")
    if branch is None or branch.get("unbalanced"):
        return "INDETERMINATE", defects
    if branch.get("hold") or branch.get("oldReasonCode") or branch.get("releaseDenied"):
        defects.append({
            "id": "gate-insufficient-hold",
            "file": str(WORKFLOW),
            "line": branch.get("line"),
            "detail": "insufficient branch still emits HOLD / "
                      "verification_insufficient / releaseAllowed=false",
        })
    if not branch.get("newReasonCode"):
        defects.append({
            "id": "gate-parametric-reason-absent",
            "file": str(WORKFLOW),
            "line": branch.get("line"),
            "detail": "insufficient branch does not emit "
                      "verification_insufficient_parametric_release",
        })
    carry = gate.get("carryPredicate")
    carry_ok = bool(carry and carry.get("acceptsNewReason")) or \
        (adapter.get("parametricFlagPresent") if adapter else False)
    if not carry_ok:
        defects.append({
            "id": "carry-predicate-misses-parametric",
            "file": str(WORKFLOW),
            "line": carry.get("line") if carry else None,
            "detail": "parametric reason is not carried onto the "
                      "verification-unknown-release lane; verificationFinding "
                      "still emits HOLD verification_rejected "
                      "(RagControlRuntimeAdapter)",
        })
    if renderer.get("filePresent") and renderer.get("shouldStopSwapsBody") \
            and not renderer.get("parametricEscape") and defects:
        defects.append({
            "id": "renderer-body-drop-on-stop",
            "file": str(RENDERER),
            "line": renderer.get("bodyDropLine"),
            "detail": "renderProjection still swaps the body for heldNotice "
                      "whenever the plan stops (live for insufficient while a "
                      "stop path remains); keep body + warning for "
                      "verification_insufficient*",
        })
    if defects and defects[0]["id"].startswith("gate-") is False:
        return "DEFECT_PRESENT", defects
    return ("PATCH_READY" if not defects else "DEFECT_PRESENT"), defects


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    ap.add_argument("--strict", action="store_true",
                    help="DEFECT_PRESENT becomes exit 3 (post-landing gate)")
    ap.add_argument("--strict-tests", action="store_true",
                    help="stale insufficient->HOLD test claims become exit 3")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    doc = analyze_policy_doc(_read(root / DOC))
    gate = analyze_gate(_read(root / WORKFLOW))
    renderer = analyze_renderer(_read(root / RENDERER))
    adapter = analyze_adapter(_read(root / ADAPTER))
    test_warnings = scan_tests(root)

    if not gate.get("filePresent") or not renderer.get("filePresent"):
        out = {"verdict": "INDETERMINATE", "reason": "required-file-missing",
               "policyDoc": doc, "gate": gate, "renderer": renderer}
        print(json.dumps(out, indent=2, sort_keys=True) if args.json else
              "INDETERMINATE required-file-missing")
        return 2

    verdict_label, defects = verdict(gate, renderer, adapter)
    code = 0 if verdict_label != "INDETERMINATE" else 2
    if verdict_label == "DEFECT_PRESENT" and args.strict:
        code = 3
    if args.strict_tests and any(w.get("staleClaim") for w in test_warnings) \
            and code == 0:
        code = 3

    out = {
        "verdict": verdict_label,
        "defectPoints": defects,
        "policyDoc": doc,
        "gate": gate,
        "renderer": renderer,
        "adapter": adapter,
        "testWarnings": test_warnings,
        "strict": bool(args.strict),
        "strictTests": bool(args.strict_tests),
        "exit": code,
    }
    if args.json:
        print(json.dumps(out, indent=2, sort_keys=True, ensure_ascii=False))
    else:
        print("%s verdict=%s defects=%d" % (
            {0: "OK", 2: "INDETERMINATE", 3: "STRICT-FAIL"}.get(code, "OK")
            if verdict_label == "PATCH_READY" else verdict_label,
            verdict_label, len(defects)))
        print("  policyDoc: parametric=%s warning=%s newReason=%s denyKept=%s oldWording=%s" % (
            doc.get("parametricKnowledgeStated"), doc.get("warningStated"),
            doc.get("newReasonCited"), doc.get("knowledgeWriteDenyKept"),
            doc.get("oldHoldWordingPresent")))
        for d in defects:
            print("  defect %s %s:%s -- %s" % (
                d["id"], d["file"], d.get("line"), d["detail"]))
        for w in test_warnings:
            print("  WARN stale-test %s:%s %s" % (w["file"], w["line"], w["marker"]))
        if verdict_label == "DEFECT_PRESENT":
            print("  meaning: Codex patch not landed yet OR regression reintroduced")
        elif verdict_label == "PATCH_READY":
            print("  meaning: insufficient releases the draft with the "
                  "parametric-knowledge warning")
    return code


if __name__ == "__main__":
    sys.exit(main())
