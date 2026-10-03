"""Read-only explainer for codex_work_checkpoint.secret_free verdicts.

Answers "which scanned fragment holds this file" without printing the matched
bytes or the line text: path, sha256_12, verdict (pass|hold) and hits as
{line, identifier, rhsKind, blocking}. rhsKind is a coarse shape label
(lambda|call|new|literal|comment|other); the comment check is best-effort.
blocking is derived by re-running secret_free on the isolated match bytes,
which reproduces reference_only() faithfully because that predicate only sees
the match text and the source path. The scanner itself is never modified;
SECRET_FRAGMENT_RE is swapped for a recording shim during the call and
restored afterwards. Usage: checkpoint_secret_explain.py --path <repo/path>
[--root <dir>] [--json]; exit 0 pass, 6 hold, 2 error.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys

SCANNER = Path(__file__).with_name("codex_work_checkpoint.py")
_SPEC = importlib.util.spec_from_file_location("codex_work_checkpoint", SCANNER)
checkpoint = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(checkpoint)

ROOT = Path(__file__).resolve().parents[1]

_LABEL = re.compile(
    r"(?i)^(authorization|cookie|password|passwd|pwd|"
    r"client[-_.]?secret|api[-_.]?key|token)")
_PREFIX = re.compile(r"(?i)^(sk-|AIza|gsk_|pcsk_|sb_(?:secret|publishable)_|sbp_|bearer)")


def _identifier(match_text):
    label = _LABEL.match(match_text)
    if label:
        return label.group(1)
    prefix = _PREFIX.match(match_text)
    return prefix.group(1) if prefix else "other"


def _in_comment(text, pos):
    # Best-effort: a line comment earlier on the line, or an unclosed block
    # comment before the match. String bytes containing "//" can mislead this.
    line_start = text.rfind("\n", 0, pos) + 1
    if "//" in text[line_start:pos]:
        return True
    return text.count("/*", 0, pos) > text.count("*/", 0, pos)


def _rhs_kind(text, match):
    """Coarse shape of the bytes after the keyword separator. Looks past the
    match end because the fragment pattern stops at whitespace ('()' before
    '->' is inside one declaration)."""
    if _in_comment(text, match.start()):
        return "comment"
    label = _LABEL.match(match.group())
    if not label:
        return "literal"
    tail = text[match.end():match.end() + 160].split("\n", 1)[0]
    rhs = re.sub(r"^[\s:=]+", "", match.group()[label.end():]) + tail
    rhs = rhs.lstrip()
    if re.match(r"(?:\([^()\r\n]*\)|[A-Za-z_$][\w$]*)\s*->", rhs):
        return "lambda"
    if re.match(r"new\s", rhs):
        return "new"
    if re.match(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\s*\(", rhs):
        return "call"
    if rhs[:1] in ('"', "'", "`"):
        return "literal"
    return "other"


def _blocking(match_text, source_path):
    try:
        checkpoint.secret_free(match_text.encode("utf-8"), source_path)
    except checkpoint.CheckpointError:
        return True
    return False


class _FinditerSpy:
    """Shim for SECRET_FRAGMENT_RE during one secret_free call; records every
    scan's text and matches so the final verdict pass can be inspected."""

    def __init__(self, pattern, scans):
        self._pattern = pattern
        self._scans = scans

    def finditer(self, text, *args, **kwargs):
        matches = list(self._pattern.finditer(text, *args, **kwargs))
        self._scans.append((text, matches))
        return iter(matches)

    def __getattr__(self, name):
        return getattr(self._pattern, name)


def explain_file(root, rel_path):
    root = Path(root).resolve()
    rel_path = str(rel_path).replace("\\", "/").lstrip("/")
    data = root.joinpath(*rel_path.split("/")).read_bytes()
    scans = []
    original = checkpoint.SECRET_FRAGMENT_RE
    checkpoint.SECRET_FRAGMENT_RE = _FinditerSpy(original, scans)
    try:
        checkpoint.secret_free(data, rel_path)
        verdict = "pass"
    except checkpoint.CheckpointError:
        verdict = "hold"
    finally:
        checkpoint.SECRET_FRAGMENT_RE = original
    hits = []
    if scans:
        # The last finditer call is the final verdict scan; earlier calls feed
        # the scanner's own masking exemptions and are not holds.
        text, matches = scans[-1]
        for match in matches:
            hits.append({
                "line": text.count("\n", 0, match.start()) + 1,
                "identifier": _identifier(match.group()),
                "rhsKind": _rhs_kind(text, match),
                "blocking": _blocking(match.group(), rel_path),
            })
    return {
        "path": rel_path,
        "sha256_12": hashlib.sha256(data).hexdigest()[:12],
        "verdict": verdict,
        "hits": hits,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Explain a checkpoint secret_free verdict; never prints matched bytes.")
    parser.add_argument("--path", required=True, help="repo-relative file path")
    parser.add_argument("--root", default=str(ROOT))
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    try:
        result = explain_file(args.root, args.path)
    except OSError as error:
        result = {"path": args.path, "sha256_12": None, "verdict": "error",
                  "hits": [], "error": type(error).__name__}
    if args.json:
        print(json.dumps(result, ensure_ascii=True))
    else:
        print("{verdict} {path} sha256_12={sha256_12}".format(**result))
        for hit in result["hits"]:
            print("  line {line} identifier={identifier} rhsKind={rhsKind} "
                  "blocking={blocking}".format(**hit))
    return {"pass": 0, "hold": 6}.get(result["verdict"], 2)


if __name__ == "__main__":
    sys.exit(main())
