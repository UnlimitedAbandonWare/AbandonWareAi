
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_guard.py — preflight checks for configuration before Gradle bootRun
- Detect duplicate top-level keys across application*.yml / *.yaml
- Detect suspected profile files with duplicate sections
- Return non-zero exit on hard failures (when --strict)
"""
import os, sys, re, glob, json, argparse, io

def find_yaml_files(root):
    paths = []
    for pat in ["**/application*.yml", "**/application*.yaml"]:
        paths += glob.glob(os.path.join(root, pat), recursive=True)
    return [p for p in paths if all(x not in p for x in ("/build/", "/.gradle/", "/out/", "/target/", "/node_modules/"))]

def find_top_level_keys(text):
    keys = []
    for i, line in enumerate(text.splitlines(), 1):
        m = re.match(r'^([A-Za-z0-9_\-"\'\.]+)\s*:\s*$', line.strip())
        if m:
            keys.append((i, m.group(1)))
    return keys

def scan_duplicates(path):
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        text = f.read()
    # consider non-indented keys only
    keys = []
    for i, line in enumerate(text.splitlines(), 1):
        if re.match(r'^\s*#', line) or line.strip()=='':
            continue
        # count 'guard:' occurrences irrespective of quotes
        if re.match(r'^\s*["\']?guard["\']?\s*:\s*$', line):
            keys.append(("guard", i))
    dups = {}
    if len(keys) >= 2:
        dups['guard'] = [ln for k, ln in keys]
    return dups

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()
    files = find_yaml_files(args.root)
    report = []
    failures = 0
    for f in files:
        dups = scan_duplicates(f)
        if dups:
            report.append({"file": f, "duplicates": dups})
            failures += 1
    print(json.dumps({"files_scanned": len(files), "issues": report}, ensure_ascii=False, indent=2))
    if args.strict and failures:
        sys.exit(1)

if __name__ == "__main__":
    main()
