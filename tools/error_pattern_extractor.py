#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Enhanced error pattern extractor for Spring/Gradle/Javac.
Recognizes both runtime bootRun failures and compile-time javac diagnostics.
Outputs:
  build-logs/error_patterns.json
  build-logs/error_patterns_detail.json
"""
import re, sys, json, os, glob, collections

R_PATTERNS = {
  'spring.boot.application-run-failed': re.compile(r'Application run failed'),
  'spring.beans.unsatisfied-dependency': re.compile(r'UnsatisfiedDependencyException'),
  'spring.beans.type-mismatch': re.compile(r'TypeMismatchException'),
  'gradle.bootRun.failed': re.compile(r'> Task :bootRun FAILED'),
  'gradle.process.non-zero-exit': re.compile(r'finished with non-zero exit value'),
  'gradle.compileJava.failed': re.compile(r'> Task :compileJava FAILED'),
  'javac.cannot-find-symbol': re.compile(r'error:\s+cannot find symbol'),
  'javac.package-not-found': re.compile(r'error:\s+package .+ does not exist'),
  'javac.class-not-found': re.compile(r'error:\s+cannot find symbol\s*\n\s*symbol:\s*class '),
  'config.value.invalid-boolean.probe.search.enabled': re.compile(r'Invalid boolean value \[?\{probe\.search\.enabled:false\}\]?')
}

def extract_patterns(text: str):
    total = collections.Counter()
    detail = collections.defaultdict(list)

    lines = text.splitlines()
    for i, raw in enumerate(lines):
        line = raw.rstrip()
        for key, rx in R_PATTERNS.items():
            if rx.search(line):
                total[key] += 1
                # Keep a small snippet for context
                ctx = "\n".join(lines[max(0,i-2):min(len(lines), i+3)])
                detail[key].append(ctx)
        # Capture missing symbol variable names
        m = re.search(r'symbol:\s+variable\s+([A-Za-z_][A-Za-z0-9_]*)', line)
        if m:
            var = m.group(1)
            key = f'javac.missing-variable.{var}'
            total[key] += 1
            detail.setdefault(key, []).append("\n".join(lines[max(0,i-3):i+2]))
    return total, detail

def main(paths):
    files=[]
    for p in paths:
        files += glob.glob(p)
    if not files:
        print("No logs matched.", file=sys.stderr)
        return 1

    summary = collections.Counter()
    detail_all = {}

    for p in files:
        try:
            text = open(p, 'r', encoding='utf-8', errors='ignore').read()
        except Exception:
            continue
        totals, detail = extract_patterns(text)
        summary.update(totals)
        for k, snippets in detail.items():
            detail_all.setdefault(k, []).extend(snippets)

    out_dir = os.path.dirname(files[0])
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "error_patterns.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    with open(os.path.join(out_dir, "error_patterns_detail.json"), "w", encoding="utf-8") as f:
        json.dump(detail_all, f, ensure_ascii=False, indent=2)
    print("Wrote", os.path.join(out_dir, "error_patterns.json"))
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["build-logs/*.log"]))
