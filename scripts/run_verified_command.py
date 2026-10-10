"""Run one verification command, preserving its exit code and fresh, scoped JUnit evidence.

Use only non-secret commands. Logs are command output, not a redaction boundary.
No shell pipeline is used. Existing XML is never deleted or counted as a new result.

Actions:
  run (default)   execute and record runId/cwd/argv/times/exit/log/source identity
  status          inspect a run directory: distinguishes still_running /
                  orphaned_unconfirmed (recorder died, child may be gone) from
                  finalized results -- never treats an unconfirmed run as passed
  stop            terminate only the recorded run's own process tree
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
if hasattr(sys.stderr, "reconfigure"):
    try:
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def stamp():
    return datetime.now(timezone.utc).isoformat()


def fingerprint(path):
    stat = path.stat()
    return (stat.st_mtime_ns, stat.st_size, hashlib.sha256(path.read_bytes()).hexdigest())


def canonical_hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
        ensure_ascii=False, allow_nan=False).encode("utf-8")).hexdigest()


def check_request_contract(doc, root=None):
    """Reuse the existing doctor; interpretation of natural language stays with the agent."""
    spec = importlib.util.spec_from_file_location("request_contract_doctor",
        Path(__file__).with_name("checkpoint_doctor.py"))
    doctor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(doctor)
    return doctor.check_request_contract(doc, root=root)


def exact_file(root, name):
    """Bound receipts may read only declared canonical, non-credential regular files."""
    if (not isinstance(name, str) or not name or "\\" in name or ":" in name or
            name.startswith("/") or any(p in ("", ".", "..") for p in name.split("/"))):
        raise ValueError("noncanonical-evidence-path")
    parts = list(root.parts) + name.split("/")
    if (any(p.casefold() in {".secrets", ".git", ".codex", ".aws"} or
            p.casefold().startswith(".env") for p in parts) or
            Path(name).suffix.lower() in {".pem", ".key", ".p12", ".pfx", ".jks"}):
        raise ValueError("sensitive-evidence-path")
    path = root / name
    if (not path.is_file() or path.is_symlink() or
            any(p.is_symlink() for p in path.parents if p != root and root in p.parents) or
            path.resolve().relative_to(root).as_posix() != name):
        raise ValueError("nonregular-evidence-file")
    return path


def bound_identity(root, names):
    rows = []
    for name in names:
        path = exact_file(root, name)
        data = path.read_bytes()
        rows.append({"path": name, "kind": "file", "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)})
    return rows


def bound_stability(root, names):
    """Observed stat snapshots also reject ordinary modify-and-restore races.

    This is a bounded snapshot check, not a filesystem lock or continuous monitor.
    """
    return [{"path": name, "mtimeNs": stat.st_mtime_ns, "ctimeNs": stat.st_ctime_ns,
        "device": stat.st_dev, "inode": stat.st_ino, "bytes": stat.st_size}
        for name in names for stat in [exact_file(root, name).stat()]]


def bind_contract(root, contract, stage, phase, sources, command, command_id=None, suites=()):
    if phase not in ("RED", "GREEN") or not stage:
        raise ValueError("contract-stage-phase-required")
    path = exact_file(root, contract)
    if path.stat().st_size > 128 * 1024:
        raise ValueError("request-contract-too-large")
    doc = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(doc, dict):
        raise ValueError("invalid-request-contract")
    checked = check_request_contract(doc, root=root)
    checked_stages = [s for s in checked.get("stages", []) if s.get("id") == stage]
    stages = [s for s in doc.get("stages", []) if s.get("id") == stage]
    if (checked.get("status") not in ("READY", "HOLD") or len(checked_stages) != 1 or
            checked_stages[0].get("status") != "READY" or len(stages) != 1):
        raise ValueError("request-contract-stage-not-ready")
    selected = stages[0]
    names = selected["sourceFiles"] + selected["testFiles"]
    if (not all(isinstance(n, str) for n in names + sources) or
            not selected["sourceFiles"] or not selected["testFiles"] or
            len(set(n.casefold() for n in names)) != len(names) or
            len(set(n.casefold() for n in sources)) != len(sources) or sorted(sources) != sorted(names)):
        raise ValueError("contract-source-test-coverage-mismatch")
    identities = bound_identity(root, names)
    planned = [t for t in selected["successTests"] if t.get("commandId") == command_id]
    if not isinstance(command_id, str) or not command_id or len(planned) != 1:
        raise ValueError("contract-command-identity-mismatch")
    argv_hash = hashlib.sha256(json.dumps(command).encode()).hexdigest()
    if planned[0].get("argvSha256") != argv_hash:
        raise ValueError("contract-planned-argv-mismatch")
    if (not suites or len(set(suites)) != len(suites) or
            sorted(planned[0].get("expectedSuites", [])) != sorted(suites)):
        raise ValueError("contract-planned-suites-mismatch")
    binding = {"taskId": doc["taskId"], "revision": doc["revision"],
        "instructionRef": doc["instructionRef"], "contractHash": canonical_hash(doc),
        "stageId": stage, "phase": phase, "commandId": command_id, "sourceFiles": selected["sourceFiles"],
        "testFiles": selected["testFiles"], "argvSha256": argv_hash, "expectedSuites": sorted(suites)}
    return binding, identities


def junit_counts(root, suite, *, strict=False):
    if root.tag != "testsuite" or root.attrib.get("name") != suite:
        raise ValueError("suite identity mismatch")
    counts = {key: int(root.attrib.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
    cases = root.findall("testcase")
    if counts["tests"] <= 0 or any(n < 0 for n in counts.values()) or len(cases) != counts["tests"]:
        raise ValueError("invalid counts")
    if strict:
        observed = {"failures": sum(c.find("failure") is not None for c in cases),
            "errors": sum(c.find("error") is not None for c in cases),
            "skipped": sum(c.find("skipped") is not None for c in cases)}
        if any(counts[k] != observed[k] for k in observed):
            raise ValueError("testcase outcome count mismatch")
    return counts


def validate_bound_receipt(output, binding=None, expected_phase=None, expected_root=None):
    """Read-only local evidence check. Hashes detect drift, not a forged record's authorship.

    GREEN needs current source and test identities. RED may precede a source patch,
    but its tests must still match; both phases must have been stable during execution.
    """
    diagnostics, report = [], None
    try:
        output = Path(output).resolve()
        report = load_run(output)
        # Reject a foreign cwd before resolving it or reading any contract/source/test.
        if expected_root is not None and report.get("cwd") != str(Path(expected_root).resolve()):
            raise ValueError("receipt-root-mismatch")
        bound = report["contractBinding"]
        phase = bound["phase"]
        if phase not in ("RED", "GREEN") or (expected_phase and phase != expected_phase):
            raise ValueError("receipt-phase-mismatch")
        if binding is not None and any(bound.get(k) != v for k, v in binding.items()):
            raise ValueError("receipt-binding-mismatch")
        if report.get("verificationPhaseOutcome") != phase or report.get("status") != ("passed" if phase == "GREEN" else "failed"):
            raise ValueError("receipt-not-confirmed")
        start, end = (datetime.fromisoformat(report[k]) for k in ("startedAt", "endedAt"))
        if (not start.tzinfo or not end.tzinfo or end < start or end > datetime.now(timezone.utc) or
                type(report.get("pid")) is not int or report["pid"] <= 0):
            raise ValueError("receipt-run-not-finalized")
        uuid.UUID(report["runId"])
        root = Path(report["cwd"]).resolve()
        command = report["commandArgv"]
        argv_hash = hashlib.sha256(json.dumps(command).encode()).hexdigest()
        if (not command or not all(isinstance(a, str) and a for a in command) or
                report.get("argvSha256") != argv_hash or report.get("commandSha256") != argv_hash):
            raise ValueError("receipt-command-hash-mismatch")
        current_binding, _ = bind_contract(root, report["contractPath"], bound["stageId"], phase,
            bound["sourceFiles"] + bound["testFiles"], command, bound["commandId"], report["expectedSuites"])
        if current_binding != bound:
            raise ValueError("receipt-contract-stale")
        names = bound["sourceFiles"] + bound["testFiles"]
        identities = report["sourceIdentity"]
        if ([r["path"] for r in identities] != names or report.get("sourceIdentityEnd") != identities or
                any(r.get("kind") != "file" or len(r.get("sha256", "")) != 64 for r in identities)):
            raise ValueError("receipt-source-identity-drift")
        stability = report["sourceStability"]
        stat_keys = {"path", "mtimeNs", "ctimeNs", "device", "inode", "bytes"}
        if (not isinstance(stability, list) or len(stability) != len(names) or
                any(not isinstance(row, dict) or set(row) != stat_keys or
                    any(type(row[key]) is not int or row[key] < 0 for key in stat_keys - {"path"})
                    for row in stability) or [row["path"] for row in stability] != names or
                stability != report["sourceStabilityEnd"]):
            raise ValueError("receipt-source-stat-drift")
        current_names = names if phase == "GREEN" else bound["testFiles"]
        current = bound_identity(root, current_names)
        recorded = [r for r in identities if r["path"] in current_names]
        if current != recorded:
            raise ValueError("receipt-current-identity-mismatch")
        log = exact_file(output, report["log"])
        if hashlib.sha256(log.read_bytes()).hexdigest() != report.get("logSha256"):
            raise ValueError("receipt-log-hash-mismatch")
        suites = report["expectedSuites"]
        records = report["resultFiles"]
        if not suites or sorted(r["suite"] for r in records) != suites or len(set(suites)) != len(suites):
            raise ValueError("receipt-junit-coverage-mismatch")
        totals = dict(tests=0, failures=0, errors=0, skipped=0)
        started_ns, ended_ns = report["commandStartedNs"], report["commandEndedNs"]
        # ISO datetimes retain microseconds; float epoch conversion can lose ns.
        offsets = [point - datetime(1970, 1, 1, tzinfo=timezone.utc) for point in (start, end)]
        start_stamp_ns, end_stamp_ns = [(delta.days * 86400 + delta.seconds) * 1_000_000_000 +
            delta.microseconds * 1000 for delta in offsets]
        if (type(started_ns) is not int or type(ended_ns) is not int or ended_ns < started_ns or
                started_ns < start_stamp_ns or ended_ns > end_stamp_ns + 999):
            raise ValueError("receipt-command-times-invalid")
        before_rows = report["initialResultFiles"]
        if sorted(row["suite"] for row in before_rows) != suites:
            raise ValueError("receipt-initial-junit-coverage-mismatch")
        before = {row["suite"]: row["fingerprint"] for row in before_rows}
        for row in records:
            modified = row["sourceModifiedNs"]
            if (type(modified) is not int or not started_ns <= modified <= ended_ns or
                    before[row["suite"]] == [modified, row["bytes"], row["sha256"]]):
                raise ValueError("receipt-junit-not-fresh-during-command")
            xml = exact_file(output, row["path"]).read_bytes()
            if hashlib.sha256(xml).hexdigest() != row["sha256"]:
                raise ValueError("receipt-xml-hash-mismatch")
            counts = junit_counts(ET.fromstring(xml), row["suite"], strict=True)
            if counts != row["counts"]:
                raise ValueError("receipt-xml-count-mismatch")
            for key in totals:
                totals[key] += counts[key]
        if totals != report["totals"] or totals["tests"] <= totals["skipped"]:
            raise ValueError("receipt-no-executed-tests")
        if phase == "GREEN":
            if report["exitCode"] != 0 or report["verificationExitCode"] != 0 or report["failures"] or totals["failures"] or totals["errors"]:
                raise ValueError("receipt-green-outcome-mismatch")
        elif (type(report["exitCode"]) is not int or report["exitCode"] <= 0 or
                not totals["failures"] + totals["errors"] or report["failures"] != ["junit_failure"]):
            raise ValueError("receipt-red-outcome-mismatch")
    except (ValueError, OSError, KeyError, TypeError, AttributeError, ET.ParseError) as failure:
        diagnostics.append(str(failure) if isinstance(failure, ValueError) else "receipt-invalid:" + type(failure).__name__)
    return {"ok": not diagnostics, "diagnostics": diagnostics, "report": report}


def pid_alive(pid):
    if not isinstance(pid, int) or pid <= 0 or pid == os.getpid():
        return False
    if os.name == "nt":
        import ctypes
        handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
        if not handle:
            return False
        try:
            code = ctypes.c_ulong()
            if not ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(code)):
                return False
            return code.value == 259  # STILL_ACTIVE
        finally:
            ctypes.windll.kernel32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def source_identity(paths, limit=200):
    """Hash evidence of the sources under test so a pass cannot be paired with a
    different tree. Files: sha256+size. Directories: manifest hash of member
    (relpath, sha256) pairs, bounded."""
    out = []
    for text in paths or []:
        path = Path(text).resolve()
        entry = {"path": str(path)}
        try:
            if path.is_file():
                entry.update(kind="file", sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                             bytes=path.stat().st_size)
            elif path.is_dir():
                members = []
                for member in sorted(path.rglob("*"))[:limit]:
                    if member.is_file():
                        members.append((str(member.relative_to(path)),
                                        hashlib.sha256(member.read_bytes()).hexdigest()))
                entry.update(kind="dir", fileCount=len(members), bounded=len(members) >= limit,
                             manifestSha256=hashlib.sha256(
                                 json.dumps(members).encode()).hexdigest())
            else:
                entry["kind"] = "absent"
        except OSError as failure:
            entry.update(kind="unreadable", error=type(failure).__name__)
        out.append(entry)
    return out


def load_run(output):
    report = json.loads((Path(output) / "run.json").read_text(encoding="utf-8"))
    if not isinstance(report, dict) or not report.get("runId"):
        raise ValueError("invalid-run-record")
    return report


def run_status(output):
    report = load_run(output)
    observed = {"runId": report["runId"], "status": report["status"],
                "exitCode": report.get("exitCode"), "startedAt": report.get("startedAt"),
                "endedAt": report.get("endedAt"), "log": report.get("log")}
    if report["status"] != "running":
        observed["observation"] = "finalized"
        return observed
    pid = report.get("pid")
    if isinstance(pid, int) and pid_alive(pid):
        observed.update(observation="still_running", pid=pid)
    else:
        # The recorder never finalized the record; the exit code is unknown.
        observed.update(observation="orphaned_unconfirmed", pid=pid)
    return observed


def run_stop(output):
    report = load_run(output)
    if report["status"] != "running":
        return {"runId": report["runId"], "observation": "finalized",
                "status": report["status"]}
    pid = report.get("pid")
    if not isinstance(pid, int) or not pid_alive(pid):
        return {"runId": report["runId"], "observation": "orphaned_unconfirmed", "pid": pid}
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    else:
        try:
            os.killpg(pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            os.kill(pid, signal.SIGKILL)
    time.sleep(0.5)
    report["stoppedAt"] = stamp()
    report["status"] = "stopped"
    report.setdefault("failures", []).append("stopped_by_request")
    (Path(output) / "run.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return {"runId": report["runId"], "observation": "stopped", "pid": pid,
            "aliveAfterStop": pid_alive(pid)}


def stop_owned(process):
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        # A restricted taskkill may fail; Popen's handle still owns this child.
        if process.poll() is None:
            process.kill()
    else:
        os.killpg(process.pid, signal.SIGKILL)
    process.wait(timeout=15)


def run(command, cwd, output, *, suites=(), xml_dir=None, timeout=1800, scope="verification",
        sources=(), contract=None, stage=None, phase=None, command_id=None):
    cwd, output = Path(cwd).resolve(), Path(output).resolve()
    if not command or not all(isinstance(arg, str) and arg for arg in command):
        raise ValueError("command must be a nonempty argument list")
    command = list(command)
    if (cwd / command[0]).is_file():
        command[0] = str((cwd / command[0]).resolve())
    if suites and xml_dir is None:
        raise ValueError("expected suites require xml_dir")
    binding, identities = None, None
    if contract is not None:
        if not suites or xml_dir is None or len(set(suites)) != len(suites):
            raise ValueError("bound-receipt-requires-junit-suites")
        binding, identities = bind_contract(cwd, contract, stage, phase, list(sources), command, command_id, suites)
    elif stage is not None or phase is not None or command_id is not None:
        raise ValueError("stage-phase-require-contract")
    output.mkdir(parents=True, exist_ok=False)
    xml_dir = Path(xml_dir).resolve() if xml_dir else None
    expected = {suite: xml_dir / ("TEST-" + suite + ".xml") for suite in suites}
    if any(path.parent != xml_dir for path in expected.values()):
        raise ValueError("invalid suite path")
    before = {name: fingerprint(path) if path.is_file() else None for name, path in expected.items()}
    report = dict(runId=str(uuid.uuid4()), scope=scope, startedAt=stamp(), status="running",
                  executable=Path(command[0]).name, argumentCount=len(command)-1,
                  cwd=str(cwd), commandArgv=command,
                  commandSha256=hashlib.sha256(json.dumps(command).encode()).hexdigest(),
                  sourceIdentity=source_identity(sources),
                  expectedSuites=sorted(expected), exitCode=None, verificationExitCode=None,
                  resultFiles=[], failures=[], log="command.log")
    if binding:
        report.update(contractBinding=binding, contractPath=contract, sourceIdentity=identities,
            sourceStability=bound_stability(cwd, binding["sourceFiles"] + binding["testFiles"]),
            argvSha256=report["commandSha256"], verificationPhaseOutcome="UNCONFIRMED",
            initialResultFiles=[{"suite": s, "fingerprint": before[s]} for s in sorted(before)],
            receiptIntegrity="local-hash-bound-evidence-not-authentication")

    def persist():
        temporary = output / "run.json.tmp"
        temporary.write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")
        temporary.replace(output / "run.json")

    persist()
    started_ns = time.time_ns()
    if binding:
        report["commandStartedNs"] = started_ns
    process = None
    try:
        with (output / "command.log").open("wb") as log:
            process = subprocess.Popen(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0,
                start_new_session=os.name != "nt")
            report["pid"] = process.pid
            persist()
            report["exitCode"] = process.wait(timeout=timeout)
    except (subprocess.TimeoutExpired, KeyboardInterrupt):
        report["status"] = "interrupted"
        report["failures"].append("command_interrupted")
        if process is not None:
            stop_owned(process)
            report["exitCode"] = process.returncode
    except OSError as failure:
        report["status"] = "launch_failed"
        report["failures"].append(type(failure).__name__)
    finally:
        ended_ns = time.time_ns()
        report["endedAt"] = stamp()
        if binding:
            report["commandEndedNs"] = ended_ns
        report["elapsedMs"] = round((time.time_ns()-started_ns)/1_000_000)
        if report["status"] == "running":
            report["status"] = "passed" if report["exitCode"] == 0 else "failed"
        totals = dict(tests=0, failures=0, errors=0, skipped=0)
        for suite, path in expected.items():
            if not path.is_file():
                report["failures"].append("missing_xml:"+suite)
                continue
            current = fingerprint(path)
            if current == before[suite] or current[0] < started_ns:
                report["failures"].append("stale_xml:"+suite)
                continue
            if binding and current[0] > time.time_ns():
                report["failures"].append("future_xml:"+suite)
                continue
            if binding and current[0] > ended_ns:
                report["failures"].append("late_xml:"+suite)
                continue
            try:
                xml_data = path.read_bytes()
                if binding and (hashlib.sha256(xml_data).hexdigest() != current[2] or fingerprint(path) != current):
                    raise ValueError("xml changed while collecting")
                root = ET.fromstring(xml_data)
                counts = junit_counts(root, suite, strict=bool(binding))
                target = output / path.name
                target.write_bytes(xml_data)
                record = dict(suite=suite, path=target.name, sha256=current[2], counts=counts)
                if binding:
                    record.update(sourceModifiedNs=current[0], bytes=current[1])
                report["resultFiles"].append(record)
                for key in totals:
                    totals[key] += counts[key]
            except (ValueError, ET.ParseError):
                report["failures"].append("invalid_xml:"+suite)
        report["totals"] = totals
        if totals["failures"] or totals["errors"]:
            report["failures"].append("junit_failure")
        if binding:
            report["logSha256"] = hashlib.sha256((output / report["log"]).read_bytes()).hexdigest()
            try:
                report["sourceIdentityEnd"] = bound_identity(cwd, binding["sourceFiles"] + binding["testFiles"])
                report["sourceStabilityEnd"] = bound_stability(cwd, binding["sourceFiles"] + binding["testFiles"])
            except (ValueError, OSError):
                report["sourceIdentityEnd"] = []
                report["sourceStabilityEnd"] = []
            if report["sourceIdentityEnd"] != report["sourceIdentity"]:
                report["failures"].append("source_identity_drift")
            if report["sourceStabilityEnd"] != report["sourceStability"]:
                report["failures"].append("source_stat_drift")
            if totals["tests"] <= totals["skipped"]:
                report["failures"].append("no_executed_tests")
            try:
                current, _ = bind_contract(cwd, contract, stage, phase, list(sources), command, command_id, suites)
                if current != binding:
                    report["failures"].append("contract_identity_drift")
            except (ValueError, OSError, KeyError):
                report["failures"].append("contract_identity_drift")
            if phase == "GREEN" and report["status"] == "passed" and not report["failures"]:
                report["verificationPhaseOutcome"] = "GREEN"
            elif (phase == "RED" and report["status"] == "failed" and report["exitCode"] > 0 and
                    report["failures"] == ["junit_failure"]):
                report["verificationPhaseOutcome"] = "RED"
        if report["status"] == "passed" and report["failures"]:
            report["status"] = "evidence_incomplete"
        report["verificationExitCode"] = (0 if report["status"] == "passed" else
            report["exitCode"] if report["exitCode"] and report["exitCode"] > 0 else 3)
        persist()
    return report


if __name__ == "__main__":
    # `command` is a greedy REMAINDER positional, so the action cannot be a
    # argparse positional — peel a leading run/status/stop token off argv first.
    argv = sys.argv[1:]
    action = "run"
    if argv and argv[0] in ("run", "status", "stop"):
        action = argv.pop(0)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cwd", default=".")
    parser.add_argument("--output", required=True)
    parser.add_argument("--xml-dir")
    parser.add_argument("--suite", action="append", default=[])
    parser.add_argument("--source", action="append", default=[],
                        help="File/dir whose identity is recorded as the tested source")
    parser.add_argument("--contract", help="Canonical repo-relative request-contract JSON")
    parser.add_argument("--stage", help="READY stage ID from the request contract")
    parser.add_argument("--phase", choices=("RED", "GREEN"))
    parser.add_argument("--command-id", help="Success-test commandId label bound separately from actual argv hash")
    parser.add_argument("--timeout", type=float, default=1800)
    parser.add_argument("--scope", default="verification")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    try:
        if action == "status":
            print(json.dumps(run_status(args.output)))
            raise SystemExit(0)
        if action == "stop":
            result = run_stop(args.output)
            print(json.dumps(result))
            raise SystemExit(0 if not result.get("aliveAfterStop") else 2)
        command = args.command[1:] if args.command[:1] == ["--"] else args.command
        result = run(command, args.cwd, args.output, suites=args.suite, xml_dir=args.xml_dir,
                     timeout=args.timeout, scope=args.scope, sources=args.source,
                     contract=args.contract, stage=args.stage, phase=args.phase, command_id=args.command_id)
        print(json.dumps({key: result[key] for key in
                          ("runId", "status", "exitCode", "verificationExitCode", "totals", "failures")}))
        raise SystemExit(result["verificationExitCode"])
    except (ValueError, OSError, KeyError) as failure:
        print(json.dumps({"status": "error", "reason": str(failure)}))
        raise SystemExit(2)
