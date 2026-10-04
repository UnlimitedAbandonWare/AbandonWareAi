#!/usr/bin/env python3
"""One-shot local astra review; without --run only records PENDING."""
from __future__ import annotations
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
LEDGER = "data/agent-handoff/codex-session-jsonl-28bf623e"
ASTRA = ("scripts/chat_session_debug_export.py", "scripts/test_chat_session_debug_export.py",
         "scripts/query_flow_notepad_bundle.py", "scripts/test_query_flow_notepad_bundle.py",
         ".agents/skills/demo1-chat-session-debug")
TEST_COMMAND = [sys.executable, "-B", "-m", "unittest", "scripts.test_chat_session_debug_export",
                "scripts.test_query_flow_notepad_bundle"]


def compare(baseline, root=ROOT):
    rows = []
    for item in baseline["entries"]:
        path = root / item["path"]
        if path.is_dir():
            files = [{"path": p.relative_to(root).as_posix(), "size": p.stat().st_size,
                      "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                     for p in sorted(path.rglob("*")) if p.is_file() and "__pycache__" not in p.parts]
            current = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
        else:
            current = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
        changed = current != item["sha256"]
        allowed = any(item["path"] == p or item["path"].startswith(p + "/") for p in ASTRA)
        rows.append({"path": item["path"], "changed": changed, "allowedAstra": allowed,
                     "attribution": "astra 아님 가능" if changed and not allowed else "not_attributed",
                     "baselineSha256": item["sha256"], "currentSha256": current})
    return {"status": "PASS", "rows": rows, "coverage": "baseline 13 paths only",
            "outsideCoverage": "NOT_RUN; hash change alone cannot attribute an editor"}


def report_contract(path: Path, ledger: Path):
    path.resolve().relative_to(ledger.resolve())
    if path.is_symlink() or path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("unsafe-report")
    text = path.read_text(encoding="utf-8-sig")
    first = text.splitlines()[0] if text.splitlines() else ""
    header = first.startswith("외부 API:")
    acceptance = bool(re.search(r"(?im)Acceptance", text) and
                      re.search(r"(?m)^\s*\|[^\n]*\|", text))
    return {"status": "PASS" if header and acceptance else "FAIL",
            "externalApiFirstLine": header, "acceptanceTable": acceptance,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def output_path(value):
    path = Path(os.path.abspath(ROOT / value))
    path.relative_to(ROOT / "var")
    if path == ROOT / "var":
        raise ValueError("var-root-forbidden")
    for parent in (path, *path.parents):
        if parent.is_symlink() or (parent.exists() and getattr(parent.lstat(), "st_file_attributes", 0) & 1024):
            raise ValueError("reparse-output")
    return path


def claim_once(path: Path, report_hash: str):
    # Reserve before test/export. A timeout/failure consumes the attempt, too.
    with path.open("x", encoding="utf-8") as f:
        json.dump({"schema": "awx.session-jsonl-review-once.v1", "reportSha256": report_hash}, f)


def run_command(command, cwd=ROOT, timeout=120):
    try:
        proc = subprocess.run(command, cwd=cwd, capture_output=True, timeout=timeout)
        combined = proc.stdout + proc.stderr
        summary = re.search(rb"Ran (\d+) tests?", combined)
        return {"status": "PASS" if proc.returncode == 0 else "FAIL",
                "exitCode": proc.returncode, "tests": int(summary.group(1)) if summary else None,
                "outputBytes": len(combined), "outputSha256": hashlib.sha256(combined).hexdigest()}
    except subprocess.TimeoutExpired:
        return {"status": "FAIL", "reason": "timeout-no-retry"}
    except OSError:
        return {"status": "FAIL", "reason": "command-start-failed-no-retry"}


def export_option(source):
    tree = ast.parse(source)
    options = []
    export_parsers = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign) and isinstance(node.value, ast.Call):
            call = node.value
            if isinstance(call.func, ast.Attribute) and call.func.attr == "add_parser" and call.args:
                if isinstance(call.args[0], ast.Constant) and call.args[0].value == "export":
                    export_parsers.update(t.id for t in node.targets if isinstance(t, ast.Name))
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr == "add_argument":
            strings = [a.value for a in node.args if isinstance(a, ast.Constant) and isinstance(a.value, str)]
            for value in strings:
                if value in ("--out", "--output-dir") and isinstance(node.func.value, ast.Name):
                    options.append((value, node.func.value.id in export_parsers))
    return options[0] if len(options) == 1 else None


def fixture_export(out: Path, source: str):
    option = export_option(source)
    if option is None:
        return {"status": "NOT_RUN", "reason": "no-unambiguous-export-output-option"}
    from chat_trace_fixture_synth import generate
    from chat_export_leak_scan import scan, failed
    synthetic = out.parent / "review-fixture"
    generate(synthetic, 7)
    destination = out.parent / "review-export"
    command = [sys.executable, "-B", str(ROOT / "scripts/chat_session_debug_export.py"), "--root", str(synthetic)]
    flag, after_command = option
    if not after_command:
        command += [flag, str(destination)]
    command += ["export", "CANARY-RAW-QUERY-7"]
    if after_command:
        command += [flag, str(destination)]
    exported = run_command(command)
    if exported["status"] != "PASS":
        return {"status": "FAIL", "export": exported}
    scanned = scan(destination, canary="CANARY-RAW-QUERY-7")
    return {"status": "FAIL" if failed(scanned) else "PASS", "export": exported, "leakScan": scanned}


def aggregate(checks):
    if any(value["status"] == "FAIL" for value in checks.values()):
        return "FAIL"
    if any(value["status"] == "NOT_RUN" for value in checks.values()):
        return "PARTIAL"
    return "PASS"


def write_review(path: Path, result):
    lines = ["# Session JSONL result review", "", f"Status: {result['status']}", "",
             "Item | Status | Evidence", "--- | --- | ---"]
    for key, value in result.get("checks", {}).items():
        lines.append(f"{key} | {value['status']} | {value.get('reason', 'see review-result.json')}")
    if result["status"] == "PENDING":
        lines += ["", "No astra tests/export were executed.",
                  "Exact ledger/report required; never choose the newest directory.",
                  "Resume: astra 끝났어, S6 채점만 1회 돌리고 review.md 갱신해"]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


class ReviewTests(unittest.TestCase):
    def test_not_run_is_partial(self):
        self.assertEqual(aggregate({"a": {"status": "PASS"}, "c": {"status": "NOT_RUN"}}), "PARTIAL")
        self.assertEqual(aggregate({"a": {"status": "FAIL"}, "c": {"status": "NOT_RUN"}}), "FAIL")
        self.assertEqual(aggregate({"a": {"status": "PASS"}}), "PASS")
    def test_report_gate_and_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report = root / "report.md"
            report.write_text("외부 API: 0회\nAcceptance\n| A1 | PASS |\n", encoding="utf-8")
            self.assertEqual(report_contract(report, root)["status"], "PASS")
            report.write_text("bad\nAcceptance\n| A1 | PASS |\n", encoding="utf-8")
            self.assertEqual(report_contract(report, root)["status"], "FAIL")
            with self.assertRaises(ValueError):
                report_contract(report, root / "different")
            marker = root / "review-run.json"
            claim_once(marker, "a" * 64)
            with self.assertRaises(FileExistsError):
                claim_once(marker, "a" * 64)
    def test_comparison_and_option(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            p = root / "other.py"
            p.write_bytes(b"new")
            result = compare({"entries": [{"path": "other.py", "sha256": "old"}]}, root)
            self.assertTrue(result["rows"][0]["changed"])
            self.assertEqual(result["rows"][0]["attribution"], "astra 아님 가능")
        self.assertIsNone(export_option('p.add_argument("--root")'))
        self.assertEqual(export_option('e=sub.add_parser("export")\ne.add_argument("--out")'), ("--out", True))


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--baseline", type=Path, default=ROOT / "var/codex-assist-session-context/baseline.json")
    p.add_argument("--ledger", type=Path, default=ROOT / LEDGER)
    p.add_argument("--report", type=Path, help="Exact final Markdown report inside the specified ledger.")
    p.add_argument("--out", default="var/codex-assist-session-context/review.md")
    p.add_argument("--run", action="store_true", help="Consume the one actual grading attempt after final report exists.")
    p.add_argument("--self-test", action="store_true")
    args = p.parse_args(argv)
    if args.self_test:
        r = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ReviewTests))
        return 0 if r.wasSuccessful() else 1
    try:
        out = output_path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        if not args.run or args.report is None or not args.report.is_file():
            result = {"status": "PENDING", "checks": {}, "reason": "exact-final-report-not-observed-or-run-not-requested"}
            if out.exists():
                raise ValueError("pending-output-exists")
        else:
            contract = report_contract(args.report, args.ledger)
            baseline = json.loads(args.baseline.read_bytes())
            source = (ROOT / "scripts/chat_session_debug_export.py").read_text(encoding="utf-8")
            claim_once(out.with_name("review-run.json"), contract["sha256"])
            checks = {"a_allowlist": compare(baseline),
                      "b_focused_tests": run_command(TEST_COMMAND),
                      "c_fixture_export": fixture_export(out, source),
                      "d_report_contract": contract}
            result = {"status": aggregate(checks),
                      "checks": checks}
        result_path = out.with_name("review-grade-result.json" if args.run else "review-result.json")
        with result_path.open("x", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
            f.write("\n")
        write_review(out, result)
        print(json.dumps({"status": result["status"], "checks": {k: v["status"] for k, v in result["checks"].items()}}))
        return 1 if result["status"] == "FAIL" else 0
    except (ValueError, OSError, SyntaxError):
        print(json.dumps({"status": "FAIL", "reason": "review-input-or-one-shot-boundary"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
