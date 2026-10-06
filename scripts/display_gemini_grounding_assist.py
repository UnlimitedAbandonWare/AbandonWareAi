"""Read-only assist for DEMO1-DISPLAY-GEMINI-GROUNDING-ARCH-20261005.

Codex owns the product patch. This script pins anchors, lists open gaps,
classifies a mock generateContent body, and flags forbidden diff lines.
Stdlib only. No network, Gradle, server, or product writes.
A clean exit is a scan result. It is not a product PASS.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

SCHEMA = "awx.display-gemini-grounding-assist.v1"
DEFAULT_SPEC = "var/codex-assist-display-gemini-grounding-20261005/spec.json"
MAX_BYTES = 3_000_000
SECRET_NAME = ("secret", "credential")
EXPECT_REQUEST = {
    "OFF", "ON_NATIVE_SNAKE", "ON_JS_CAMEL", "INTERACTIONS_COPY",
    "LEGACY_RETRIEVAL", "UNKNOWN_TOOL",
}
EXPECT_RESPONSE = {
    "UNOBSERVED", "CITATION_METADATA_ONLY", "EMPTY_OR_ERROR",
    "QUERY_ONLY", "LINKED", "SUGGESTIONS",
}
SECRET_VALUE = re.compile(
    r"(?i)(api[_-]?key|authorization|x-goog-api-key)\s*[\"']?\s*[:=]\s*[\"'][^\"']{6,}"
)
GROUNDED_CALL = re.compile(r"\.generate\(\s*[\s\S]{0,180}?,\s*true\s*\)")


class AssistError(Exception):
    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


def sha12_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()[:12]


def load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise AssistError("json-unreadable") from exc


def compile_rule(rule_id: str, pattern: str):
    if not isinstance(pattern, str) or not pattern or len(pattern) > 240:
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


def read_bytes(path: Path):
    if not path.is_file():
        return None
    if path.is_symlink():
        raise AssistError("symlink-refused")
    if path.stat().st_size > MAX_BYTES:
        raise AssistError("file-too-large")
    return path.read_bytes()


def first_line(text: str, pattern: re.Pattern):
    for index, line in enumerate(text.splitlines(), start=1):
        if pattern.search(line):
            return index
    return None


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
            "optional": bool(item.get("optional")),
        })
    return rules


def scan_rules(text: str, rules):
    found = []
    for rule in rules:
        line = first_line(text, rule["compiled"])
        if line:
            status = "FOUND"
        elif rule["optional"]:
            status = "OPEN_OPTIONAL"
        else:
            status = "MISSING"
        found.append({
            "id": rule["id"],
            "status": status,
            "line": line,
            "optional": rule["optional"],
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


def load_spec(path: Path):
    spec = load_json(path)
    if not isinstance(spec, dict) or spec.get("schemaVersion") != SCHEMA:
        raise AssistError("spec-schema")
    for row in spec.get("sources") or []:
        if not isinstance(row, dict):
            raise AssistError("spec-shape")
        normalize_rules(row.get("needles"), "needle")
        normalize_rules(row.get("forbidden"), "forbidden")
    for key in ("gaps", "coverage"):
        for row in spec.get(key) or []:
            if not isinstance(row, dict):
                raise AssistError("spec-shape")
            normalize_rules(row.get("tokens"), key)
    seam = spec.get("seam") or {}
    if not isinstance(seam, dict):
        raise AssistError("spec-shape")
    for key in ("nativeOptIn", "displayLink", "clientCalls"):
        row = seam.get(key) or {}
        if not isinstance(row, dict):
            raise AssistError("spec-shape")
        compile_rule(key, row.get("regex"))
    for rule in spec.get("diffRules") or []:
        if not isinstance(rule, dict):
            raise AssistError("spec-shape")
        compile_rule(rule.get("id"), rule.get("lineRegex"))
        compile_rule(rule.get("id"), rule.get("pathRegex"))
        if rule.get("allowPathRegex"):
            compile_rule(rule.get("id"), rule.get("allowPathRegex"))
        if rule.get("side", "added") not in ("added", "removed"):
            raise AssistError("spec-shape")
    return spec


def base_report(command: str, status: str):
    return {
        "schemaVersion": SCHEMA,
        "command": command,
        "status": status,
        "productPass": False,
        "gradleRan": False,
        "networkUsed": False,
        "liveCall": False,
    }


def file_rows(root: Path, rows, rule_key: str):
    items = []
    missing_file = False
    required_gap = False
    forbidden_hit = False
    for row in rows:
        rel = row.get("path")
        path = under_root(root, rel)
        rules = normalize_rules(row.get("needles" if rule_key == "needles" else "tokens"), rule_key)
        forbidden = normalize_rules(row.get("forbidden"), "forbidden") if rule_key == "needles" else []
        item = {"path": str(rel).replace("\\", "/"), "present": False, rule_key: []}
        data = read_bytes(path)
        if data is None:
            missing_file = True
            item[rule_key] = [
                {
                    "id": rule["id"],
                    "status": "OPEN_OPTIONAL" if rule["optional"] else "MISSING",
                    "line": None,
                    "optional": rule["optional"],
                }
                for rule in rules
            ]
            if rule_key == "needles":
                item["forbidden"] = [
                    {"id": rule["id"], "status": "CLEAR", "line": None} for rule in forbidden
                ]
            if any(token["status"] == "MISSING" for token in item[rule_key]):
                required_gap = True
            items.append(item)
            continue
        text = data.decode("utf-8", errors="replace")
        item["present"] = True
        if rule_key == "needles":
            item["sha12"] = sha12_of(data)
            expected = row.get("expectSha12")
            item["expectSha12"] = expected
            if isinstance(expected, str) and expected:
                item["sha12Status"] = "MATCH" if expected == item["sha12"] else "DRIFT"
            else:
                item["sha12Status"] = "UNPINNED"
            item["forbidden"] = scan_forbidden(text, forbidden)
            if any(hit["status"] == "HIT" for hit in item["forbidden"]):
                forbidden_hit = True
        scanned = scan_rules(text, rules)
        item[rule_key] = scanned
        if any(token["status"] == "MISSING" for token in scanned):
            required_gap = True
        items.append(item)
    return items, missing_file, required_gap, forbidden_hit


def cmd_pin(root: Path, spec):
    files, missing_file, gap, forbidden_hit = file_rows(root, spec.get("sources") or [], "needles")
    stale = any(item.get("sha12Status") == "DRIFT" for item in files)
    if missing_file or gap or forbidden_hit:
        status, code = "CONTRACT_GAP", 3
    elif stale:
        status, code = "ANCHOR_STALE", 4
    else:
        status, code = "FRESH", 0
    report = base_report("pin", status)
    report["contract"] = spec.get("contract")
    report["files"] = files
    report["note"] = "FRESH means the pinned invariants are still on disk. It does not mean grounding is wired."
    return report, code


def phrase_command(root: Path, spec, key: str, command: str, open_status: str, closed_status: str):
    files, missing_file, gap, _forbidden = file_rows(root, spec.get(key) or [], "tokens")
    if missing_file:
        status, code = "MISSING_FILE", 3
    elif gap:
        status, code = open_status, 4
    else:
        status, code = closed_status, 0
    report = base_report(command, status)
    report["contract"] = spec.get("contract")
    report["files"] = files
    if command == "gaps":
        report["note"] = (
            "GAP_OPEN is the pre-patch work list. OPEN_OPTIONAL is a name hint, not a required phrase. "
            "Closing a gap is not a product PASS."
        )
    else:
        report["note"] = "SEARCH_GAP is a missing phrase in the named test file, not proof the behavior is absent."
    return report, code


def cmd_seam(root: Path, spec):
    seam = spec.get("seam") or {}
    rows = {}
    native_present = False
    display_linked = False
    for key in ("nativeOptIn", "displayLink", "clientCalls"):
        row = seam.get(key) or {}
        rel = row.get("path")
        path = under_root(root, rel)
        pattern = compile_rule(key, row.get("regex"))
        item = {"path": str(rel).replace("\\", "/"), "present": False, "line": None, "status": "NO_FILE"}
        data = read_bytes(path)
        if data is None:
            rows[key] = item
            continue
        text = data.decode("utf-8", errors="replace")
        item["present"] = True
        line = first_line(text, pattern)
        item["line"] = line
        if key == "nativeOptIn":
            item["status"] = "PRESENT" if line else "ABSENT"
            native_present = line is not None
        elif key == "displayLink":
            item["status"] = "LINKED" if line else "ABSENT"
            display_linked = line is not None
        else:
            grounded = GROUNDED_CALL.search(text)
            if grounded:
                item["status"] = "GROUNDED_CALL"
                item["line"] = text[:grounded.start()].count("\n") + 1
            elif line:
                item["status"] = "DEFAULT_OFF"
            else:
                item["status"] = "NO_CALL"
        rows[key] = item
    if not native_present:
        status, code = "SEAM_BROKEN", 3
    elif not display_linked:
        status, code = "SEAM_OPEN", 4
    else:
        status, code = "SEAM_LINKED", 0
    report = base_report("seam", status)
    report["contract"] = spec.get("contract")
    report["rows"] = rows
    report["note"] = (
        "SEAM_OPEN means the native google_search body exists and the Display answer file "
        "does not reference webGrounding. This result is not a reason to add a second model pipeline."
    )
    return report, code


def classify_request(obj: dict):
    tools = obj.get("tools") if "tools" in obj else None
    notes = []
    if tools is None:
        return "TOOL_ABSENT", notes, {"toolCount": 0}
    if not isinstance(tools, list):
        return "TOOL_SHAPE", ["tools-not-list"], {"toolCount": 0}
    if not tools:
        return "TOOL_ABSENT", notes, {"toolCount": 0}
    kinds = []
    for tool in tools:
        if not isinstance(tool, dict):
            kinds.append("UNKNOWN")
            continue
        if tool.get("type") == "google_search":
            kinds.append("INTERACTIONS_COPY")
            continue
        snake = tool.get("google_search")
        camel = tool.get("googleSearch")
        if isinstance(snake, dict) and "type" not in tool:
            kinds.append("NATIVE_SNAKE")
            if snake:
                notes.append("snake-object-not-empty")
            continue
        if isinstance(camel, dict) and "type" not in tool:
            kinds.append("JS_CAMEL")
            if camel:
                notes.append("camel-object-not-empty")
            continue
        if "google_search_retrieval" in tool or "googleSearchRetrieval" in tool:
            kinds.append("LEGACY_RETRIEVAL")
            continue
        kinds.append("UNKNOWN")
    unique = set(kinds)
    if unique == {"NATIVE_SNAKE"}:
        shape = "ON_NATIVE_SNAKE"
    elif unique == {"JS_CAMEL"}:
        shape = "ON_JS_CAMEL"
    elif unique == {"INTERACTIONS_COPY"}:
        shape = "INTERACTIONS_COPY"
    elif unique == {"LEGACY_RETRIEVAL"}:
        shape = "LEGACY_RETRIEVAL"
    elif "INTERACTIONS_COPY" in unique or len(unique) > 1:
        shape = "MIXED"
    else:
        shape = "UNKNOWN_TOOL"
    return shape, notes, {"toolCount": len(tools)}


def text_present(candidate: dict) -> bool:
    content = candidate.get("content")
    if not isinstance(content, dict):
        return False
    parts = content.get("parts")
    if not isinstance(parts, list):
        return False
    for part in parts:
        if isinstance(part, dict) and isinstance(part.get("text"), str) and part.get("text").strip():
            return True
    return False


def classify_response(obj: dict):
    candidates = obj.get("candidates")
    counts = {
        "queryCount": 0,
        "chunkCount": 0,
        "supportCount": 0,
        "suggestionChars": 0,
        "textPresent": False,
    }
    if not isinstance(candidates, list) or not candidates or not isinstance(candidates[0], dict):
        return "NO_CANDIDATE", [], counts
    candidate = candidates[0]
    counts["textPresent"] = text_present(candidate)
    meta = candidate.get("groundingMetadata")
    if not isinstance(meta, dict):
        if counts["textPresent"] and isinstance(candidate.get("citationMetadata"), dict):
            return "CITATION_METADATA_ONLY", ["citation-metadata-is-not-search-complete"], counts
        if counts["textPresent"]:
            return "UNOBSERVED", ["metadata-absent-does-not-prove-search-never-ran"], counts
        return "EMPTY_OR_ERROR", [], counts
    queries = meta.get("webSearchQueries")
    chunks = meta.get("groundingChunks")
    supports = meta.get("groundingSupports")
    counts["queryCount"] = len(queries) if isinstance(queries, list) else 0
    if isinstance(chunks, list):
        counts["chunkCount"] = sum(
            1 for chunk in chunks if isinstance(chunk, dict) and isinstance(chunk.get("web"), dict)
        )
    counts["supportCount"] = len(supports) if isinstance(supports, list) else 0
    entry = meta.get("searchEntryPoint")
    rendered = entry.get("renderedContent") if isinstance(entry, dict) else None
    if isinstance(rendered, str):
        counts["suggestionChars"] = len(rendered)
    if counts["chunkCount"] and counts["supportCount"]:
        flags = ["LINKED"]
    elif counts["chunkCount"]:
        flags = ["CHUNKS_ONLY"]
    elif counts["queryCount"]:
        flags = ["QUERY_ONLY"]
    else:
        flags = ["METADATA_EMPTY"]
    flags.append("SUGGESTIONS_PRESENT" if counts["suggestionChars"] else "SUGGESTIONS_ABSENT")
    return "+".join(flags), [], counts


def expect_match(status: str, expect: str) -> bool:
    parts = set(status.split("+"))
    if expect == "OFF":
        return status == "TOOL_ABSENT"
    if expect == "SUGGESTIONS":
        return "SUGGESTIONS_PRESENT" in parts
    if expect == "LINKED":
        return "LINKED" in parts
    if expect == "QUERY_ONLY":
        return "QUERY_ONLY" in parts and "LINKED" not in parts
    return status == expect


def classify_document(obj):
    if not isinstance(obj, dict):
        raise AssistError("body-shape")
    if "candidates" in obj:
        status, notes, counts = classify_response(obj)
        return "response", status, notes, counts
    if "contents" in obj or "tools" in obj:
        status, notes, counts = classify_request(obj)
        return "request", status, notes, counts
    raise AssistError("body-kind")


def load_body(path: Path):
    raw = read_bytes(path)
    if raw is None:
        raise AssistError("body-missing")
    text = raw.decode("utf-8", errors="replace")
    if SECRET_VALUE.search(text):
        raise AssistError("secret-value-refused")
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise AssistError("body-unreadable") from exc


def cmd_body(root: Path, spec_path: Path, file_arg: str | None, fixture: str | None, expect: str | None):
    if bool(file_arg) == bool(fixture):
        raise AssistError("body-source")
    if expect is not None and expect not in EXPECT_REQUEST | EXPECT_RESPONSE:
        raise AssistError("expect-unknown")
    if fixture:
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,60}", fixture):
            raise AssistError("fixture-name")
        try:
            rel = (spec_path.resolve().parent.relative_to(root.resolve()) / "fixtures" / (fixture + ".json")).as_posix()
        except ValueError as exc:
            raise AssistError("spec-path") from exc
        path = under_root(root, rel)
    else:
        path = Path(file_arg)
        reject_name(path.name)
        if path.is_symlink():
            raise AssistError("symlink-refused")
    kind, status, notes, counts = classify_document(load_body(path))
    if expect is None:
        expect_status, code = "UNSET", 0
    elif expect_match(status, expect):
        expect_status, code = "MATCH", 0
    else:
        expect_status, code = "MISMATCH", 3
    report = base_report("body", status)
    report["kind"] = kind
    report["expect"] = expect
    report["expectStatus"] = expect_status
    report["counts"] = counts
    report["notes"] = notes
    report["fixture"] = fixture
    report["note"] = (
        "ON_NATIVE_SNAKE matches this repo serializer and the official generateContent curl body. "
        "ON_JS_CAMEL is the JS SDK shape. INTERACTIONS_COPY must stay off the generateContent body. "
        "UNOBSERVED keeps the answer text and does not prove that search never ran."
    )
    return report, code


def changed_lines(diff_text: str):
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
            yield "added", index, path, line[1:]
        elif line.startswith("-") and not line.startswith("---"):
            yield "removed", index, path, line[1:]
    if not saw_header:
        raise AssistError("invalid-diff")


def cmd_diff(spec, diff_text: str):
    rules = []
    for rule in spec.get("diffRules") or []:
        rules.append({
            "id": rule.get("id"),
            "side": rule.get("side", "added"),
            "path": compile_rule(rule.get("id"), rule.get("pathRegex")),
            "line": compile_rule(rule.get("id"), rule.get("lineRegex")),
            "allow": (
                compile_rule(rule.get("id"), rule["allowPathRegex"])
                if rule.get("allowPathRegex") else None
            ),
        })
    hits = []
    for side, diff_line, path, text in changed_lines(diff_text):
        if not path:
            continue
        for rule in rules:
            if rule["side"] != side:
                continue
            if rule["allow"] and rule["allow"].search(path):
                continue
            if rule["path"].search(path) and rule["line"].search(text):
                hits.append({"id": rule["id"], "path": path, "diffLine": diff_line, "side": side})
    status, code = ("DIFF_HIT", 3) if hits else ("DIFF_CLEAR", 0)
    report = base_report("diff-forbid", status)
    report["contract"] = spec.get("contract")
    report["hits"] = hits
    report["note"] = "A hit is a review signal. The matched source text is not copied."
    return report, code


def cmd_scan(root: Path, spec):
    pieces = {
        "pin": cmd_pin(root, spec),
        "gaps": phrase_command(root, spec, "gaps", "gaps", "GAP_OPEN", "GAP_CLOSED"),
        "cover": phrase_command(root, spec, "coverage", "cover", "SEARCH_GAP", "COVERED"),
        "seam": cmd_seam(root, spec),
    }
    codes = [code for _report, code in pieces.values()]
    if any(code == 3 for code in codes):
        status, code = "SCAN_GAP", 3
    elif any(code == 4 for code in codes):
        status, code = "SCAN_OPEN", 4
    else:
        status, code = "SCAN_CLEAR", 0
    report = base_report("scan", status)
    report["contract"] = spec.get("contract")
    report["parts"] = {name: piece[0]["status"] for name, piece in pieces.items()}
    report["detail"] = {name: piece[0] for name, piece in pieces.items()}
    report["note"] = "SCAN_OPEN means pin, gaps, cover, or seam still has an open list. Exit 0 is not a product PASS."
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


def resolve_spec(root: Path, spec_arg: str) -> Path:
    spec_path = Path(spec_arg)
    if not spec_path.is_absolute():
        return under_root(root, spec_arg)
    reject_name(spec_path.name)
    return spec_path


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description="Read-only Display Gemini grounding assist")
    sub = parser.add_subparsers(dest="cmd")

    def add_common(command):
        command.add_argument("--root", default=".")
        command.add_argument("--spec", default=DEFAULT_SPEC)
        command.add_argument("--out")
        return command

    add_common(sub.add_parser("pin"))
    add_common(sub.add_parser("gaps"))
    add_common(sub.add_parser("cover"))
    add_common(sub.add_parser("seam"))
    add_common(sub.add_parser("scan"))
    body = add_common(sub.add_parser("body"))
    body.add_argument("--file")
    body.add_argument("--fixture")
    body.add_argument("--expect")
    diff = add_common(sub.add_parser("diff-forbid"))
    diff.add_argument("--diff", required=True)
    args = parser.parse_args(argv)
    known = {"pin", "gaps", "cover", "seam", "scan", "body", "diff-forbid"}
    if args.cmd not in known:
        print(json.dumps({
            "schemaVersion": SCHEMA,
            "status": "error",
            "reason": "usage",
            "productPass": False,
        }))
        return 2
    try:
        root = Path(args.root).resolve()
        spec_path = resolve_spec(root, args.spec)
        spec = load_spec(spec_path)
        if args.cmd == "pin":
            report, code = cmd_pin(root, spec)
        elif args.cmd == "gaps":
            report, code = phrase_command(root, spec, "gaps", "gaps", "GAP_OPEN", "GAP_CLOSED")
        elif args.cmd == "cover":
            report, code = phrase_command(root, spec, "coverage", "cover", "SEARCH_GAP", "COVERED")
        elif args.cmd == "seam":
            report, code = cmd_seam(root, spec)
        elif args.cmd == "scan":
            report, code = cmd_scan(root, spec)
        elif args.cmd == "body":
            report, code = cmd_body(root, spec_path, args.file, args.fixture, args.expect)
        else:
            diff_path = Path(args.diff)
            reject_name(diff_path.name)
            if diff_path.is_symlink():
                raise AssistError("symlink-refused")
            data = read_bytes(diff_path)
            if data is None:
                raise AssistError("diff-missing")
            report, code = cmd_diff(spec, data.decode("utf-8", errors="replace"))
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
