#!/usr/bin/env python3
"""source_nul_scan.py - scan text sources for NUL and C0 control bytes.

Finds byte 0x00 and other C0 control characters (0x01-0x08, 0x0B, 0x0C,
0x0E-0x1F) in files under the scanned roots. TAB/LF/CR are allowed.
Binary extensions are excluded. Output is path:line:col:codepoint only -
file content is never printed.

Usage:
    python -B scripts/source_nul_scan.py [--root .] [--json] [--out P]
        [--dir DIR ...] [--max-file-bytes N]

Exit 0 = clean, 4 = findings, 2 = usage/io error.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SCHEMA = "awx.source-nul-scan.v1"

DEFAULT_DIRS = ("main/java", "main/resources", "src/test/java", "scripts")

# Binary/container/key-material extensions skipped entirely.
BINARY_EXTS = {
    ".jar", ".war", ".zip", ".7z", ".gz", ".tgz", ".tar", ".rar", ".bz2",
    ".xz", ".class", ".dll", ".exe", ".so", ".dylib", ".bin", ".dat",
    ".db", ".mv.db", ".trace.db", ".h2", ".pack", ".idx", ".obj", ".o",
    ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".webp", ".tif",
    ".tiff", ".svgz", ".onnx", ".pt", ".pth", ".gguf", ".safetensors",
    ".woff", ".woff2", ".ttf", ".otf", ".eot", ".pdf", ".mp3", ".mp4",
    ".wav", ".ogg", ".webm", ".avi", ".mov", ".mkv",
    ".keystore", ".jks", ".p12", ".pfx", ".key", ".pem", ".der", ".cer",
    ".parquet", ".avro", ".pyc", ".pyo", ".min.js.map",
}

# C0 minus allowed TAB(09) LF(0A) CR(0D)
BAD_BYTES = re.compile(rb"[\x00-\x08\x0b\x0c\x0e-\x1f]")

MAX_FINDINGS_PER_FILE = 64
DEFAULT_MAX_FILE_BYTES = 32 * 1024 * 1024


def _line_col(data: bytes, idx: int) -> tuple[int, int]:
    line = data.count(b"\n", 0, idx) + 1
    last_nl = data.rfind(b"\n", 0, idx)
    col = idx - last_nl
    return line, col


def scan_file(path: Path, rel: str, max_per_file: int = MAX_FINDINGS_PER_FILE
              ) -> list[dict]:
    data = path.read_bytes()
    findings = []
    for m in BAD_BYTES.finditer(data):
        line, col = _line_col(data, m.start())
        findings.append({"path": rel, "line": line, "col": col,
                         "codepoint": f"U+{m.group()[0]:04X}"})
        if len(findings) >= max_per_file:
            break
    return findings


def scan_root(root: Path, dirs: list[str] | tuple[str, ...],
              max_file_bytes: int = DEFAULT_MAX_FILE_BYTES) -> dict:
    findings: list[dict] = []
    scanned = 0
    skipped_ext = 0
    skipped_large = 0
    unreadable = 0
    for d in dirs:
        base = root / d
        if not base.is_dir():
            continue
        for fp in sorted(base.rglob("*")):
            if not fp.is_file():
                continue
            ext = fp.suffix.lower()
            if ext in BINARY_EXTS or any(
                    fp.name.lower().endswith(e) for e in BINARY_EXTS
                    if "." in e and len(e) > 4):
                skipped_ext += 1
                continue
            try:
                if fp.stat().st_size > max_file_bytes:
                    skipped_large += 1
                    continue
                rel = fp.relative_to(root).as_posix()
                findings.extend(scan_file(fp, rel))
                scanned += 1
            except OSError:
                unreadable += 1
    verdict = "CLEAN" if not findings else "FOUND"
    return {"schemaVersion": SCHEMA, "root": str(root), "verdict": verdict,
            "filesScanned": scanned, "skippedByExtension": skipped_ext,
            "skippedLarge": skipped_large, "unreadable": unreadable,
            "findingCount": len(findings), "findings": findings}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--out", help="write JSON result to this path")
    ap.add_argument("--dir", dest="dirs", action="append",
                    help="scan dir relative to root (repeatable; default "
                         + ",".join(DEFAULT_DIRS) + ")")
    ap.add_argument("--max-file-bytes", type=int,
                    default=DEFAULT_MAX_FILE_BYTES)
    args = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    root = Path(args.root).resolve()
    if not root.is_dir():
        print(f"INPUT_ERROR root-not-dir:{args.root}", file=sys.stderr)
        return 2
    dirs = args.dirs if args.dirs else list(DEFAULT_DIRS)
    result = scan_root(root, dirs, args.max_file_bytes)
    if args.out:
        try:
            out = Path(args.out)
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(json.dumps(result, ensure_ascii=False, indent=2)
                           + "\n", encoding="utf-8")
        except OSError as exc:
            print(f"OUTPUT_UNWRITABLE {type(exc).__name__}", file=sys.stderr)
            return 2
    if args.json:
        print(json.dumps(result, ensure_ascii=False))
    else:
        print(f"{result['verdict']} scanned={result['filesScanned']} "
              f"skippedExt={result['skippedByExtension']} "
              f"skippedLarge={result['skippedLarge']} "
              f"unreadable={result['unreadable']} "
              f"findings={result['findingCount']}")
        for f in result["findings"][:200]:
            print(f"  {f['path']}:{f['line']}:{f['col']}:{f['codepoint']}")
        if result["findingCount"] > 200:
            print(f"  ... {result['findingCount'] - 200} more")
    return 0 if not result["findings"] else 4


if __name__ == "__main__":
    raise SystemExit(main())
