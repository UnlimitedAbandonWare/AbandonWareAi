"""Read-only assist for Display search capability fallback.

pin, cover, and diff-forbid delegate to pair_brief_assist.
gap checks the landed effective-fallback shape and the timeout mapper.
A clean exit is a scan result, not a product PASS.
No network, Gradle, server, or product write.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pair_brief_assist as scan

SPEC = "var/codex-assist-display-search-capability-20261006/spec.json"
COMMANDS = ("pin", "cover", "diff-forbid", "gap")
SETTINGS = "main/java/com/example/lms/assist/NovaFocusSettings.java"
ANSWER = "main/java/com/example/lms/assist/NovaFocusAnswerService.java"
CONTROLS = "main/resources/static/assets/display/display-focus-controls.js"
GATEWAY = "main/java/com/example/lms/learning/gemini/GeminiGateway.java"


def emit(report):
    print(json.dumps(report, ensure_ascii=False, indent=2))


def read_rel(root: Path, rel: str):
    path = scan.under_root(root, rel)
    data = scan.read_text(path)
    if data is None:
        return None
    return data.decode("utf-8", errors="replace")


def line_of(text: str, needle: str):
    for index, line in enumerate(text.splitlines(), start=1):
        if needle in line:
            return index
    return None


def timeout_signal(text: str):
    lines = text.splitlines()
    for index, line in enumerate(lines, start=1):
        if 'contains("timeout")' not in line:
            continue
        start = max(0, index - 8)
        window = "\n".join(lines[start:index])
        aware = "errorClass" in window or "TimeoutException" in window
        return {
            "id": "timeout-map",
            "status": "CLASS_AWARE" if aware else "REASON_ONLY",
            "line": index,
        }
    return {"id": "timeout-map", "status": "MAP_MISSING", "line": None}


def marker(text, rel, rule_id, needle, present_status):
    if text is None:
        return {"id": rule_id, "path": rel, "status": "FILE_MISSING", "line": None}
    found = line_of(text, needle)
    return {
        "id": rule_id,
        "path": rel,
        "status": present_status if found else "ABSENT",
        "line": found,
    }


def cmd_gap(root: Path):
    settings = read_rel(root, SETTINGS)
    answer = read_rel(root, ANSWER)
    controls = read_rel(root, CONTROLS)
    gateway = read_rel(root, GATEWAY)
    signals = [
        marker(settings, SETTINGS, "effective-getter", "effectiveFallbackAllowed()", "PRESENT"),
        marker(settings, SETTINGS, "effective-read-only", "JsonProperty.Access.READ_ONLY", "PRESENT"),
        marker(settings, SETTINGS, "single-enum",
               "enum ExecutionTarget { AUTO,API_ONLY,LOCAL_ONLY,GEMINI_WEBSEARCH_ONLY }", "PRESENT"),
        marker(answer, ANSWER, "execute-uses-effective", "policy.effectiveFallbackAllowed()", "PRESENT"),
        marker(controls, CONTROLS, "ui-raw-preserved", "exclusive?fallbackPreference", "PRESENT"),
        marker(controls, CONTROLS, "ui-effective-off-copy", "모델 자동 전환 OFF", "PRESENT"),
    ]
    if gateway is None:
        signals.append({"id": "timeout-map", "path": GATEWAY, "status": "FILE_MISSING", "line": None})
    else:
        row = timeout_signal(gateway)
        row["path"] = GATEWAY
        signals.append(row)
    if settings is not None and settings.count("enum ExecutionTarget") > 1:
        signals.append({
            "id": "second-enum",
            "path": SETTINGS,
            "status": "HIT",
            "line": line_of(settings, "enum ExecutionTarget"),
        })
    open_gap = any(row["status"] in ("ABSENT", "REASON_ONLY", "MAP_MISSING", "FILE_MISSING", "HIT")
                   for row in signals)
    report = scan.base_report("gap", "GAP_OPEN" if open_gap else "GAP_CLEAR")
    report["signals"] = signals
    report["note"] = (
        "GAP_CLEAR means these markers are present. It does not mean A1-A9 passed."
    )
    return report, (4 if open_gap else 0)


def main(argv=None):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    args = list(sys.argv[1:] if argv is None else argv)
    if not args or args[0] not in COMMANDS:
        emit({
            "schemaVersion": scan.SCHEMA,
            "status": "error",
            "reason": "usage",
            "productPass": False,
        })
        return 2
    if args[0] == "gap":
        root = Path(".")
        if "--root" in args:
            root = Path(args[args.index("--root") + 1])
        try:
            report, code = cmd_gap(root.resolve())
        except scan.AssistError as exc:
            emit({
                "schemaVersion": scan.SCHEMA,
                "status": "error",
                "reason": exc.reason,
                "productPass": False,
            })
            return 2
        emit(report)
        return code
    if "--spec" not in args:
        args.extend(["--spec", SPEC])
    return scan.main(args)


if __name__ == "__main__":
    sys.exit(main())
