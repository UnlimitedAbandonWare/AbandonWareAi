#!/usr/bin/env python3
"""Owner-scoped dynamic port lease for parallel agent servers.

Contract: port.acquire -> process.start -> health.check -> debug.trace
-> verify -> process.stop -> port.release.

Stops and releases only the pid and lease recorded for the same owner and
session. Meta Display ports 18180-18182 are never bound or killed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path

SCHEMA = "awx.agent-port-lease.v1"
TRACE_SCHEMA = "awx.agent-port-trace.v1"
CONTRACT = (
    "port.acquire",
    "process.start",
    "health.check",
    "debug.trace",
    "verify",
    "process.stop",
    "port.release",
)
PROTECTED_PORTS = frozenset({18180, 18181, 18182})
DEFAULT_RANGE = (25000, 25999)
ACTIVE_STATES = frozenset({"reserved", "starting", "running", "unhealthy"})
RETRYABLE = frozenset({
    "port-conflict",
    "occupied-foreign",
    "start-failed",
    "http-unreachable",
    "http-failed",
    "timeout",
})
ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$")
SECRET_RE = re.compile(
    r"(?i)\b(api[_-]?key|token|secret|password|authorization|bearer)\b\s*[=:]\s*\S+"
)
TRACE_KEYS = (
    "traceId", "leaseId", "owner", "session", "service", "port", "pid",
    "startedAt", "endedAt", "endStatus", "httpStatus", "exceptionType",
    "cause", "phase", "attempt",
)


class LeaseError(Exception):
    def __init__(self, cause, exit_code=2):
        super().__init__(cause)
        self.cause = cause
        self.exit_code = exit_code


def utc_now():
    return datetime.now(timezone.utc)


def iso(moment):
    return moment.astimezone(timezone.utc).isoformat()


def parse_iso(text):
    if not text:
        return None
    try:
        moment = datetime.fromisoformat(str(text).replace("Z", "+00:00"))
    except ValueError:
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment


def require_id(value, label):
    if not isinstance(value, str) or not ID_RE.match(value):
        raise LeaseError(f"invalid-{label}")
    return value


def redact(text):
    return SECRET_RE.sub(lambda match: match.group(1) + "=<redacted>", text or "")


def substitute(text, values):
    out = str(text)
    for key, value in values.items():
        out = out.replace("{" + key + "}", str(value))
    return out


def new_id(prefix):
    return prefix + hashlib.sha256(os.urandom(16)).hexdigest()[:12]


def result(ok, cause=None, exit_code=0, **extra):
    body = {
        "schemaVersion": SCHEMA,
        "ok": bool(ok),
        "cause": cause,
        "exitCode": exit_code if not ok else 0,
        "contract": list(CONTRACT),
    }
    body.update(extra)
    return body


class AgentPortLease:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.store = self.root / "var" / "agent-port-lease"

    def lease_path(self, lease_id):
        require_id(lease_id, "lease")
        return self.store / "leases" / (lease_id + ".json")

    def log_path(self, lease_id):
        require_id(lease_id, "lease")
        return self.store / "logs" / (lease_id + ".log")

    @contextmanager
    def allocation_lock(self):
        self.store.mkdir(parents=True, exist_ok=True)
        handle = open(self.store / "alloc.lock", "a+b")
        locked = False
        try:
            handle.seek(0, os.SEEK_END)
            if handle.tell() < 1:
                handle.write(b"\0")
                handle.flush()
            handle.seek(0)
            if os.name == "nt":
                import msvcrt
                deadline = time.monotonic() + 8
                while True:
                    try:
                        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                        locked = True
                        break
                    except OSError:
                        if time.monotonic() >= deadline:
                            raise LeaseError("alloc-lock-busy", 3)
                        time.sleep(0.02)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
                locked = True
            yield
        finally:
            if locked:
                try:
                    if os.name == "nt":
                        import msvcrt
                        handle.seek(0)
                        msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                    else:
                        import fcntl
                        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
                except OSError:
                    pass
            handle.close()

    def port_bindable(self, port):
        probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            if os.name == "nt":
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            probe.bind(("127.0.0.1", int(port)))
            return True
        except OSError:
            return False
        finally:
            probe.close()

    def listen_owner(self, port):
        if os.name != "nt":
            return None
        try:
            completed = subprocess.run(
                ["netstat", "-ano", "-p", "tcp"],
                capture_output=True, timeout=5, check=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            return None
        decoded = (completed.stdout or b"").decode("utf-8", errors="replace")
        for line in decoded.splitlines():
            if "LISTENING" not in line.upper():
                continue
            parts = line.split()
            if len(parts) < 5 or not parts[1].endswith(":" + str(int(port))):
                continue
            try:
                return int(parts[-1])
            except ValueError:
                return None
        return None

    def process_identity(self, pid):
        pid = int(pid)
        if pid <= 4 or pid == os.getpid():
            return None
        if os.name != "nt":
            try:
                os.kill(pid, 0)
            except OSError:
                return None
            return {"pid": pid, "createTime": None}
        import ctypes
        filetime = ctypes_filetime()
        kernel = ctypes.windll.kernel32
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            return None
        try:
            created, exited = filetime(), filetime()
            kernel_time, user_time = filetime(), filetime()
            ok = kernel.GetProcessTimes(
                handle, ctypes.byref(created), ctypes.byref(exited),
                ctypes.byref(kernel_time), ctypes.byref(user_time),
            )
            if not ok:
                return None
            create_time = (created.dwHighDateTime << 32) | created.dwLowDateTime
            return {"pid": pid, "createTime": int(create_time)}
        finally:
            kernel.CloseHandle(handle)

    def kill_process(self, pid):
        pid = int(pid)
        if pid <= 4 or pid == os.getpid():
            raise LeaseError("refuse-protected-pid", 3)
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/PID", str(pid), "/T", "/F"],
                capture_output=True, timeout=15, check=False,
            )
            return "taskkill"
        os.kill(pid, 15)
        return "sigterm"

    def holds_port(self, lease, now=None):
        if lease.get("state") not in ACTIVE_STATES:
            return False
        expires = parse_iso(lease.get("expiresAt"))
        if expires is None:
            return True
        return expires > (now or utc_now())

    def list_leases(self):
        folder = self.store / "leases"
        found = []
        if not folder.is_dir():
            return found
        for path in sorted(folder.glob("apl-*.json")):
            try:
                found.append(json.loads(path.read_text(encoding="utf-8")))
            except (OSError, json.JSONDecodeError):
                continue
        return found

    def load_lease(self, lease_id):
        path = self.lease_path(lease_id)
        if not path.is_file():
            raise LeaseError("lease-not-found", 3)
        return json.loads(path.read_text(encoding="utf-8"))

    def save_lease(self, lease):
        path = self.lease_path(lease["leaseId"])
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(lease, ensure_ascii=True, indent=2), encoding="utf-8")
        os.replace(temporary, path)

    def append_trace(self, trace):
        for key in TRACE_KEYS:
            trace.setdefault(key, None)
        trace["schemaVersion"] = TRACE_SCHEMA
        path = self.store / "traces.jsonl"
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(trace, ensure_ascii=True) + "\n")
        return trace

    def read_traces(self):
        path = self.store / "traces.jsonl"
        if not path.is_file():
            return []
        data = path.read_bytes()
        if len(data) > 8_000_000:
            data = data[-8_000_000:]
            data = data.split(b"\n", 1)[-1]
        rows = []
        for line in data.decode("utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                continue
        return rows

    def _owned(self, lease, owner, session):
        return lease.get("owner") == owner and lease.get("session") == session

    def _public_lease(self, lease):
        now = utc_now()
        state = lease.get("state")
        if state in ACTIVE_STATES and not self.holds_port(lease, now):
            state = "expired"
        return {
            "leaseId": lease.get("leaseId"),
            "owner": lease.get("owner"),
            "session": lease.get("session"),
            "service": lease.get("service"),
            "port": lease.get("port"),
            "pid": lease.get("pid"),
            "state": state,
            "startedAt": lease.get("startedAt"),
            "endedAt": lease.get("endedAt"),
            "expiresAt": lease.get("expiresAt"),
            "killAllowed": bool(lease.get("killAllowed")),
            "cause": lease.get("cause"),
            "logPath": lease.get("logPath"),
            "tracePath": "var/agent-port-lease/traces.jsonl",
        }

    def acquire(self, owner, session, service="agent-server", port_range=DEFAULT_RANGE,
                port=None, ttl_seconds=1800, extra_protected=()):
        owner = require_id(owner, "owner")
        session = require_id(session, "session")
        service = require_id(service, "service")
        if ttl_seconds < 30 or ttl_seconds > 86400:
            raise LeaseError("ttl-out-of-range")
        protected = set(PROTECTED_PORTS)
        protected.update(int(item) for item in extra_protected)
        lo, hi = port_range
        if lo < 1 or hi > 65535 or hi < lo or hi - lo > 2000:
            raise LeaseError("port-range")
        with self.allocation_lock():
            now = utc_now()
            taken = set()
            for lease in self.list_leases():
                if self.holds_port(lease, now):
                    taken.add(int(lease["port"]))
            skipped = []
            chosen = None
            candidates = [int(port)] if port is not None else list(range(int(lo), int(hi) + 1))
            for candidate in candidates:
                if candidate in protected:
                    skipped.append({"port": candidate, "cause": "protected-port", "occupantPid": None})
                    continue
                if candidate in taken:
                    skipped.append({"port": candidate, "cause": "lease-held", "occupantPid": None})
                    continue
                if not self.port_bindable(candidate):
                    skipped.append({
                        "port": candidate,
                        "cause": "occupied-foreign",
                        "occupantPid": self.listen_owner(candidate),
                    })
                    continue
                chosen = candidate
                break
            if chosen is None:
                cause = "protected-port" if port is not None and int(port) in protected else (
                    "occupied-foreign" if port is not None else "no-free-port"
                )
                self.append_trace(self._trace(
                    None, owner, session, service, port, None, "acquire", "refused", cause,
                ))
                return result(False, cause, 3, skipped=skipped[:20], owner=owner, session=session)
            lease = {
                "schemaVersion": SCHEMA,
                "leaseId": new_id("apl-"),
                "owner": owner,
                "session": session,
                "service": service,
                "port": chosen,
                "pid": None,
                "processCreateTime": None,
                "killAllowed": False,
                "state": "reserved",
                "cause": None,
                "startedAt": iso(now),
                "endedAt": None,
                "expiresAt": iso(now + timedelta(seconds=int(ttl_seconds))),
                "logPath": None,
                "argvRedacted": [],
                "attempt": 1,
            }
            self.save_lease(lease)
            trace = self.append_trace(self._trace(
                lease, owner, session, service, chosen, None, "acquire", "ok", None,
            ))
            return result(
                True, lease=self._public_lease(lease), skipped=skipped[:20],
                traceId=trace["traceId"], owner=owner, session=session, port=chosen,
            )

    def _trace(self, lease, owner, session, service, port, pid, phase, end_status, cause,
               http_status=None, exception_type=None, attempt=1, started_at=None,
               log_start=None, log_end=None):
        now = iso(utc_now())
        return {
            "traceId": new_id("trc-"),
            "leaseId": None if lease is None else lease.get("leaseId"),
            "owner": owner,
            "session": session,
            "service": service,
            "port": port,
            "pid": pid,
            "startedAt": started_at or now,
            "endedAt": now,
            "endStatus": end_status,
            "httpStatus": http_status,
            "exceptionType": exception_type,
            "cause": cause,
            "phase": phase,
            "attempt": attempt,
            "logOffsetStart": log_start,
            "logOffsetEnd": log_end,
        }

    def _require_owned(self, lease_id, owner, session):
        owner = require_id(owner, "owner")
        session = require_id(session, "session")
        lease = self.load_lease(lease_id)
        if not self._owned(lease, owner, session):
            raise LeaseError("owner-mismatch", 3)
        return lease

    def release(self, owner, session, lease_id):
        try:
            return self._release_owned(owner, session, lease_id)
        except LeaseError as exc:
            return result(False, exc.cause, exc.exit_code)

    def _release_owned(self, owner, session, lease_id):
        with self.allocation_lock():
            lease = self._require_owned(lease_id, owner, session)
            if lease.get("state") in {"running", "starting", "unhealthy"} and lease.get("pid"):
                return result(False, "process-still-running", 3, lease=self._public_lease(lease))
            if lease.get("state") == "released":
                return result(True, lease=self._public_lease(lease))
            lease["state"] = "released"
            lease["endedAt"] = iso(utc_now())
            lease["cause"] = None
            self.save_lease(lease)
            trace = self.append_trace(self._trace(
                lease, owner, session, lease.get("service"), lease.get("port"), lease.get("pid"),
                "release", "released", None,
            ))
            return result(True, lease=self._public_lease(lease), traceId=trace["traceId"])

    def heartbeat(self, owner, session, lease_id, ttl_seconds=1800):
        if ttl_seconds < 30 or ttl_seconds > 86400:
            raise LeaseError("ttl-out-of-range")
        with self.allocation_lock():
            lease = self._require_owned(lease_id, owner, session)
            if lease.get("state") not in ACTIVE_STATES:
                return result(False, "lease-not-active", 3, lease=self._public_lease(lease))
            lease["expiresAt"] = iso(utc_now() + timedelta(seconds=int(ttl_seconds)))
            self.save_lease(lease)
            return result(True, lease=self._public_lease(lease))

    def _stop_locked(self, lease, owner, session):
        pid = lease.get("pid")
        if not pid:
            lease["state"] = "stopped" if lease.get("state") != "released" else lease["state"]
            lease["endedAt"] = iso(utc_now())
            self.save_lease(lease)
            trace = self.append_trace(self._trace(
                lease, owner, session, lease.get("service"), lease.get("port"), None,
                "stop", "stopped", "process-missing",
            ))
            return result(True, cause="process-missing", lease=self._public_lease(lease),
                          traceId=trace["traceId"])
        live = self.process_identity(pid)
        if live is None:
            lease["state"] = "stopped"
            lease["pid"] = None
            lease["endedAt"] = iso(utc_now())
            lease["cause"] = "process-missing"
            self.save_lease(lease)
            trace = self.append_trace(self._trace(
                lease, owner, session, lease.get("service"), lease.get("port"), pid,
                "stop", "stopped", "process-missing",
            ))
            return result(True, cause="process-missing", lease=self._public_lease(lease),
                          traceId=trace["traceId"])
        expected = lease.get("processCreateTime")
        if not lease.get("killAllowed") or expected is None or live.get("createTime") != expected:
            cause = "identity-unproven" if not lease.get("killAllowed") or expected is None else "pid-reused"
            trace = self.append_trace(self._trace(
                lease, owner, session, lease.get("service"), lease.get("port"), pid,
                "stop", "refused", cause, exception_type=cause,
            ))
            return result(False, cause, 3, lease=self._public_lease(lease), traceId=trace["traceId"])
        again = self.process_identity(pid)
        if again is None or again.get("createTime") != expected:
            trace = self.append_trace(self._trace(
                lease, owner, session, lease.get("service"), lease.get("port"), pid,
                "stop", "refused", "pid-reused", exception_type="pid-reused",
            ))
            return result(False, "pid-reused", 3, lease=self._public_lease(lease),
                          traceId=trace["traceId"])
        self.kill_process(pid)
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline and self.process_identity(pid) is not None:
            current = self.process_identity(pid)
            if current is None or current.get("createTime") != expected:
                break
            time.sleep(0.05)
        still = self.process_identity(pid)
        if still is not None and still.get("createTime") == expected:
            trace = self.append_trace(self._trace(
                lease, owner, session, lease.get("service"), lease.get("port"), pid,
                "stop", "failed", "stop-failed", exception_type="StopFailed",
            ))
            return result(False, "stop-failed", 4, lease=self._public_lease(lease),
                          traceId=trace["traceId"])
        lease["state"] = "stopped"
        lease["pid"] = None
        lease["killAllowed"] = False
        lease["endedAt"] = iso(utc_now())
        lease["cause"] = None
        self.save_lease(lease)
        trace = self.append_trace(self._trace(
            lease, owner, session, lease.get("service"), lease.get("port"), pid,
            "stop", "stopped", None,
        ))
        return result(True, lease=self._public_lease(lease), traceId=trace["traceId"], stoppedPid=pid)

    def stop(self, owner, session, lease_id):
        try:
            with self.allocation_lock():
                lease = self._require_owned(lease_id, owner, session)
                return self._stop_locked(lease, owner, session)
        except LeaseError as exc:
            return result(False, exc.cause, exc.exit_code)

    def close(self, owner, session, lease_id):
        stopped = self.stop(owner, session, lease_id)
        if not stopped["ok"] and stopped.get("cause") not in {"process-missing"}:
            return stopped
        released = self.release(owner, session, lease_id)
        released["stopped"] = stopped
        return released

    def http_probe(self, url, timeout):
        request = urllib.request.Request(url, method="GET")
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                status = int(response.status)
                ok = 200 <= status < 300
                return {
                    "ok": ok,
                    "httpStatus": status,
                    "cause": None if ok else "http-failed",
                    "exceptionType": None if ok else "HTTPError",
                }
        except urllib.error.HTTPError as exc:
            return {
                "ok": False, "httpStatus": int(exc.code), "cause": "http-failed",
                "exceptionType": "HTTPError",
            }
        except urllib.error.URLError as exc:
            reason = exc.reason
            timed_out = isinstance(reason, (TimeoutError, socket.timeout)) or "timed out" in str(reason).lower()
            return {
                "ok": False,
                "httpStatus": None,
                "cause": "timeout" if timed_out else "http-unreachable",
                "exceptionType": type(reason).__name__ if reason is not None else "URLError",
            }
        except (TimeoutError, socket.timeout):
            return {"ok": False, "httpStatus": None, "cause": "timeout", "exceptionType": "TimeoutError"}

    def _health_url(self, template, port):
        url = substitute(template, {"port": int(port)})
        parsed = urllib.parse.urlparse(url)
        host = (parsed.hostname or "").lower()
        if parsed.scheme != "http" or host not in {"127.0.0.1", "localhost"}:
            raise LeaseError("health-url-not-loopback")
        if parsed.port != int(port):
            raise LeaseError("health-url-port")
        return url

    def _log_size(self, lease_id):
        path = self.log_path(lease_id)
        if not path.is_file():
            return 0
        return path.stat().st_size

    def _log_text(self, lease_id, start=0, end=None):
        path = self.log_path(lease_id)
        if not path.is_file():
            return ""
        data = path.read_bytes()
        start = max(0, int(start or 0))
        end = len(data) if end is None else min(len(data), int(end))
        return redact(data[start:end].decode("utf-8", errors="replace"))

    def _classify_exit(self, excerpt, code):
        lowered = excerpt.lower()
        if (
            "address already in use" in lowered
            or "eaddrinuse" in lowered
            or "10048" in lowered
            or "only one usage of each socket address" in lowered
        ):
            return "port-conflict", "AddressInUse"
        return "start-failed", "Exit_" + str(code)

    def start(self, owner, session, lease_id, argv, health_url="http://127.0.0.1:{port}/",
              health_timeout=5, cwd=None):
        if not argv or not all(isinstance(item, str) and item for item in argv):
            raise LeaseError("command-required")
        started_at = iso(utc_now())
        with self.allocation_lock():
            lease = self._require_owned(lease_id, owner, session)
            if lease.get("state") != "reserved":
                return result(False, "lease-not-reserved", 3, lease=self._public_lease(lease))
            port = int(lease["port"])
            url = self._health_url(health_url, port)
            lease["state"] = "starting"
            self.save_lease(lease)
            values = {
                "port": port,
                "lease": lease["leaseId"],
                "owner": owner,
                "session": session,
                "service": lease.get("service"),
            }
            expanded = [substitute(item, values) for item in argv]
            log_path = self.log_path(lease["leaseId"])
            log_path.parent.mkdir(parents=True, exist_ok=True)
            work = Path(cwd) if cwd else self.root
            if not work.is_dir():
                lease["state"] = "failed"
                lease["cause"] = "cwd-missing"
                self.save_lease(lease)
                return result(False, "cwd-missing", 2, lease=self._public_lease(lease))
        log_start = self._log_size(lease["leaseId"])
        env = os.environ.copy()
        env.update({
            "AWX_AGENT_PORT": str(port),
            "AWX_AGENT_LEASE": lease["leaseId"],
            "AWX_AGENT_OWNER": owner,
            "AWX_AGENT_SESSION": session,
            "AWX_AGENT_SERVICE": lease.get("service") or "",
            "PYTHONUNBUFFERED": "1",
        })
        flags = {}
        if os.name == "nt":
            flags["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
        try:
            log_handle = open(log_path, "ab", buffering=0)
            try:
                proc = subprocess.Popen(
                    expanded,
                    stdin=subprocess.DEVNULL,
                    stdout=log_handle,
                    stderr=subprocess.STDOUT,
                    cwd=str(work),
                    env=env,
                    **flags,
                )
            finally:
                log_handle.close()
        except OSError as exc:
            with self.allocation_lock():
                lease = self.load_lease(lease_id)
                lease["state"] = "failed"
                lease["cause"] = "start-failed"
                lease["endedAt"] = iso(utc_now())
                self.save_lease(lease)
                trace = self.append_trace(self._trace(
                    lease, owner, session, lease.get("service"), port, None, "start",
                    "failed", "start-failed", exception_type=type(exc).__name__,
                    started_at=started_at, log_start=log_start, log_end=self._log_size(lease_id),
                ))
            return result(False, "start-failed", 4, lease=self._public_lease(lease),
                          traceId=trace["traceId"], exceptionType=type(exc).__name__)
        identity = self.process_identity(proc.pid)
        with self.allocation_lock():
            lease = self.load_lease(lease_id)
            lease["pid"] = int(proc.pid)
            lease["processCreateTime"] = None if identity is None else identity.get("createTime")
            lease["killAllowed"] = identity is not None and identity.get("createTime") is not None
            lease["argvRedacted"] = [redact(item)[:180] for item in expanded]
            lease["logPath"] = "var/agent-port-lease/logs/" + lease["leaseId"] + ".log"
            self.save_lease(lease)
        probe = self._wait_healthy(proc, url, float(health_timeout), lease_id)
        if proc.poll() is None:
            # Lease stop uses pid plus create time. Drop this handle so the
            # starter can exit without reaping a server that is still in use.
            proc.returncode = 0
        log_end = self._log_size(lease_id)
        with self.allocation_lock():
            lease = self.load_lease(lease_id)
            end_status = "ok" if probe["ok"] else "failed"
            lease["state"] = "running" if probe["ok"] else "failed"
            lease["cause"] = probe.get("cause")
            if not probe["ok"]:
                lease["endedAt"] = iso(utc_now())
            self.save_lease(lease)
            if not probe["ok"] and lease.get("pid"):
                stopped = self._stop_locked(lease, owner, session)
                lease = self.load_lease(lease_id)
                if stopped["ok"]:
                    lease["state"] = "failed"
                    lease["cause"] = probe.get("cause")
                    self.save_lease(lease)
            trace = self.append_trace(self._trace(
                lease, owner, session, lease.get("service"), port, proc.pid, "health",
                "ok" if probe["ok"] else ("timeout" if probe.get("cause") == "timeout" else "failed"),
                probe.get("cause"), http_status=probe.get("httpStatus"),
                exception_type=probe.get("exceptionType"), started_at=started_at,
                log_start=log_start, log_end=log_end,
            ))
        return result(
            probe["ok"], None if probe["ok"] else probe.get("cause"),
            0 if probe["ok"] else 4,
            lease=self._public_lease(lease), traceId=trace["traceId"],
            httpStatus=probe.get("httpStatus"), exceptionType=probe.get("exceptionType"),
            healthUrl=url,
        )

    def _wait_healthy(self, proc, url, timeout, lease_id):
        deadline = time.monotonic() + max(0.2, timeout)
        last = {"ok": False, "cause": "timeout", "exceptionType": "TimeoutError", "httpStatus": None}
        while True:
            code = proc.poll()
            if code is not None:
                excerpt = self._log_text(lease_id)
                cause, exception_type = self._classify_exit(excerpt[-4000:], code)
                return {"ok": False, "cause": cause, "exceptionType": exception_type, "httpStatus": None}
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return last
            last = self.http_probe(url, timeout=min(0.5, remaining))
            if last["ok"] or last["cause"] == "http-failed":
                return last
            if time.monotonic() >= deadline:
                last = dict(last)
                last["cause"] = "timeout"
                last["exceptionType"] = last.get("exceptionType") or "TimeoutError"
                return last
            time.sleep(0.1)

    def health(self, owner, session, lease_id, health_url="http://127.0.0.1:{port}/", timeout=3):
        lease = self._require_owned(lease_id, owner, session)
        url = self._health_url(health_url, int(lease["port"]))
        probe = self.http_probe(url, float(timeout))
        end_status = "ok" if probe["ok"] else ("timeout" if probe["cause"] == "timeout" else "failed")
        trace = self.append_trace(self._trace(
            lease, owner, session, lease.get("service"), lease.get("port"), lease.get("pid"),
            "health", end_status, probe.get("cause"), http_status=probe.get("httpStatus"),
            exception_type=probe.get("exceptionType"),
        ))
        if not probe["ok"] and lease.get("state") == "running":
            with self.allocation_lock():
                current = self.load_lease(lease_id)
                if self._owned(current, owner, session) and current.get("state") == "running":
                    current["state"] = "unhealthy"
                    current["cause"] = probe.get("cause")
                    self.save_lease(current)
                    lease = current
        return result(
            probe["ok"], None if probe["ok"] else probe.get("cause"), 0 if probe["ok"] else 4,
            lease=self._public_lease(lease), traceId=trace["traceId"],
            httpStatus=probe.get("httpStatus"), exceptionType=probe.get("exceptionType"),
        )

    def verify(self, owner, session, lease_id, argv=None, health_url="http://127.0.0.1:{port}/",
               timeout=5):
        lease = self._require_owned(lease_id, owner, session)
        if not argv:
            probed = self.health(owner, session, lease_id, health_url=health_url, timeout=timeout)
            trace = self.append_trace(self._trace(
                lease, owner, session, lease.get("service"), lease.get("port"), lease.get("pid"),
                "verify", "ok" if probed["ok"] else "failed", probed.get("cause"),
                http_status=probed.get("httpStatus"), exception_type=probed.get("exceptionType"),
            ))
            probed["traceId"] = trace["traceId"]
            probed["verifyMode"] = "health-confirm"
            return probed
        values = {
            "port": lease.get("port"),
            "lease": lease["leaseId"],
            "owner": owner,
            "session": session,
        }
        expanded = [substitute(item, values) for item in argv]
        try:
            completed = subprocess.run(
                expanded, cwd=str(self.root), capture_output=True,
                timeout=float(timeout), check=False,
            )
        except subprocess.TimeoutExpired:
            trace = self.append_trace(self._trace(
                lease, owner, session, lease.get("service"), lease.get("port"), lease.get("pid"),
                "verify", "timeout", "timeout", exception_type="TimeoutExpired",
            ))
            return result(False, "timeout", 4, lease=self._public_lease(lease), traceId=trace["traceId"],
                          exceptionType="TimeoutExpired", verifyMode="command")
        except OSError as exc:
            trace = self.append_trace(self._trace(
                lease, owner, session, lease.get("service"), lease.get("port"), lease.get("pid"),
                "verify", "failed", "verify-failed", exception_type=type(exc).__name__,
            ))
            return result(False, "verify-failed", 4, lease=self._public_lease(lease),
                          traceId=trace["traceId"], exceptionType=type(exc).__name__,
                          verifyMode="command")
        ok = completed.returncode == 0
        cause = None if ok else "verify-failed"
        trace = self.append_trace(self._trace(
            lease, owner, session, lease.get("service"), lease.get("port"), lease.get("pid"),
            "verify", "ok" if ok else "failed", cause,
            exception_type=None if ok else "Exit_" + str(completed.returncode),
        ))
        return result(ok, cause, 0 if ok else 4, lease=self._public_lease(lease),
                      traceId=trace["traceId"], verifyMode="command",
                      exceptionType=None if ok else "Exit_" + str(completed.returncode))

    def trace_add(self, owner, session, lease_id, http_status=None, end_status="recorded",
                  exception_type=None, cause=None, phase="request"):
        lease = self._require_owned(lease_id, owner, session)
        offset = self._log_size(lease_id)
        trace = self.append_trace(self._trace(
            lease, owner, session, lease.get("service"), lease.get("port"), lease.get("pid"),
            phase, end_status, cause, http_status=http_status, exception_type=exception_type,
            log_start=offset, log_end=offset,
        ))
        return result(True, trace=trace, traceId=trace["traceId"])

    def trace_query(self, owner=None, session=None, lease_id=None, trace_id=None, cause=None):
        rows = []
        for row in self.read_traces():
            if owner and row.get("owner") != owner:
                continue
            if session and row.get("session") != session:
                continue
            if lease_id and row.get("leaseId") != lease_id:
                continue
            if trace_id and row.get("traceId") != trace_id:
                continue
            if cause and row.get("cause") != cause:
                continue
            rows.append({key: row.get(key) for key in TRACE_KEYS})
        return result(True, traces=rows[-200:], count=len(rows))

    def diagnose(self, action, owner, session, lease_id=None, trace_id=None, lines=80,
                 follow_seconds=0, window_bytes=4000):
        if action == "trace":
            return self.trace_query(owner=owner, session=session, trace_id=trace_id, lease_id=lease_id)
        require_id(owner or "", "owner")
        require_id(session or "", "session")
        if action == "tail":
            lease = self._require_owned(lease_id, owner, session)
            return self._tail(lease, int(lines), float(follow_seconds))
        if action == "around":
            return self._around(owner, session, lease_id, trace_id, int(window_bytes))
        raise LeaseError("diagnose-action")

    def _tail(self, lease, lines, follow_seconds):
        path = self.log_path(lease["leaseId"])
        collected = []
        deadline = time.monotonic() + max(0.0, follow_seconds)

        def snapshot():
            if not path.is_file():
                return []
            text = redact(path.read_text(encoding="utf-8", errors="replace"))
            return text.splitlines()[-lines:]

        collected = snapshot()
        while time.monotonic() < deadline:
            time.sleep(0.2)
            collected = snapshot()
        return result(
            True, lease=self._public_lease(lease), lines=collected,
            logPath=lease.get("logPath"), followedSeconds=follow_seconds,
        )

    def _around(self, owner, session, lease_id, trace_id, window_bytes):
        matches = self.trace_query(owner=owner, session=session, lease_id=lease_id, trace_id=trace_id)
        chosen = None
        for row in self.read_traces():
            if trace_id and row.get("traceId") != trace_id:
                continue
            if lease_id and row.get("leaseId") != lease_id:
                continue
            if owner and row.get("owner") != owner:
                continue
            if session and row.get("session") != session:
                continue
            chosen = row
            if trace_id:
                break
        if chosen is None:
            return result(False, "trace-not-found", 3, traces=matches["traces"])
        target = chosen.get("leaseId")
        if target:
            require_id(target, "lease")
            start = chosen.get("logOffsetStart")
            end = chosen.get("logOffsetEnd")
            if start is None:
                start = 0
            pad_start = max(0, int(start) - window_bytes)
            pad_end = None if end is None else int(end) + window_bytes
            excerpt = self._log_text(target, pad_start, pad_end)[:8000]
        else:
            excerpt = ""
        return result(True, trace={key: chosen.get(key) for key in TRACE_KEYS}, excerpt=excerpt)

    def status(self, owner=None, session=None):
        rows = []
        for lease in self.list_leases():
            if owner and lease.get("owner") != owner:
                continue
            if session and lease.get("session") != session:
                continue
            rows.append(self._public_lease(lease))
        return result(True, leases=rows, count=len(rows), store="var/agent-port-lease")

    def reap(self, owner, session):
        owner = require_id(owner, "owner")
        session = require_id(session, "session")
        closed = []
        skipped = []
        for lease in self.list_leases():
            if not self._owned(lease, owner, session):
                continue
            if lease.get("state") not in ACTIVE_STATES or self.holds_port(lease):
                continue
            outcome = self.close(owner, session, lease["leaseId"])
            (closed if outcome["ok"] else skipped).append({
                "leaseId": lease["leaseId"],
                "port": lease.get("port"),
                "cause": outcome.get("cause"),
            })
        return result(True, closed=closed, skipped=skipped)

    def run(self, owner, session, service, argv, port_range=DEFAULT_RANGE, attempts=3,
            health_url="http://127.0.0.1:{port}/", health_timeout=5, verify_argv=None,
            verify_timeout=5, keep=False, ttl_seconds=1800, cwd=None):
        if attempts < 1 or attempts > 8:
            raise LeaseError("attempts-out-of-range")
        if not argv:
            raise LeaseError("command-required")
        attempt_rows = []
        last = None
        for number in range(1, int(attempts) + 1):
            acquired = self.acquire(
                owner, session, service=service, port_range=port_range, ttl_seconds=ttl_seconds,
            )
            acquired["attempt"] = number
            acquired["phase"] = "port.acquire"
            if not acquired["ok"]:
                attempt_rows.append(acquired)
                last = acquired
                if acquired.get("cause") not in RETRYABLE and acquired.get("cause") != "no-free-port":
                    break
                continue
            lease_id = acquired["lease"]["leaseId"]
            try:
                started = self.start(
                    owner, session, lease_id, argv, health_url=health_url,
                    health_timeout=health_timeout, cwd=cwd,
                )
            except LeaseError as exc:
                self.release(owner, session, lease_id)
                return result(False, exc.cause, exc.exit_code, attempts=attempt_rows, kept=False)
            started["attempt"] = number
            started["phase"] = "health.check"
            started["skipped"] = acquired.get("skipped")
            attempt_rows.append(started)
            last = started
            if not started["ok"]:
                self.release(owner, session, lease_id)
                if started.get("cause") in RETRYABLE and number < attempts:
                    continue
                break
            verified = self.verify(
                owner, session, lease_id, argv=verify_argv, health_url=health_url,
                timeout=verify_timeout,
            )
            verified["attempt"] = number
            verified["phase"] = "verify"
            attempt_rows.append(verified)
            last = verified
            if not verified["ok"]:
                self.close(owner, session, lease_id)
                if number < attempts:
                    continue
                break
            if keep:
                return result(
                    True, lease=self.load_public(owner, session, lease_id),
                    attempts=attempt_rows, kept=True, traceId=verified.get("traceId"),
                )
            closed = self.close(owner, session, lease_id)
            return result(
                closed["ok"], None if closed["ok"] else closed.get("cause"),
                0 if closed["ok"] else closed.get("exitCode", 4),
                attempts=attempt_rows, kept=False, lease=closed.get("lease"),
                traceId=verified.get("traceId"),
            )
        cause = None if last is None else last.get("cause")
        return result(False, cause or "retries-exhausted", 4, attempts=attempt_rows, kept=False)

    def load_public(self, owner, session, lease_id):
        return self._public_lease(self._require_owned(lease_id, owner, session))


def ctypes_filetime():
    import ctypes
    class FILETIME(ctypes.Structure):
        _fields_ = [("dwLowDateTime", ctypes.c_uint32), ("dwHighDateTime", ctypes.c_uint32)]
    return FILETIME


def parse_range(text):
    if not text:
        return DEFAULT_RANGE
    if "-" not in text:
        raise LeaseError("port-range")
    left, right = text.split("-", 1)
    return int(left), int(right)


def build_parser():
    shared = argparse.ArgumentParser(add_help=False)
    shared.add_argument("--root", default=".")
    parser = argparse.ArgumentParser(
        description="Lease a free loopback port for one agent session.", parents=[shared])
    sub = parser.add_subparsers(dest="action", required=True)

    def common(command):
        command.add_argument("--owner", required=True)
        command.add_argument("--session", required=True)

    acquire = sub.add_parser("acquire", parents=[shared])
    common(acquire)
    acquire.add_argument("--service", default="agent-server")
    acquire.add_argument("--range", default="")
    acquire.add_argument("--port", type=int)
    acquire.add_argument("--ttl-seconds", type=int, default=1800)

    for name in ("release", "stop", "close", "heartbeat"):
        command = sub.add_parser(name, parents=[shared])
        common(command)
        command.add_argument("--lease", required=True)
        if name == "heartbeat":
            command.add_argument("--ttl-seconds", type=int, default=1800)

    start = sub.add_parser("start", parents=[shared])
    common(start)
    start.add_argument("--lease", required=True)
    start.add_argument("--health-url", default="http://127.0.0.1:{port}/")
    start.add_argument("--health-timeout", type=float, default=5)
    start.add_argument("--cwd", default="")
    start.add_argument("argv", nargs=argparse.REMAINDER)

    health = sub.add_parser("health", parents=[shared])
    common(health)
    health.add_argument("--lease", required=True)
    health.add_argument("--health-url", default="http://127.0.0.1:{port}/")
    health.add_argument("--timeout", type=float, default=3)

    verify = sub.add_parser("verify", parents=[shared])
    common(verify)
    verify.add_argument("--lease", required=True)
    verify.add_argument("--health-url", default="http://127.0.0.1:{port}/")
    verify.add_argument("--timeout", type=float, default=5)
    verify.add_argument("argv", nargs=argparse.REMAINDER)

    run = sub.add_parser("run", parents=[shared])
    common(run)
    run.add_argument("--service", default="agent-server")
    run.add_argument("--range", default="")
    run.add_argument("--attempts", type=int, default=3)
    run.add_argument("--health-url", default="http://127.0.0.1:{port}/")
    run.add_argument("--health-timeout", type=float, default=5)
    run.add_argument("--verify-timeout", type=float, default=5)
    run.add_argument("--ttl-seconds", type=int, default=1800)
    run.add_argument("--keep", action="store_true")
    run.add_argument("--cwd", default="")
    run.add_argument("argv", nargs=argparse.REMAINDER)

    trace = sub.add_parser("trace", parents=[shared])
    trace.add_argument("trace_action", choices=("add", "query"))
    trace.add_argument("--owner", default="")
    trace.add_argument("--session", default="")
    trace.add_argument("--lease", default="")
    trace.add_argument("--trace-id", default="")
    trace.add_argument("--http-status", type=int)
    trace.add_argument("--end-status", default="recorded")
    trace.add_argument("--exception-type", default="")
    trace.add_argument("--cause", default="")
    trace.add_argument("--phase", default="request")

    diagnose = sub.add_parser("diagnose", parents=[shared])
    diagnose.add_argument("diagnose_action", choices=("tail", "trace", "around"))
    diagnose.add_argument("--owner", default="")
    diagnose.add_argument("--session", default="")
    diagnose.add_argument("--lease", default="")
    diagnose.add_argument("--trace-id", default="")
    diagnose.add_argument("--lines", type=int, default=80)
    diagnose.add_argument("--follow-seconds", type=float, default=0)
    diagnose.add_argument("--window-bytes", type=int, default=4000)

    status = sub.add_parser("status", parents=[shared])
    status.add_argument("--owner", default="")
    status.add_argument("--session", default="")

    reap = sub.add_parser("reap", parents=[shared])
    common(reap)

    sub.add_parser("contract", parents=[shared])
    return parser


def command_argv(items):
    argv = list(items or [])
    if argv and argv[0] == "--":
        argv = argv[1:]
    return argv


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    tool = AgentPortLease(args.root)
    try:
        if args.action == "contract":
            body = result(True, protectedPorts=sorted(PROTECTED_PORTS), defaultRange=list(DEFAULT_RANGE),
                          store="var/agent-port-lease")
        elif args.action == "acquire":
            body = tool.acquire(
                args.owner, args.session, service=args.service, port_range=parse_range(args.range),
                port=args.port, ttl_seconds=args.ttl_seconds,
            )
        elif args.action == "release":
            body = tool.release(args.owner, args.session, args.lease)
        elif args.action == "heartbeat":
            body = tool.heartbeat(args.owner, args.session, args.lease, ttl_seconds=args.ttl_seconds)
        elif args.action == "stop":
            body = tool.stop(args.owner, args.session, args.lease)
        elif args.action == "close":
            body = tool.close(args.owner, args.session, args.lease)
        elif args.action == "start":
            body = tool.start(
                args.owner, args.session, args.lease, command_argv(args.argv),
                health_url=args.health_url, health_timeout=args.health_timeout,
                cwd=args.cwd or None,
            )
        elif args.action == "health":
            body = tool.health(
                args.owner, args.session, args.lease, health_url=args.health_url, timeout=args.timeout,
            )
        elif args.action == "verify":
            body = tool.verify(
                args.owner, args.session, args.lease, argv=command_argv(args.argv) or None,
                health_url=args.health_url, timeout=args.timeout,
            )
        elif args.action == "run":
            body = tool.run(
                args.owner, args.session, args.service, command_argv(args.argv),
                port_range=parse_range(args.range), attempts=args.attempts,
                health_url=args.health_url, health_timeout=args.health_timeout,
                verify_timeout=args.verify_timeout, keep=args.keep,
                ttl_seconds=args.ttl_seconds, cwd=args.cwd or None,
            )
        elif args.action == "trace" and args.trace_action == "add":
            body = tool.trace_add(
                args.owner, args.session, args.lease, http_status=args.http_status,
                end_status=args.end_status, exception_type=args.exception_type or None,
                cause=args.cause or None, phase=args.phase,
            )
        elif args.action == "trace":
            body = tool.trace_query(
                owner=args.owner or None, session=args.session or None,
                lease_id=args.lease or None, trace_id=args.trace_id or None,
                cause=args.cause or None,
            )
        elif args.action == "diagnose":
            body = tool.diagnose(
                args.diagnose_action, args.owner, args.session, lease_id=args.lease or None,
                trace_id=args.trace_id or None, lines=args.lines,
                follow_seconds=args.follow_seconds, window_bytes=args.window_bytes,
            )
        elif args.action == "status":
            body = tool.status(owner=args.owner or None, session=args.session or None)
        elif args.action == "reap":
            body = tool.reap(args.owner, args.session)
        else:
            raise LeaseError("unknown-action")
    except LeaseError as exc:
        body = result(False, exc.cause, exc.exit_code)
    print(json.dumps(body, ensure_ascii=True))
    return int(body.get("exitCode") or 0)


if __name__ == "__main__":
    sys.exit(main())
