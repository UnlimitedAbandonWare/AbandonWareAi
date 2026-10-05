"""Lint .agents/skills/*/SKILL.md frontmatter (awx.skill-frontmatter-lint.v1).

Checks per SKILL.md:
  1. line 1 is exactly '---'
  2. frontmatter parses (yaml.safe_load when PyYAML is installed; otherwise a
     stdlib check that flags ': ' inside an unquoted scalar)
  3. name == parent folder name
  4. description is non-empty

Exit 0 = clean, 3 = violations, 2 = usage/io error.
--fix-quote rewrites only an unquoted `description:` value as a single-quoted
scalar (content unchanged). Paths are repo-relative.

Usage:
    python -B scripts\\skill_frontmatter_lint.py [--root .agents/skills]
        [--path FILE] [--fix-quote] [--json]
"""
import argparse
import glob
import json
import os
import re
import sys

try:
    import yaml  # PyYAML (optional)
except ImportError:  # pragma: no cover - environment without pyyaml
    yaml = None

_UNQUOTED_COLON = re.compile(r"^([A-Za-z_][A-Za-z0-9_-]*)[ ]*:[ ]+([^'\"|>\[{][^\n]*?: [^\n]*)$")


def _simple_fields(fm_lines):
    """stdlib fallback: flag unquoted ': ' scalars; collect raw key values."""
    fields = {}
    violations = []
    for i, line in enumerate(fm_lines, 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        m = _UNQUOTED_COLON.match(line)
        if m:
            violations.append(("unquoted-colon", "line %d: %r contains ': ' in an unquoted scalar" % (i, line.strip()[:80])))
            key = m.group(1)
            fields[key] = m.group(2).strip()
            continue
        m2 = re.match(r"^([A-Za-z_][A-Za-z0-9_-]*)[ ]*:[ ]*(.*)$", line)
        if m2:
            key, val = m2.group(1), m2.group(2).strip()
            if val.startswith("'") and val.endswith("'") and len(val) >= 2:
                val = val[1:-1].replace("''", "'")
            elif val.startswith('"') and val.endswith('"') and len(val) >= 2:
                val = val[1:-1]
            fields[key] = val
        else:
            violations.append(("yaml-line", "line %d not key: value: %r" % (i, line.strip()[:80])))
    return fields, violations


def lint_file(path):
    """Return a list of (code, detail) violations for one SKILL.md."""
    violations = []
    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            text = fh.read()
    except OSError as exc:
        return [("io-error", str(exc))]
    lines = text.split("\n")
    if not lines or lines[0].rstrip("\r") != "---":
        return [("missing-frontmatter", "line 1 is not '---'")]
    end = None
    for i in range(1, len(lines)):
        if lines[i].rstrip("\r") == "---":
            end = i
            break
    if end is None:
        return [("unclosed-frontmatter", "no closing '---'")]
    fm_lines = lines[1:end]
    fm_text = "\n".join(fm_lines)
    fields = {}
    if yaml is not None:
        try:
            data = yaml.safe_load(fm_text)
        except Exception as exc:
            violations.append(("yaml-error", str(exc).split("\n")[0][:160]))
            data = None
        if isinstance(data, dict):
            fields = data
        elif data is not None:
            violations.append(("frontmatter-not-mapping", "frontmatter is not a mapping"))
    else:
        fields, violations = _simple_fields(fm_lines)
    expected = os.path.basename(os.path.dirname(os.path.abspath(path)))
    name = fields.get("name")
    if name != expected:
        violations.append(("name-mismatch", "name=%r expected=%r" % (name, expected)))
    desc = fields.get("description")
    if not (isinstance(desc, str) and desc.strip()):
        violations.append(("empty-description", "description missing or empty"))
    return violations


def fix_quote(path):
    """Wrap an unquoted `description:` value in single quotes. Returns True if changed."""
    with open(path, "r", encoding="utf-8-sig", newline="") as fh:
        lines = fh.read().split("\n")
    if not lines or lines[0].rstrip("\r") != "---":
        return False
    end = None
    for i in range(1, len(lines)):
        if lines[i].rstrip("\r") == "---":
            end = i
            break
    if end is None:
        return False
    changed = False
    for i in range(1, end):
        m = re.match(r"^(description[ ]*:[ ]+)([^'\"|>\[{].*)$", lines[i].rstrip("\r"))
        if m:
            value = m.group(2).strip()
            lines[i] = m.group(1) + "'" + value.replace("'", "''") + "'"
            changed = True
            break
    if changed:
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write("\n".join(lines))
    return changed


def find_skill_files(root):
    return sorted(glob.glob(os.path.join(root, "*", "SKILL.md")))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=os.path.join(".agents", "skills"))
    parser.add_argument("--path", action="append", default=[])
    parser.add_argument("--fix-quote", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)

    files = args.path or find_skill_files(args.root)
    if not files:
        print("[skill-lint] no SKILL.md under %s" % args.root, file=sys.stderr)
        return 2

    report = []
    bad = 0
    for path in files:
        violations = lint_file(path)
        fixed = False
        if violations and args.fix_quote:
            fixed = fix_quote(path)
            if fixed:
                violations = lint_file(path)
        if violations:
            bad += 1
        rel = os.path.relpath(path, os.getcwd()).replace(os.sep, "/")
        report.append({"file": rel, "violations": [{"code": c, "detail": d} for c, d in violations],
                       "fixed": fixed})
    if args.json:
        print(json.dumps({"schemaVersion": "awx.skill-frontmatter-lint.v1",
                          "files": report, "violationFiles": bad}, ensure_ascii=False, indent=2))
    else:
        for entry in report:
            mark = "OK" if not entry["violations"] else "VIOLATION"
            if entry["fixed"]:
                mark += " (fixed --fix-quote)"
            print("%s %s" % (mark, entry["file"]))
            for v in entry["violations"]:
                print("    %s: %s" % (v["code"], v["detail"]))
        print("[skill-lint] files=%d violations=%d" % (len(report), bad))
    return 3 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
