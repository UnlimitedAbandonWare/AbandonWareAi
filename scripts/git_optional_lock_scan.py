#!/usr/bin/env python3
"""Find git status/diff read calls that can take the optional index lock.

git status / git diff take .git/index.lock "optionally" to refresh cached stat
info. On this checkout many agent sessions run read git calls concurrently, so
the optional lock collides with the user's GitHub Desktop commits. The fix is
`git --no-optional-locks status|diff` (or GIT_OPTIONAL_LOCKS=0 in the child
environment) which never affects the required locks of real write commands.

This scanner is read-only: it never runs git and never writes. Stdlib only.

Scans: scripts/, __patch_drop__/*.ps1 (recursive, minus applied/**),
.codex/hooks/**, .agents/skills/**/scripts/**.

Skips: test files (test_*.py, *_test.py, *.Tests.ps1, *_tests.ps1),
__patch_drop__/applied/**, producer-kit/**, __pycache__.

Finding kinds:
  direct        a git exe (literal or *git*-named exe var) reaches
                status/diff without the flag
  via-helper    a local helper that launches git without the flag carries a
                status/diff call (fix the helper once)
  indirect      call goes through an injected/unknown callable -- reported for
                visibility, not counted as unprotected

Usage:
  python -B scripts/git_optional_lock_scan.py            # report, exit 0
  python -B scripts/git_optional_lock_scan.py --baseline before.json
      # exit 1 only when NEW unprotected findings appear vs the baseline file
"""
from __future__ import annotations

import argparse
import ast
import io
import json
import re
import sys
import tokenize
from pathlib import Path

SCHEMA = "awx.git-optional-lock-scan.v1"
FLAG = "--no-optional-locks"
ENV_NAME = "GIT_OPTIONAL_LOCKS"
READ_SUBCMDS = frozenset({"status", "diff"})

SCAN_DIRS = ("scripts", "__patch_drop__", ".codex/hooks", ".agents/skills")
EXCLUDE_PARTS = {"applied", "producer-kit", "__pycache__", ".git"}
TEST_RE = re.compile(r"^(test_.*\.py|.*_test\.py|.*[._-]tests?\.ps1)$", re.I)
PY_EXT = ".py"
GEN_EXT = {".ps1", ".psm1", ".bat", ".cmd", ".js", ".mjs"}

EXE_VARS = {"git", "git_exe", "git_bin", "gitexe", "git_bin_path", "GIT_EXE"}
LAUNCH_LAST = {"run", "popen", "check_output", "check_call", "call", "system",
               "exec", "execsync", "exec_sync", "spawn", "spawn_sync"}

DEF_RE = re.compile(r"^(?P<indent>[ \t]*)def\s+(?P<name>[A-Za-z_]\w*)\s*\(", re.M)


GIT_NAME_RE = re.compile(r"(?:^|_)git", re.I)


def _is_gitish(name: str) -> bool:
    return "git" in name.lower()


def _is_git_name(name: str) -> bool:
    # 'git', 'git_exe', 'run_git' -> True; 'legit', 'digest' -> False
    return bool(GIT_NAME_RE.search(name))


def _helper_defs(text: str) -> dict:
    """name -> (def_line, body) using indent boundaries (module approx)."""
    matches = list(DEF_RE.finditer(text))
    out = {}
    lines = text.splitlines()
    for i, m in enumerate(matches):
        name = m.group("name")
        def_line = text[: m.start()].count("\n") + 1
        indent = len(m.group("indent").replace("\t", "    "))
        end = len(text)
        for m2 in matches[i + 1:]:
            ind2 = len(m2.group("indent").replace("\t", "    "))
            if ind2 <= indent:
                end = m2.start()
                break
        # body starts after the def line (signatures may span lines);
        # find the first newline after the match start
        nl = text.find("\n", m.start())
        body_start = nl + 1 if nl != -1 else end
        # ...and ends at the first non-blank line dedented to the def's
        # indent — module-level statements between two defs are not part
        # of the earlier function's body
        pos = body_start
        while pos < end:
            eol = text.find("\n", pos)
            line = text[pos:eol if eol != -1 else end]
            stripped = line.strip()
            if stripped and not stripped.startswith(("#", "@", '"""', "'''", 'r"""', "r'''")):
                cur = len(line) - len(line.lstrip())
                cur = len(line[:cur].replace("\t", "    "))
                if cur <= indent:
                    end = pos
                    break
            pos = (eol + 1) if eol != -1 else end
        out[name] = (def_line, text[body_start:end], len(lines))
    return out


def _safe_eval(s):
    try:
        return ast.literal_eval(s)
    except (ValueError, SyntaxError):
        return None


def _stmts_of(text: str) -> list:
    """Group tokens into logical statements (NEWLINE ends one; NL does not)."""
    try:
        toks = list(tokenize.generate_tokens(io.StringIO(text).readline))
    except (tokenize.TokenError, SyntaxError, IndentationError, ValueError):
        return []
    stmts, cur = [], []
    for t in toks:
        if t.type in (tokenize.ENCODING, tokenize.ENDMARKER, tokenize.COMMENT,
                      tokenize.INDENT, tokenize.DEDENT, tokenize.NL):
            continue
        if t.type == tokenize.NEWLINE:
            if cur:
                stmts.append(cur)
                cur = []
            continue
        cur.append((t.type, t.string, t.start[0]))
    if cur:
        stmts.append(cur)
    return stmts


def _basename_is_git(s) -> bool:
    if not isinstance(s, str):
        return False
    base = s.replace("\\", "/").rsplit("/", 1)[-1].lower()
    return base in {"git", "git.exe"}


def _collect_sub_vars(stmts: list) -> set:
    """Names assigned a value that contains a status/diff literal.

    `args = ["diff", "--cached"]` followed later by `git(args)` is still a
    read call; track the variable so the call site can be classified.
    """
    out = set()
    for stmt in stmts:
        has_sub = False
        for tt2, s2, _r2 in stmt:
            if tt2 == tokenize.STRING:
                try:
                    if ast.literal_eval(s2) in READ_SUBCMDS:
                        has_sub = True
                        break
                except (ValueError, SyntaxError):
                    pass
        if not has_sub:
            continue
        names = [s for tt, s, _r in stmt if tt == tokenize.NAME]
        # NAME = [<expr with subcommand>]  -- only when the RHS is a
        # sequence literal (args = ["diff", ...]); `proc = git(...)` must
        # not mark `proc`, that would corrupt its own sub_idx.
        if "=" in (s for _t, s, _r in stmt):
            eq = next(i for i, (_t, s, _r) in enumerate(stmt) if s == "=")
            rhs_first = next((s for _t, s, _r in stmt[eq + 1:]
                              if _t != tokenize.NL), "")
            if rhs_first in ("[", "("):
                for tt, s, _r in stmt[:eq]:
                    if tt == tokenize.NAME:
                        out.add(s)
        # for NAME, NAME in <expr with subcommand>
        if names and names[0] == "for" and "in" in names:
            in_pos = next(i for i, (_t, s, _r) in enumerate(stmt) if s == "in")
            for tt, s, _r in stmt[1:in_pos]:
                if tt == tokenize.NAME:
                    out.add(s)
    return out


def scan_python(path: Path, text: str) -> list[dict]:
    helpers = _helper_defs(text)
    safe_helpers = {n for n, (_, body, _) in helpers.items()
                    if FLAG in body or ENV_NAME in body}
    # transitive: a helper that delegates to a safe helper is covered too
    for _ in range(2):
        for n, (_, body, _) in helpers.items():
            if n in safe_helpers:
                continue
            if FLAG in body or ENV_NAME in body:
                safe_helpers.add(n)
                continue
            for s in safe_helpers:
                if re.search(rf"\b{re.escape(s)}\s*\(", body):
                    safe_helpers.add(n)
                    break

    # a helper counts as a git launcher only when its body actually reaches
    # a git exe: a bare "git"/"git.exe" literal, an exe var name, or a
    # module-level constant holding the exe path (GIT_CANDIDATES = ["git",...])
    module_consts = set()
    for stmt in _stmts_of(text):
        strs = [s for tt, s, _r in stmt if tt == tokenize.STRING]
        if not any(_basename_is_git(ast.literal_eval(s))
                   for s in strs if _safe_eval(s) is not None):
            continue
        if "=" in (s for _t, s, _r in stmt):
            eq = next(i for i, (_t, s, _r) in enumerate(stmt) if s == "=")
            for tt, s, _r in stmt[:eq]:
                if tt == tokenize.NAME:
                    module_consts.add(s)

    def launches_git(body: str) -> bool:
        # body is an indented fragment; flatten indentation for tokenize
        flat = "\n".join(l.lstrip() for l in body.splitlines())
        for stmt in _stmts_of(flat):
            for tt, s, _r in stmt:
                if tt == tokenize.STRING:
                    try:
                        if _basename_is_git(ast.literal_eval(s)):
                            return True
                    except (ValueError, SyntaxError):
                        pass
                elif tt == tokenize.NAME and (s in EXE_VARS or s in module_consts):
                    return True
        return False

    unsafe_helpers = {n: ln for n, (ln, body, _) in helpers.items()
                      if launches_git(body) and n not in safe_helpers}

    stmts = _stmts_of(text)
    if not stmts and text.strip():
        return [{"file": str(path), "line": 0, "kind": "parse-error",
                 "snippet": "could not tokenize", "unprotected": True}]

    findings = []
    sub_vars = _collect_sub_vars(stmts)
    for stmt in stmts:
        f = _classify_stmt(stmt, path, safe_helpers, unsafe_helpers, sub_vars)
        if f:
            findings.append(f)
    return findings


def _classify_stmt(stmt: list, path: Path, safe_helpers: set,
                   unsafe_helpers: dict, sub_vars: set) -> dict | None:
    strings = set()
    names = set()
    for tt, s, _row in stmt:
        if tt == tokenize.STRING:
            try:
                v = ast.literal_eval(s)
            except (ValueError, SyntaxError):
                continue
            if isinstance(v, str):
                strings.add(v)
        elif tt == tokenize.NAME:
            names.add(s)

    if stmt[0][0] == tokenize.NAME and stmt[0][1] == "def":
        return None  # the def line itself is not a call site
    sub_hits = strings & READ_SUBCMDS
    if not sub_hits and not (names & sub_vars):
        return None
    start_row = stmt[0][2]
    snippet = " ".join(s for _t, s, _r in stmt)[:200]

    if FLAG in strings or ENV_NAME in strings or ENV_NAME in names:
        return None  # covered at this call site

    # collect calls: NAME immediately followed by '('; compute its arg span
    calls = []  # (name, open_i, close_i)
    depth_marks = []
    for i, (tt, s, row) in enumerate(stmt):
        if tt == tokenize.NAME and i + 1 < len(stmt) and stmt[i + 1][1] == "(":
            calls.append({"name": s, "open": i + 1, "close": None})
    # match parens by counting from each call's '(' over the raw op stream
    ops = [s for _t, s, _r in stmt]
    for c in calls:
        depth = 0
        for j in range(c["open"], len(stmt)):
            ch = stmt[j][1]
            if ch in "([":
                if ch == "(":
                    depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    c["close"] = j
                    break
    del ops, depth_marks

    # index of first subcommand string token (or a tracked arg variable)
    sub_idx = None
    for i, (tt, s, _r) in enumerate(stmt):
        if tt == tokenize.STRING:
            try:
                if ast.literal_eval(s) in READ_SUBCMDS:
                    sub_idx = i
                    break
            except (ValueError, SyntaxError):
                continue
        elif tt == tokenize.NAME and s in sub_vars:
            sub_idx = i
            break
    if sub_idx is None:
        return None

    containing = [c for c in calls
                  if c["close"] is not None and c["open"] < sub_idx < c["close"]]
    # innermost carrier = smallest span
    carrier = min(containing, key=lambda c: c["close"] - c["open"]) if containing else None

    def exe_inside(lo: int, hi: int) -> bool:
        for i in range(lo, hi + 1):
            tt, s, _r = stmt[i]
            if tt == tokenize.STRING:
                try:
                    if _basename_is_git(ast.literal_eval(s)):
                        return True
                except (ValueError, SyntaxError):
                    pass
            elif tt == tokenize.NAME and s in EXE_VARS:
                # exe var only counts when it is not itself the carrier call
                if i + 1 >= len(stmt) or stmt[i + 1][1] != "(":
                    return True
        return False

    lo, hi = (0, len(stmt) - 1)
    if carrier is not None:
        lo, hi = carrier["open"] + 1, carrier["close"] - 1

    if exe_inside(lo, hi):
        return {"file": str(path), "line": start_row, "kind": "direct",
                "snippet": snippet, "unprotected": True,
                "note": "git exe reaches status/diff without the flag"}

    if carrier is not None:
        cname = carrier["name"]
        last = cname.rsplit(".", 1)[-1].lower()
        if _is_gitish(cname):
            if cname in safe_helpers:
                return None
            if cname in unsafe_helpers:
                return {"file": str(path), "line": start_row,
                        "kind": "via-helper",
                        "snippet": snippet, "unprotected": True,
                        "note": f"helper {cname}() def line "
                                f"{unsafe_helpers[cname]} lacks {FLAG}; fix once"}
            return {"file": str(path), "line": start_row, "kind": "indirect",
                    "snippet": snippet, "unprotected": False,
                    "note": f"call via {cname}() (injected/external) - scan its provider"}
        if last in LAUNCH_LAST or _is_gitish(last):
            # launcher carrying status/diff: only a hit when the args carry
            # git evidence (git_exe()/git var/...); curl-style calls pass
            for i in range(lo, hi + 1):
                tt, s, _r = stmt[i]
                if tt == tokenize.NAME and (s in EXE_VARS or _is_git_name(s)):
                    return {"file": str(path), "line": start_row,
                            "kind": "direct",
                            "snippet": snippet, "unprotected": True,
                            "note": f"launcher {cname}() carries status/diff args"}
            return None  # launcher args carry no visible git token
        if cname in unsafe_helpers:
            return {"file": str(path), "line": start_row, "kind": "via-helper",
                    "snippet": snippet, "unprotected": True,
                    "note": f"helper {cname}() def line {unsafe_helpers[cname]} "
                            f"lacks {FLAG}; fix once"}
        # gitish unknown carrier -> possibly injected callable; anything else
        # (get(), dumps() on a "status" key, ...) is just noise
        if not _is_gitish(cname):
            return None
        return {"file": str(path), "line": start_row, "kind": "indirect",
                "snippet": snippet, "unprotected": False,
                "note": f"call via {cname}() - provider not resolved here"}

    # no carrier: exe token anywhere?
    if exe_inside(0, len(stmt) - 1):
        return {"file": str(path), "line": start_row, "kind": "direct",
                "snippet": snippet, "unprotected": True,
                "note": "git exe reaches status/diff without the flag"}
    return None


# ---------- generic line scanner (ps1/bat/cmd/js) ----------

GIT_EXE_RE = re.compile(r"(?:\bgit(?:\.exe)?\b|\$git\w*|&\s*\$?git)", re.I)
# subcommand must be a bareword argument: exclude .status property,
# -Status/--diff-filter spellings and *Status/*gitStatus* names
SUBCMD_RE = re.compile(r"(?<![.\-\w])(status|diff)\b", re.I)


def _strip_strings(line: str) -> str:
    out = []
    i = 0
    quote = None
    while i < len(line):
        ch = line[i]
        if quote:
            if ch == quote:
                # doubled quote escape in ps1
                if i + 1 < len(line) and line[i + 1] == quote:
                    i += 2
                    continue
                quote = None
            i += 1
            continue
        if ch in "'\"`":
            quote = ch
            i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def scan_generic(path: Path, text: str) -> list[dict]:
    ext = path.suffix.lower()
    file_env = (ENV_NAME in text) and ext in {".ps1", ".psm1", ".bat", ".cmd"}
    findings = []
    in_block = False
    in_here = False
    for n, raw in enumerate(text.splitlines(), 1):
        line = raw
        low = line.lstrip().lower()
        if ext in {".bat", ".cmd"}:
            if low.startswith(("rem ", "rem\t", "::", "echo ", "@echo")):
                continue
        if ext == ".js" or ext == ".mjs":
            if in_block:
                if "*/" in line:
                    line = line.split("*/", 1)[1]
                    in_block = False
                else:
                    continue
            if "/*" in line:
                pre, _, post = line.partition("/*")
                line = pre + post
                in_block = True
            line = line.split("//", 1)[0]
        if ext in {".ps1", ".psm1"}:
            if in_here:
                if line.strip() in ("'@", '"@'):
                    in_here = False
                continue
            if re.search(r"@['\"]\s*$", line):
                in_here = True
                line = line[: line.rfind("@")]
            if in_block:
                if "#>" in line:
                    line = line.split("#>", 1)[1]
                    in_block = False
                else:
                    continue
            if "<#" in line:
                pre, _, post = line.partition("<#")
                line = pre + post
                if "#>" not in post:
                    in_block = True
                    continue
                line = pre + post.split("#>", 1)[1]
        stripped = _strip_strings(line)
        if ext in {".ps1", ".psm1"} and "#" in stripped:
            stripped = stripped.split("#", 1)[0]
        if not stripped.strip():
            continue
        has_git = bool(GIT_EXE_RE.search(stripped))
        has_sub = bool(SUBCMD_RE.search(stripped))
        if not (has_git and has_sub):
            continue
        if FLAG in stripped or file_env:
            findings.append({"file": str(path), "line": n, "kind": "covered",
                             "snippet": raw.strip()[:200], "unprotected": False,
                             "note": "flag/env present"})
            continue
        findings.append({"file": str(path), "line": n, "kind": "direct",
                         "snippet": raw.strip()[:200], "unprotected": True,
                         "note": "git read call without --no-optional-locks"})
    return findings


def iter_files(root: Path):
    for top in SCAN_DIRS:
        base = root / top
        if not base.is_dir():
            continue
        if top == "__patch_drop__":
            patterns = ("*.ps1",)
        else:
            patterns = ("*.py", "*.ps1", "*.psm1", "*.bat", "*.cmd", "*.js", "*.mjs")
        for pat in patterns:
            for p in sorted(base.rglob(pat)):
                rel = p.relative_to(root).as_posix()
                parts = set(p.relative_to(root).parts)
                if parts & EXCLUDE_PARTS:
                    continue
                if top == ".agents/skills" and "/scripts/" not in "/" + rel:
                    continue
                if TEST_RE.match(p.name):
                    continue
                yield p, rel


def scan(root: Path) -> dict:
    findings = []
    scanned = 0
    for path, rel in iter_files(root):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        scanned += 1
        if path.suffix == PY_EXT:
            fs = scan_python(Path(rel), text)
        elif path.suffix.lower() in GEN_EXT:
            fs = scan_generic(Path(rel), text)
        else:
            continue
        for f in fs:
            f["file"] = rel
        findings.extend(fs)
    findings.sort(key=lambda f: (f["file"], f["line"]))
    unprotected = [f for f in findings if f["unprotected"]]
    return {
        "schemaVersion": SCHEMA,
        "root": str(root),
        "filesScanned": scanned,
        "findings": findings,
        "unprotectedCount": len(unprotected),
        "unprotected": unprotected,
    }


def _key(f: dict) -> str:
    return f"{f['file']}:{f['line']}"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--baseline", help="prior scan JSON; exit 1 only on NEW "
                                       "unprotected findings")
    ap.add_argument("--out", help="also write the report JSON here")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    report = scan(root)

    new_findings = []
    if args.baseline:
        try:
            base = json.loads(Path(args.baseline).read_text(encoding="utf-8"))
            known = {_key(f) for f in base.get("unprotected", [])}
        except (OSError, json.JSONDecodeError) as exc:
            print(json.dumps({"schemaVersion": SCHEMA, "status": "error",
                              "reason": f"baseline-unreadable: {exc}"}))
            return 6
        new_findings = [f for f in report["unprotected"]
                        if _key(f) not in known]
        report["baseline"] = {"path": args.baseline,
                              "knownCount": len(known),
                              "newCount": len(new_findings),
                              "new": new_findings}

    if args.out:
        Path(args.out).write_text(json.dumps(report, indent=1, ensure_ascii=False),
                                  encoding="utf-8")
    print(json.dumps(report, indent=1, ensure_ascii=False))
    if args.baseline and new_findings:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
