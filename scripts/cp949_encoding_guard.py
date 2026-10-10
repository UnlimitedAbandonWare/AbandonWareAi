"""Detect and repair characters that crash Python CLIs on cp949 consoles.

Windows consoles in the Korean locale default to cp949. When a script's
docstring, argparse help text, or print output carries characters cp949
cannot encode (em-dash U+2014 is the most common), the interpreter dies with
UnicodeEncodeError before real work starts.

Commands:
  scan   report non-cp949 characters in scripts/*.py (JSON or --summary text)
  fix    replace unsafe characters with ASCII (supports --dry-run)
"""
import argparse
import bisect
import io
import json
import sys
import tokenize
from pathlib import Path

DEFAULT_REPLACEMENTS = {
    "\u2014": "--",   # em dash
    "\u2013": "-",    # en dash
    "\u2026": "...",  # horizontal ellipsis
    "\u2018": "'",    # left single quotation mark
    "\u2019": "'",    # right single quotation mark
    "\u201c": '"',    # left double quotation mark
    "\u201d": '"',    # right double quotation mark
}

MAX_FINDINGS_PER_FILE = 20


def ensure_utf8_streams():
    """Reconfigure console streams so this tool never dies on cp949."""
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except Exception:
                pass


def non_cp949_positions(text):
    """Yield (offset, char) for every character cp949 cannot encode."""
    for offset, char in enumerate(text):
        try:
            char.encode("cp949")
        except UnicodeEncodeError:
            yield offset, char


def _line_starts(text):
    starts = [0]
    for index, char in enumerate(text):
        if char == "\n":
            starts.append(index + 1)
    return starts


def _to_offset(starts, pos):
    row, col = pos
    return starts[row - 1] + col if 0 < row <= len(starts) else -1


def _string_comment_spans(text, starts):
    """(start, end, kind) spans for STRING and COMMENT tokens."""
    spans = []
    try:
        for token in tokenize.generate_tokens(io.StringIO(text).readline):
            if token.type == tokenize.STRING:
                spans.append((_to_offset(starts, token.start),
                              _to_offset(starts, token.end), "string"))
            elif token.type == tokenize.COMMENT:
                spans.append((_to_offset(starts, token.start),
                              _to_offset(starts, token.end), "comment"))
    except (tokenize.TokenError, SyntaxError, IndentationError, ValueError):
        pass
    return spans


def scan_file(path, max_findings=MAX_FINDINGS_PER_FILE):
    """Scan one file for non-cp949 characters, classified by token context."""
    text = path.read_text(encoding="utf-8")
    starts = _line_starts(text)
    spans = _string_comment_spans(text, starts)
    findings, kinds, codepoints = [], {"string": 0, "comment": 0, "code": 0}, {}
    total = 0
    for offset, char in non_cp949_positions(text):
        total += 1
        kind = "code"
        for begin, end, span_kind in spans:
            if begin <= offset < end:
                kind = span_kind
                break
        kinds[kind] += 1
        codepoints["U+%04X" % ord(char)] = codepoints.get("U+%04X" % ord(char), 0) + 1
        if len(findings) < max_findings:
            findings.append({"line": bisect.bisect_right(starts, offset),
                             "offset": offset, "kind": kind,
                             "codepoint": "U+%04X" % ord(char),
                             "char": char})
    return {"path": str(path), "nonCp949Count": total,
            "uniqueCodepoints": codepoints, "kinds": kinds,
            "truncated": total > len(findings), "findings": findings}


def scan_dir(directory, pattern="*.py"):
    """Scan every file matching pattern directly inside directory."""
    directory = Path(directory)
    files = sorted(p for p in directory.glob(pattern) if p.is_file())
    results = [scan_file(path) for path in files]
    flagged = [row for row in results if row["nonCp949Count"] > 0]
    return {"schema": "awx.cp949-encoding-guard.v1", "action": "scan",
            "dir": str(directory), "glob": pattern,
            "filesScanned": len(results), "filesFlagged": len(flagged),
            "totalFindings": sum(row["nonCp949Count"] for row in results),
            "files": flagged}


def fix_file(path, replacements=None, dry_run=False):
    """Replace unsafe characters with ASCII. Returns a bounded report."""
    replacements = DEFAULT_REPLACEMENTS if replacements is None else replacements
    path = Path(path)
    text = path.read_text(encoding="utf-8")
    counts = {bad: text.count(bad) for bad in replacements if text.count(bad)}
    new_text = text
    for bad, good in replacements.items():
        new_text = new_text.replace(bad, good)
    changed = new_text != text
    if changed and not dry_run:
        path.write_text(new_text, encoding="utf-8")
    remaining = sum(1 for _ in non_cp949_positions(new_text))
    return {"path": str(path), "dryRun": dry_run, "changed": changed,
            "replacements": counts,
            "replacedTotal": sum(counts.values()),
            "remainingNonCp949": remaining}


def _print_summary(report):
    print("scanned=%d flagged=%d findings=%d dir=%s" %
          (report["filesScanned"], report["filesFlagged"],
           report["totalFindings"], report["dir"]))
    for row in report["files"]:
        cps = ", ".join("%s x%d" % (cp, n) for cp, n in
                        sorted(row["uniqueCodepoints"].items()))
        print("FLAG %s: %d char(s) [%s] string=%d comment=%d code=%d" %
              (row["path"], row["nonCp949Count"], cps,
               row["kinds"]["string"], row["kinds"]["comment"],
               row["kinds"]["code"]))


def main(argv=None):
    ensure_utf8_streams()
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    scan_p = sub.add_parser("scan", help="report non-cp949 characters")
    scan_p.add_argument("--dir", default=str(Path(__file__).resolve().parent),
                        help="directory to scan (default: this scripts dir)")
    scan_p.add_argument("--glob", default="*.py", help="file glob (default *.py)")
    scan_p.add_argument("--summary", action="store_true",
                        help="print a compact text summary instead of JSON")
    scan_p.add_argument("--strict", action="store_true",
                        help="exit 1 when any findings exist")
    fix_p = sub.add_parser("fix", help="replace unsafe characters with ASCII")
    fix_p.add_argument("files", nargs="+", help="files to repair")
    fix_p.add_argument("--dry-run", action="store_true",
                       help="report replacements without writing")
    args = parser.parse_args(argv)

    if args.action == "scan":
        report = scan_dir(args.dir, args.glob)
        if args.summary:
            _print_summary(report)
        else:
            print(json.dumps(report, indent=2, ensure_ascii=False))
        return 1 if args.strict and report["totalFindings"] else 0

    results = [fix_file(name, dry_run=args.dry_run) for name in args.files]
    print(json.dumps({"schema": "awx.cp949-encoding-guard.v1", "action": "fix",
                      "dryRun": args.dry_run, "results": results},
                     indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
