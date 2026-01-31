#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Build Error Resolver (enhanced)
- Scans provided logs and the persisted DB (.build/error_patterns_db.json)
- Matches against formulas.yaml/json
- Prints remediation plan and optionally runs basic commands
Usage:
  python build_fix_enhanced.py --logs "logs/**/*.log" --apply
  python build_fix_enhanced.py --scan . --persist
"""
import os, re, sys, json, glob, argparse, subprocess, datetime
from pathlib import Path

REGEX_CACHE = {}

def load_formulas(root: Path):
    for cand in ["formulas.yaml","formulas.yml","formulas.json"]:
        p = root / cand
        if p.exists():
            if p.suffix in (".yaml",".yml"):
                try:
                    import yaml
                    with open(p, "r", encoding="utf-8") as f:
                        return yaml.safe_load(f) or {}
                except Exception as e:
                    print(f"[WARN] failed to read YAML formulas: {e}")
            else:
                with open(p, "r", encoding="utf-8") as f:
                    return json.load(f)
    return {"patterns": {}}

def compile_regex(pattern):
    if pattern in REGEX_CACHE:
        return REGEX_CACHE[pattern]
    rx = re.compile(pattern, re.I | re.M)
    REGEX_CACHE[pattern] = rx
    return rx

def scan_text_for_hits(text, formulas):
    hits = {}
    for key, spec in (formulas.get("patterns") or {}).items():
        pattern = spec.get("regex") or key
        try:
            rx = compile_regex(pattern)
            if rx.search(text):
                hits[key] = len(rx.findall(text))
        except re.error:
            # bad regex
            continue
    return hits

def read_text(path, limit=4_000_000):
    try:
        data = Path(path).read_bytes()[:limit]
        return data.decode("utf-8", errors="replace")
    except Exception as e:
        return ""

def scan_paths(paths, formulas):
    agg = {}
    details = {}
    for p in paths:
        for file in glob.glob(p, recursive=True):
            if os.path.isdir(file): 
                continue
            txt = read_text(file)
            if not txt: 
                continue
            hits = scan_text_for_hits(txt, formulas)
            if hits:
                agg = {k: agg.get(k,0) + v for k,v in hits.items()}
                details[file] = hits
    return agg, details

def load_persisted_db(root: Path):
    p = root / ".build" / "error_patterns_db.json"
    if p.exists():
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            return {}
    return {}

def save_persisted_db(root: Path, agg, details):
    root.joinpath(".build").mkdir(parents=True, exist_ok=True)
    p = root / ".build" / "error_patterns_db.json"
    now = datetime.datetime.utcnow().isoformat()+"Z"
    prev = {}
    if p.exists():
        try:
            prev = json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            prev = {}
    # merge counts
    prev_counts = prev.get("aggregated_counts", {})
    for k,v in agg.items():
        prev_counts[k] = prev_counts.get(k,0) + v
    prev["aggregated_counts"] = prev_counts
    prev["last_run_at"] = now
    prev["last_details"] = details
    p.write_text(json.dumps(prev, ensure_ascii=False, indent=2), encoding="utf-8")
    return p

def make_plan(agg, formulas):
    plan = []
    patterns = formulas.get("patterns") or {}
    for key, count in sorted(agg.items(), key=lambda kv: (-kv[1], kv[0])):
        spec = patterns.get(key, {})
        actions = spec.get("actions") or []
        notes = spec.get("notes") or ""
        plan.append({
            "key": key,
            "count": count,
            "actions": actions,
            "notes": notes
        })
    return plan

def maybe_run(actions, apply=False, cwd=None):
    results = []
    for a in actions:
        if isinstance(a, str) and (a.startswith("mvn ") or a.startswith("gradle ") or a.startswith("./gradlew") or a.startswith("npm ") or a.startswith("pnpm ")):
            if apply:
                try:
                    print(f"[RUN] {a}")
                    ret = subprocess.run(a, shell=True, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
                    results.append({"cmd": a, "rc": ret.returncode, "out": ret.stdout.decode("utf-8","replace")[-2000:]})
                except Exception as e:
                    results.append({"cmd": a, "rc": 999, "out": str(e)})
            else:
                results.append({"cmd": a, "rc": None, "out": "<dry-run>"})
        else:
            results.append({"cmd": a, "rc": None, "out": "<manual step>"})
    return results

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--project-root", default=".")
    ap.add_argument("--logs", default="**/*.log,**/*.txt")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--persist", action="store_true")
    args = ap.parse_args()

    root = Path(args.project_root).resolve()
    formulas = load_formulas(root)
    globs = [g.strip() for g in args.logs.split(",") if g.strip()]
    agg, details = scan_paths([str(root.joinpath(g)) for g in globs], formulas)

    db = load_persisted_db(root)
    if args.persist:
        db_path = save_persisted_db(root, agg, details)
        print(f"[INFO] persisted DB updated: {db_path}")
    # Combine counts with persisted for planning visibility
    combined = dict(db.get("aggregated_counts", {}))
    for k,v in agg.items():
        combined[k] = combined.get(k,0) + v

    plan = make_plan(combined, formulas)
    print(json.dumps({
        "summary": {"found_now": agg, "combined_counts": combined},
        "plan": plan
    }, ensure_ascii=False, indent=2))

    # Optional: execute top-N actions (conservative: only first action per key)
    for item in plan[:5]:
        actions = item.get("actions") or []
        if actions:
            res = maybe_run([actions[0]], apply=args.apply, cwd=str(root))
            print(json.dumps({"exec": res}, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
