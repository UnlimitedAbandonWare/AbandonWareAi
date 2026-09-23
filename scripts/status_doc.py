"""Row-scoped updates for shared status documents (docs/PROJECT_STATUS.md).

The status doc is append/update-by-row only through this tool: a caller reads the
file (read --key prints matching rows plus the whole-file sha256), then writes with
--expect-sha256. If the file changed between read and apply the write is refused
as a conflict — never a silent overwrite, never an auto-filled replacement row.

Actions:
  read        print each row containing --key with 1-based line numbers + file hash
  update-row  replace exactly the one row containing --key with --line
  append-row  insert --line immediately after the row containing --after-key

Row content arrives via --line <text>, --line-file <utf-8 path>, or --line -
(stdin). Prefer --line-file for Korean/quotes/backticks/$ text: a file path
survives shell quoting where an inline -Command argument cannot.

A row is a single line beginning with '|' and ending with '|'. The key must appear
verbatim inside the new line for update-row, so a row can only be rewritten by a
line that still names the same identifier (no row takeover, no section rewrite).
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import uuid


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read_doc(path):
    path = Path(path).resolve()
    if str(path).startswith("\\\\"):
        raise ValueError("local-path-required")
    data = path.read_bytes()
    return path, data, data.decode("utf-8-sig").splitlines(keepends=True)


def matching(data_lines, key):
    return [(i, line) for i, line in enumerate(data_lines)
            if key in line and line.lstrip().startswith("|")]


def read(path, key):
    path, data, lines = read_doc(path)
    rows = [{"line": i + 1, "text": line.rstrip("\r\n")} for i, line in matching(lines, key)]
    return {"file": str(path), "sha256": sha(data), "key": key,
            "matches": len(rows), "rows": rows}


_CONTROL_CHARS = tuple(chr(c) for c in range(0x00, 0x09)) + \
    ("\x0b", "\x0c") + tuple(chr(c) for c in range(0x0e, 0x20)) + ("\x7f",)


def check_row(line, key, label):
    if "\n" in line or "\r" in line:
        raise ValueError(label + "-line-must-be-single-line")
    if any(ch in line for ch in _CONTROL_CHARS):
        raise ValueError(label + "-line-disallowed-control-char")
    text = line.strip()
    if not (text.startswith("|") and text.endswith("|") and text.count("|") >= 3):
        raise ValueError(label + "-line-must-be-table-row")
    if key not in line:
        raise ValueError(label + "-line-must-contain-key")
    return line


def write_atomic(path, lines):
    temp = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with temp.open("xb") as stream:
            stream.write("".join(lines).encode("utf-8"))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        if temp.exists():
            temp.unlink()


def mutate(path, expect_sha256, change):
    path, data, lines = read_doc(path)
    actual = sha(data)
    if actual != expect_sha256:
        return {"status": "conflict", "reason": "status-doc-changed-since-read",
                "expected": expect_sha256, "actual": actual, "file": str(path)}
    result = change(lines)
    write_atomic(path, lines)
    after = sha(path.read_bytes())
    return {"status": "applied", "file": str(path), "beforeSha256": actual,
            "afterSha256": after, **result}


def update_row(path, key, line, expect_sha256):
    new = check_row(line, key, "update-row")

    def change(lines):
        found = matching(lines, key)
        if not found:
            raise ValueError("row-key-missing")
        if len(found) > 1:
            raise ValueError("row-key-ambiguous")
        i, old = found[0]
        lines[i] = new + ("\r\n" if old.endswith("\r\n") else "\n")
        return {"replacedLine": i + 1, "beforeRowSha256": sha(old.encode("utf-8")),
                "afterRowSha256": sha((new + "\n").encode("utf-8"))}
    return mutate(path, expect_sha256, change)


def append_row(path, after_key, line, expect_sha256):
    # The new row must be a table row but names its own key, not the anchor's.
    if "\n" in line or "\r" in line:
        raise ValueError("append-line-must-be-single-line")
    if any(ch in line for ch in _CONTROL_CHARS):
        raise ValueError("append-line-disallowed-control-char")
    text = line.strip()
    if not (text.startswith("|") and text.endswith("|") and text.count("|") >= 3):
        raise ValueError("append-line-must-be-table-row")

    def change(lines):
        found = matching(lines, after_key)
        if not found:
            raise ValueError("row-key-missing")
        if len(found) > 1:
            raise ValueError("row-key-ambiguous")
        i, old = found[0]
        lines.insert(i + 1, text + ("\r\n" if old.endswith("\r\n") else "\n"))
        return {"insertedAfterLine": i + 1,
                "afterRowSha256": sha((text + "\n").encode("utf-8"))}
    return mutate(path, expect_sha256, change)


def _load_line(args):
    if args.line_file:
        text = Path(args.line_file).read_text(encoding="utf-8-sig")
        return text.rstrip("\r\n")
    if args.line == "-":
        return sys.stdin.read().rstrip("\r\n")
    return args.line


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("read", "update-row", "append-row"))
    parser.add_argument("--file", required=True)
    parser.add_argument("--key")
    parser.add_argument("--after-key")
    parser.add_argument("--line")
    parser.add_argument("--line-file")
    parser.add_argument("--expect-sha256")
    args = parser.parse_args(argv)
    try:
        if args.action == "read":
            if not args.key:
                raise ValueError("key-required")
            result = read(args.file, args.key)
            print(json.dumps(result, ensure_ascii=True))
            return 0
        if args.line and args.line_file:
            raise ValueError("line-and-line-file-are-exclusive")
        if not args.expect_sha256 or (args.line is None and not args.line_file):
            raise ValueError("expect-sha256-and-line-required")
        line = _load_line(args)
        if args.action == "update-row":
            if not args.key:
                raise ValueError("key-required")
            result = update_row(args.file, args.key, line, args.expect_sha256)
        else:
            if not args.after_key:
                raise ValueError("after-key-required")
            result = append_row(args.file, args.after_key, line, args.expect_sha256)
        print(json.dumps(result, ensure_ascii=True))
        return 0 if result["status"] == "applied" else 2
    except (ValueError, OSError, KeyError) as failure:
        print(json.dumps({"status": "error", "reason": str(failure)}, ensure_ascii=True))
        return 2


if __name__ == "__main__":
    sys.exit(main())
