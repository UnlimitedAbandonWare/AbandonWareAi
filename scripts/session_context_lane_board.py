#!/usr/bin/env python3
"""One-shot board for the four session-context lanes. Not a watcher or a server.

Reads codex_parallel_preflight.py, agent_scope_lease.py who, ledger journals
(kind and time only), and git status. Writes board.md and board.json.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

SCHEMA = "awx.session-context-lane-board.v1"
KST = timezone(timedelta(hours=9))
GIT = os.environ.get("AWX_GIT") or r"F:\git\cmd\git.exe"
DROP_KEYS = {"text", "purpose", "query", "prompt", "answer", "body", "raw", "summary"}
LANES = (
    {
        "id": "astra",
        "goalKey": "codex-session-jsonl-28bf623e",
        "task": "codex-session-jsonl-28bf623e",
        "agent": "codex",
        "ledger": "data/agent-handoff/codex-session-jsonl-28bf623e",
        "scope": (
            "scripts/chat_session_debug_export.py",
            "scripts/test_chat_session_debug_export.py",
            "scripts/query_flow_notepad_bundle.py",
            "scripts/test_query_flow_notepad_bundle.py",
            ".agents/skills/demo1-chat-session-debug",
        ),
    },
    {
        "id": "sol",
        "goalKey": "codex-sol-prep-73e61c19",
        "task": "codex-sol-prep-73e61c19",
        "agent": "codex",
        "ledger": "data/agent-handoff/codex-sol-prep-73e61c19",
        "scope": (
            "scripts/chat_trace_fixture_synth.py",
            "scripts/chat_export_leak_scan.py",
            "scripts/handoff_contract_probe.py",
            "scripts/session_jsonl_result_review.py",
            ".agents/skills/demo1-session-context-prep",
            "var/codex-assist-session-context",
        ),
    },
    {
        "id": "grok",
        "goalKey": "grok-session-context-759293a4",
        "task": "grok-session-context-759293a4",
        "agent": "grok",
        "ledger": "data/agent-handoff/grok-session-context-759293a4",
        "scope": (
            "scripts/session_context_lane_board.py",
            "scripts/win_export_name_adversarial.py",
            "scripts/task_context_blackbox_probe.py",
            ".grok/rules/session-context-assist-20261004.md",
        ),
    },
    {
        "id": "task-context",
        "goalKey": "codex-task-context-3a5d2fbb",
        "task": "codex-task-context-3a5d2fbb",
        "agent": "codex",
        "ledger": "data/agent-handoff/codex-task-context-3a5d2fbb",
        "scope": (
            "scripts/task_context.py",
            "scripts/test_task_context.py",
        ),
    },
)


def norm(path):
    return str(path).replace("\\", "/").strip("/")


def paths_overlap(left, right):
    if left == right:
        return True
    return left.startswith(right + "/") or right.startswith(left + "/")


def to_kst(value):
    if not value:
        return None
    try:
        stamp = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    if stamp.tzinfo is None:
        stamp = stamp.replace(tzinfo=timezone.utc)
    return stamp.astimezone(KST).strftime("%Y-%m-%d %H:%M:%S KST")


def rel_path(root, path):
    try:
        return Path(path).resolve().relative_to(Path(root).resolve()).as_posix()
    except (OSError, ValueError):
        return Path(path).name


def parse_json_blob(text):
    raw = (text or "").strip()
    if not raw:
        raise ValueError("empty-json")
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        start = raw.find("{")
        end = raw.rfind("}")
        if start < 0 or end <= start:
            raise
        return json.loads(raw[start:end + 1])


def strip_dropped(value):
    if isinstance(value, dict):
        return {key: strip_dropped(item) for key, item in value.items() if key not in DROP_KEYS}
    if isinstance(value, list):
        return [strip_dropped(item) for item in value]
    return value


def overlap_paths(lanes):
    items = []
    for lane in lanes:
        for scope in lane.get("scope") or ():
            items.append((norm(scope), lane["id"]))
    hit = {}
    for path, lane_id in items:
        mates = sorted({other for other_path, other in items
                        if other != lane_id and paths_overlap(path, other_path)})
        if mates:
            hit.setdefault(path, set()).update(mates)
            hit[path].add(lane_id)
    rows = [{"path": path, "lanes": sorted(names), "mark": "OVERLAP"}
            for path, names in sorted(hit.items())]
    return rows


def headline(overlap_count, live_count, finished):
    done = ", ".join(finished) if finished else "없음"
    return "겹침 %d건 / 살아있는 쓰기 %d / 끝난 갈래: %s" % (overlap_count, live_count, done)


def journal_files(root, lane):
    found = []
    ledger = Path(root) / lane["ledger"]
    if ledger.is_dir():
        direct = ledger / "journal.json"
        if direct.is_file():
            found.append(direct)
        found.extend(path for path in ledger.glob("*/journal.json") if path.is_file())
    auto = Path(root) / "data/agent-handoff/codex-autonomy"
    name = Path(lane["ledger"]).name
    direct_auto = auto / name / "journal.json"
    if direct_auto.is_file():
        found.append(direct_auto)
    stem = name.rsplit("-", 1)[0]
    if auto.is_dir() and stem:
        found.extend(path for path in auto.glob(stem + "-*/journal.json") if path.is_file())
    unique = {}
    for path in found:
        try:
            unique[str(path.resolve())] = path
        except OSError:
            unique[str(path)] = path
    return list(unique.values())


def journal_meta(root, path):
    try:
        doc = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(doc, dict):
        return None
    events = doc.get("events") if isinstance(doc.get("events"), list) else []
    last_at = ""
    kind = None
    for event in events:
        if not isinstance(event, dict):
            continue
        at = str(event.get("at") or "")
        if at >= last_at:
            last_at = at
            kind = event.get("kind")
    when = last_at or str(doc.get("updatedAtUtc") or "")
    status = doc.get("status")
    finished = status == "closed" or kind == "report"
    return {
        "journal": rel_path(root, path),
        "status": status,
        "kind": kind,
        "atKst": to_kst(when),
        "atUtc": when or None,
        "finished": bool(finished),
    }


def latest_journal(root, lane):
    best = None
    for path in journal_files(root, lane):
        meta = journal_meta(root, path)
        if meta is None:
            continue
        stamp = meta.get("atUtc") or ""
        if best is None or stamp >= (best.get("atUtc") or ""):
            best = meta
    if best is not None:
        best = dict(best)
        best.pop("atUtc", None)
    return best


def reduce_preflight(doc):
    writers = []
    for row in doc.get("liveWriters") or []:
        if isinstance(row, dict):
            writers.append({
                "kind": row.get("kind"),
                "agent": row.get("agent"),
                "taskId": row.get("taskId"),
                "paths": row.get("paths") or [],
            })
    claims = []
    for row in doc.get("overlappingClaims") or []:
        if isinstance(row, dict):
            claims.append({
                "taskId": row.get("taskId"),
                "agent": row.get("agent"),
                "topic": row.get("topic") or row.get("lease"),
                "paths": row.get("paths") or [],
            })
    quota = doc.get("quota") if isinstance(doc.get("quota"), dict) else {}
    return {
        "role": doc.get("role"),
        "verdict": doc.get("verdict"),
        "summaryKo": doc.get("summaryKo"),
        "liveWriters": writers,
        "overlappingClaims": claims,
        "duplicateCount": len(doc.get("duplicates") or []),
        "quota": strip_dropped(quota),
    }


def reduce_who(doc):
    if not isinstance(doc, dict):
        return {"status": "ERROR"}
    leases = []
    for row in doc.get("leases") or []:
        if not isinstance(row, dict):
            continue
        leases.append({
            "topic": row.get("topic"),
            "status": row.get("status"),
            "lifecycle": row.get("lifecycle"),
            "targetCount": len(row.get("targetPaths") or []),
        })
    return {
        "leaseCounts": doc.get("leaseCounts") or {},
        "leases": leases,
        "claimCount": len(doc.get("claims") or []),
        "activeJournalCount": len(doc.get("activeJournals") or []),
    }


def run_preflight(root, lane):
    # --lane is planId/laneId. These four lanes have no plan file, so the
    # scope is what separates the calls. A short --lane value is plan-missing.
    cmd = [
        sys.executable, "-B", str(Path(root) / "scripts" / "codex_parallel_preflight.py"),
        "--root", str(root),
        "--goal-key", lane["goalKey"],
        "--scope", ",".join(lane["scope"]),
        "--agent", lane["agent"],
        "--task", lane["task"],
        "--json",
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120, cwd=str(root))
    if proc.returncode != 0 and not (proc.stdout or "").strip():
        raise RuntimeError("preflight-exit-%s" % proc.returncode)
    doc = parse_json_blob(proc.stdout)
    reduced = reduce_preflight(doc)
    if proc.returncode != 0:
        reason = str(doc.get("reason") or "")
        raise RuntimeError(reason or ("preflight-exit-%s" % proc.returncode))
    reduced["preflightCall"] = "scope-only"
    return reduced


def run_who(root):
    cmd = [sys.executable, "-B", str(Path(root) / "scripts" / "agent_scope_lease.py"),
           "--root", str(root), "who"]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120, cwd=str(root))
    if proc.returncode != 0 and not (proc.stdout or "").strip():
        raise RuntimeError("who-exit-%s" % proc.returncode)
    return reduce_who(parse_json_blob(proc.stdout))


def run_git(root, paths):
    git = GIT if Path(GIT).is_file() else "git"
    env = os.environ.copy()
    env["GIT_OPTIONAL_LOCKS"] = "0"
    cmd = [git, "-C", str(root), "status", "--porcelain", "--untracked-files=normal", "--", *paths]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=60, env=env)
    if proc.returncode != 0:
        return {"status": "ERROR", "exit": proc.returncode}
    return {"status": "OK", "lines": [line for line in proc.stdout.splitlines() if line.strip()]}



def _status_line(blob):
    chosen = ""
    for line in (blob or "").splitlines():
        row = line.strip()
        if row.startswith("Ran ") or row.startswith("FAILED") or row == "OK" or row.startswith("OK "):
            chosen = row
    return chosen[:180]


def _load_module(root, filename, mod_name):
    path = Path(root) / "scripts" / filename
    spec = importlib.util.spec_from_file_location(mod_name, path)
    if spec is None or spec.loader is None:
        return None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _contract_gap(root):
    module = _load_module(root, "task_context_blackbox_probe.py", "tc_probe_for_board")
    script = Path(root) / "scripts" / "task_context.py"
    if module is None or not hasattr(module, "contract_rows") or not script.is_file():
        return [], []
    absent = {(row["symbol"], row["kind"]) for row in module.contract_rows(script) if row["present"] == "no"}
    blocking = []
    for name in ("build", "verify", "list_contexts", "main"):
        if (name, "function") in absent:
            blocking.append(name)
    for name in ("build", "show", "verify", "list"):
        if (name, "command") in absent:
            blocking.append("cmd-" + name)
    if ("--root", "cli") in absent and ("--journal-base", "cli") in absent:
        blocking.append("root-override")
    info = []
    if ("--journal-base", "cli") in absent and ("--root", "cli") not in absent:
        info.append("no-journal-base")
    if ("show", "function") in absent and ("show", "command") not in absent:
        info.append("show-is-cli")
    return blocking, info


def lane_signals(root):
    """Live probe signals. Empty when the root has no scripts directory."""
    root = Path(root)
    if not (root / "scripts").is_dir():
        return {}
    out = {}
    export_tool = root / "scripts" / "win_export_name_adversarial.py"
    if export_tool.is_file() and (root / "scripts" / "chat_session_debug_export.py").is_file():
        proc = subprocess.run(
            [sys.executable, "-B", str(export_tool), "--repro", "--root", str(root)],
            cwd=str(root), capture_output=True, text=True, timeout=60)
        payload = {}
        try:
            payload = json.loads((proc.stdout or "").strip().splitlines()[-1])
        except (ValueError, IndexError):
            payload = {}
        collisions = ""
        if payload.get("sameName"):
            collisions = "%s,%s" % (payload.get("leftId") or "", payload.get("rightId") or "")
        out["astra"] = {
            "result": "FAIL" if proc.returncode == 1 else ("PASS" if proc.returncode == 0 else "ERROR"),
            "collisions": collisions.strip(","),
            "cause": str(payload.get("cause") or ""),
            "detail": "repro-exit=%s" % proc.returncode,
        }
    if (root / "scripts" / "test_task_context.py").is_file():
        proc = subprocess.run(
            [sys.executable, "-B", "-m", "unittest", "test_task_context"],
            cwd=str(root / "scripts"), capture_output=True, text=True, timeout=180)
        summary = _status_line((proc.stderr or "") + "\n" + (proc.stdout or ""))
        blocking, info = _contract_gap(root)
        failed = proc.returncode != 0 or bool(blocking)
        out["task-context"] = {
            "result": "FAIL" if failed else "PASS",
            "tests": summary,
            "missing": ",".join(blocking),
            "detail": "tests=%s missing=%s note=%s" % (
                summary or ("exit-%s" % proc.returncode),
                ",".join(blocking) or "0",
                ",".join(info) or "0"),
        }
    sol_names = (
        "scripts/chat_trace_fixture_synth.py",
        "scripts/chat_export_leak_scan.py",
        "scripts/handoff_contract_probe.py",
        "scripts/session_jsonl_result_review.py",
    )
    if all((root / name).is_file() for name in sol_names):
        out["sol"] = {"result": "COMPLETE", "detail": "prep scripts present"}
    tool_names = (
        "scripts/win_export_name_adversarial.py",
        "scripts/task_context_blackbox_probe.py",
        "scripts/session_context_lane_board.py",
    )
    if all((root / name).is_file() for name in tool_names):
        bits = []
        ok = True
        for name in tool_names:
            proc = subprocess.run(
                [sys.executable, "-B", str(root / name), "--self-test"],
                cwd=str(root), capture_output=True, text=True, timeout=180)
            bits.append("%s=%s" % (Path(name).name, "PASS" if proc.returncode == 0 else "FAIL"))
            ok = ok and proc.returncode == 0
        out["grok"] = {"result": "PASS" if ok else "FAIL", "detail": " ".join(bits)}
    return out


def collect(root, preflight_fn=None, who_fn=None, git_fn=None, journal_fn=None, signal_fn=None):
    preflight_fn = preflight_fn or run_preflight
    who_fn = who_fn or run_who
    git_fn = git_fn or run_git
    journal_fn = journal_fn or latest_journal
    try:
        who = who_fn(root)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        who = {"status": "ERROR", "error": type(exc).__name__}
    rows = []
    for lane in LANES:
        row = {"id": lane["id"], "scope": list(lane["scope"]), "status": "OK"}
        try:
            row["preflight"] = preflight_fn(root, lane)
        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
            row["status"] = "ERROR"
            row["error"] = (type(exc).__name__ + ": " + str(exc))[:180]
            row["preflight"] = None
        try:
            row["journal"] = journal_fn(root, lane)
        except (OSError, ValueError) as exc:
            row["journal"] = None
            row["journalError"] = type(exc).__name__
        try:
            row["git"] = git_fn(root, list(lane["scope"]))
        except (OSError, subprocess.SubprocessError) as exc:
            row["git"] = {"status": "ERROR", "error": type(exc).__name__}
        sig = {}
        rows.append(row)
    try:
        signals = (signal_fn or lane_signals)(root) or {}
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
        signals = {}
    if not isinstance(signals, dict):
        signals = {}
    for row in rows:
        sig = signals.get(row["id"])
        if not isinstance(sig, dict):
            continue
        row["signal"] = {key: value for key, value in sig.items() if key not in DROP_KEYS}
        if sig.get("result") in ("FAIL", "INCOMPLETE") and row.get("status") == "OK":
            row["status"] = "FAIL"
    overlaps = overlap_paths(rows)
    finished = [row["id"] for row in rows if (row.get("journal") or {}).get("finished")]
    live = 0
    for row in rows:
        pre = row.get("preflight") or {}
        live += len(pre.get("liveWriters") or [])
    board = {
        "schemaVersion": SCHEMA,
        "generatedAtKst": datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S KST"),
        "headline": headline(len(overlaps), live, finished),
        "overlapCount": len(overlaps),
        "liveWriterCount": live,
        "finishedLanes": finished,
        "overlaps": overlaps,
        "who": who,
        "lanes": rows,
    }
    return strip_dropped(board)


def render_markdown(board):
    lines = [board["headline"], ""]
    for row in board["overlaps"]:
        lines.append("OVERLAP %s lanes=%s" % (row["path"], ",".join(row["lanes"])))
    if board["overlaps"]:
        lines.append("")
    who = board.get("who") or {}
    counts = who.get("leaseCounts") or {}
    lines.append("who claims=%s journals=%s leases=%s" % (
        who.get("claimCount"), who.get("activeJournalCount"),
        counts.get("active") if isinstance(counts, dict) else None))
    lines.append("")
    for row in board["lanes"]:
        lines.append("## %s" % row["id"])
        lines.append("- status: %s" % row.get("status"))
        if row.get("error"):
            lines.append("- error: %s" % row["error"])
        pre = row.get("preflight") or {}
        if pre:
            lines.append("- preflight: role=%s verdict=%s liveWriters=%d foreignClaims=%d" % (
                pre.get("role"), pre.get("verdict"),
                len(pre.get("liveWriters") or []),
                len(pre.get("overlappingClaims") or [])))
        journal = row.get("journal") or {}
        if journal:
            lines.append("- journal: %s kind=%s status=%s" % (
                journal.get("atKst"), journal.get("kind"), journal.get("status")))
        else:
            lines.append("- journal: absent")
        git = row.get("git") or {}
        git_lines = git.get("lines") or []
        if git.get("status") == "ERROR":
            lines.append("- git: ERROR")
        elif git_lines:
            lines.append("- git:")
            lines.extend("  " + item for item in git_lines)
        else:
            lines.append("- git: clean-or-absent")
        sig = row.get("signal") or {}
        if sig:
            lines.append("- signal: %s %s" % (sig.get("result"), sig.get("detail") or ""))
            if sig.get("collisions"):
                lines.append("- collisions: %s" % sig["collisions"])
            if sig.get("cause"):
                lines.append("- cause: %s" % sig["cause"])
            if sig.get("missing"):
                lines.append("- missing: %s" % sig["missing"])
            if sig.get("tests"):
                lines.append("- tests: %s" % sig["tests"])
        scope_overlap = [item["path"] for item in board["overlaps"] if row["id"] in item["lanes"]]
        if scope_overlap:
            lines.append("- mark: OVERLAP %s" % ",".join(scope_overlap))
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def write_board(out_dir, board):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    md = render_markdown(board)
    (out / "board.md").write_text(md, encoding="utf-8", newline="\n")
    (out / "board.json").write_text(json.dumps(board, ensure_ascii=False, indent=2) + "\n",
                                    encoding="utf-8", newline="\n")
    return md


def self_test():
    marker = "LEAK_MARKER_XQ"
    with tempfile.TemporaryDirectory(prefix="awx-lane-board-") as tmp:
        root = Path(tmp)
        journal_dir = root / "data" / "agent-handoff" / "codex-autonomy" / "codex-session-jsonl-self"
        journal_dir.mkdir(parents=True)
        journal = {
            "status": "closed",
            "updatedAtUtc": "2026-10-04T01:00:00+00:00",
            "events": [{"at": "2026-10-04T01:02:00+00:00", "kind": "report", "text": marker}],
        }
        (journal_dir / "journal.json").write_text(json.dumps(journal), encoding="utf-8")
        meta = journal_meta(root, journal_dir / "journal.json")
        if not meta or meta.get("kind") != "report" or not meta.get("finished"):
            print("self-test FAIL journal-meta")
            return 1
        if marker in json.dumps(meta):
            print("self-test FAIL journal-text-leaked")
            return 1

        def fake_preflight(_root, lane):
            if lane["id"] == "grok":
                raise RuntimeError("boom")
            writers = [{"kind": "coop-writer", "taskId": "t1", "paths": ["scripts/shared.py"]}] if lane["id"] == "astra" else []
            return {"role": "OWNER", "verdict": "FREE", "summaryKo": "ok",
                    "liveWriters": writers, "overlappingClaims": [], "duplicateCount": 0, "quota": {}}

        def fake_who(_root):
            return {"leaseCounts": {"active": 1}, "leases": [], "claimCount": 0, "activeJournalCount": 1}

        def fake_git(_root, _paths):
            return {"status": "OK", "lines": []}

        def fake_journal(_root, lane):
            if lane["id"] == "astra":
                return meta
            return None

        saved = list(LANES)
        try:
            LANES_OVER = []
            for lane in saved:
                copy = dict(lane)
                if lane["id"] in ("sol", "task-context"):
                    copy["scope"] = ("scripts/shared.py",)
                LANES_OVER.append(copy)
            globals()["LANES"] = tuple(LANES_OVER)
            board = collect(root, fake_preflight, fake_who, fake_git, fake_journal)
        finally:
            globals()["LANES"] = tuple(saved)
        md = write_board(root / "out", board)
        blob = md + json.dumps(board)
        if marker in blob:
            print("self-test FAIL leak")
            return 1
        if not md.startswith("겹침 1건 / 살아있는 쓰기 1 / 끝난 갈래: astra\n"):
            print("self-test FAIL headline")
            return 1
        if "OVERLAP scripts/shared.py" not in md:
            print("self-test FAIL overlap")
            return 1
        if any(("## %s" % lane["id"]) not in md for lane in saved):
            print("self-test FAIL lanes")
            return 1
        grok = next(row for row in board["lanes"] if row["id"] == "grok")
        if grok.get("status") != "ERROR":
            print("self-test FAIL error-lane")
            return 1
        sample = dict(board)
        sample["lanes"] = [dict(row) for row in board["lanes"]]
        sample["lanes"][0]["signal"] = {
            "result": "FAIL",
            "detail": "repro-exit=1",
            "collisions": "case-lower,trail-space",
            "cause": "strip-before-hash",
        }
        signed = render_markdown(sample)
        if "collisions: case-lower,trail-space" not in signed or "cause: strip-before-hash" not in signed:
            print("self-test FAIL signal")
            return 1
        if marker in signed:
            print("self-test FAIL signal-leak")
            return 1
    print("self-test PASS")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description="One-shot four-lane session-context board")
    parser.add_argument("--root", default=".")
    parser.add_argument("--out", default="var/codex-assist-grok-session-context")
    parser.add_argument("--run", action="store_true", help="Collect live evidence and write the board")
    parser.add_argument("--self-test", action="store_true", help="Run the offline fixture test")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    root = Path(args.root).resolve()
    out = Path(args.out)
    if not out.is_absolute():
        out = root / out
    board = collect(root)
    md = write_board(out, board)
    sys.stdout.write(md.splitlines()[0] + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
