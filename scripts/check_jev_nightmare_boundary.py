#!/usr/bin/env python3
"""Offline boundary lint for the Codex Jev FACT_META_CHECK patch.

Watches the forbidden seams while Codex owns the product sources:

  B1  NightmareBreaker must not import or reference Jev/assist classes or the
      Vercel evaluate endpoint (circuit-breaker stays local count/CAS only).
  B2  com.example.lms.assist classes must not call NightmareBreaker
      (no reverse dependency either).
  B3  Legacy `typesafe/v1/systemone` (or bare `systemone`) strings must not
      reappear in product code, and `/v1/evaluate` references stay inside the
      assist package + its YAML config + tests.
  B4  The FACT_META seam is single: `.factMetaVerdict(` receiver calls live
      only in FactVerifierService, and `FACT_META` constant usage stays inside
      the assist package + FactVerifierService.

Read-only, stdlib only. Exit 0 = zero violations.
Usage: python -B scripts/check_jev_nightmare_boundary.py [--json]
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BREAKER = ROOT / "main/java/com/example/lms/infra/resilience/NightmareBreaker.java"
ASSIST_DIR = ROOT / "main/java/com/example/lms/assist"
VERIFIER = "main/java/com/example/lms/service/FactVerifierService.java"

SCAN_SUFFIXES = {".java", ".yml", ".yaml", ".properties", ".xml", ".kt", ".kts"}
SCAN_DIRS = ("main/java", "main/resources", "src/test")

B1_PATTERNS = [
    re.compile(r"com\.example\.lms\.assist"),
    re.compile(r"\b[Jj]ev[A-Za-z_]*\b"),
    re.compile(r"ai-gateway\.vercel\.sh"),
    re.compile(r"/v1/(evaluate|decide)\b"),
]
B2_PATTERN = re.compile(r"\bNightmareBreaker\b")
B3_PATTERN = re.compile(r"systemone", re.IGNORECASE)
B3_EVALUATE = re.compile(r"/v1/(evaluate|decide)\b")
B4_CALL = re.compile(r"\.factMetaVerdict\s*\(")
B4_CONST = re.compile(r"\bFACT_META\b")

EVALUATE_ALLOW_PREFIXES = (
    "main/java/com/example/lms/assist/",
    "main/resources/application-meta-display.yml",
    "src/test/",
)


def _iter_files():
    for base in SCAN_DIRS:
        base_path = ROOT / base
        if not base_path.is_dir():
            continue
        for path in sorted(base_path.rglob("*")):
            if path.is_file() and path.suffix.lower() in SCAN_SUFFIXES:
                yield path


def _scan(path, patterns):
    """Yield (line_no, line_text) for every pattern hit."""
    hits = []
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return hits
    for no, line in enumerate(lines, 1):
        if any(p.search(line) for p in patterns):
            hits.append((no, line.strip()[:160]))
    return hits


def main():
    as_json = "--json" in sys.argv[1:]
    violations = []

    # B1 breaker -> jev/assist/evaluate
    if BREAKER.is_file():
        for no, line in _scan(BREAKER, B1_PATTERNS):
            violations.append(("B1", "main/java/com/example/lms/infra/resilience/NightmareBreaker.java", no, line))
    else:
        violations.append(("B1", "main/java/com/example/lms/infra/resilience/NightmareBreaker.java", 0, "FILE MISSING"))

    # B2 assist -> breaker
    if ASSIST_DIR.is_dir():
        for path in sorted(ASSIST_DIR.rglob("*.java")):
            rel = path.relative_to(ROOT).as_posix()
            for no, line in _scan(path, [B2_PATTERN]):
                violations.append(("B2", rel, no, line))

    # B3 legacy endpoint strings + evaluate endpoint containment
    for path in _iter_files():
        rel = path.relative_to(ROOT).as_posix()
        for no, line in _scan(path, [B3_PATTERN]):
            violations.append(("B3", rel, no, line))
        if not rel.startswith(EVALUATE_ALLOW_PREFIXES):
            for no, line in _scan(path, [B3_EVALUATE]):
                violations.append(("B3-eval", rel, no, line))

    # B4 single FACT_META seam
    java_root = ROOT / "main/java"
    if java_root.is_dir():
        for path in sorted(java_root.rglob("*.java")):
            rel = path.relative_to(ROOT).as_posix()
            in_assist = rel.startswith("main/java/com/example/lms/assist/")
            if rel == VERIFIER:
                continue
            for no, line in _scan(path, [B4_CALL]):
                violations.append(("B4-call", rel, no, line))
            if not in_assist:
                for no, line in _scan(path, [B4_CONST]):
                    violations.append(("B4-const", rel, no, line))

    counts = {}
    for check, *_ in violations:
        counts[check] = counts.get(check, 0) + 1
    if as_json:
        print(json.dumps({"violations": [
            {"check": c, "file": f, "line": n, "text": t} for c, f, n, t in violations],
            "counts": counts, "result": "PASS" if not violations else "FAIL"}, indent=2))
    else:
        for check in ("B1", "B2", "B3", "B3-eval", "B4-call", "B4-const"):
            status = "PASS" if counts.get(check, 0) == 0 else "FAIL"
            print(f"  [{status}] {check}: violations={counts.get(check, 0)}")
        for check, rel, no, line in violations:
            print(f"    {check} {rel}:{no}: {line}")
    print(f"RESULT: {'PASS' if not violations else 'FAIL'} violations={len(violations)}")
    return 0 if not violations else 1


if __name__ == "__main__":
    sys.exit(main())
