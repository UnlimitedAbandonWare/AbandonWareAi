#!/usr/bin/env python3
"""delivery_guard_fixture_runner.py — materialize synthetic delivery-guard
fixtures and compare a delivery-obligation checker's verdict against golden
expectations.

Companion tool for TASK-CONTINUITY-DELIVERY-20261008 (Devin assist, journal
devin-task-continuity-delivery-assist-b2188157). It does NOT implement the
delivery check itself: with no --checker bound every case reports NOT_RUN,
and a fixture PASS here is harness evidence only, never an application
completion verdict.

    python -B scripts/delivery_guard_fixture_runner.py [options]

Options:
    --fixtures <dir|file>   Fixture JSON files (array or single object).
                           Default: var/codex-assist-task-continuity-20261008/fixtures
    --work-root <dir>      Sandbox materialization root.
                           Default: <fixtures-dir>/_work
    --checker "<cmd>"      Checker command template. Placeholders substituted
                           per case: {caseDir} {stateFile} {deliverLog}
                           {downloadsDir} {eventsFile} {caseJson}
                           The checker's LAST non-empty stdout line must be a
                           JSON object (the observed verdict).
    --rename <json-file>   Optional {"expected.dot.path": "observed.dot.path"}
                           map for checkers whose verdict keys differ.
    --only T1,P3           Comma-separated case-id subset.
    --report <json-file>   Also write the full result table.
    --dry-run              Materialize only; print sandbox paths; exit 0.
    --self-test            Harness self-check on synthetic temp fixtures
                           (proves the runner, not the product).

Eval subcommands (thin adapters over checkpoint_doctor's own library-only
helpers so fixture checkers can bind them without writing Python — the
adapter adds no logic and its JSON output is harness evidence only):

    --eval-slots <directive.txt> [--source-type user]
        Prints instruction_slots(text, source_type) as the last JSON line.
    --eval-preference <events.jsonl> --scope <s> [--artifact-type report]
        [--explicit-destination <d>] [--cancelled] [--minimum N]
        Prints preference_default(events, ...) as the last JSON line.
    Adapter exits 3 on missing helper (doctor surface drift), 2 on usage/IO.

Exit codes: 0 = every bound case matched AND zero NOT_RUN; 7 = no failures
but >=1 case stayed NOT_RUN (coverage gap, never a pass); 6 = >=1 mismatch
or checker error; 4 = no checker bound (all cases NOT_RUN); 2 = usage/setup.
"""
import argparse
import json
import os
import shlex
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_FIXTURES = ROOT / "var" / "codex-assist-task-continuity-20261008" / "fixtures"

SCHEMA = "awx.delivery-guard-fixture.v1"


def load_cases(fixtures):
    fixtures = Path(fixtures)
    files = []
    if fixtures.is_dir():
        files = sorted(p for p in fixtures.glob("*.json"))
    elif fixtures.is_file():
        files = [fixtures]
    cases, problems = [], []
    for path in files:
        if path.name in ("targets.json",):
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            problems.append(f"{path.name}: unreadable JSON ({exc})")
            continue
        items = data if isinstance(data, list) else [data]
        for i, item in enumerate(items):
            if not isinstance(item, dict) or "caseId" not in item or "expect" not in item:
                problems.append(f"{path.name}[{i}]: missing caseId/expect")
                continue
            item.setdefault("setup", {})
            item["_sourceFile"] = path.name
            cases.append(item)
    return cases, problems


def materialize(case, work_root):
    """Build the sandbox directory for one case; return dict of paths."""
    case_id = str(case["caseId"])
    setup = case.get("setup") or {}
    case_dir = work_root / case_id
    case_dir.mkdir(parents=True, exist_ok=True)
    task_dir = case_dir / "task"
    task_dir.mkdir(exist_ok=True)
    paths = {"caseDir": case_dir}

    state_file = task_dir / "state.md"
    if "contract" in setup:
        # awx.task-continuity.v1: contract embedded in a `continuity:` line,
        # __CASE_DIR__ substituted with the materialized sandbox (posix path).
        blob = json.dumps(setup["contract"], ensure_ascii=False)
        blob = blob.replace("__CASE_DIR__", case_dir.as_posix())
        state_text = f"goal: {setup['contract'].get('goal', 'fixture')}\ncontinuity: {blob}\n"
    else:
        state_text = setup.get("state_md", "")
    state_file.write_text(state_text, encoding="utf-8")
    paths["stateFile"] = state_file

    deliver_dir = case_dir / "var" / "deliver"
    deliver_dir.mkdir(parents=True, exist_ok=True)
    log_file = deliver_dir / "deliver-log.jsonl"
    with log_file.open("w", encoding="utf-8", newline="\n") as fh:
        for row in setup.get("deliver_log", []):
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    paths["deliverLog"] = log_file

    downloads_dir = case_dir / "Downloads"
    downloads_dir.mkdir(exist_ok=True)
    for name, payload in (setup.get("downloads_files") or {}).items():
        safe = Path(str(name)).name  # fixture writes stay inside the sandbox
        content = payload.get("content", "") if isinstance(payload, dict) else str(payload)
        (downloads_dir / safe).write_text(content, encoding="utf-8")
    paths["downloadsDir"] = downloads_dir

    for name, content in (setup.get("source_files") or {}).items():
        safe = Path(str(name)).name
        (case_dir / safe).write_text(str(content), encoding="utf-8")

    for name, obj in (setup.get("receipt_files") or {}).items():
        safe = Path(str(name)).name
        blob = json.dumps(obj, ensure_ascii=False, indent=2)
        (case_dir / safe).write_text(blob.replace("__CASE_DIR__", case_dir.as_posix()),
                                     encoding="utf-8")

    attach_file = case_dir / "attachments.json"
    attach_file.write_text(json.dumps(setup.get("attachments", {}), ensure_ascii=False, indent=2),
                           encoding="utf-8")
    paths["attachmentsFile"] = attach_file

    events_file = case_dir / "events.jsonl"
    with events_file.open("w", encoding="utf-8", newline="\n") as fh:
        for row in setup.get("events", []):
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    paths["eventsFile"] = events_file

    req_file = case_dir / "user_requests.jsonl"
    with req_file.open("w", encoding="utf-8", newline="\n") as fh:
        for row in setup.get("user_requests", []):
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    paths["userRequestsFile"] = req_file

    variants_dir = case_dir / "state_variants"
    for name, obj in (setup.get("state_variants") or {}).items():
        variants_dir.mkdir(exist_ok=True)
        (variants_dir / f"{Path(str(name)).name}.json").write_text(
            json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")
    paths["stateVariantsDir"] = variants_dir

    if setup.get("directive_text"):
        directive_file = case_dir / "directive.txt"
        directive_file.write_text(setup["directive_text"], encoding="utf-8")
        paths["directiveFile"] = directive_file

    case_json = case_dir / "case.json"
    case_json.write_text(json.dumps({k: v for k, v in case.items() if not k.startswith("_")},
                                   ensure_ascii=False, indent=2), encoding="utf-8")
    paths["caseJson"] = case_json
    return paths


def run_checker(template, paths, timeout_sec):
    mapping = {key: str(value) for key, value in paths.items()}
    try:
        rendered = template.format(**mapping)
    except (KeyError, IndexError) as exc:
        return {"error": f"template-placeholder:{exc}"}
    try:
        argv = shlex.split(rendered, posix=(os.name != "nt"))
    except ValueError as exc:
        return {"error": f"template-split:{exc}"}
    if not argv:
        return {"error": "empty-checker-command"}
    try:
        proc = subprocess.run(argv, capture_output=True, text=True,
                              timeout=timeout_sec, cwd=str(ROOT))
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"error": f"checker-exec:{exc}"}
    out = (proc.stdout or "").strip()
    verdict = None
    for line in reversed(out.splitlines()):
        line = line.strip()
        if not line:
            continue
        try:
            verdict = json.loads(line)
            break
        except ValueError:
            continue
    return {"exit": proc.returncode, "stdoutTail": out[-400:], "verdict": verdict}


def dig(obj, dot_path):
    cur = obj
    for part in str(dot_path).split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        elif isinstance(cur, list) and part.isdigit() and int(part) < len(cur):
            cur = cur[int(part)]
        else:
            return None, False
    return cur, True


def compare(expect_verdict, observed, rename):
    """Subset-compare: each expected dot-path must exist and equal in observed."""
    if not isinstance(observed, dict):
        return False, [("<checker-json>", "no JSON verdict on last stdout line")]
    misses = []
    for key, want in (expect_verdict or {}).items():
        target_key = rename.get(key, key)
        got, found = dig(observed, target_key)
        if not found:
            misses.append((key, "missing"))
            continue
        if isinstance(want, list):
            ok = got in want
        else:
            ok = got == want
        if not ok:
            misses.append((key, f"expected={want!r} observed={got!r}"))
    return (len(misses) == 0), misses


def load_rename(path):
    if not path:
        return {}
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        return {str(k): str(v) for k, v in data.items()} if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def check_substrings(expect, stdout_tail):
    problems = []
    for needle in expect.get("mustContain", []) or []:
        if needle not in stdout_tail:
            problems.append(f"mustContain missing: {needle!r}")
    for needle in expect.get("mustNotContain", []) or []:
        if needle in stdout_tail:
            problems.append(f"mustNotContain present: {needle!r}")
    return problems


def self_test():
    """Prove materialize+compare wiring on synthetic temp fixtures."""
    with tempfile.TemporaryDirectory(prefix="dg-selftest-") as tmp:
        tmp = Path(tmp)
        fx = tmp / "fixtures"
        fx.mkdir()
        case = {
            "caseId": "S1", "title": "selftest",
            "setup": {"state_md": "goal: x\n", "deliver_log": [{"event": "E"}],
                      "downloads_files": {"a.txt": "hello"}, "events": [{"e": 1}]},
            "expect": {"verdict": {"doneAllowed": False, "deliverables.a.status": "BLOCKED"}},
        }
        (fx / "cases.json").write_text(json.dumps([case]), encoding="utf-8")
        checker = tmp / "fake_checker.py"
        checker.write_text(
            "import json,sys\n"
            "print('note line')\n"
            "print(json.dumps({'doneAllowed': False, 'deliverables': {'a': {'status': 'BLOCKED'}}}))\n",
            encoding="utf-8")
        template = f"{sys.executable} -B {checker} --case {{caseDir}}"
        work = tmp / "work"
        cases, problems = load_cases(fx)
        assert not problems and len(cases) == 1, problems
        paths = materialize(cases[0], work)
        assert paths["stateFile"].is_file() and paths["deliverLog"].is_file()
        assert (paths["downloadsDir"] / "a.txt").read_text() == "hello"
        res = run_checker(template, paths, 60)
        ok, misses = compare(cases[0]["expect"]["verdict"], res.get("verdict"), {})
        assert res.get("verdict") and ok, misses
        res2 = run_checker(f"{sys.executable} -B -c \"print('no json here')\" {{caseDir}}", paths, 60)
        ok2, _ = compare(case["expect"]["verdict"], res2.get("verdict"), {})
        assert not ok2
        print("SELFTEST materialize+checker+compare: PASS")
        print("SELFTEST no-json checker -> NOT_MATCHED: PASS")
    return 0


def eval_mode(args):
    """Thin JSON adapter over checkpoint_doctor's library-only helpers."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    try:
        import checkpoint_doctor as doctor
    except ImportError as exc:
        print(json.dumps({"error": f"doctor-import:{exc}"}))
        return 3
    if args.eval_slots:
        if not hasattr(doctor, "instruction_slots"):
            print(json.dumps({"error": "doctor surface drift: no instruction_slots"}))
            return 3
        try:
            text = Path(args.eval_slots).read_text(encoding="utf-8")
            out = doctor.instruction_slots(text, args.source_type)
        except (OSError, TypeError, ValueError) as exc:
            print(json.dumps({"error": f"eval-slots:{exc}"}))
            return 3
        print(json.dumps(out, ensure_ascii=True))
        return 0
    if not hasattr(doctor, "preference_default"):
        print(json.dumps({"error": "doctor surface drift: no preference_default"}))
        return 3
    try:
        events = [json.loads(line) for line in
                  Path(args.eval_preference).read_text(encoding="utf-8").splitlines()
                  if line.strip()]
        out = doctor.preference_default(events, args.scope, args.artifact_type,
                                        explicit_destination=args.explicit_destination,
                                        cancelled=args.cancelled, minimum=args.minimum)
    except (OSError, TypeError, ValueError) as exc:
        print(json.dumps({"error": f"eval-preference:{exc}"}))
        return 3
    print(json.dumps(out, ensure_ascii=True))
    return 0


def main():
    try:  # Windows consoles may be cp949; docstrings/findings carry non-ASCII
        sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--fixtures", default=str(DEFAULT_FIXTURES))
    ap.add_argument("--work-root", default=None)
    ap.add_argument("--checker", default=None)
    ap.add_argument("--rename", default=None)
    ap.add_argument("--only", default=None)
    ap.add_argument("--report", default=None)
    ap.add_argument("--timeout-sec", type=float, default=90.0)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--eval-slots", default=None, metavar="FILE")
    ap.add_argument("--eval-preference", default=None, metavar="EVENTS_JSONL")
    ap.add_argument("--source-type", default="user")
    ap.add_argument("--scope", default=None)
    ap.add_argument("--artifact-type", default="report")
    ap.add_argument("--explicit-destination", default=None)
    ap.add_argument("--cancelled", action="store_true")
    ap.add_argument("--minimum", type=int, default=3)
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if args.eval_slots or args.eval_preference:
        return eval_mode(args)

    fixtures = Path(args.fixtures)
    work_root = Path(args.work_root) if args.work_root else fixtures.parent / "_work"
    rename = load_rename(args.rename)
    only = {s.strip() for s in args.only.split(",")} if args.only else None

    cases, problems = load_cases(fixtures)
    for p in problems:
        print(f"FIXTURE-PROBLEM {p}")
    if only:
        cases = [c for c in cases if str(c["caseId"]) in only]
    if not cases:
        print("no fixture cases loaded")
        return 2

    results = []
    counts = {"PASS": 0, "FAIL": 0, "NOT_RUN": 0, "CHECKER_ERROR": 0}
    for case in cases:
        case_id = str(case["caseId"])
        paths = materialize(case, work_root)
        if args.dry_run:
            print(f"{case_id} materialized -> {paths['caseDir']}")
            continue
        expect = case.get("expect") or {}
        # explicit "checker": null = deliberately unbound (NOT_RUN), never
        # backfilled by the global --checker
        template = case["checker"] if "checker" in case else args.checker
        if not template:
            verdict = "NOT_RUN"
            detail = ("explicit checker:null" if "checker" in case
                      else "no --checker bound")
        else:
            res = run_checker(template, paths, args.timeout_sec)
            if res.get("error"):
                verdict, detail = "CHECKER_ERROR", res["error"]
            elif res.get("verdict") is None:
                verdict, detail = "CHECKER_ERROR", f"exit={res.get('exit')} no-json-tail"
            else:
                ok, misses = compare(expect.get("verdict"), res["verdict"], rename)
                subs = check_substrings(expect, res.get("stdoutTail", ""))
                misses = misses + [(k, v) for k, v in enumerate(subs)]
                if ok and not subs:
                    verdict, detail = "PASS", f"{len(expect.get('verdict') or {})} keys matched"
                else:
                    verdict = "FAIL"
                    detail = "; ".join(f"{k}:{m}" for k, m in misses[:6])
        counts[verdict] = counts.get(verdict, 0) + 1
        results.append({"caseId": case_id, "title": case.get("title"),
                        "verdict": verdict, "detail": detail,
                        "caseDir": str(paths["caseDir"])})
        print(f"{verdict:<13} {case_id:<4} {case.get('title') or ''}  {detail}")

    if args.dry_run:
        return 0
    summary = {"schema": "awx.delivery-guard-runner.v1",
               "fixtures": str(fixtures), "checkerBound": bool(args.checker),
               "counts": counts, "results": results}
    print(json.dumps({"summary": {k: counts[k] for k in ("PASS", "FAIL", "NOT_RUN", "CHECKER_ERROR")}},
                     ensure_ascii=False))
    if args.report:
        Path(args.report).write_text(json.dumps(summary, ensure_ascii=False, indent=2),
                                     encoding="utf-8")
    if counts["FAIL"] or counts["CHECKER_ERROR"]:
        return 6
    if counts["NOT_RUN"] == len(cases):
        print("hint: no checker bound (global --checker or per-case \"checker\"); "
              "unbound cases stay NOT_RUN")
        return 4
    if counts["NOT_RUN"]:
        return 7
    return 0


if __name__ == "__main__":
    sys.exit(main())
