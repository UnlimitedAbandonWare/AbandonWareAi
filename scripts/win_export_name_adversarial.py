#!/usr/bin/env python3
"""Adversarial checks for chat_session_debug_export.safe_export_name.

Real import runs only after the astra lane has a final report. Until then
--run writes PENDING. --self-test uses one safe fake and one dangerous fake.
The markdown report stores case ids, lengths, and export names. Raw case
values stay in CASES and in export-name-repro.json. --repro compares the
case-lower and trail-space inputs on the live function.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

EXPORT_RE = __import__("re").compile(r"^export-[0-9a-f]{16}$")
RESERVED = {"CON", "PRN", "AUX", "NUL"}
RESERVED.update("COM%d" % i for i in range(1, 10))
RESERVED.update("LPT%d" % i for i in range(1, 10))
FINAL_NAMES = {"final-report.md", "final-report.json", "final_report.md", "FINAL.md"}
CASES = (
    ("reserved-con", "CON"),
    ("reserved-nul", "NUL"),
    ("reserved-prn", "PRN"),
    ("reserved-aux", "AUX"),
    ("reserved-com1", "COM1"),
    ("reserved-lpt1", "LPT1"),
    ("reserved-con-txt", "con.txt"),
    ("trail-dot", "abc."),
    ("trail-space", "abc "),
    ("path-dotdot-win", "..\\"),
    ("path-dotdot-posix", "../"),
    ("path-drive", "C:\\x"),
    ("path-unc", "\\\\server\\share"),
    ("path-ads", "a:b"),
    ("len-255", "a" * 255),
    ("len-300", "b" * 300),
    ("hangul", "한글이름"),
    ("emoji", "emoji-\U0001F600"),
    ("ctrl-nul", "a\x00b"),
    ("ctrl-tab", "a\tb"),
    ("case-upper", "ABC"),
    ("case-lower", "abc"),
    ("uuid", "123e4567-e89b-12d3-a456-426614174000"),
    ("client-token", "clientToken-abc123"),
)


def fragment_hit(name, raw):
    if not name or len(raw) < 3:
        return False
    cap = min(len(raw), 24)
    for start in range(len(raw) - 2):
        stop = min(len(raw), start + cap)
        for end in range(start + 3, stop + 1):
            if raw[start:end] in name:
                return True
    return False


def windows_bad(name):
    if not isinstance(name, str) or not name:
        return True
    if name[-1] in " .":
        return True
    base = name.replace("/", "\\").split("\\")[-1]
    stem = base.split(".")[0]
    return stem.upper() in RESERVED or base.upper() in RESERVED


def call_name(fn, raw):
    try:
        return fn(raw, None)
    except TypeError:
        return fn(raw)


def evaluate(fn, cases=CASES):
    rows = []
    produced = []
    for case_id, raw in cases:
        row = {"id": case_id, "a": "FAIL", "b": "FAIL", "c": "FAIL", "d": "FAIL", "e": "FAIL"}
        try:
            first = call_name(fn, raw)
            second = call_name(fn, raw)
        except Exception as exc:
            row["error"] = type(exc).__name__
            rows.append(row)
            continue
        name = first if isinstance(first, str) else ""
        row["a"] = "PASS" if isinstance(name, str) and EXPORT_RE.fullmatch(name) else "FAIL"
        row["b"] = "PASS" if first == second else "FAIL"
        row["d"] = "PASS" if isinstance(name, str) and not fragment_hit(name, raw) else "FAIL"
        row["e"] = "PASS" if isinstance(name, str) and not windows_bad(name) else "FAIL"
        rows.append(row)
        produced.append((case_id, name if isinstance(name, str) else None))
    groups = {}
    for case_id, name in produced:
        if name is None:
            continue
        groups.setdefault(name, []).append(case_id)
    collision_ids = set()
    for ids in groups.values():
        if len(ids) > 1:
            collision_ids.update(ids)
    for row in rows:
        if row.get("error"):
            continue
        row["c"] = "FAIL" if row["id"] in collision_ids else "PASS"
    ok = bool(rows) and all(
        row.get(key) == "PASS" for row in rows for key in ("a", "b", "c", "d", "e")
    )
    return {"ok": ok, "rows": rows, "collisionIds": sorted(collision_ids)}


def input_stage(raw):
    text = raw if isinstance(raw, str) else ""
    stripped = text.strip()
    return {
        "rawLen": len(text),
        "strippedLen": len(stripped),
        "leadingWs": len(text) - len(text.lstrip()),
        "trailingWs": len(text) - len(text.rstrip()),
        "stripChanged": text != stripped,
    }


def pair_cause(raws, same_name):
    if not same_name:
        return "distinct"
    if len(set(raws)) > 1 and len({item.strip() for item in raws}) == 1:
        return "strip-before-hash"
    if len(set(raws)) > 1 and len({item.casefold() for item in raws}) == 1:
        return "casefold-before-hash"
    return "same-export-name"


def diagnose(fn, cases=CASES):
    result = evaluate(fn, cases)
    by_id = {case_id: raw for case_id, raw in cases}
    names = {}
    stages = {}
    for case_id, raw in cases:
        stage = input_stage(raw)
        stage["id"] = case_id
        try:
            name = call_name(fn, raw)
        except Exception as exc:
            stage["error"] = type(exc).__name__
            stage["exportName"] = ""
            stages[case_id] = stage
            continue
        name = name if isinstance(name, str) else ""
        names[case_id] = name
        stage["exportName"] = name
        stages[case_id] = stage
    groups = {}
    for case_id, name in names.items():
        if name:
            groups.setdefault(name, []).append(case_id)
    pairs = []
    for name, ids in groups.items():
        if len(ids) < 2:
            continue
        raws = [by_id[case_id] for case_id in ids]
        pairs.append({
            "ids": ids,
            "exportName": name,
            "cause": pair_cause(raws, True),
            "stages": [stages[case_id] for case_id in ids],
        })
    causes = sorted({pair["cause"] for pair in pairs})
    result["pairs"] = pairs
    result["cause"] = causes[0] if len(causes) == 1 else ("mixed" if causes else "none")
    return result


def reproduce_pair(fn, left_id="case-lower", right_id="trail-space", cases=CASES):
    table = dict(cases)
    left = table[left_id]
    right = table[right_id]
    left_name = call_name(fn, left)
    right_name = call_name(fn, right)
    same = left_name == right_name
    return {
        "leftId": left_id,
        "rightId": right_id,
        "sameName": same,
        "cause": pair_cause([left, right], same),
        "leftName": left_name if isinstance(left_name, str) else "",
        "rightName": right_name if isinstance(right_name, str) else "",
        "leftStage": input_stage(left),
        "rightStage": input_stage(right),
    }


def anchor_lines(root):
    found = {"hash12": None, "query_identity": None, "safe_export_name": None}
    path = Path(root) / "scripts" / "chat_session_debug_export.py"
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return found
    for index, line in enumerate(lines, 1):
        for name in found:
            if line.startswith("def %s(" % name):
                found[name] = index
    return found


def render_report(mode, result, detail, anchors=None):
    lines = [
        "# win export name adversarial",
        "mode: %s" % mode,
        "result: %s" % result,
        "detail: %s" % detail,
        "caseCount: %d" % len(CASES),
        "caseIds: %s" % ",".join(case_id for case_id, _raw in CASES),
        "",
    ]
    rows = detail if isinstance(detail, dict) else None
    table = rows.get("rows") if isinstance(rows, dict) else None
    if table:
        lines.append("| id | a | b | c | d | e |")
        lines.append("| --- | --- | --- | --- | --- | --- |")
        for row in table:
            lines.append("| %s | %s | %s | %s | %s | %s |" % (
                row["id"], row["a"], row["b"], row["c"], row["d"], row["e"]))
        lines.append("")
        lines.append("collisions: %s" % ",".join(rows.get("collisionIds") or []) or "0")
        if rows.get("collisionIds"):
            lines.append("cause: %s" % (rows.get("cause") or "same-export-name"))
            lines.append(
                "mechanism: query_identity strips, hash12 strips again, "
                "safe_export_name hashes only the 12-hex canonical"
            )
            if isinstance(anchors, dict):
                lines.append(
                    "anchor: scripts/chat_session_debug_export.py hash12=%s query_identity=%s safe_export_name=%s"
                    % (anchors.get("hash12"), anchors.get("query_identity"), anchors.get("safe_export_name"))
                )
            lines.append("repro: python -B scripts/win_export_name_adversarial.py --repro")
            lines.append("")
            lines.append("| id | rawLen | strippedLen | trailingWs | stripChanged | exportName |")
            lines.append("| --- | --- | --- | --- | --- | --- |")
            seen = set()
            for pair in rows.get("pairs") or []:
                for stage in pair.get("stages") or []:
                    if stage.get("id") in seen:
                        continue
                    seen.add(stage.get("id"))
                    lines.append("| %s | %s | %s | %s | %s | %s |" % (
                        stage.get("id"), stage.get("rawLen"), stage.get("strippedLen"),
                        stage.get("trailingWs"), stage.get("stripChanged"), stage.get("exportName")))
    return "\n".join(lines).rstrip() + "\n"


def journal_is_final(path):
    try:
        doc = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return False
    if not isinstance(doc, dict):
        return False
    events = doc.get("events") if isinstance(doc.get("events"), list) else []
    has_report = any(isinstance(event, dict) and event.get("kind") == "report" for event in events)
    return doc.get("status") == "closed" and has_report


def astra_final_ready(root):
    root = Path(root)
    ledgers = [root / "data/agent-handoff/codex-session-jsonl-28bf623e"]
    auto = root / "data/agent-handoff/codex-autonomy"
    if auto.is_dir():
        ledgers.extend(path for path in auto.glob("codex-session-jsonl-*") if path.is_dir())
    for ledger in ledgers:
        if not ledger.is_dir():
            continue
        for path in ledger.rglob("*"):
            if not path.is_file():
                continue
            if path.name in FINAL_NAMES:
                return True
            if path.name == "journal.json" and journal_is_final(path):
                return True
    return False


def load_safe_export_name(root):
    path = Path(root) / "scripts" / "chat_session_debug_export.py"
    spec = importlib.util.spec_from_file_location("chat_session_debug_export_live", path)
    if spec is None or spec.loader is None:
        raise ImportError("missing-loader")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    fn = getattr(module, "safe_export_name", None)
    if not callable(fn):
        raise ImportError("missing-safe-export-name")
    return fn


def safe_fake(query, matches=None):
    digest = hashlib.sha256(b"s1\0" + query.encode("utf-8", "surrogateescape")).hexdigest()[:16]
    return "export-" + digest


def strip_fake(query, matches=None):
    digest = hashlib.sha256(query.strip().encode("utf-8", "surrogateescape")).hexdigest()[:16]
    return "export-" + digest


def danger_fake(query, matches=None):
    return query


def write_report(path, text):
    dest = Path(path)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(text, encoding="utf-8", newline="\n")


def write_repro_fixture(path, diagnosis, anchors):
    table = dict(CASES)
    ids = list(diagnosis.get("collisionIds") or [])
    payload = {
        "schema": "awx.export-name-repro.v1",
        "cause": diagnosis.get("cause"),
        "collisionIds": ids,
        "anchors": anchors,
        "repro": "python -B scripts/win_export_name_adversarial.py --repro",
        "inputs": [{"id": case_id, "value": table[case_id]} for case_id in ids if case_id in table],
    }
    write_report(path, json.dumps(payload, ensure_ascii=False, indent=2) + "\n")


def self_test():
    safe = evaluate(safe_fake)
    danger = evaluate(danger_fake)
    report = render_report("self-test", "PASS" if safe["ok"] and not danger["ok"] else "FAIL", safe)
    danger_report = render_report("self-test-danger", "FAIL" if not danger["ok"] else "PASS", danger)
    leaked = False
    for _case_id, raw in CASES:
        if len(raw) >= 3 and raw in report:
            leaked = True
            break
    bug = reproduce_pair(strip_fake)
    healthy = reproduce_pair(safe_fake)
    diag = diagnose(strip_fake)
    for pair in diag.get("pairs") or []:
        pair["exportName"] = "export-0000000000000000"
        for stage in pair.get("stages") or []:
            stage["exportName"] = "export-0000000000000000"
    diag_text = render_report(
        "self-test-diag", "FAIL", diag,
        {"hash12": 1, "query_identity": 2, "safe_export_name": 3})
    leaked_diag = any(len(raw) >= 3 and raw in diag_text for _case_id, raw in CASES)
    if (not safe["ok"]) or danger["ok"] or leaked or "한글이름" in danger_report:
        print("self-test FAIL safe=%s danger_caught=%s leaked=%s" % (
            safe["ok"], not danger["ok"], leaked))
        return 1
    if (not bug["sameName"]) or bug["cause"] != "strip-before-hash" or healthy["sameName"]:
        print("self-test FAIL repro")
        return 1
    if ("cause: strip-before-hash" not in diag_text
            or "repro: python -B scripts/win_export_name_adversarial.py --repro" not in diag_text
            or leaked_diag):
        print("self-test FAIL diag")
        return 1
    print("self-test PASS safe=PASS danger=FAIL")
    return 0


def run_real(root, out):
    root = Path(root)
    if not astra_final_ready(root):
        text = render_report("run", "PENDING", "astra-final-report-absent")
        write_report(out, text)
        print("PENDING")
        return 0
    try:
        fn = load_safe_export_name(root)
        anchors = anchor_lines(root)
        result = diagnose(fn)
    except Exception as exc:
        text = render_report("run", "FAIL", "import-" + type(exc).__name__)
        write_report(out, text)
        print("FAIL")
        return 1
    status = "PASS" if result["ok"] else "FAIL"
    write_report(out, render_report("run", status, result, anchors))
    if result.get("collisionIds"):
        write_repro_fixture(Path(out).parent / "export-name-repro.json", result, anchors)
    print(status)
    return 0 if result["ok"] else 1


def run_repro(root):
    root = Path(root)
    try:
        fn = load_safe_export_name(root)
        pair = reproduce_pair(fn)
        anchors = anchor_lines(root)
    except Exception as exc:
        print(json.dumps({"schema": "awx.export-name-repro.v1", "error": type(exc).__name__}))
        return 2
    table = dict(CASES)
    payload = {
        "schema": "awx.export-name-repro.v1",
        "cause": pair["cause"],
        "sameName": pair["sameName"],
        "leftId": pair["leftId"],
        "rightId": pair["rightId"],
        "leftName": pair["leftName"],
        "rightName": pair["rightName"],
        "leftStage": pair["leftStage"],
        "rightStage": pair["rightStage"],
        "anchors": anchors,
        "inputs": [
            {"id": pair["leftId"], "value": table[pair["leftId"]]},
            {"id": pair["rightId"], "value": table[pair["rightId"]]},
        ],
    }
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    return 1 if pair["sameName"] else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description="Windows export-name adversarial probe")
    parser.add_argument("--root", default=".")
    parser.add_argument("--out", default="var/codex-assist-grok-session-context/export-name-report.md")
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--repro", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    root = Path(args.root).resolve()
    if args.repro:
        return run_repro(root)
    out = Path(args.out)
    if not out.is_absolute():
        out = root / out
    return run_real(root, out)


if __name__ == "__main__":
    sys.exit(main())
