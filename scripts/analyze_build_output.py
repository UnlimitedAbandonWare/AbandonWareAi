#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Analyze Gradle build output and source tree for common error patterns.
- Parses a given log file (or runs the build command if requested)
- Scans source code for problematic constructs
- Emits JSON (analysis/build_error_report.json) and CSV history (analysis/history.csv)
- Prints a short human-readable summary to stdout
"""
import os, re, sys, json, time, datetime, pathlib, math, csv, subprocess, shlex
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_CODE_ROOT = ROOT  # project root (src_91)
REPO_ROOT = ROOT  # one level up ("1229")

# -----------------------------------------------------------------------------
# Regex catalog (log-driven)
LOG_PATTERNS = {
    "gradle_build_failed": re.compile(r"FAILURE: Build failed with an exception", re.I),
    "gradle_task_failed": re.compile(r"Execution failed for task", re.I),
    "dependency_resolve_failed": re.compile(r"Could not resolve (?:all )?files? for configuration|Could not resolve [^:]+:[^:]+", re.I),
    "java_symbol_not_found": re.compile(r"symbol:\s+(?:class|method|variable)\s", re.I),
    "java_package_missing": re.compile(r"package\s+[a-zA-Z0-9_.]+\s+does not exist", re.I),
    "java_classfile_not_found": re.compile(r"class file for\s+[a-zA-Z0-9_.]+\s+not found", re.I),
    "java_compilation_failed": re.compile(r"\d+\s+error(?:s)?\s*$", re.I | re.M),
    "java_illegal_start": re.compile(r"illegal start of expression", re.I),
    "java_incompatible_types": re.compile(r"incompatible types:", re.I),
    "java_unreachable_statement": re.compile(r"unreachable statement", re.I),
    "lombok_missing": re.compile(r"Lombok processor not found|package lombok does not exist", re.I),
    "java_version_mismatch": re.compile(r"Unsupported class file major version|invalid target release", re.I),
    "regex_backref_stray": re.compile(r"illegal escape character|dangling meta character|Unclosed group", re.I),
}

# Source scanning heuristics (code-driven)
SRC_PATTERNS = {
    "malformed_import_public": re.compile(r"^\s*import\s+public\s+", re.M),
    "malformed_import_final":  re.compile(r"^\s*import\s+final\s+", re.M),
    "double_brace_init":       re.compile(r"new\s+\w+(?:<[^>]*>)?\s*\(\)\s*\{\{", re.M),
    # Single-escaped regex meta in Java strings (likely wrong): \d, \w, \s, \b, \. without double escaping
    "regex_single_escape_suspect": re.compile(r'(?P<prefix>Pattern\.compile\(|matches\(|replaceAll\(|split\()\s*"[^"]*(?<!\\)\\[dwsb.]', re.M),
    # Email regex with single escape for dot like "\." not double escaped; this is heuristic
    "pii_email_single_escape": re.compile(r'@[a-zA-Z0-9_-]+\\\.[a-zA-Z]{2,}'),
}

SEVERITY_WEIGHTS = {
    "gradle_build_failed": 0.9,
    "gradle_task_failed": 0.7,
    "dependency_resolve_failed": 0.7,
    "java_symbol_not_found": 0.7,
    "java_package_missing": 0.8,
    "java_classfile_not_found": 0.7,
    "java_compilation_failed": 0.8,
    "java_illegal_start": 0.6,
    "java_incompatible_types": 0.5,
    "java_unreachable_statement": 0.4,
    "lombok_missing": 0.6,
    "java_version_mismatch": 0.8,
    "regex_backref_stray": 0.3,
    "malformed_import_public": 0.4,
    "malformed_import_final": 0.4,
    "double_brace_init": 0.3,
    "regex_single_escape_suspect": 0.3,
    "pii_email_single_escape": 0.2,
}

def sigmoid(x: float) -> float:
    # numerically stable-ish
    if x >= 0:
        z = math.exp(-x)
        return 1.0 / (1.0 + z)
    else:
        z = math.exp(x)
        return z / (1.0 + z)

def normalized_severity(count: int, weight: float, threshold: float = 1.0, scale: float = 1.0) -> float:
    """Map (count, weight) -> [0,1] via a logistic curve.
    x = (count * weight - threshold) / scale
    """
    x = (count * weight - threshold) / max(1e-6, scale)
    return round(sigmoid(x), 4)

def ensure_dir(p: pathlib.Path):
    p.mkdir(parents=True, exist_ok=True)

def read_text(p: pathlib.Path) -> str:
    return p.read_text(encoding="utf-8", errors="ignore")

def scan_log(log_path: pathlib.Path):
    text = read_text(log_path)
    hits = defaultdict(int)
    for name, regex in LOG_PATTERNS.items():
        matches = list(regex.finditer(text))
        hits[name] = len(matches)
    return hits

def scan_source(code_root: pathlib.Path):
    hits = defaultdict(lambda: {"count": 0, "files": defaultdict(int)})
    exts = (".java", ".kt", ".kts", ".gradle", ".md", ".properties", ".yml", ".yaml")
    for p in code_root.rglob("*"):
        if p.is_file() and p.suffix in exts:
            try:
                txt = read_text(p)
            except Exception:
                continue
            for name, regex in SRC_PATTERNS.items():
                if regex.search(txt):
                    cnt = len(list(regex.finditer(txt)))
                    hits[name]["count"] += cnt
                    hits[name]["files"][str(p.relative_to(REPO_ROOT))] += cnt
    # flatten files
    for name in list(hits.keys()):
        files_map = hits[name]["files"]
        sorted_files = sorted(files_map.items(), key=lambda kv: kv[1], reverse=True)
        hits[name]["files"] = [{"path": k, "hits": v} for k, v in sorted_files[:20]]
    return hits

def aggregate(hits_log: dict, hits_src: dict):
    # unify into a single patterns dict: {pattern: {count, severity, files?}}
    patterns = {}
    # start with log hits
    for name, count in hits_log.items():
        weight = SEVERITY_WEIGHTS.get(name, 0.3)
        patterns[name] = {
            "count": count,
            "severity": normalized_severity(count, weight),
        }
    # merge src hits
    for name, info in hits_src.items():
        count = info["count"]
        weight = SEVERITY_WEIGHTS.get(name, 0.2)
        files = info.get("files", [])
        if name in patterns:
            patterns[name]["count"] += count
            patterns[name]["severity"] = normalized_severity(patterns[name]["count"], weight)
            patterns[name]["files"] = files
        else:
            patterns[name] = {
                "count": count,
                "severity": normalized_severity(count, weight),
                "files": files
            }
    # overall score: 1 - product(1-sev_i) over patterns with count>0
    sevs = [p["severity"] for p in patterns.values() if p["count"] > 0]
    prod = 1.0
    for s in sevs:
        prod *= (1.0 - s)
    overall = round(1.0 - prod, 4) if sevs else 0.0
    return patterns, overall

def write_json_report(patterns: dict, overall: float, out_dir: pathlib.Path, meta: dict, log_rel: str):
    ensure_dir(out_dir)
    out = {
        "timestamp": datetime.datetime.utcnow().isoformat() + "Z",
        "overall_risk": overall,
        "patterns": patterns,
        "log_file": log_rel,
        "meta": meta,
    }
    out_path = out_dir / "build_error_report.json"
    out_path.write_text(json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")
    return out_path

def append_history_csv(patterns: dict, overall: float, out_dir: pathlib.Path, log_rel: str):
    ensure_dir(out_dir)
    hist = out_dir / "history.csv"
    # header: timestamp, overall, pattern:count ...
    ts = datetime.datetime.utcnow().isoformat() + "Z"
    # unify keys stable order
    keys = sorted(patterns.keys())
    row = {
        "timestamp": ts,
        "overall": overall,
        "log_file": log_rel,
    }
    for k in keys:
        row[k] = patterns[k]["count"]
    write_header = not hist.exists()
    with hist.open("a", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["timestamp", "overall", "log_file"] + keys)
        if write_header:
            w.writeheader()
        w.writerow(row)

def update_summary_md(patterns: dict, out_md: pathlib.Path):
    lines = ["# Build Error Pattern Summary (auto)"]
    for name, info in sorted(patterns.items(), key=lambda kv: kv[1]["severity"], reverse=True):
        lines.append(f"- {name}: {info['count']} (sev={info['severity']})")
        files = info.get("files")
        if files:
            sample = ", ".join([f["path"] for f in files[:3]])
            if sample:
                lines.append(f"  - samples: {sample}")
    out_md.write_text("\n".join(lines) + "\n", encoding="utf-8")

def run_build_and_capture():
    jar = ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar"
    java_bin = os.environ.get("JAVA_HOME")
    java_bin = (pathlib.Path(java_bin) / "bin" / "java") if java_bin else "java"
    cmd = f'{java_bin} -classpath "{jar}" org.gradle.wrapper.GradleWrapperMain build -x test'
    print(f"[analyzer] Running: {cmd}", file=sys.stderr)
    # run and capture
    proc = subprocess.Popen(shlex.split(str(cmd)), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, cwd=str(ROOT))
    out, _ = proc.communicate()
    return out.decode("utf-8", errors="ignore"), proc.returncode

def main(argv=None):
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", help="Path to a Gradle build log to analyze.")
    ap.add_argument("--code-root", default=str(REPO_ROOT), help="Root directory to scan for source issues.")
    ap.add_argument("--run-build", action="store_true", help="If set, run the Gradle build and capture output.")
    args = ap.parse_args(argv)

    code_root = pathlib.Path(args.code_root)
    ensure_dir(ROOT / "analysis")
    log_dir = ROOT / "analysis"

    log_path = None
    build_exit = None
    if args.log:
        log_path = pathlib.Path(args.log)
        text = log_path.read_text(encoding="utf-8", errors="ignore")
    elif args.run_build:
        text, build_exit = run_build_and_capture()
        ts = time.strftime("%Y%m%d_%H%M%S")
        log_path = log_dir / f"gradle_build_{ts}.log"
        log_path.write_text(text, encoding="utf-8")
    else:
        # fallback: pick the latest log in analysis/
        cands = sorted(log_dir.glob("gradle_build_*.log"))
        if not cands:
            print("[analyzer] No log provided and none found; scanning source only.", file=sys.stderr)
            log_path = None
        else:
            log_path = cands[-1]
            text = log_path.read_text(encoding="utf-8", errors="ignore")

    hits_log = defaultdict(int)
    log_rel = None
    if log_path and log_path.exists():
        hits_log = scan_log(log_path)
        log_rel = str(log_path.relative_to(ROOT))
    hits_src = scan_source(code_root)

    patterns, overall = aggregate(hits_log, hits_src)

    meta = {
        "repo_root": str(REPO_ROOT),
        "project_root": str(ROOT),
        "build_exit_code": build_exit,
        "java_home": os.environ.get("JAVA_HOME"),
        "gradle_args": "build -x test",
    }

    out_json = write_json_report(patterns, overall, ROOT / "analysis", meta, log_rel or "")
    append_history_csv(patterns, overall, ROOT / "analysis", log_rel or "")
    update_summary_md(patterns, REPO_ROOT / "BUILD_ERROR_PATTERN_SUMMARY.md")

    print(f"[analyzer] overall_risk={overall}")
    print(f"[analyzer] report: {out_json}")

if __name__ == "__main__":
    main()
