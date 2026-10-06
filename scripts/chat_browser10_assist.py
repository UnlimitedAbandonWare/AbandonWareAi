"""Read-only assist for the main /chat 10-turn regression brief.

pin, cover, and diff-forbid delegate to pair_brief_assist.
score checks a redacted scorecard shape. It does not open a browser.
A clean exit is a scan result, not a product PASS.
No network, Gradle, server, or product write.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pair_brief_assist as scan

SPEC = "var/codex-assist-chat-browser10-20261006/spec.json"
CARD = "var/codex-assist-chat-browser10-20261006/scorecard.json"
COMMANDS = ("pin", "cover", "diff-forbid", "score")
CONTRACT = "DEMO1-CHAT-BROWSER-10TURN-REGRESSION-20261006"
VERDICTS = {"PASS", "FAIL", "PARTIAL", "NOT_RUN", "HOLD"}
BLOCKED_KEYS = (
    "author" + "ization",
    "coo" + "kie",
    "run" + "Token",
    "access" + "Token",
    "refresh" + "Token",
    "csrf" + "Token",
    "bea" + "rer",
    "answer",
    "responseText",
    "promptBody",
    "rawTrace",
    "har",
)


def emit(report):
    print(json.dumps(report, ensure_ascii=False, indent=2))


def contract_body(spec):
    body = (spec.get("contracts") or {}).get(CONTRACT)
    if not isinstance(body, dict):
        raise scan.AssistError("spec-shape")
    return body


def score_spec(spec):
    row = contract_body(spec).get("scorecard")
    if not isinstance(row, dict):
        raise scan.AssistError("spec-shape")
    cases = row.get("cases")
    search = row.get("searchCases")
    sides = row.get("sideCases")
    if not isinstance(cases, list) or not isinstance(search, list) or not isinstance(sides, list):
        raise scan.AssistError("spec-shape")
    return row


def blocked_key(name):
    folded = str(name).casefold()
    return any(folded == item.casefold() for item in BLOCKED_KEYS)


def walk_keys(value, found):
    if isinstance(value, dict):
        for key, item in value.items():
            if blocked_key(key):
                found.append(str(key))
            walk_keys(item, found)
    elif isinstance(value, list):
        for item in value:
            walk_keys(item, found)


def case_map(card):
    rows = card.get("cases")
    if not isinstance(rows, list):
        raise scan.AssistError("card-shape")
    found = {}
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("id"), str):
            raise scan.AssistError("card-shape")
        found[row["id"]] = row
    return found


def expect_equal(row, field, expected, problems, case_id):
    if field not in row:
        problems.append({"id": case_id, "reason": "missing-" + field})
        return
    if row.get(field) != expected:
        problems.append({"id": case_id, "reason": "mismatch-" + field})


def judge_card(spec, card):
    rules = score_spec(spec)
    if not isinstance(card, dict):
        raise scan.AssistError("card-shape")
    blocked = []
    walk_keys(card, blocked)
    if blocked:
        report = scan.base_report("score", "CARD_SECRET")
        report["blockedKeys"] = sorted(set(blocked))
        report["note"] = "Remove the named keys. Values were not copied."
        return report, 3
    cases = case_map(card)
    problems = []
    recorded_fail = False
    partial = False
    for case_id in rules["cases"]:
        row = cases.get(case_id)
        if row is None:
            partial = True
            problems.append({"id": case_id, "reason": "missing-case"})
            continue
        verdict = row.get("verdict")
        if verdict not in VERDICTS:
            raise scan.AssistError("card-shape")
        if verdict == "FAIL":
            recorded_fail = True
        if verdict != "PASS":
            partial = True
        if verdict == "PASS" and case_id in rules["searchCases"] and row.get("linkage") != "PROVEN":
            problems.append({"id": case_id, "reason": "search-pass-without-linkage"})
        if row.get("durableMemoryWrite") == "PASS":
            problems.append({"id": case_id, "reason": "durable-memory-pass"})
        if row.get("rescueCountedAsOriginal") is True:
            problems.append({"id": case_id, "reason": "rescue-counted-as-original"})
        if case_id == "T03" and verdict == "PASS" and row.get("heldForZeroEvidence") is True:
            problems.append({"id": case_id, "reason": "general-knowledge-held"})
    t10 = rules.get("t10") or {}
    t02 = rules.get("t02") or {}
    if cases.get("T10", {}).get("verdict") == "PASS":
        for field, expected in t10.items():
            expect_equal(cases["T10"], field, expected, problems, "T10")
    if cases.get("T02", {}).get("verdict") == "PASS":
        for field, expected in t02.items():
            if field == "recalledColorBefore" and field not in cases["T02"]:
                continue
            expect_equal(cases["T02"], field, expected, problems, "T02")
    fresh = cases.get("NEW_SESSION")
    if fresh and fresh.get("verdict") == "PASS" and fresh.get("leaked") is not False:
        problems.append({"id": "NEW_SESSION", "reason": "leak-not-disproved"})
    if card.get("actualOAuth") != "PROVEN" or card.get("ordinaryUserAccess") != "PROVEN":
        partial = True
    surface = str(card.get("surface") or "")
    if "abandonwareai.kro.kr" in surface and card.get("publicCapException") is not True:
        problems.append({"id": "surface", "reason": "public-cap"})
    if card.get("overallVerdict") == "PASS" and (partial or problems):
        problems.append({"id": "overall", "reason": "overall-pass-too-early"})
    if problems:
        report = scan.base_report("score", "CARD_CONTRADICTION")
        report["problems"] = problems
        report["note"] = "A contradiction is a scorecard shape error. It is not a product FAIL."
        return report, 3
    if recorded_fail:
        status, code = "CARD_FAIL", 4
    elif partial or "127.0.0.1" not in surface or "/chat" not in surface:
        status, code = "CARD_PARTIAL", 4
    else:
        status, code = "CARD_SHAPE_OK", 0
    report = scan.base_report("score", status)
    report["caseCount"] = len(rules["cases"])
    report["note"] = (
        "CARD_SHAPE_OK means the redacted card is complete. This process did not open a browser."
    )
    return report, code


def cmd_score(root: Path, card_rel: str):
    spec = scan.load_spec(scan.under_root(root, SPEC) if not Path(SPEC).is_absolute() else Path(SPEC))
    path = scan.under_root(root, card_rel)
    data = scan.read_text(path)
    if data is None:
        report = scan.base_report("score", "CARD_ABSENT")
        report["card"] = card_rel.replace("\\", "/")
        report["note"] = "No scorecard yet. Absence is NOT_RUN, not a product FAIL."
        return report, 4
    try:
        card = json.loads(data.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise scan.AssistError("card-unreadable") from exc
    return judge_card(spec, card)


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
    if args[0] == "score":
        root = Path(".")
        card = CARD
        if "--root" in args:
            root = Path(args[args.index("--root") + 1])
        if "--card" in args:
            card = args[args.index("--card") + 1]
        try:
            report, code = cmd_score(root.resolve(), card)
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
