#!/usr/bin/env python3
"""probe_front_router_consistency.py — front-classifier / router consistency probe.

Read-only diagnostic for the P6 front-router findings. Two halves:

STATIC (live or fixture Java tree, never modified):
  R-GATE-DIVERGENCE  a non-gate class instantiates `new QueryComplexityGate()`
                     (own ruleset) while the Spring bean may carry an injected
                     classifier -> same input, different verdict by call path.
  R-MODEL-FILE-FLIP  a classifier's verdict depends on whether a model path
                     file merely exists (modelUnavailable feeds classify()).
  R-TOKEN-CAP-PROMO  a promotion predicate fires on maxTokens alone — output
                     length is not task difficulty.
  R-CONFIDENCE-NAME  `confidenceAccepted` is probability>=threshold, a
                     different thing from a scorer confidence (informational).

MOCK (built-in fixture, no JVM, no paid calls):
  Jev candidate-selection contract — a search-phase webNeed handle and a
  post-search rerank handle are separate stages; a rerank policy that drops
  the sole counter-example / deciding sentence (protected candidates) is
  flagged even when its top-k score ordering looks plausible.

  python -B scripts/probe_front_router_consistency.py --root .
  python -B scripts/probe_front_router_consistency.py --root <fixture-tree>

Exit 0 = no blocking findings · 2 = findings present · 3 = usage error.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

SCHEMA = "awx.front-router-consistency.v1"

RE_NEW_GATE = re.compile(r"new\s+(?:[\w.]+\.)?QueryComplexityGate\s*\(")
RE_MODEL_FILE = re.compile(r"modelUnavailable|isRegularFile|modelPath")
RE_CLASSIFY_RETURN = re.compile(r"modelUnavailable")
RE_TOKEN_CAP = re.compile(r"maxTokens\s*\(\s*\)\s*>=")
RE_PROMOTE_METHOD = re.compile(r"boolean\s+(?:shouldPromote|complexMainRequest)")
RE_CONFIDENCE = re.compile(r"confidenceAccepted")


def load_accepted(root: Path) -> list:
    """Verified acceptances from configs/probe-front-router-accepted.json.

    Entries: {"id","file","reason","evidence","verifiedAt"}. A missing or
    unreadable file yields no acceptances — behaviour identical to before."""
    cfg = root / "configs" / "probe-front-router-accepted.json"
    if not cfg.is_file():
        return []
    try:
        data = json.loads(cfg.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    if not isinstance(data, list):
        return []
    return [e for e in data if isinstance(e, dict)
            and e.get("id") and e.get("file")]


def apply_accepted(findings: list, accepted: list) -> list:
    """Downgrade a violation to severity "accepted" only on exact id+file
    match. An id or file that drifts keeps the finding a violation."""
    out = []
    for f in findings:
        match = next((e for e in accepted
                      if e["id"] == f["id"] and e["file"] == f["file"]), None)
        if match and f["severity"] == "violation":
            f = dict(f, severity="accepted",
                     acceptedReason=match.get("reason", ""),
                     acceptedEvidence=match.get("evidence", ""),
                     acceptedVerifiedAt=match.get("verifiedAt", ""))
        out.append(f)
    return out


def scan_tree(root: Path) -> list:
    findings = []
    java_root = root / "main" / "java"
    if not java_root.is_dir():
        return findings
    for path in sorted(java_root.rglob("*.java")):
        rel = str(path.relative_to(root)).replace("\\", "/")
        try:
            lines = path.read_text(encoding="utf-8",
                                   errors="replace").splitlines()
        except OSError:
            continue
        is_gate = path.name == "QueryComplexityGate.java"
        for i, ln in enumerate(lines, 1):
            if not is_gate and RE_NEW_GATE.search(ln):
                findings.append({
                    "id": "R-GATE-DIVERGENCE", "file": rel, "line": i,
                    "severity": "violation",
                    "detail": "separate `new QueryComplexityGate()` instance "
                              "bypasses the injected classifier; same input "
                              "can be judged by two rulesets",
                    "text": ln.strip()[:160]})
            if RE_TOKEN_CAP.search(ln):
                findings.append({
                    "id": "R-TOKEN-CAP-PROMO", "file": rel, "line": i,
                    "severity": "violation",
                    "detail": "maxTokens threshold alone promotes to the "
                              "high-tier model; output cap is not difficulty",
                    "text": ln.strip()[:160]})
            if RE_CONFIDENCE.search(ln):
                findings.append({
                    "id": "R-CONFIDENCE-NAME", "file": rel, "line": i,
                    "severity": "info",
                    "detail": "confidenceAccepted is probability>=threshold, "
                              "not scorer confidence — do not merge meanings",
                    "text": ln.strip()[:160]})
        # model-file fragility: a classifier whose verdict path references
        # modelUnavailable (file existence flips the verdict)
        if "Classifier" in path.name or "Complexity" in path.name:
            body = "\n".join(lines)
            if RE_MODEL_FILE.search(body) and RE_CLASSIFY_RETURN.search(body):
                hit = next((i for i, ln in enumerate(lines, 1)
                            if "modelUnavailable" in ln), None)
                findings.append({
                    "id": "R-MODEL-FILE-FLIP", "file": rel, "line": hit,
                    "severity": "violation",
                    "detail": "verdict depends on whether the model-path file "
                              "exists (modelUnavailable in classify path), "
                              "not on the ruleset alone"})
    return findings


# ------------------------------------------------------------- mock rerank --

def mock_rerank_check() -> dict:
    """Jev search->candidate-selection contract on a synthetic candidate set.

    The webNeed handle (pre-search decision) and the rerank handle (post-search
    candidate selection) are separate stages. A naive top-k rerank that drops
    the sole counter-example or deciding sentence violates the protected-
    candidate rule even though score ordering is plausible."""
    candidates = [
        {"id": "c1", "role": "evidence", "score": 0.91},
        {"id": "c2", "role": "evidence", "score": 0.87},
        {"id": "c3", "role": "counterexample", "score": 0.42},
        {"id": "c4", "role": "deciding_sentence", "score": 0.38},
        {"id": "c5", "role": "filler", "score": 0.30},
    ]
    protected = {"counterexample", "deciding_sentence"}

    def naive_topk(cands, k=2):
        return sorted(cands, key=lambda c: -c["score"])[:k]

    def guarded(cands, k=2):
        kept = naive_topk(cands, k)
        for c in cands:
            if c["role"] in protected and c not in kept:
                kept.append(c)
        return kept

    naive = naive_topk(candidates)
    guarded_sel = guarded(candidates)
    naive_dropped = [c["id"] for c in candidates
                     if c["role"] in protected and c not in naive]
    guarded_dropped = [c["id"] for c in candidates
                       if c["role"] in protected and c not in guarded_sel]
    return {
        "handlesSeparated": True,
        "webNeedHandle": "pre-search",
        "rerankHandle": "post-search",
        "naiveDroppedProtected": naive_dropped,
        "guardedDroppedProtected": guarded_dropped,
        "naiveViolatesContract": bool(naive_dropped),
        "guardedPreservesContract": not guarded_dropped,
    }


def main(argv=None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--mock-only", action="store_true",
                        help="skip the static tree scan")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()

    findings = [] if args.mock_only else scan_tree(root)
    findings = apply_accepted(findings, load_accepted(root))
    mock = mock_rerank_check()
    violations = [f for f in findings if f["severity"] == "violation"]
    if mock["naiveViolatesContract"] and not mock["guardedPreservesContract"]:
        violations.append({"id": "R-JEV-PROTECTED-DROPPED",
                           "severity": "violation",
                           "detail": "guarded rerank dropped protected "
                                     "candidates"})
    accepted_hits = [f for f in findings if f["severity"] == "accepted"]
    payload = {"schemaVersion": SCHEMA, "root": str(root),
               "ok": not violations,
               "violationCount": len(violations),
               "acceptedCount": len(accepted_hits),
               "findings": findings, "mock": mock}
    print(json.dumps(payload, ensure_ascii=False))
    ids = sorted({f["id"] for f in violations})
    print(f"violations={len(violations)} rules={','.join(ids) or '-'} "
          f"accepted={len(accepted_hits)} "
          f"mockNaiveDrops={len(mock['naiveDroppedProtected'])} "
          f"mockGuardedDrops={len(mock['guardedDroppedProtected'])}")
    for f in accepted_hits:
        print(f"accepted {f['id']} {f['file']}:{f.get('line')} "
              f"reason={f.get('acceptedReason', '')[:120]}")
    return 2 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main())
