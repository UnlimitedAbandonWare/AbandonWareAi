#!/usr/bin/env python3
"""git_guard_explain.py — turn awx.git-staged-scan.v1 findings (pathHash+rule
only) back into real paths, classify each finding, and draft allow-list
candidates. Reads blobs to locate matching LINE NUMBERS and markers, but never
prints secret values or any fragment of them.

Input: scan JSON file path or '-' for stdin.
Candidates: --staged | --diff A B | --all  (where real paths come from).
Classes: fake / placeholder / path-rule / binary / size / suspect / unmapped.
Output: console + <root>/var/codex-assist-git-guard-fast/explain-<utc>.md
(--out-dir overrides). --suggest-allow prints candidate JSON only;
--write-allow --reason "..." is the only path that writes configs.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import git_staged_guard as gsg  # noqa: E402  single rule source
import git_guard_fast as ggf  # noqa: E402  git helpers + allow policy

OUT_REL = "var/codex-assist-git-guard-fast"
PATH_RULE_NAMES = {"invalid-index-path", "private-or-generated-path",
                   "runtime-data-path", "credential-path",
                   "credential-or-database-path", "private-profile",
                   "symlink-or-submodule"}
FAKE_MARKERS = ("fake", "synthetic", "example", "xxxx", "0123", "1234", "abcdef")
PLACEHOLDER_MARKERS = ("__missing__", "${", "dummy", "changeme", "change-me")
PLACEHOLDER_ANGLE_RE = re.compile(rb"<[A-Za-z0-9_ -]{2,40}>")
MAX_LINES_PER_FINDING = 50


def build_candidates_staged(root):
    """Index candidates {path: oid}."""
    raw = ggf._git(root, "ls-files", "--stage", "-z")
    cand = {}
    for row in raw.split(b"\0"):
        if not row:
            continue
        header, path = row.split(b"\t", 1)
        parts = header.decode("ascii").split()
        if len(parts) >= 2 and parts[2] == "0":
            cand[path.decode("utf-8", errors="strict")] = parts[1]
    return cand


def build_candidates_diff(root, a, b):
    cand = {}
    for path, mode, oid in ggf._diff_raw(root, a, b):
        cand[path] = oid
    return cand


def _match_lines(blob, rules):
    """Line numbers where each content rule first matches. Values stay local."""
    lines = {}
    wanted = [r for r in rules if r not in PATH_RULE_NAMES
              and r not in ("blob-size-limit", "binary-scan-unavailable")]
    pats = {name: pat for name, pat in gsg.PATTERNS if name in wanted}
    if not pats and b"\0" not in blob:
        return lines
    for i, line in enumerate(blob.split(b"\n"), 1):
        for name, pat in pats.items():
            if re.search(pat, line):
                lines.setdefault(name, []).append(i)
        if sum(len(v) for v in lines.values()) >= MAX_LINES_PER_FINDING:
            break
    return lines


def _span_markers(blob, rules, line_no):
    """Marker check inside the matched span(s) on a given line."""
    try:
        line = blob.split(b"\n")[line_no - 1]
    except IndexError:
        return False, False
    pats = [pat for name, pat in gsg.PATTERNS if name in rules]
    spans = []
    for pat in pats:
        for m in re.finditer(pat, line):
            spans.append(m.group(0))
    joined = b" ".join(spans).lower() if spans else b""
    ph = any(m.encode() in joined for m in PLACEHOLDER_MARKERS) or bool(PLACEHOLDER_ANGLE_RE.search(joined))
    fk = any(m.encode() in joined for m in FAKE_MARKERS)
    return ph, fk


def classify(path, rule, blob=None, line_map=None):
    rules = rule.split(",")
    if path is None:
        return "unmapped"
    if set(rules) <= {"blob-size-limit"}:
        return "size"
    if set(rules) <= {"binary-scan-unavailable"}:
        return "binary"
    if set(rules) <= PATH_RULE_NAMES:
        return "path-rule"
    if ggf._allow_path_ok(path):
        return "fake"
    if blob is not None and line_map:
        for name, nums in line_map.items():
            for n in nums[:3]:
                ph, fk = _span_markers(blob, rules, n)
                if ph:
                    return "placeholder"
                if fk:
                    return "fake"
        low = blob.lower()
        if any(m.encode() in low for m in FAKE_MARKERS):
            return "fake"
        if any(m.encode() in low for m in PLACEHOLDER_MARKERS):
            return "placeholder"
    return "suspect"


def explain(scan, candidates, root):
    """scan = parsed scan JSON; candidates = {path: oid}. Returns report dict."""
    by_hash = {hashlib.sha256(p.encode()).hexdigest(): p for p in candidates}
    rows = []
    blobs_needed = {}
    for f in (scan.get("findings") or []) + [dict(a, allowed=True) for a in (scan.get("allowed") or [])]:
        path = by_hash.get(f["pathHash"])
        oid = candidates.get(path) if path else None
        rules = f["rule"].split(",")
        needs_blob = (path and oid and not set(rules) <= PATH_RULE_NAMES
                      and "blob-size-limit" not in rules)
        if needs_blob:
            blobs_needed[oid] = None
        rows.append({"path": path, "oid": oid, "rule": f["rule"],
                     "pathHash": f["pathHash"], "allowed": bool(f.get("allowed")),
                     "needsBlob": bool(needs_blob)})
    if blobs_needed:
        for oid, data in ggf._cat_batch(root, blobs_needed).items():
            blobs_needed[oid] = data[1]
    counts = {"fake": 0, "placeholder": 0, "path-rule": 0, "binary": 0,
              "size": 0, "suspect": 0, "unmapped": 0}
    for row in rows:
        blob = blobs_needed.get(row["oid"])
        line_map = _match_lines(blob, row["rule"].split(",")) if blob else {}
        row["lines"] = line_map
        row["class"] = classify(row["path"], row["rule"], blob, line_map)
        counts[row["class"]] += 1
        del row["needsBlob"]
    return {"schema": "awx.git-guard-explain.v1", "counts": counts,
            "scanOk": scan.get("ok"), "findings": rows}


def render_md(report):
    out = ["# git guard explain", "",
           f"scanOk={report.get('scanOk')} "
           + " ".join(f"{k}={v}" for k, v in sorted(report["counts"].items()) if v), "",
           "| path | rule | lines | class | allowed |", "|---|---|---|---|---|"]
    for f in report["findings"]:
        path = f["path"] or "(unmapped)"
        lines = ";".join(f"{k}:{','.join(str(n) for n in v[:8])}" for k, v in f.get("lines", {}).items())
        out.append(f"| {path} | {f['rule']} | {lines or '-'} | {f['class']} | {'yes' if f['allowed'] else ''} |")
    return "\n".join(out) + "\n"


def suggest_allow(report):
    cands = []
    for f in report["findings"]:
        if f["class"] == "fake" and f["oid"] and ggf._allow_path_ok(f["path"] or ""):
            cands.append({"path": f["path"], "rule": f["rule"], "oid": f["oid"],
                          "reason": ""})
    return cands


def write_allow(root, candidates, reason, allow_file=None):
    src = Path(allow_file) if allow_file else Path(root) / ggf.ALLOW_REL
    try:
        doc = json.loads(src.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        doc = {"entries": []}
    doc.setdefault("entries", [])
    have = {(e.get("path"), e.get("rule"), e.get("oid")) for e in doc["entries"]}
    added, skipped = 0, []
    for c in candidates:
        if set(c["rule"].split(",")) & set(ggf.KEY_RULES) and not ggf._allow_path_ok(c["path"]):
            skipped.append({"path": c["path"], "rule": c["rule"], "reason": "path-not-test-or-fixture"})
            continue
        key = (c["path"], c["rule"], c["oid"])
        if key in have:
            continue
        doc["entries"].append({"path": c["path"], "rule": c["rule"],
                               "oid": c["oid"], "reason": reason})
        have.add(key)
        added += 1
    src.parent.mkdir(parents=True, exist_ok=True)
    src.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return added, skipped


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("scan", help="scan JSON file or '-' for stdin")
    ap.add_argument("--root", default=".")
    scope = ap.add_mutually_exclusive_group()
    scope.add_argument("--staged", action="store_true")
    scope.add_argument("--diff", nargs=2, metavar=("A", "B"))
    scope.add_argument("--all", action="store_true")
    ap.add_argument("--suggest-allow", action="store_true")
    ap.add_argument("--write-allow", action="store_true")
    ap.add_argument("--reason", default="")
    ap.add_argument("--allow-file")
    ap.add_argument("--out-dir")
    args = ap.parse_args()
    ggf._prep_git_path()
    root = Path(args.root).resolve()
    try:
        scan = json.loads(sys.stdin.read() if args.scan == "-"
                          else Path(args.scan).read_text(encoding="utf-8"))
        if args.diff:
            candidates = build_candidates_diff(root, *args.diff)
        else:  # --staged and --all both map through the whole index
            candidates = build_candidates_staged(root)
        report = explain(scan, candidates, root)
    except (ggf.gsg.GuardFailure, OSError, ValueError) as failure:
        print(json.dumps({"schema": "awx.git-guard-explain.v1", "ok": False,
                          "reason": str(failure) if isinstance(failure, ggf.gsg.GuardFailure)
                                    else "explain-input-unreadable"}, ensure_ascii=True))
        return 2
    md = render_md(report)
    sys.stdout.write(md)
    out_dir = Path(args.out_dir) if args.out_dir else root / OUT_REL
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_file = out_dir / f"explain-{stamp}.md"
    out_file.write_text(md, encoding="utf-8")
    print(f"\n[explain] wrote {out_file}", flush=True)
    if args.suggest_allow or args.write_allow:
        cands = suggest_allow(report)
        if args.write_allow:
            if not args.reason.strip():
                print("[explain] --write-allow refused: --reason is required", file=sys.stderr)
                return 2
            added, skipped = write_allow(root, cands, args.reason.strip(), args.allow_file)
            print(json.dumps({"allowWrite": {"added": added, "skipped": skipped}},
                             ensure_ascii=True))
        else:
            print(json.dumps({"suggestAllow": cands}, ensure_ascii=True))
    print(json.dumps({"counts": report["counts"]}, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
