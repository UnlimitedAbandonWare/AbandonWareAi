#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
persist_build_error_patterns.py
- Scans build logs and analysis reports to persist aggregated build error patterns.
- Writes to .build/error_patterns_db.json and updates BUILD_ERROR_PATTERNS.json / BUILD_ERROR_PATTERN_SUMMARY.md.
Usage:
  python scripts/persist_build_error_patterns.py --ingest build-logs --ingest ../some/other/src111_merge15/build-logs
"""
import os, re, json, argparse, datetime
from pathlib import Path

LOG_PATTERNS = {
    "java.cannot_find_symbol": re.compile(r"cannot find symbol", re.I),
    "java.package_does_not_exist": re.compile(r"package\s+[a-zA-Z0-9_.]+\s+does not exist", re.I),
    "java.incompatible_types": re.compile(r"incompatible types", re.I),
    "java.method_arg_mismatch": re.compile(r"method\s+.*\s+in\s+class\s+.*\s+cannot\s+be\s+applied\s+to\s+given\s+types", re.I),
    "java.reference_ambiguous": re.compile(r"reference to\s+\w+\s+is\s+ambiguous", re.I),
    "java.inference_variable_bounds": re.compile(r"inference variable [A-Z]\w* has incompatible bounds", re.I),
    "java.class_interface_expected": re.compile(r"class,\s*interface,\s*enum,\s*or\s*record\s*expected", re.I),
    "java.illegal_escape_character": re.compile(r"illegal escape character", re.I),
    "gradle.build_failed": re.compile(r"FAILURE:\s*Build failed with an exception", re.I),
    "gradle.dependency_resolution": re.compile(r"Could not resolve [^:]+:[^:]+", re.I),
    "gradle.lms_core_not_found": re.compile(r":lms-core not found|project\s+:lms-core\s+not\s+found|Project with path ':lms-core' could not be found|Could not resolve project :lms-core", re.I),
    "maven.build_failure": re.compile(r"^\[INFO\] BUILD FAILURE", re.I | re.M),
    "lombok.missing": re.compile(r"Lombok processor not found|package lombok does not exist", re.I),
}

def read_text(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="ignore") if p.exists() else ""

def scan_logs(root: Path):
    candidates = []
    logs_dir = root / "build-logs"
    if logs_dir.exists():
        candidates.extend(list(logs_dir.glob("**/*.log")))
        candidates.extend(list(logs_dir.glob("**/*.txt")))
    latest = root / "BUILD_ERROR__latest.txt"
    if latest.exists():
        candidates.append(latest)
    analysis_json = root / "analysis" / "build_error_report.json"
    if analysis_json.exists():
        candidates.append(analysis_json)

    agg = {k: 0 for k in LOG_PATTERNS.keys()}
    examples = {k: [] for k in LOG_PATTERNS.keys()}
    for f in candidates:
        text = read_text(f)
        if f.suffix.lower() == ".json":
            try:
                data = json.loads(text)
                pats = data.get("patterns") or {}
                for k in agg.keys():
                    v = pats.get(k) or pats.get(k.replace(".", "_")) or {}
                    if isinstance(v, dict):
                        agg[k] += int(v.get("count", 0))
            except Exception:
                pass
        for k, rx in LOG_PATTERNS.items():
            matches = list(rx.finditer(text))
            agg[k] += len(matches)
            if matches and len(examples[k]) < 5:
                for m in matches[:5-len(examples[k])]:
                    ctx = text[max(0, m.start()-120): m.end()+120].splitlines()[0][:200]
                    examples[k].append(f"{f.name}: ...{ctx}...")
    return agg, examples, [str(p) for p in candidates]

def merge_db(existing: dict, delta_counts: dict, examples: dict, sources: list):
    out = existing.copy() if existing else {}
    out.setdefault("generated_at", datetime.datetime.utcnow().isoformat()+"Z")
    src = set(out.get("sources_scanned", []))
    src.update(sources)
    out["sources_scanned"] = sorted(src)
    agg = out.get("aggregated_counts") or {}
    for k, v in delta_counts.items():
        agg[k] = int(agg.get(k, 0)) + int(v or 0)
    out["aggregated_counts"] = agg
    ex = out.get("examples") or {}
    for k, arr in examples.items():
        ex.setdefault(k, [])
        existing_set = set(ex[k])
        for e in arr:
            if len(ex[k]) >= 10:
                break
            if e not in existing_set:
                ex[k].append(e)
                existing_set.add(e)
    out["examples"] = ex
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ingest", action="append", default=[], help="Extra directories to scan for logs")
    args = ap.parse_args()

    root = Path(".").resolve()
    db_path = root / ".build" / "error_patterns_db.json"
    root.joinpath(".build").mkdir(parents=True, exist_ok=True)
    existing = {}
    if db_path.exists():
        try:
            existing = json.loads(read_text(db_path))
        except Exception:
            existing = {}

    counts, examples, sources = scan_logs(root)
    for extra in args.ingest:
        d = Path(extra).resolve()
        if d.exists():
            c, ex, srcs = scan_logs(d if d.is_dir() else d.parent)
            for k, v in c.items():
                counts[k] = counts.get(k, 0) + v
            for k, arr in ex.items():
                examples.setdefault(k, [])
                for e in arr:
                    if e not in examples[k] and len(examples[k]) < 10:
                        examples[k].append(e)
            sources.extend(srcs)

    merged = merge_db(existing, counts, examples, sources)
    db_path.write_text(json.dumps(merged, ensure_ascii=False, indent=2), encoding="utf-8")

    # Also expose top-level artifacts
    (root / "BUILD_ERROR_PATTERNS.json").write_text(json.dumps({
        "updated_at": datetime.datetime.utcnow().isoformat()+"Z",
        "aggregated_counts": merged.get("aggregated_counts", {}),
        "examples": merged.get("examples", {}),
        "sources": merged.get("sources_scanned", []),
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    top = sorted(merged.get("aggregated_counts", {}).items(), key=lambda kv: kv[1], reverse=True)[:12]
    summary = ["# BUILD Error Pattern Summary (persisted)","","- Top patterns:"]
    for k, v in top:
        summary.append(f"  - {k}: {v}")
    (root / "BUILD_ERROR_PATTERN_SUMMARY.md").write_text("\n".join(summary)+"\n", encoding="utf-8")

    print(json.dumps({"db": str(db_path), "top": top}, ensure_ascii=False))

if __name__ == "__main__":
    main()
