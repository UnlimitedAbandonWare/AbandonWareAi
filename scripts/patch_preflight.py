#!/usr/bin/env python3
"""apply_patch preflight (read-only) — diagnose before a doomed apply.

Checks a unified-diff patch file (or paste file) for the three failure
modes seen in session logs:

  P1 path issues   : doubled roots (src\\src\\test\\...), missing files,
                     a/ b/ prefixes that do not resolve under --root
  P2 stale context : hunk context/'-' lines not found verbatim in the
                     current file (the #1 "Failed to find expected lines")
  P3 header format : malformed @@ -a,b +c,d @@ hunks

For every bad hunk it prints the line range to RE-READ so the next patch
is built from current text — not a blind retry of the same patch.

    python -B scripts/patch_preflight.py <patch-file> [--root .] [--max-hints 10]

Exit 0 = patch looks applicable. Exit 2 = problems found (or usage error).
"""
import argparse
import json
import re
import sys
from pathlib import Path

HUNK_RE = re.compile(r"^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@")
PATH_RE = re.compile(r"^(?:---|\+\+\+)\s+(?:[ab]/)?([^\t ]+)")


def norm_path(raw):
    p = raw.strip().strip('"')
    for pref in ("a/", "b/"):
        if p.startswith(pref):
            p = p[2:]
    return p.replace("\\", "/")


def doubled_root(path):
    parts = [x for x in path.split("/") if x]
    return len(parts) >= 2 and parts[0] == parts[1]


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("patch")
    ap.add_argument("--root", default=".")
    ap.add_argument("--max-hints", type=int, default=10)
    args = ap.parse_args()
    root = Path(args.root)

    pfile = Path(args.patch)
    if not pfile.is_file():
        print(json.dumps({"status": "error", "reason": "patch file not found",
                          "patch": args.patch}))
        return 2
    lines = pfile.read_text(encoding="utf-8", errors="replace").splitlines()

    problems, hints = [], []
    cur_file, cur_hunk, hunks = None, None, 0
    file_cache = {}

    def file_lines(rel):
        if rel not in file_cache:
            p = root / rel
            try:
                file_cache[rel] = p.read_text(encoding="utf-8", errors="replace").splitlines() \
                    if p.is_file() else None
            except OSError:
                file_cache[rel] = None
        return file_cache[rel]

    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("--- "):
            i += 1
            continue
        if line.startswith("+++ "):
            cur_file = norm_path(line[4:])
            real = cur_file
            if doubled_root(real):
                # try dropping the doubled first segment once
                parts = real.split("/")
                alt = "/".join(parts[1:])
                if (root / alt).is_file():
                    hints.append({"file": real, "kind": "doubled-root",
                                  "detail": "resolves as %s — rewrite path" % alt})
                    real = alt
                else:
                    problems.append({"file": real, "kind": "doubled-root-unresolved"})
            elif not (root / real).is_file() and not real.startswith("__"):
                # new files are fine only when the patch has '--- /dev/null'
                prev = lines[i - 1] if i else ""
                if "/dev/null" not in prev:
                    problems.append({"file": real, "kind": "target-missing",
                                     "detail": "file does not exist under --root"})
            cur_file = real
            i += 1
            continue
        m = HUNK_RE.match(line)
        if m:
            hunks += 1
            cur_hunk = {"file": cur_file, "header": line,
                        "old_start": int(m.group(1)), "ctx": []}
            i += 1
            while i < len(lines) and not HUNK_RE.match(lines[i]) \
                    and not lines[i].startswith(("--- ", "+++ ", "diff --git")):
                if lines[i].startswith((" ", "-")) and cur_hunk is not None:
                    cur_hunk["ctx"].append(lines[i][1:])
                elif lines[i] and not lines[i].startswith(("+", "\\", " ")):
                    problems.append({"file": cur_file, "kind": "bad-hunk-line",
                                     "detail": lines[i][:80]})
                i += 1
            # validate context against live file
            if cur_hunk and cur_file:
                live = file_lines(cur_file)
                if live is None:
                    pass  # new file — nothing to verify
                elif cur_hunk["ctx"]:
                    hay = "\n".join(live)
                    needle = "\n".join(cur_hunk["ctx"])
                    if needle not in hay:
                        first = cur_hunk["ctx"][0][:60]
                        approx = next((n + 1 for n, l in enumerate(live)
                                       if first[:20] in l), None)
                        lo = max(1, (approx or cur_hunk["old_start"]) - 6)
                        hi = (approx or cur_hunk["old_start"]) + len(cur_hunk["ctx"]) + 6
                        problems.append({"file": cur_file, "kind": "stale-context",
                                         "hunk": cur_hunk["header"],
                                         "reread_lines": "%d-%d" % (lo, hi)})
                        hints.append({"file": cur_file,
                                      "re-read": "%s lines %d-%d" % (cur_file, lo, hi)})
            cur_hunk = None
            continue
        if line.startswith("@@") and not HUNK_RE.match(line):
            problems.append({"file": cur_file, "kind": "malformed-hunk-header",
                             "detail": line[:80]})
        i += 1

    report = {"status": "ok" if not problems else "problems",
              "patch": str(args.patch), "hunks": hunks,
              "problem_count": len(problems), "problems": problems[:args.max_hints],
              "hints": hints[:args.max_hints]}
    print(json.dumps(report, ensure_ascii=False))
    return 0 if not problems else 2


if __name__ == "__main__":
    sys.exit(main())
