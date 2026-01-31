#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Build Error Miner — v1.2
- Scans directories or .zip bundles for build logs
- Extracts robust, normalized error patterns (Gradle/Maven/Javac/Kotlin/Spring/Test)
- Writes machine-readable summaries (JSON/NDJSON/CSV) and a Markdown report
Usage:
  python tools/build_error_miner.py scan --in <dir_or_zip>[,<dir_or_zip>...] --out analysis/build_error_report
"""
import os, re, sys, json, csv, zipfile, io, argparse, hashlib, datetime

PATTERNS = [
    ("GradleBuildFailed", r'BUILD FAILED|> Task .+ FAILED|Execution failed for task'),
    ("GradleDependencyNotFound", r'Could not (resolve|find) (?:any )?version for|Could not find [^ ]+:[^ ]+:[^ ]+'),
    ("GradlePluginNotFound", r'Plugin [\'"][^\'"]+[\'"] not found'),
    ("GradleLmsCoreModuleNotFound", r':lms-core not found\.'),
    ("MavenBuildFailed", r'\[ERROR\] BUILD FAILURE|\[ERROR\] Failed to execute goal'),
    ("JavacCannotFindSymbol", r'error: cannot find symbol'),
    ("JavacReferenceAmbiguous", r'reference to \w+ is ambiguous'),
    ("JavacChatResultMoved", r'ChatService\.ChatResult'),
    ("JavacMetadataGetRemoved", r'location:\s+variable\s+metadata\s+of\s+type\s+Metadata|symbol:\s+method\s+get\(String\)'),
    ("JavacMissingHelper_composeEvidenceOnlyAnswer", r'composeEvidenceOnlyAnswer\('),
    ("JavacTimeoutMissingImport", r'symbol:\s+class\s+TimeoutException'),
    ("JavacPackageDoesNotExist", r'error: package [\w\.]+ does not exist'),
    ("JavacDuplicateClass", r'error: duplicate class:|duplicate class'),
    ("JavacIncompatibleTypes", r'error: incompatible types'),
    ("JavacIncompatibleTypesTextSegment", r'incompatible types:\s+String cannot be converted to TextSegment'),
    ("JavacInferenceVariableBounds", r"inference variable [A-Z]\w* has incompatible bounds"),
    ("JavacBadSourceFile", r'bad source file: .* file does not contain class'),
    ("JavacClassInterfaceExpected", r'(class|interface|enum|record) expected'),
    ("JavacIllegalStartOfType", r'illegal start of (type|expression)'),
    ("JavacMissingSemicolon", r"';' expected"),
    ("JavacLambdaLocalNotFinal", r"error: local variables referenced from a lambda expression must be final or effectively final"),
    ("JavacOrphanedCase", r"error: orphaned case"),
    ("JavacCaseDefaultOrBraceExpected", r"error: case, default, or '}' expected"),
    ("KotlinUnresolvedReference", r'Unresolved reference:'),
    ("KotlinTypeMismatch", r'Type mismatch'),
    ("TestFailures", r'There were failing tests|FAILURES!!!'),
    ("SpringApplicationRunFailed", r'Application run failed'),
    ("SpringUnsatisfiedDependency", r'UnsatisfiedDependencyException|NoSuchBeanDefinitionException'),
    ("SpringConfigBindingFailed", r'Binding to target .* failed|Failed to bind properties|ConfigurationPropertiesBindException'),
    ("NoSuchMethodError", r'NoSuchMethodError'),
    ("ClassNotFound", r'ClassNotFoundException|NoClassDefFoundError')
]

PATTERNS.append(("JavacMethodArgMismatch", r"method .* in class .* cannot be applied to given types"))

LOG_EXTS = ('.log', '.txt', '.out', '.err', '.stderr', '.stdout')

def normalize_line(line: str) -> str:
    line = re.sub(r'([A-Za-z]:)?[\\/][^\s:]*(?:[\\/][^\s:]+)+', '<path>', line)
    line = re.sub(r'(:|\s)line\s*\d+', r' \1line <n>', line, flags=re.IGNORECASE)
    line = re.sub(r'(?<=:|\()\d+(?=\)|:)', '<n>', line)
    line = re.sub(r'\b[0-9a-fA-F]{7,40}\b', '<hex>', line)
    line = re.sub(r'https?://\S+', '<url>', line)
    return re.sub(r'\s+', ' ', line).strip()

def sha1(s: str) -> str:
    return hashlib.sha1(s.encode('utf-8')).hexdigest()[:12]

def scan_text(text: str):
    lines = text.splitlines()
    hits = {}
    for i, line in enumerate(lines):
        for code, rx in PATTERNS:
            if re.search(rx, line):
                start = max(0, i-2); end = min(len(lines), i+3)
                snippet = "\n".join(lines[start:end])
                entry = hits.setdefault(code, {"count":0, "examples": []})
                entry["count"] += 1
                if len(entry["examples"]) < 5:
                    entry["examples"].append(normalize_line(snippet))
    if "JavacCannotFindSymbol" in hits:
        for i, line in enumerate(lines):
            if "cannot find symbol" in line:
                seg = "\n".join(lines[i:min(len(lines), i+4)])
                hits["JavacCannotFindSymbol"]["examples"].append(normalize_line(seg))
    return hits

def scan_file(path: str):
    try:
        with open(path, 'r', encoding='utf-8', errors='ignore') as f:
            return scan_text(f.read())
    except Exception:
        return {}

def scan_zip(zpath: str):
    results = {}
    with zipfile.ZipFile(zpath, 'r') as z:
        for name in z.namelist():
            low = name.lower()
            if any(low.endswith(ext) for ext in LOG_EXTS) or "build" in low or "error" in low:
                try:
                    with z.open(name) as fh:
                        data = fh.read().decode('utf-8', errors='ignore')
                    hits = scan_text(data)
                    if hits:
                        results[name] = hits
                except Exception:
                    continue
    return results

def collect_inputs(paths):
    inputs = []
    for p in paths:
        if os.path.isdir(p):
            for root, dirs, files in os.walk(p):
                for f in files:
                    low = f.lower()
                    if any(low.endswith(ext) for ext in LOG_EXTS) or "build" in low or "error" in low:
                        inputs.append(os.path.join(root, f))
        elif os.path.isfile(p) and p.lower().endswith('.zip'):
            inputs.append(p)
        elif os.path.isfile(p):
            inputs.append(p)
    return inputs

def main(argv):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    sc = sub.add_parser('scan')
    sc.add_argument('--in', dest='inputs', required=True, help='comma-separated list of dirs/files/zips')
    sc.add_argument('--out', dest='out', required=True, help='output path without extension')
    args = ap.parse_args(argv)

    paths = [s.strip() for s in args.inputs.split(',') if s.strip()]
    outputs = {"summary": {}, "files": {}, "zips": {}}
    inputs = collect_inputs(paths)
    for p in inputs:
        if os.path.isfile(p) and p.lower().endswith('.zip'):
            zres = scan_zip(p)
            if zres:
                outputs["zips"][p] = zres
                for _, hits in zres.items():
                    for code, data in hits.items():
                        outputs["summary"][code] = outputs["summary"].get(code, 0) + data["count"]
        elif os.path.isfile(p):
            hits = scan_file(p)
            if hits:
                outputs["files"][p] = hits
                for code, data in hits.items():
                    outputs["summary"][code] = outputs["summary"].get(code, 0) + data["count"]

    # Sort summary
    summary_sorted = [{"code": k, "count": v} for k, v in sorted(outputs["summary"].items(), key=lambda kv: kv[1], reverse=True)]
    base = args.out
    os.makedirs(os.path.dirname(base), exist_ok=True)
    # JSON
    with open(base + ".json", "w", encoding="utf-8") as f:
        json.dump({"generated_at": datetime.datetime.utcnow().isoformat() + "Z",
                   "summary": summary_sorted,
                   "files": outputs["files"],
                   "zips": outputs["zips"]}, f, ensure_ascii=False, indent=2)
    # CSV
    with open(base + ".csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f); w.writerow(["code","count"])
        for row in summary_sorted: w.writerow([row["code"], row["count"]])
    # NDJSON of examples
    with open(base + ".ndjson", "w", encoding="utf-8") as f:
        for scope, obj in (("file", outputs["files"]), ("zip", outputs["zips"])):
            for name, hits in obj.items():
                for code, data in hits.items():
                    for ex in data.get("examples", []):
                        rec = {"scope": scope, "source": name, "code": code, "example": ex, "fp": sha1(code + "|" + ex)}
                        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    # Markdown
    with open(base + ".md", "w", encoding="utf-8") as f:
        f.write(f"# Build Error Patterns — Report\n\n")
        for row in summary_sorted:
            f.write(f"- **{row['code']}**: {row['count']}\n")
        f.write("\n---\n")
        for scope, obj in (("file", outputs["files"]), ("zip", outputs["zips"])):
            for name, hits in obj.items():
                f.write(f"\n## {scope.upper()}: {name}\n")
                for code, data in hits.items():
                    f.write(f"\n### {code} ({data['count']})\n")
                    for ex in data.get("examples", [])[:3]:
                        f.write(f"\n> {ex}\n")
    print("Wrote:", base + ".[json|csv|ndjson|md]")
    return 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))