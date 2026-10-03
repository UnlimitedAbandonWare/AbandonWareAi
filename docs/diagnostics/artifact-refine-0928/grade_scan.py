"""Artifact grade scan for DEMO1-DEVIN-ARTIFACT-REFINE-MEMORY-PERM-20260928.

Grades live scripts/* files into S/A/B/C/D/X using reference evidence only.
Read-only on the repo: writes only --out-csv / --out-json artifacts.

Tiers:
  p1 = references in instructional/code corpus (AGENTS.md, .agents/, agent-prompts/,
       docs/ minus diagnostics/, configs/, main/, app/, root *.bat, __patch_drop__/, ...)
  p2 = references from other scripts/* files (basename or `import/from <stem>`)
  p3 = references in fresh (<=14d) data/agent-handoff/** or docs/diagnostics/**
  p4 = references in stale docs/diagnostics/** or stale agent-handoff files only

Grade map:
  X = active source-lease target or Clean-Kit max_push* (OWNER_OTHER, never touch)
  D = p1==0 and p2==0 and p3==0 and p4==0            (dead: triple-proof zero)
  C = p1==0 and p2==0 and p3==0 and p4>0           (stale-doc mention only)
  B = referenced but thin ps1 wrapper of a sibling (MERGE candidate)
  S = .py CLI (argparse/__main__/sys.argv) + has test + referenced
  A = otherwise referenced

Test/infra files (test_*, *_tests.*, conftest.py, __init__.py) are suite-runnable:
floor grade C when unreferenced, never D on zero-ref alone.
"""
from __future__ import annotations

import csv
import json
import re
import sys
import time
from pathlib import Path

ROOT = Path(r"C:\AbandonWare\demo-1\demo-1\src")
SCRIPTS = ROOT / "scripts"
OUTDIR = ROOT / "docs" / "diagnostics" / "artifact-refine-0928"
FRESH_SEC = 14 * 86400
SIZE_CAP = 2_000_000

EXCLUDE_DIRNAMES = {
    ".git", ".secrets", "node_modules", "build", ".gradle", "out", "dist",
    "target", "__pycache__", ".pytest_cache", ".idea", ".vs", ".vscode",
    "bin", "obj", "coverage", "htmlcov", "var", "logs", ".devin", ".claude",
}
INCLUDE_EXT = {
    ".md", ".py", ".ps1", ".js", ".cjs", ".mjs", ".bat", ".sh", ".java",
    ".yaml", ".yml", ".json", ".txt", ".kts", ".gradle", ".toml", ".cfg",
    ".ini", ".html", ".css", ".ts", ".tsx", ".jsx", ".xml", ".properties",
}

ACTIVE_LEASE_PATHS = {
    "scripts/agent_machine_context.ps1", "scripts/agent_machine_context.py",
    "scripts/agent_preflight.py", "agent-machinecontext.bat",
    "scripts/category_cleanup_scan.py",
    "scripts/codex_work_checkpoint.py", "scripts/test_checkpoint_java_json_field_read.py",
    "scripts/agent_done_evidence_guard.py", "scripts/agent_harmony_status_cas.py",
    "scripts/quarantine_seed_mine.py", "scripts/test_agent_done_evidence_guard.py",
    "scripts/uaw_spine_probe.py",
}
EXPIRED_LEASE_PATHS = {
    "read-rag-debug.bat", "scripts/read_rag_debug_trail.ps1",
    "scripts/read_rag_debug_trail_tests.ps1",
}

TEST_NAME = re.compile(r"^(test_.+|.+(?:_tests|_contract_tests|_smoke_tests))\.(?:py|ps1|js|cjs|mjs)$", re.I)
IMPORT_RE = re.compile(r"\b(?:import|from)\s+([A-Za-z_][\w]*)")


def classify(path: Path, rel: str, now: float) -> int:
    """1=instructional/code, 3=fresh handoff/diag, 4=stale diag/handoff."""
    low = rel.replace("\\", "/")
    fresh = (now - path.stat().st_mtime) <= FRESH_SEC
    if low.startswith("data/agent-handoff/"):
        return 3 if fresh else 4
    if low.startswith("docs/diagnostics/"):
        return 3 if fresh else 4
    return 1


def iter_corpus(root: Path):
    import os
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRNAMES]
        for fn in filenames:
            p = Path(dirpath) / fn
            if p.suffix.lower() not in INCLUDE_EXT:
                continue
            try:
                if p.stat().st_size > SIZE_CAP or not p.is_file():
                    continue
            except OSError:
                continue
            yield p


def main() -> int:
    now = time.time()
    scripts = sorted(p for p in SCRIPTS.iterdir() if p.is_file())
    names = [p.name for p in scripts]
    stems = {p.stem: p.name for p in scripts}

    tiers: dict[int, list[tuple[Path, str]]] = {1: [], 3: [], 4: []}
    skip_prefix = str(OUTDIR).lower()
    for p in iter_corpus(ROOT):
        if str(p).lower().startswith(skip_prefix):
            continue
        rel = str(p.relative_to(ROOT))
        if p.parent == SCRIPTS:
            continue  # scripts handled as p2 separately
        tiers[classify(p, rel, now)].append((p, rel))

    alt = "|".join(re.escape(n) for n in sorted(names, key=len, reverse=True))
    name_re = re.compile(r"(?<![\w.-])(?:" + alt + r")(?![\w.-])")

    hits: dict[str, dict[int, set]] = {n: {1: set(), 2: set(), 3: set(), 4: set()} for n in names}
    stem_hits: dict[str, set] = {n: set() for n in names}

    def scan_file(p: Path, rel: str, tier: int):
        try:
            text = p.read_bytes().decode("utf-8", errors="replace")
        except OSError:
            return ""
        for m in name_re.finditer(text):
            hits[m.group(0)][tier].add(rel)
        return text

    for t in (1, 3, 4):
        for p, rel in tiers[t]:
            scan_file(p, rel, t)

    for p in scripts:
        rel = f"scripts/{p.name}"
        text = scan_file(p, rel, 2)
        if p.suffix.lower() == ".py":
            for m in IMPORT_RE.finditer(text):
                tgt = stems.get(m.group(1))
                if tgt and tgt != p.name:
                    stem_hits[tgt].add(rel)

    rows = []
    for p in scripts:
        n = p.name
        rel = f"scripts/{n}"
        try:
            text = p.read_bytes().decode("utf-8", errors="replace")
        except OSError:
            text = ""
        nlines = text.count("\n") + 1
        is_py = p.suffix.lower() == ".py"
        is_ps1 = p.suffix.lower() == ".ps1"
        cli = is_py and ("argparse" in text or "__main__" in text or "sys.argv" in text)
        is_test = bool(TEST_NAME.match(n)) or n in {"conftest.py", "__init__.py"}
        stem = p.stem
        target = None
        if stem.startswith("test_"):
            cand = stem[5:]
            for ext in (".py", ".ps1", ".js", ".cjs"):
                if (SCRIPTS / (cand + ext)).is_file():
                    target = cand + ext
        for suffix in ("_contract_tests", "_smoke_tests", "_tests"):
            if stem.endswith(suffix):
                cand = stem[: -len(suffix)]
                for ext in (".py", ".ps1", ".js", ".cjs"):
                    if (SCRIPTS / (cand + ext)).is_file():
                        target = cand + ext
        has_test = False
        if not is_test:
            for cand in (f"test_{stem}.py", f"{stem}_tests.ps1", f"{stem}_tests.js",
                         f"{stem}_tests.cjs", f"{stem}_contract_tests.ps1",
                         f"{stem}_smoke_tests.ps1"):
                if (SCRIPTS / cand).is_file():
                    has_test = True
        wrapper_of = None
        if is_ps1 and nlines <= 60:
            others = [m.group(0) for m in name_re.finditer(text) if m.group(0) != n]
            if others:
                wrapper_of = others[0]
        p1 = len(hits[n][1]); p3 = len(hits[n][3]); p4 = len(hits[n][4])
        ext2 = hits[n][2] | stem_hits[n]
        ext2.discard(rel)
        p2 = len(ext2)
        leased = rel in ACTIVE_LEASE_PATHS or n in ACTIVE_LEASE_PATHS
        expired_lease = rel in EXPIRED_LEASE_PATHS or n in EXPIRED_LEASE_PATHS
        clean_kit = n.lower().startswith("max_push")

        if leased or clean_kit:
            grade = "X"
        elif p1 == 0 and p2 == 0 and p3 == 0:
            grade = "C" if (p4 > 0 or is_test) else "D"
        elif is_test:
            grade = "A"
        elif wrapper_of:
            grade = "B"
        elif is_py and cli and has_test:
            grade = "S"
        else:
            grade = "A"
        notes = []
        if expired_lease:
            notes.append("expired-lease")
        if is_test and target is None:
            notes.append("orphan-test?")
        if wrapper_of:
            notes.append(f"wrapper-of:{wrapper_of}")
        if is_test and target:
            notes.append(f"test-of:{target}")
        mtime_days = int((now - p.stat().st_mtime) // 86400)
        rows.append({
            "name": n, "ext": p.suffix.lower(), "bytes": p.stat().st_size,
            "mtimeDays": mtime_days, "grade": grade, "cli": cli, "hasTest": has_test,
            "isTest": is_test, "p1": p1, "p2": p2, "p3": p3, "p4": p4,
            "leased": leased, "notes": ";".join(notes),
            "refSamples": "|".join(sorted(hits[n][1] | hits[n][3])[:4]),
        })

    counts: dict[str, int] = {}
    for r in rows:
        counts[r["grade"]] = counts.get(r["grade"], 0) + 1

    out_csv = OUTDIR / "02_ARTIFACT_GRADES.csv"
    with out_csv.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    summary = {
        "schemaVersion": "awx.artifact-grade-scan.v1",
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "total": len(rows), "counts": counts,
        "corpusFiles": {str(k): len(v) for k, v in tiers.items()},
        "scriptsCorpus": len(scripts),
        "csv": str(out_csv.relative_to(ROOT)),
    }
    (OUTDIR / "02_grade_scan_summary.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")
    print(json.dumps(summary))

    ghost_csv = OUTDIR / "00_SEED_MINE.csv"
    ghosts = []
    if ghost_csv.is_file():
        for row in csv.DictReader(ghost_csv.open(encoding="utf-8")):
            if row["live"] == "exists":
                continue
            token = row["token"]
            base = Path(token).name
            cnt = 0
            refs = []
            for p, rel in tiers[1]:
                try:
                    if base in p.read_text(encoding="utf-8", errors="replace"):
                        cnt += 1
                        refs.append(rel)
                except OSError:
                    pass
            ghosts.append({"token": token, "live": row["live"], "p1Refs": cnt, "samples": refs[:4]})
    (OUTDIR / "02_ghost_refs.json").write_text(json.dumps(ghosts, indent=1), encoding="utf-8")
    print(json.dumps({"ghosts": ghosts}, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
