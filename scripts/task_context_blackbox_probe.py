#!/usr/bin/env python3
"""Black-box probe for scripts/task_context.py.

The probe builds its fixture only under %TEMP%. If task_context.py is absent
the report is PENDING. If the tool has no root override, the report is
NOT_RUN and the real data/agent-handoff tree is not used. A repo-root --root
flag uses a temp configs/agent-paths.yaml so journal files stay under that
temp root. Every report includes the interface table. --self-test drives a
local stub through P1-P10 and an incomplete-module gap table.
"""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

CHECKS = ("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9", "P10")
MD_LIMIT = 12 * 1024
JSON_LIMIT = 64 * 1024
OVERRIDE_TOKENS = (
    "--journal-base",
    "--root",
    "AWX_JOURNAL_BASE",
    "AWX_TASK_CONTEXT_ROOT",
    "AWX_JOURNAL_ROOT",
    "AWX_ROOT",
)
REQUIRED = (
    ("build", "function", "build(root, task, dry_run=False, **kwargs) -> dict"),
    ("show", "function", "Python show(); the live CLI show calls load_current"),
    ("verify", "function", "verify(root, task) -> dict with state"),
    ("list_contexts", "function", "list_contexts(root, limit=1) -> list"),
    ("main", "function", "main(argv=None) -> int"),
    ("--journal-base", "cli", "direct journal-directory override"),
    ("--root", "cli", "project-root override"),
    ("build", "command", "CLI verb build"),
    ("show", "command", "CLI verb show"),
    ("verify", "command", "CLI verb verify"),
    ("list", "command", "CLI verb list"),
)
ESCAPE_ENV = (
    "AWX_PATH_JOURNAL_BASE",
    "AWX_PATH_HANDOFF_ROOT",
    "AWX_ROOT",
    "AWX_JOURNAL_BASE",
    "AWX_TASK_CONTEXT_ROOT",
    "AWX_JOURNAL_ROOT",
)
REGISTRY_YAML = (
    "paths:\n"
    "  handoff.root:\n"
    "    path: handoff\n"
    "    env: AWX_PATH_HANDOFF_ROOT\n"
    "  journal.base:\n"
    "    path: journals\n"
    "    env: AWX_PATH_JOURNAL_BASE\n"
)
STUB = r'''
import hashlib, json, os, sys, time
from pathlib import Path

def parse(argv):
    opt = {}
    pos = []
    i = 0
    while i < len(argv):
        item = argv[i]
        if item in ("--journal-base", "--root", "--task", "--limit", "--since-hours") and i + 1 < len(argv):
            opt[item] = argv[i + 1]
            i += 2
            continue
        if item in ("--dry-run", "--json", "--help", "-h"):
            opt[item] = "1"
            i += 1
            continue
        pos.append(item)
        i += 1
    return pos, opt

def main(argv):
    if "--help" in argv or "-h" in argv:
        print("usage: task_context.py --journal-base PATH build|show|verify|list")
        print("env AWX_JOURNAL_BASE")
        return 0
    pos, opt = parse(argv)
    base = opt.get("--journal-base") or opt.get("--root") or os.environ.get("AWX_JOURNAL_BASE")
    if not base:
        print("no-root-override", file=sys.stderr)
        return 2
    base = Path(base)
    cmd = pos[0] if pos else ""
    task = opt.get("--task") or ""
    task_dir = base / task
    if cmd == "build":
        if "--dry-run" in opt:
            return 0
        lock = base / ".build.lock"
        try:
            fd = os.open(str(lock), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            print("BUSY")
            return 3
        try:
            os.write(fd, b"1")
            time.sleep(float(os.environ.get("AWX_PROBE_LOCK_SLEEP", "1.0")))
            target = task_dir / "target.txt"
            sha = hashlib.sha256(target.read_bytes()).hexdigest() if target.is_file() else ""
            rev = task_dir / "context" / "r000001"
            rev.mkdir(parents=True, exist_ok=True)
            (rev / "context.md").write_text("probe-context\n", encoding="utf-8")
            (rev / "context.json").write_text('{"ok":true}\n', encoding="utf-8")
            (rev / "sources.json").write_text('{"sources":[]}\n', encoding="utf-8")
            (rev / "manifest.json").write_text(json.dumps({"targetSha256": sha}) + "\n", encoding="utf-8")
            tmp = task_dir / "current.json.tmp"
            tmp.write_text('{"rev":"r000001"}\n', encoding="utf-8")
            os.replace(str(tmp), str(task_dir / "current.json"))
            return 0
        finally:
            os.close(fd)
            try:
                os.remove(lock)
            except OSError:
                pass
    if cmd == "show":
        md = task_dir / "context" / "r000001" / "context.md"
        if not md.is_file():
            return 4
        sys.stdout.write(md.read_text(encoding="utf-8"))
        return 0
    if cmd == "verify":
        man = task_dir / "context" / "r000001" / "manifest.json"
        try:
            doc = json.loads(man.read_text(encoding="utf-8"))
            expect = doc["targetSha256"]
        except (OSError, ValueError, KeyError, TypeError):
            print("CORRUPT")
            return 20
        target = task_dir / "target.txt"
        got = hashlib.sha256(target.read_bytes()).hexdigest() if target.is_file() else ""
        if got != expect:
            print("STALE")
            return 10
        print("FRESH")
        return 0
    if cmd == "list":
        limit = int(opt.get("--limit") or "1")
        names = []
        if base.is_dir():
            for child in sorted(base.iterdir()):
                if child.is_dir() and not child.name.startswith("."):
                    if (child / "journal.json").is_file() or (child / "current.json").is_file():
                        names.append(child.name)
        for name in names[:max(0, limit)]:
            print(name)
        return 0
    return 2

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
'''



def contract_rows(script):
    try:
        source = Path(script).read_text(encoding="utf-8", errors="ignore")
    except OSError:
        source = ""
    try:
        tree = ast.parse(source) if source else None
    except SyntaxError:
        tree = None
    funcs = set()
    constants = set()
    if tree is not None:
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                funcs.add(node.name)
            elif isinstance(node, ast.Constant) and isinstance(node.value, str):
                constants.add(node.value)
    rows = []
    for symbol, kind, note in REQUIRED:
        if kind == "function":
            present = symbol in funcs
        else:
            present = symbol in constants
        rows.append({
            "symbol": symbol,
            "kind": kind,
            "required": "yes",
            "present": "yes" if present else "no",
            "note": note,
        })
    return rows


def isolated_env(extra):
    env = os.environ.copy()
    for name in ESCAPE_ENV:
        env.pop(name, None)
    env.update(extra or {})
    return env


def write_registry(repo):
    cfg = Path(repo) / "configs"
    cfg.mkdir(parents=True, exist_ok=True)
    (cfg / "agent-paths.yaml").write_text(REGISTRY_YAML, encoding="utf-8", newline="\n")


def bind_probe_target(repo, task_dir, task):
    repo = Path(repo).resolve()
    target = Path(task_dir) / "target.txt"
    raw = target.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    rel = Path(task_dir).resolve().relative_to(repo).as_posix() + "/target.txt"
    entries = [{"path": rel, "bytes": len(raw), "sha256": digest}]
    manifest_sha = hashlib.sha256(
        json.dumps(entries, sort_keys=True, ensure_ascii=True).encode("utf-8")).hexdigest()
    manifest = {"entries": entries, "fileCount": 1, "manifestSha256": manifest_sha}
    ticket = "probe-ticket"
    store = repo / "handoff" / "coop-verify"
    (store / "receipts").mkdir(parents=True, exist_ok=True)
    state = {"tickets": {ticket: {
        "ticketId": ticket,
        "taskId": task,
        "verifyCommand": ["python", "synthetic.py"],
        "scope": [rel],
        "receiptPath": "receipts/" + ticket + ".json",
    }}}
    receipt = {
        "schemaVersion": "awx.coop-verify.v1.receipt",
        "cwd": str(repo),
        "ticketId": ticket,
        "verdict": "VERIFIED_PASS",
        "exitCode": 0,
        "inputManifestBefore": manifest,
        "inputManifestAfter": manifest,
        "verifyCommand": ["python", "synthetic.py"],
        "interferingWriters": [],
    }
    (store / "state.json").write_text(json.dumps(state), encoding="utf-8")
    (store / "receipts" / (ticket + ".json")).write_text(json.dumps(receipt), encoding="utf-8")


def canary_values():
    return ("sk-" + "canary-XXXX", "C:\\Users\\someone")


def contains_canary(blob):
    return any(value in blob for value in canary_values())


def file_snapshot(root):
    rows = []
    base = Path(root)
    if not base.exists():
        return rows
    for path in base.rglob("*"):
        if path.is_file():
            rows.append(path.relative_to(base).as_posix())
    return sorted(rows)


def invoke(script, args, cwd, env, timeout=30):
    cmd = [sys.executable, "-B", str(script), *args]
    proc = subprocess.run(cmd, cwd=str(cwd), env=env, capture_output=True, text=True, timeout=timeout)
    return proc.returncode, proc.stdout or "", proc.stderr or ""


def invoke_pair(script, args, cwd, env, timeout=30):
    cmd = [sys.executable, "-B", str(script), *args]
    procs = [
        subprocess.Popen(cmd, cwd=str(cwd), env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        for _ in range(2)
    ]
    rows = []
    for proc in procs:
        try:
            out, err = proc.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            proc.kill()
            out, err = proc.communicate()
            rows.append((124, out or "", (err or "") + "timeout"))
            continue
        rows.append((proc.returncode, out or "", err or ""))
    return rows


def support_flags(script):
    text = ""
    try:
        text = Path(script).read_text(encoding="utf-8", errors="ignore")
    except OSError:
        text = ""
    try:
        code, out, err = invoke(script, ["--help"], Path(script).parent, os.environ.copy(), timeout=20)
        text += "\n" + out + "\n" + err
    except (OSError, subprocess.SubprocessError):
        code = 1
    found = [token for token in OVERRIDE_TOKENS if token in text]
    return found, code


def prefix_for(found, base):
    if "--journal-base" in found:
        return ["--journal-base", str(base)], {}
    if "--root" in found:
        return ["--root", str(base)], {}
    env = {}
    for name in ("AWX_JOURNAL_BASE", "AWX_TASK_CONTEXT_ROOT", "AWX_JOURNAL_ROOT", "AWX_ROOT"):
        if name in found:
            env[name] = str(base)
            return [], env
    return None, None


def make_fixture(base, task):
    task_dir = Path(base) / task
    task_dir.mkdir(parents=True, exist_ok=True)
    key_a, key_b = canary_values()
    (task_dir / "journal.json").write_text(json.dumps({
        "schemaVersion": "awx.work_journal.v1",
        "taskId": task,
        "status": "closed",
        "result": "verified",
        "events": [{"kind": "report", "at": "2026-10-04T00:00:00+00:00"}],
    }), encoding="utf-8")
    (task_dir / "target.txt").write_text("seed\n" + key_a + "\n" + key_b + "\n", encoding="utf-8")
    (task_dir / "receipt.json").write_text(json.dumps({
        "status": "VERIFIED_PASS", "taskId": task, "target": "target.txt",
    }), encoding="utf-8")
    return task_dir


def output_blobs(task_dir, captured):
    blobs = list(captured)
    rev = Path(task_dir) / "context"
    current = Path(task_dir) / "current.json"
    paths = []
    if rev.is_dir():
        paths.extend(path for path in rev.rglob("*") if path.is_file())
    if current.is_file():
        paths.append(current)
    for path in paths:
        try:
            blobs.append(path.read_text(encoding="utf-8", errors="ignore"))
        except OSError:
            continue
    return blobs


def run_suite(script, base, prefix, extra_env, prepare=None):
    task = "probe-task-759293a4"
    task_dir = make_fixture(base, task)
    if prepare:
        prepare(task_dir, task)
    env = isolated_env(extra_env)
    env.setdefault("AWX_PROBE_LOCK_SLEEP", "1.0")
    captured = []
    rows = {name: {"id": name, "result": "FAIL", "detail": ""} for name in CHECKS}

    before = file_snapshot(base)
    try:
        code, out, err = invoke(script, prefix + ["build", "--task", task, "--dry-run"], base, env)
        captured.extend((out, err))
    except (OSError, subprocess.SubprocessError) as exc:
        code, out, err = 1, "", type(exc).__name__
    after = file_snapshot(base)
    rows["P1"] = {"id": "P1", "result": "PASS" if code == 0 and before == after else "FAIL",
                  "detail": "exit=%s new=%d" % (code, len(set(after) - set(before)))}

    try:
        code, out, err = invoke(script, prefix + ["build", "--task", task], base, env, timeout=40)
        captured.extend((out, err))
    except (OSError, subprocess.SubprocessError) as exc:
        code, out, err = 1, "", type(exc).__name__
    rev = task_dir / "context" / "r000001"
    names = ("context.json", "context.md", "sources.json", "manifest.json")
    pointer = task_dir / "current.json"
    if not pointer.is_file():
        pointer = task_dir / "context" / "current.json"
    present = all((rev / name).is_file() for name in names) and pointer.is_file()
    json_ok = (rev / "context.json").is_file() and (rev / "context.json").stat().st_size <= JSON_LIMIT
    rows["P2"] = {"id": "P2", "result": "PASS" if code == 0 and present and json_ok else "FAIL",
                  "detail": "exit=%s files=%s" % (code, present)}

    md = rev / "context.md"
    try:
        code, out, err = invoke(script, prefix + ["show", "--task", task], base, env)
        captured.extend((out, err))
    except (OSError, subprocess.SubprocessError) as exc:
        code, out, err = 1, "", type(exc).__name__
    md_ok = md.is_file() and md.stat().st_size <= MD_LIMIT
    rows["P3"] = {"id": "P3", "result": "PASS" if code == 0 and md_ok else "FAIL",
                  "detail": "exit=%s mdOk=%s" % (code, md_ok)}

    try:
        code, out, err = invoke(script, prefix + ["verify", "--task", task], base, env)
        captured.extend((out, err))
    except (OSError, subprocess.SubprocessError) as exc:
        code = 1
    rows["P4"] = {"id": "P4", "result": "PASS" if code == 0 else "FAIL", "detail": "exit=%s" % code}

    target = task_dir / "target.txt"
    target.write_bytes(target.read_bytes() + b"x")
    try:
        code, out, err = invoke(script, prefix + ["verify", "--task", task], base, env)
        captured.extend((out, err))
    except (OSError, subprocess.SubprocessError) as exc:
        code = 1
    rows["P5"] = {"id": "P5", "result": "PASS" if code == 10 else "FAIL", "detail": "exit=%s" % code}

    manifest = rev / "manifest.json"
    if manifest.is_file() or rev.is_dir():
        rev.mkdir(parents=True, exist_ok=True)
        manifest.write_text("CORRUPT\n", encoding="utf-8")
    try:
        code, out, err = invoke(script, prefix + ["verify", "--task", task], base, env)
        captured.extend((out, err))
    except (OSError, subprocess.SubprocessError) as exc:
        code = 1
    rows["P6"] = {"id": "P6", "result": "PASS" if code == 20 else "FAIL", "detail": "exit=%s" % code}

    try:
        code, out, err = invoke(script, prefix + ["show", "--task", "missing-task-759293a4"], base, env)
        captured.extend((out, err))
    except (OSError, subprocess.SubprocessError) as exc:
        code = 1
    rows["P7"] = {"id": "P7", "result": "PASS" if code == 4 else "FAIL", "detail": "exit=%s" % code}

    try:
        pair = invoke_pair(script, prefix + ["build", "--task", task], base, env, timeout=40)
    except (OSError, subprocess.SubprocessError) as exc:
        pair = [(1, "", type(exc).__name__), (1, "", "")]
    busy = any("BUSY" in (out + err) for _code, out, err in pair)
    for _code, out, err in pair:
        captured.extend((out, err))
    rows["P8"] = {"id": "P8", "result": "PASS" if busy else "FAIL", "detail": "busy=%s" % busy}

    hits = sum(1 for blob in output_blobs(task_dir, captured) if contains_canary(blob))
    rows["P9"] = {"id": "P9", "result": "PASS" if hits == 0 else "FAIL", "detail": "hits=%d" % hits}

    try:
        code, out, err = invoke(script, prefix + ["list", "--limit", "1"], base, env)
        captured.extend((out, err))
    except (OSError, subprocess.SubprocessError) as exc:
        code, out, err = 1, "", type(exc).__name__
    line_count = len([line for line in out.splitlines() if line.strip()])
    rows["P10"] = {"id": "P10", "result": "PASS" if code == 0 and line_count == 1 else "FAIL",
                   "detail": "exit=%s lines=%d" % (code, line_count)}
    ordered = [rows[name] for name in CHECKS]
    return ordered


def render_report(mode, rows, contract=None, note=""):
    lines = ["# task-context blackbox", "mode: %s" % mode, ""]
    if contract:
        missing = ["%s:%s" % (row["symbol"], row["kind"]) for row in contract if row["present"] == "no"]
        lines.append("interface: %s" % ("complete" if not missing else "incomplete"))
        lines.append("")
        lines.append("| symbol | kind | required | present | note |")
        lines.append("| --- | --- | --- | --- | --- |")
        for row in contract:
            lines.append("| %s | %s | %s | %s | %s |" % (
                row["symbol"], row["kind"], row["required"], row["present"], row["note"]))
        lines.append("")
        lines.append("missing: %s" % (",".join(missing) if missing else "0"))
        lines.append("")
    if note:
        lines.append(note)
        lines.append("")
    lines.extend(["| id | result | detail |", "| --- | --- | --- |"])
    for row in rows:
        lines.append("| %s | %s | %s |" % (row["id"], row["result"], row.get("detail") or ""))
    lines.append("")
    return "\n".join(lines)


def blank_rows(result, detail):
    return [{"id": name, "result": result, "detail": detail} for name in CHECKS]


def write_report(path, text):
    dest = Path(path)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(text, encoding="utf-8", newline="\n")
    return text


def under_temp(path):
    temp = Path(tempfile.gettempdir()).resolve()
    resolved = Path(path).resolve()
    return resolved == temp or temp in resolved.parents


def self_test():
    if not contains_canary("xx " + canary_values()[0] + " yy"):
        print("self-test FAIL detector")
        return 1
    if contains_canary("clean-probe-text"):
        print("self-test FAIL detector-false")
        return 1
    base = Path(tempfile.mkdtemp(prefix="awx-tc-self-"))
    stub = base / "task_context_stub.py"
    try:
        stub.write_text(STUB, encoding="utf-8", newline="\n")
        found, _code = support_flags(stub)
        prefix, extra = prefix_for(found, base)
        if not prefix and prefix != []:
            print("self-test FAIL stub-override")
            return 1
        if prefix is None:
            print("self-test FAIL stub-override")
            return 1
        rows = run_suite(stub, base, prefix, extra or {})
        text = render_report("self-test", rows)
        if contains_canary(text):
            print("self-test FAIL report-canary")
            return 1
        bad = [row["id"] for row in rows if row["result"] != "PASS"]
        if bad:
            print("self-test FAIL " + ",".join(bad))
            print(text)
            return 1
        incomplete = base / "incomplete_task_context.py"
        incomplete.write_text(
            "def build(root, task, dry_run=False, **kwargs):\n    return {}\n",
            encoding="utf-8")
        gaps = contract_rows(incomplete)
        missing = {(row["symbol"], row["kind"]) for row in gaps if row["present"] == "no"}
        need = {
            ("list_contexts", "function"),
            ("verify", "function"),
            ("main", "function"),
            ("--journal-base", "cli"),
            ("--root", "cli"),
            ("show", "command"),
            ("list", "command"),
        }
        if not need <= missing:
            print("self-test FAIL contract-gap")
            return 1
        gap_text = render_report("self-test-contract", blank_rows("NOT_RUN", "interface-incomplete"), gaps)
        if "| symbol | kind | required | present | note |" not in gap_text or "list_contexts" not in gap_text:
            print("self-test FAIL contract-table")
            return 1
        if contains_canary(gap_text):
            print("self-test FAIL contract-canary")
            return 1
    finally:
        shutil.rmtree(base, ignore_errors=True)
    print("self-test PASS")
    return 0


def run_real(root, out):
    script = Path(root) / "scripts" / "task_context.py"
    if not script.is_file():
        text = render_report("run", blank_rows("PENDING", "task_context.py absent"))
        write_report(out, text)
        print("PENDING")
        return 0
    contract = contract_rows(script)
    found, _code = support_flags(script)
    base = Path(tempfile.mkdtemp(prefix="awx-tc-probe-"))
    if not under_temp(base):
        print("NOT_RUN")
        return 0
    try:
        prefix, extra = prefix_for(found, base)
        journal_override = "--journal-base" in found or any(
            name in found for name in ("AWX_JOURNAL_BASE", "AWX_JOURNAL_ROOT"))
        repo_override = "--root" in found or "AWX_ROOT" in found
        if prefix is None and not repo_override:
            text = render_report("run", blank_rows("NOT_RUN", "no-root-override"), contract)
            write_report(out, text)
            print("NOT_RUN")
            return 0
        note = ""
        if journal_override:
            rows = run_suite(script, base, prefix, extra or {})
        else:
            write_registry(base)
            journal = base / "journals"
            journal.mkdir(parents=True, exist_ok=True)

            def prepare(task_dir, task, repo=base):
                bind_probe_target(repo, task_dir, task)

            rows = run_suite(script, journal, ["--root", str(base.resolve())], {}, prepare=prepare)
            note = (
                "layout: --root is the project root. The fixture journal is journals/<task> "
                "via configs/agent-paths.yaml. --journal-base is not a live CLI flag."
            )
        failed = any(row["result"] == "FAIL" for row in rows)
        text = render_report("run", rows, contract, note)
        write_report(out, text)
        print("FAIL" if failed else "PASS")
        return 1 if failed else 0
    finally:
        shutil.rmtree(base, ignore_errors=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Black-box probe for task_context.py")
    parser.add_argument("--root", default=".")
    parser.add_argument("--out", default="var/codex-assist-grok-session-context/probe-report.md")
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    root = Path(args.root).resolve()
    out = Path(args.out)
    if not out.is_absolute():
        out = root / out
    return run_real(root, out)


if __name__ == "__main__":
    sys.exit(main())
