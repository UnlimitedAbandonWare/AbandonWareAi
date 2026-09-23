"""Run one verification command, preserving its exit code and fresh, scoped JUnit evidence.

Use only non-secret commands. Logs are command output, not a redaction boundary.
No shell pipeline is used. Existing XML is never deleted or counted as a new result.

Actions:
  run (default)   execute and record runId/cwd/argv/times/exit/log/source identity
  status          inspect a run directory: distinguishes still_running /
                  orphaned_unconfirmed (recorder died, child may be gone) from
                  finalized results — never treats an unconfirmed run as passed
  stop            terminate only the recorded run's own process tree
"""
import argparse
import hashlib
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


def stamp():
    return datetime.now(timezone.utc).isoformat()


def fingerprint(path):
    stat = path.stat()
    return (stat.st_mtime_ns, stat.st_size, hashlib.sha256(path.read_bytes()).hexdigest())


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
    else:
        os.killpg(process.pid, signal.SIGKILL)
    process.wait(timeout=15)


def run(command, cwd, output, *, suites=(), xml_dir=None, timeout=1800, scope="verification",
        sources=()):
    cwd, output = Path(cwd).resolve(), Path(output).resolve()
    if not command or not all(isinstance(arg, str) and arg for arg in command):
        raise ValueError("command must be a nonempty argument list")
    command = list(command)
    if (cwd / command[0]).is_file():
        command[0] = str((cwd / command[0]).resolve())
    if suites and xml_dir is None:
        raise ValueError("expected suites require xml_dir")
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

    def persist():
        temporary = output / "run.json.tmp"
        temporary.write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")
        temporary.replace(output / "run.json")

    persist()
    started_ns = time.time_ns()
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
        report["endedAt"] = stamp()
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
            try:
                root = ET.fromstring(path.read_bytes())
                if root.tag != "testsuite" or root.attrib.get("name") != suite:
                    raise ValueError("suite identity mismatch")
                counts = {key: int(root.attrib.get(key, "0")) for key in totals}
                if counts["tests"] <= 0 or any(n < 0 for n in counts.values()):
                    raise ValueError("invalid counts")
                if len(root.findall("testcase")) != counts["tests"]:
                    raise ValueError("testcase count mismatch")
                target = output / path.name
                target.write_bytes(path.read_bytes())
                report["resultFiles"].append(dict(suite=suite, path=target.name,
                    sha256=current[2], counts=counts))
                for key in totals:
                    totals[key] += counts[key]
            except (ValueError, ET.ParseError):
                report["failures"].append("invalid_xml:"+suite)
        report["totals"] = totals
        if totals["failures"] or totals["errors"]:
            report["failures"].append("junit_failure")
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
                     timeout=args.timeout, scope=args.scope, sources=args.source)
        print(json.dumps({key: result[key] for key in
                          ("runId", "status", "exitCode", "verificationExitCode", "totals", "failures")}))
        raise SystemExit(result["verificationExitCode"])
    except (ValueError, OSError, KeyError) as failure:
        print(json.dumps({"status": "error", "reason": str(failure)}))
        raise SystemExit(2)
