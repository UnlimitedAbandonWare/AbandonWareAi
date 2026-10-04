#!/usr/bin/env python3
"""brief_fact_check.py — re-check 'file:line' facts inside a directive against
the live tree. Extracts refs like scripts/x.py:62, path\\f.py:10-20,
name.py(313행), .gitignore 303행; resolves each path (root-relative, env vars,
unique basename via git ls-files); verdicts:

  OK / DRIFT (quoted expected phrase absent within cited line ±3) /
  MISSING / OUT_OF_RANGE / AMBIGUOUS

Exit: 0 = all OK, 1 = any non-OK verdict, 2 = input error.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

_NAME = r"[\w~$%@(){}\[\]+\-]+"
_PATH = (r"(?:[A-Za-z]:[\\/]|%[\w]+%[\\/])?(?:" + _NAME + r"[\\/])*"
         r"(?:" + _NAME + r"\.[A-Za-z0-9]{1,12}|\.[A-Za-z0-9]{2,})")
REF_RE = re.compile(
    r"(?P<path>" + _PATH + r")(?:"
    r"\s*:\s*(?P<c1>\d{1,7})(?:\s*[-–—~]\s*(?P<c2>\d{1,7}))?"
    r"|\s*\(\s*(?P<p1>\d{1,7})(?:\s*[-–—~]\s*(?P<p2>\d{1,7}))?\s*(?:행|줄)?\s*\)"
    r"|\s+(?P<w1>\d{1,7})(?:\s*[-–—~]\s*(?P<w2>\d{1,7}))?\s*(?:행|줄)"
    r")")
PHRASE_RE = re.compile(r"`([^`\n]{3,200})`|\"([^\"\n]{3,200})\"")
WINDOW = 3


def _repo_files(root):
    try:
        r = subprocess.run(["git", "--no-optional-locks", "ls-files"], cwd=root,
                           capture_output=True, timeout=30)
        if r.returncode == 0:
            return [p.decode("utf-8") for p in r.stdout.split(b"\n") if p]
    except (OSError, subprocess.TimeoutExpired):
        pass
    out = []
    for p in root.rglob("*"):
        if p.is_file() and ".git" not in p.parts:
            out.append(p.relative_to(root).as_posix())
    return out


def _expand(path):
    def rep(m):
        return os.environ.get(m.group(1) or m.group(2), m.group(0))
    return re.sub(r"%([^%]+)%|\$(\w+)", rep, path)


def _resolve(path, root, repo_index):
    p = _expand(path).replace("\\", "/")
    if len(p) > 1 and p[1] == ":" and Path(p).exists():
        return Path(p), "absolute"
    cand = root / p
    if cand.exists():
        return cand, "root"
    base = p.rsplit("/", 1)[-1].casefold()
    hits = [f for f in repo_index if f.rsplit("/", 1)[-1].casefold() == base]
    if len(hits) == 1:
        return root / hits[0], "unique-basename"
    if len(hits) > 1:
        return None, "ambiguous"
    return None, "missing"


def extract_refs(text):
    """[(raw, path, l1, l2, [phrases])] — deduped, file order."""
    seen, refs = set(), []
    for line in text.splitlines():
        for m in REF_RE.finditer(line):
            l1 = int(m.group("c1") or m.group("p1") or m.group("w1"))
            l2 = m.group("c2") or m.group("p2") or m.group("w2")
            l2 = int(l2) if l2 else l1
            path = m.group("path")
            phrases = [g for g in
                       (a or b for a, b in PHRASE_RE.findall(line))
                       if g and path not in g and m.group(0) not in g
                       and any(c.isascii() and c.isalnum() for c in g)]
            key = (path, l1, l2, tuple(phrases[:4]))
            if key not in seen:
                seen.add(key)
                refs.append((m.group(0).strip(), path, l1, l2, phrases[:4]))
    return refs


def check_file(path, root):
    try:
        text = Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return {"schema": "awx.brief-fact-check.v1", "file": str(path),
                "error": "input-unreadable", "refs": []}
    repo_index = _repo_files(root)
    results = []
    for raw, ref_path, l1, l2, phrases in extract_refs(text):
        resolved, how = _resolve(ref_path, root, repo_index)
        row = {"ref": raw, "path": ref_path, "lines": [l1, l2],
               "resolvedPath": str(resolved) if resolved else None,
               "verdict": None}
        if how == "missing":
            row["verdict"] = "MISSING"
        elif how == "ambiguous":
            row["verdict"] = "AMBIGUOUS"
        else:
            lines = resolved.read_text(encoding="utf-8", errors="replace").splitlines()
            if l1 < 1 or l2 < l1 or l1 > len(lines):
                row["verdict"] = "OUT_OF_RANGE"
            elif phrases:
                window = "\n".join(lines[max(0, l1 - 1 - WINDOW):l2 + WINDOW])
                missing = [p for p in phrases if p not in window]
                row["verdict"] = "DRIFT" if missing else "OK"
                if missing:
                    row["missingPhrases"] = [p[:80] for p in missing]
            else:
                row["verdict"] = "OK"
        results.append(row)
    return {"schema": "awx.brief-fact-check.v1", "file": str(path), "refs": results}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("briefs", nargs="+")
    ap.add_argument("--root", default=".")
    args = ap.parse_args()
    root = Path(args.root).resolve()
    docs = [check_file(f, root) for f in args.briefs]
    bad = {"DRIFT", "MISSING", "OUT_OF_RANGE", "AMBIGUOUS"}
    any_bad = any(r["verdict"] in bad or r.get("error") for d in docs for r in d["refs"]) \
        or any(d.get("error") for d in docs)
    print(json.dumps(docs, ensure_ascii=False, indent=2))
    print("\n| ref | verdict | resolved |", "|---|---|---|")
    for d in docs:
        for r in d["refs"]:
            print(f"| {r['ref']} | {r['verdict']} | {r['resolvedPath'] or '-'} |")
    return 2 if any(d.get("error") for d in docs) else (1 if any_bad else 0)


if __name__ == "__main__":
    sys.exit(main())
