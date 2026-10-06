"""Read-only assist for two Codex briefs.

Checks source anchors, named-test search gaps, and added diff lines.
Stdlib only. No network, Gradle, server, or product writes.
Exit 0 is a clean scan. It is not a product PASS.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

SCHEMA = "awx.pair-brief-assist.v1"
DEFAULT_SPEC = "var/codex-assist-pair-brief-20261005/spec.json"
MAX_BYTES = 3_000_000
SECRET_NAME = ("secret", "credential")


class AssistError(Exception):
    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


def sha12_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:12]


def load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise AssistError("spec-unreadable") from exc


def compile_rule(rule_id: str, pattern: str):
    if not isinstance(pattern, str) or not pattern or len(pattern) > 200:
        raise AssistError("invalid-regex:" + str(rule_id))
    try:
        return re.compile(pattern)
    except re.error as exc:
        raise AssistError("invalid-regex:" + str(rule_id)) from exc


def reject_name(name: str):
    lower = name.lower()
    if lower.startswith(".env") or any(part in lower for part in SECRET_NAME):
        raise AssistError("secret-filename")


def under_root(root: Path, rel: str) -> Path:
    if not isinstance(rel, str) or not rel or rel.startswith(("/", "\\")) or ":" in rel:
        raise AssistError("path-not-relative")
    parts = Path(rel).parts
    if any(part in ("..", "") for part in parts):
        raise AssistError("path-escape")
    reject_name(parts[-1])
    current = root.resolve()
    for part in parts:
        current = current / part
        if current.is_symlink():
            raise AssistError("symlink-refused")
    resolved = current.resolve()
    root_resolved = root.resolve()
    if resolved != root_resolved and root_resolved not in resolved.parents:
        raise AssistError("path-escape")
    return resolved


def read_text(path: Path):
    if not path.is_file():
        return None
    if path.stat().st_size > MAX_BYTES:
        raise AssistError("file-too-large")
    return path.read_bytes()


def first_line(text: str, pattern: re.Pattern):
    for index, line in enumerate(text.splitlines(), start=1):
        if pattern.search(line):
            return index
    return None


def scan_rules(text: str, rules):
    found = []
    for rule in rules:
        line = first_line(text, rule["compiled"])
        found.append({
            "id": rule["id"],
            "status": "FOUND" if line else "MISSING",
            "line": line,
        })
    return found


def scan_forbidden(text: str, rules):
    found = []
    for rule in rules:
        line = first_line(text, rule["compiled"])
        found.append({
            "id": rule["id"],
            "status": "HIT" if line else "CLEAR",
            "line": line,
        })
    return found


def normalize_rules(items, label):
    rules = []
    if items is None:
        return rules
    if not isinstance(items, list):
        raise AssistError("spec-shape")
    for item in items:
        if not isinstance(item, dict):
            raise AssistError("spec-shape")
        rule_id = item.get("id")
        if not isinstance(rule_id, str) or not rule_id:
            raise AssistError("spec-shape")
        rules.append({
            "id": rule_id,
            "compiled": compile_rule(label + ":" + rule_id, item.get("regex")),
        })
    return rules


def iter_sources(spec):
    contracts = spec.get("contracts")
    if not isinstance(contracts, dict) or not contracts:
        raise AssistError("spec-shape")
    for name, body in contracts.items():
        if not isinstance(body, dict):
            raise AssistError("spec-shape")
        sources = body.get("sources") or []
        coverage = body.get("coverage") or []
        if not isinstance(sources, list) or not isinstance(coverage, list):
            raise AssistError("spec-shape")
        yield name, sources, coverage, body.get("diffRules") or []


def load_spec(path: Path):
    spec = load_json(path)
    if not isinstance(spec, dict) or spec.get("schemaVersion") != SCHEMA:
        raise AssistError("spec-schema")
    for _name, sources, coverage, diff_rules in iter_sources(spec):
        for row in sources:
            normalize_rules(row.get("needles"), "needle")
            normalize_rules(row.get("forbidden"), "forbidden")
        for row in coverage:
            normalize_rules(row.get("tokens"), "token")
        if not isinstance(diff_rules, list):
            raise AssistError("spec-shape")
        for rule in diff_rules:
            compile_rule(rule.get("id"), rule.get("lineRegex"))
            compile_rule(rule.get("id"), rule.get("pathRegex"))
            if rule.get("allowPathRegex"):
                compile_rule(rule.get("id"), rule.get("allowPathRegex"))
    return spec


def base_report(command: str, status: str):
    return {
        "schemaVersion": SCHEMA,
        "command": command,
        "status": status,
        "productPass": False,
        "gradleRan": False,
        "networkUsed": False,
    }


def cmd_pin(root: Path, spec):
    files = []
    for name, sources, _coverage, _diff in iter_sources(spec):
        for row in sources:
            rel = row.get("path")
            path = under_root(root, rel)
            needles = normalize_rules(row.get("needles"), "needle")
            forbidden = normalize_rules(row.get("forbidden"), "forbidden")
            item = {
                "contract": name,
                "path": rel.replace("\\", "/"),
                "present": False,
                "sha12": None,
                "expectSha12": row.get("expectSha12"),
                "sha12Status": "UNPINNED",
                "needles": [],
                "forbidden": [],
            }
            data = read_text(path)
            if data is None:
                item["needles"] = [
                    {"id": rule["id"], "status": "MISSING", "line": None} for rule in needles
                ]
                item["forbidden"] = [
                    {"id": rule["id"], "status": "CLEAR", "line": None} for rule in forbidden
                ]
                files.append(item)
                continue
            text = data.decode("utf-8", errors="replace")
            digest = sha12_of(data)
            item["present"] = True
            item["sha12"] = digest
            expected = row.get("expectSha12")
            if isinstance(expected, str) and expected:
                item["sha12Status"] = "MATCH" if expected == digest else "DRIFT"
            item["needles"] = scan_rules(text, needles)
            item["forbidden"] = scan_forbidden(text, forbidden)
            files.append(item)
    gap = False
    stale = False
    for item in files:
        if not item["present"]:
            gap = True
        if any(row["status"] == "MISSING" for row in item["needles"]):
            gap = True
        if any(row["status"] == "HIT" for row in item["forbidden"]):
            gap = True
        if item["sha12Status"] == "DRIFT":
            stale = True
    if gap:
        status, code = "CONTRACT_GAP", 3
    elif stale:
        status, code = "ANCHOR_STALE", 4
    else:
        status, code = "FRESH", 0
    report = base_report("pin", status)
    report["files"] = files
    report["note"] = "FRESH means anchors matched. It does not mean the brief is implemented."
    return report, code


def cmd_cover(root: Path, spec):
    rows = []
    missing_file = False
    gap = False
    for name, _sources, coverage, _diff in iter_sources(spec):
        for row in coverage:
            rel = row.get("path")
            path = under_root(root, rel)
            tokens = normalize_rules(row.get("tokens"), "token")
            item = {
                "contract": name,
                "path": rel.replace("\\", "/"),
                "present": False,
                "tokens": [],
            }
            data = read_text(path)
            if data is None:
                missing_file = True
                item["tokens"] = [
                    {"id": rule["id"], "status": "MISSING", "line": None} for rule in tokens
                ]
                rows.append(item)
                continue
            item["present"] = True
            item["tokens"] = scan_rules(data.decode("utf-8", errors="replace"), tokens)
            if any(token["status"] == "MISSING" for token in item["tokens"]):
                gap = True
            rows.append(item)
    if missing_file:
        status, code = "MISSING_FILE", 3
    elif gap:
        status, code = "SEARCH_GAP", 4
    else:
        status, code = "COVERED", 0
    report = base_report("cover", status)
    report["files"] = rows
    report["note"] = (
        "SEARCH_GAP is a phrase miss in the named test file, not proof the behavior is absent."
    )
    return report, code


def added_lines(diff_text: str):
    path = None
    saw_header = False
    for index, line in enumerate(diff_text.splitlines(), start=1):
        if line.startswith("+++ "):
            saw_header = True
            raw = line[4:].strip()
            if raw == "/dev/null":
                path = None
            elif raw.startswith("b/"):
                path = raw[2:]
            else:
                path = raw
            continue
        if line.startswith("+") and not line.startswith("+++"):
            yield index, path, line[1:]
    if not saw_header:
        raise AssistError("invalid-diff")


def cmd_diff(spec, diff_text: str):
    rules = []
    for name, _sources, _coverage, diff_rules in iter_sources(spec):
        for rule in diff_rules:
            if not isinstance(rule, dict):
                raise AssistError("spec-shape")
            rules.append({
                "contract": name,
                "id": rule.get("id"),
                "path": compile_rule(rule.get("id"), rule.get("pathRegex")),
                "line": compile_rule(rule.get("id"), rule.get("lineRegex")),
                "allow": (
                    compile_rule(rule.get("id"), rule["allowPathRegex"])
                    if rule.get("allowPathRegex") else None
                ),
            })
    hits = []
    for diff_line, path, text in added_lines(diff_text):
        if not path:
            continue
        for rule in rules:
            if rule["allow"] and rule["allow"].search(path):
                continue
            if rule["path"].search(path) and rule["line"].search(text):
                hits.append({
                    "contract": rule["contract"],
                    "id": rule["id"],
                    "path": path,
                    "diffLine": diff_line,
                })
    status, code = ("DIFF_HIT", 3) if hits else ("DIFF_CLEAR", 0)
    report = base_report("diff-forbid", status)
    report["hits"] = hits
    report["note"] = "A hit is a review signal on an added line. The matched source text is not copied."
    return report, code


def emit(report, out: str | None):
    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if out:
        dest = Path(out)
        reject_name(dest.name)
        if dest.is_symlink():
            raise AssistError("symlink-refused")
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(text + "\n", encoding="utf-8")


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description="Read-only pair-brief assist scans")
    sub = parser.add_subparsers(dest="cmd")

    def add_common(command):
        command.add_argument("--root", default=".")
        command.add_argument("--spec", default=DEFAULT_SPEC)
        command.add_argument("--out")
        return command

    add_common(sub.add_parser("pin"))
    add_common(sub.add_parser("cover"))
    diff = add_common(sub.add_parser("diff-forbid"))
    diff.add_argument("--diff", required=True)
    args = parser.parse_args(argv)
    if args.cmd not in ("pin", "cover", "diff-forbid"):
        print(json.dumps({"schemaVersion": SCHEMA, "status": "error", "reason": "usage"}))
        return 2
    try:
        root = Path(args.root).resolve()
        spec_path = Path(args.spec)
        if not spec_path.is_absolute():
            spec_path = under_root(root, args.spec)
        else:
            reject_name(spec_path.name)
        spec = load_spec(spec_path)
        if args.cmd == "pin":
            report, code = cmd_pin(root, spec)
        elif args.cmd == "cover":
            report, code = cmd_cover(root, spec)
        else:
            diff_path = Path(args.diff)
            reject_name(diff_path.name)
            if diff_path.is_symlink():
                raise AssistError("symlink-refused")
            if not diff_path.is_file():
                raise AssistError("diff-missing")
            if diff_path.stat().st_size > MAX_BYTES:
                raise AssistError("file-too-large")
            report, code = cmd_diff(spec, diff_path.read_text(encoding="utf-8", errors="replace"))
        emit(report, args.out)
        return code
    except AssistError as exc:
        print(json.dumps({
            "schemaVersion": SCHEMA,
            "status": "error",
            "reason": exc.reason,
            "productPass": False,
        }))
        return 2


if __name__ == "__main__":
    sys.exit(main())
