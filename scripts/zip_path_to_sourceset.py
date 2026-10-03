#!/usr/bin/env python3
"""zip_path_to_sourceset — translate ZIP-path `file:line` anchors to the live tree.

Directive anchors cite ZIP-relative paths like `main/java/com/.../X.java` with
`symbol(), start~end` ranges. ZIP line numbers drift; this tool re-locates the
named symbol in the live file and reports the live line + drift instead of
trusting stale numbers. Missing anchors are ANCHOR_MISSING — never guessed.

Usage:
    python -B scripts/zip_path_to_sourceset.py --root . \
        --directive agent-prompts/devin-hotfix-r2-toolkit-20260930/CODEX_DEVIN_RUNTIME_HOTFIX_R2_20260930.md \
        [--directive <another.md>] [--zip %USERPROFILE%/Downloads/mfwasainx.zip] \
        [--out var/anchor_map.json]

Output: JSON (schema awx.zip-path-to-sourceset.v1) + a markdown table.
Source root SSOT: scripts/test_tree_contamination_report.py
(ACTIVE_MAIN_ROOTS / TEST_ROOT_CANDIDATES).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import zipfile
from pathlib import Path

SCHEMA = "awx.zip-path-to-sourceset.v1"

ACTIVE_MAIN_ROOTS = ("main/java", "app/src/main/java_clean")
ACTIVE_RES_ROOTS = ("main/resources", "app/src/main/resources")
TEST_ROOT_BY_MODULE = {":": "src/test/java", ":app": "app/src/test/java"}
TEST_TASK_BY_MODULE = {":": "test", ":app": ":app:test"}

# `main/` path anchors; a preceding `/` or `.` means it is a URL path segment.
ZIP_PATH_RE = re.compile(r"(?<![:/\w.-])(main/[A-Za-z0-9_./-]+\.(?:java|yml|yaml|xml|properties|js|html))")
PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;", re.M)
# Inherited-path anchor rows must still look like `symbol(...): N~M` — bare
# Korean prose like `clamp한다(174~177)` must not bind to the previous file.
ANCHORISH_RE = re.compile(
    r"\b([A-Za-z_$][A-Za-z0-9_$]*)\s*\([^)]*\)\s*[:,]?\s*(\d+)\s*~\s*(\d+)")


def parse_anchors(text: str) -> list[dict]:
    """Extract (zipPath, symbol, start~end) anchors; a bare symbol+range binds to
    the most recently seen zip path (Jev-style `exchange(...): 59~128`)."""
    anchors: list[dict] = []
    current_path = None
    for lineno, line in enumerate(text.splitlines(), start=1):
        path_match = ZIP_PATH_RE.search(line)
        if path_match:
            current_path = path_match.group(1)
        anchorish = list(ANCHORISH_RE.finditer(line))
        if anchorish:
            target = path_match.group(1) if path_match else current_path
            if target is None:
                continue
            for m in anchorish:
                anchors.append({
                    "mdLine": lineno, "zipPath": target,
                    "symbol": m.group(1),
                    "zipStart": int(m.group(2)),
                    "zipEnd": int(m.group(3)),
                })
        elif path_match:
            anchors.append({"mdLine": lineno, "zipPath": path_match.group(1),
                            "symbol": None, "zipStart": None, "zipEnd": None})
    seen = set()
    unique = []
    for a in anchors:
        key = (a["zipPath"], a["symbol"], a["zipStart"], a["zipEnd"])
        if key not in seen:
            seen.add(key)
            unique.append(a)
    return unique


CONTROL_PREFIXES = (
    "if", "else", "while", "for", "return", "throw", "assert", "switch",
    "case", "new", "yield", "do", "try", "catch", "finally", "super", "this",
)


def find_symbol_lines(text: str, symbol: str) -> dict:
    """Declaration-ish lines first. A line is a decl candidate only when the
    symbol's `(` is the first `(` on the stripped line (rules out
    `if (symbol(`/`x = f(symbol(`), it is not a `x.symbol(` qualified call,
    and it is not a `;`-terminated statement or comment/control line."""
    call_re = re.compile(r"\b" + re.escape(symbol) + r"\s*\(")
    qualified = re.compile(r"[\w$]\.\s*" + re.escape(symbol) + r"\s*\(")
    lines = text.splitlines()
    decl_lines, all_lines = [], []
    for i, line in enumerate(lines, start=1):
        m = call_re.search(line)
        if not m:
            continue
        all_lines.append(i)
        s = line.strip()
        sm = call_re.search(s)
        if sm is None or sm.end() - 1 != s.find("("):
            continue
        head = s.split("(", 1)[0].strip()
        if head.startswith(("//", "*", "import ", ".")):
            continue
        if head.split(" ", 1)[0].rstrip("=<>!&|") in CONTROL_PREFIXES:
            continue
        if qualified.search(head):
            continue
        if s.endswith(";") and not re.search(
                r"\b(public|private|protected|abstract|static|final|synchronized|native)\b", head):
            continue
        decl_lines.append(i)
    return {"declaration": decl_lines, "all": all_lines}


def module_of(rel_path: str) -> str:
    return ":app" if rel_path.startswith("app/") else ":"


def test_task_for(module: str, fqcn: str | None) -> str | None:
    task = TEST_TASK_BY_MODULE.get(module)
    if task is None or not fqcn:
        return task
    return f"{task} --tests '{fqcn}'"


def resolve_anchor(root: Path, anchor: dict, zip_names: set[str] | None,
                   zip_handle: zipfile.ZipFile | None) -> dict:
    zip_path = anchor["zipPath"]
    result: dict = {
        "directive": anchor,
        "zipMemberFound": None,
        "zipSymbolLine": None,
        "live": {"path": zip_path, "exists": False},
        "module": None, "testTask": None, "testSourceRoot": None,
        "fqcn": None, "status": None, "driftFromZipStart": None,
    }
    if zip_names is not None:
        result["zipMemberFound"] = zip_path in zip_names

    live = root / zip_path
    if not live.is_file():
        # Try the other active roots before declaring missing.
        alt = None
        if zip_path.startswith("main/"):
            for cand_root in (*ACTIVE_MAIN_ROOTS, *ACTIVE_RES_ROOTS):
                cand = root / cand_root / zip_path.split("/", 2)[-1]
                if cand.is_file():
                    alt = cand
                    break
        if alt is None:
            result["status"] = "ANCHOR_MISSING"
            if zip_names is not None and not result["zipMemberFound"]:
                result["status"] = "ANCHOR_MISSING+ZIP_MEMBER_MISSING"
            return result
        live = alt
        result["live"]["path"] = str(alt.relative_to(root)).replace("\\", "/")
    result["live"]["exists"] = True
    result["module"] = module_of(result["live"]["path"])
    result["testSourceRoot"] = TEST_ROOT_BY_MODULE.get(result["module"])

    text = live.read_text(encoding="utf-8", errors="replace")
    if zip_path.endswith(".java"):
        pkg = PACKAGE_RE.search(text)
        stem = Path(zip_path).stem
        result["fqcn"] = f"{pkg.group(1)}.{stem}" if pkg else stem
        result["testTask"] = test_task_for(result["module"], result["fqcn"])

    symbol = anchor.get("symbol")
    if symbol:
        lines = find_symbol_lines(text, symbol)
        live_line = lines["declaration"][0] if lines["declaration"] else (lines["all"][0] if lines["all"] else None)
        result["live"]["symbol"] = symbol
        result["live"]["declLines"] = lines["declaration"][:10]
        result["live"]["allLines"] = lines["all"][:20]
        result["live"]["line"] = live_line
        if live_line is None:
            result["status"] = "SYMBOL_NOT_FOUND"
        else:
            result["status"] = "OK"
            if anchor.get("zipStart") is not None:
                result["driftFromZipStart"] = live_line - anchor["zipStart"]
    else:
        result["status"] = "OK_FILE_ONLY"

    if zip_handle is not None and result.get("zipMemberFound") and symbol:
        try:
            ztext = zip_handle.read(zip_path).decode("utf-8", errors="replace")
            zlines = find_symbol_lines(ztext, symbol)
            result["zipSymbolLine"] = (zlines["declaration"] or zlines["all"] or [None])[0]
        except KeyError:
            result["zipMemberFound"] = False
    return result


def translate(root: Path, directive_paths: list[Path], zip_path: Path | None = None) -> dict:
    zip_names, zip_handle = None, None
    if zip_path is not None and zip_path.is_file():
        zip_handle = zipfile.ZipFile(zip_path)
        zip_names = set(zip_handle.namelist())
    anchors: list[dict] = []
    for dp in directive_paths:
        text = dp.read_text(encoding="utf-8", errors="replace")
        for a in parse_anchors(text):
            a["directiveFile"] = dp.name
            anchors.append(a)
    resolved = [resolve_anchor(root, a, zip_names, zip_handle) for a in anchors]
    if zip_handle is not None:
        zip_handle.close()
    counts = {}
    for r in resolved:
        counts[r["status"]] = counts.get(r["status"], 0) + 1
    return {
        "schemaVersion": SCHEMA,
        "root": str(root),
        "zip": str(zip_path) if zip_path else None,
        "anchorCount": len(resolved),
        "statusCounts": counts,
        "anchors": resolved,
    }


def to_table(report: dict) -> str:
    rows = ["| md | zipPath | symbol | zipRange | live | liveLine | drift | module | testTask | status |",
            "|---|---|---|---|---|---|---|---|---|---|"]
    for a in report["anchors"]:
        d = a["directive"]
        rng = f"{d['zipStart']}~{d['zipEnd']}" if d.get("zipStart") is not None else "-"
        live_line = a["live"].get("line", "-")
        drift = a.get("driftFromZipStart")
        rows.append(
            f"| {d['directiveFile']}:{d['mdLine']} | {d['zipPath']} | {d.get('symbol') or '-'} | {rng} | "
            f"{a['live']['path']} | {live_line} | {drift if drift is not None else '-'} | "
            f"{a.get('module') or '-'} | {a.get('testTask') or '-'} | {a['status']} |"
        )
    return "\n".join(rows)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--directive", action="append", required=True,
                    help="directive .md to scan; repeatable")
    ap.add_argument("--zip", default=None, help="optional source ZIP for member checks")
    ap.add_argument("--out", default=None)
    args = ap.parse_args(argv)

    root = Path(os.path.abspath(args.root))
    directives = [Path(d) for d in args.directive]
    report = translate(root, directives, Path(args.zip) if args.zip else None)
    text = json.dumps(report, ensure_ascii=True, indent=1)
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(text + "\n", encoding="utf-8")
        table_path = Path(str(args.out)).with_suffix(".md")
        table_path.write_text(to_table(report) + "\n", encoding="utf-8")
    print(to_table(report))
    print(json.dumps({k: v for k, v in report.items() if k != "anchors"}, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
