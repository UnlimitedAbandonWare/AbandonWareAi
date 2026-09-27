#!/usr/bin/env python3
"""Minimal stdio JSON-RPC bridge for the AWX MCP-style toolbox.

This intentionally stays outside the Java runtime. It exposes the repo-local
tool manifest as MCP-shaped tools/resources/prompts and delegates tool calls to
awx_mcp_toolbox without printing raw secrets.
"""

from __future__ import annotations

import json
import hashlib
import math
import os
import queue
import signal
import subprocess
import threading
from contextlib import redirect_stdout
from collections import Counter
import sys
import time
from pathlib import Path
from typing import Any

import awx_mcp_toolbox as toolbox
import awx_codex_review_adapter as review_adapter


ROOT = Path(__file__).absolute().parents[1]
MANIFEST_PATH = ROOT / "main" / "resources" / "mcp" / "awx-control-tower-tools.json"
PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "awx-control-tower"
# Includes both text and structuredContent, measured after JSON escaping.
MAX_TOOL_RESULT_BYTES = 262144

HANDLERS = {
    "device_work": toolbox.device_work,
    "grok_review_change": toolbox.grok_review_change,
    "kimi_review_change": toolbox.kimi_review_change,
    "codex_review_change": toolbox.codex_review_change,
    "source_scan": toolbox.source_scan,
    "harmony_scan": toolbox.harmony_scan,
    "agent_db_snapshot": toolbox.agent_db_snapshot,
    "trace_snapshot_probe": toolbox.trace_snapshot_probe,
    "supabase_context_probe": toolbox.supabase_context_probe,
    "supabase_schema_snapshot": toolbox.supabase_schema_snapshot,
    "supabase_schema_snapshot_import": toolbox.supabase_schema_snapshot_import,
    "patch_plan": toolbox.patch_plan,
    "patch_render": toolbox.patch_render,
    "archive_index_build": toolbox.archive_index_build,
    "archive_search": toolbox.archive_search,
    "archive_restore": toolbox.archive_restore,
    "boot_verify": toolbox.boot_verify,
    "build_error_mine": toolbox.build_error_mine,
    "run_pipeline": toolbox.run_pipeline,
    "external_evidence_intake": toolbox.external_evidence_intake,
    "external_evidence_audit": toolbox.external_evidence_audit,
    "producer_command_plan": toolbox.producer_command_plan,
    "producer_kit_export": toolbox.producer_kit_export,
    "desktop_dispatch_packet": toolbox.desktop_dispatch_packet,
    "desktop_control_loop": toolbox.desktop_control_loop,
    "smb_decommission_debug_probe": toolbox.smb_decommission_debug_probe,
    "peer_evidence_bus": toolbox.peer_evidence_bus,
    "web_probe_refresh": toolbox.web_probe_refresh,
    "guard_status": toolbox.guard_status,
    "session_evidence": toolbox.session_evidence,
}


def main() -> int:
    return tool_worker() if "--tool-worker" in sys.argv else StdioSession().run()


class ToolCancelled(BaseException):
    """Control flow, deliberately outside business exception handlers."""


def lifecycle_event(event: str, **fields: Any) -> None:
    # Fixed event names and count/state fields only; no request/session content.
    print(json.dumps({"service": "awx-stdio", "event": event, **fields}), file=sys.stderr, flush=True)


def tool_worker() -> int:
    """Internal execution mode of this bridge, never an additional MCP server.

    The parent assigns process ownership before sending work. Cancellation is
    checked at Python execution boundaries; a blocked native call is stopped by
    the parent's bounded owned-process cleanup. Prior side effects are not undone.
    """
    request = json.loads(sys.stdin.readline())
    cancelled = threading.Event()

    def receive_cancel():
        # Only one control message is needed; EOF is also cancellation.
        sys.stdin.readline()
        cancelled.set()

    threading.Thread(target=receive_cancel, name="awx-worker-control", daemon=True).start()
    handler_codes = {handler.__code__ for handler in HANDLERS.values() if hasattr(handler, "__code__")}
    entered = False

    def checkpoint(frame, event, arg):
        nonlocal entered
        if cancelled.is_set():
            raise ToolCancelled()
        if not entered and event == "call" and frame.f_code in handler_codes:
            entered = True
            lifecycle_event("handler_entered")
        return checkpoint

    try:
        with redirect_stdout(sys.stderr), review_adapter.owned_worker(
                OwnedWorker, time.monotonic() + request.pop("_awxWorkerBudgetSeconds", 120), cancelled):
            sys.settrace(checkpoint)
            reply = handle_request(request)
    except ToolCancelled:
        return 0
    finally:
        sys.settrace(None)
    if reply is not None:
        print(json.dumps(reply, ensure_ascii=True, separators=(",", ":")), flush=True)
    return 0


class OwnedWorker:
    """Own only the Popen handle and descendants created by that worker.

    Windows Job objects close the descendant gap; failure to assign ownership
    stops the child before any tool arguments are sent. POSIX uses a new session.
    """
    def __init__(self, command, *, capture=True, cwd=None, env=None, binary=False):
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                                        stderr=subprocess.PIPE if capture else subprocess.DEVNULL, text=not binary, encoding=None if binary else "utf-8", cwd=cwd, env=env,
                                        start_new_session=os.name != "nt",
                                        creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        self.job = None
        try:
            if os.name == "nt":
                import ctypes
                from ctypes import wintypes as w

                class Basic(ctypes.Structure):
                    _fields_ = [("user", ctypes.c_int64), ("job", ctypes.c_int64), ("flags", w.DWORD),
                                ("min", ctypes.c_size_t), ("max", ctypes.c_size_t), ("active", w.DWORD),
                                ("affinity", ctypes.c_size_t), ("priority", w.DWORD), ("scheduling", w.DWORD)]

                class Extended(ctypes.Structure):
                    _fields_ = [("basic", Basic), ("io", ctypes.c_uint64 * 6),
                                ("processMemory", ctypes.c_size_t), ("jobMemory", ctypes.c_size_t),
                                ("peakProcess", ctypes.c_size_t), ("peakJob", ctypes.c_size_t)]

                k = ctypes.WinDLL("kernel32", use_last_error=True)
                k.CreateJobObjectW.argtypes = [ctypes.c_void_p, w.LPCWSTR]
                k.CreateJobObjectW.restype = w.HANDLE
                k.SetInformationJobObject.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD]
                k.AssignProcessToJobObject.argtypes = [w.HANDLE, w.HANDLE]
                k.CloseHandle.argtypes = [w.HANDLE]
                self.kernel = k
                self.job = k.CreateJobObjectW(None, None)
                limits = Extended()
                limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                if not self.job or not k.SetInformationJobObject(self.job, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
                    raise RuntimeError("worker_ownership_failed")
                if not k.AssignProcessToJobObject(self.job, w.HANDLE(int(self.process._handle))):
                    raise RuntimeError("worker_ownership_failed")
        except BaseException:
            self.close()
            raise

    def close(self):
        if self.job:
            self.kernel.CloseHandle(self.job)
            self.job = None
        elif os.name != "nt":
            try:
                os.killpg(self.process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        if self.process.poll() is None:
            self.process.kill()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            return False
        return self.process.poll() is not None


class StdioSession:
    def __init__(self, *, worker_command=None, request_timeout=120, queue_limit=8):
        self.worker_command = worker_command or [sys.executable, str(Path(__file__).resolve()), "--tool-worker"]
        if not 0 < request_timeout <= 600 or not 1 <= queue_limit <= 32:
            raise ValueError("invalid_session_bounds")
        self.request_timeout = request_timeout
        self.queue = queue.Queue(maxsize=queue_limit)
        self.active = {}
        self.lock = threading.RLock()
        self.output_lock = threading.Lock()
        self.closing = threading.Event()
        self.worker = threading.Thread(target=self.work, name="awx-stdio-execution", daemon=True)
        self.current_owned = None

    def emit(self, reply):
        if reply is not None:
            with self.output_lock:
                sys.stdout.write(json.dumps(reply, ensure_ascii=True, separators=(",", ":")) + "\n")
                sys.stdout.flush()

    def run(self):
        self.worker.start()
        try:
            for line in sys.stdin:
                if not line.strip():
                    continue
                try:
                    request = json.loads(line, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
                except (ValueError, UnicodeError):
                    self.emit(error_response(None, -32700, "invalid_json"))
                    continue
                self.receive(request)
        finally:
            try:
                self.queue.put_nowait(None)
            except queue.Full:
                pass
            self.worker.join(timeout=8)
            if self.worker.is_alive():
                self.closing.set()
                with self.lock:
                    for state in self.active.values():
                        state["cancel"].set()
                if self.current_owned is not None:
                    self.current_owned.close()
                self.worker.join(timeout=3)
            else:
                self.closing.set()
            lifecycle_event("session_closed", activeRequests=len(self.active), workerAlive=self.worker.is_alive())
        return 0 if not self.worker.is_alive() else 1

    def receive(self, request):
        if (not isinstance(request, dict) or request.get("jsonrpc") != "2.0"
                or not isinstance(request.get("method"), str) or not valid_id(request.get("id"))):
            self.emit(handle_request(request))
            return
        method = request["method"]
        params = request.get("params", {})
        if method == "notifications/cancelled" and "id" not in request:
            if isinstance(params, dict) and "requestId" in params and valid_id(params["requestId"]):
                key = (type(params["requestId"]), params["requestId"])
                with self.lock:
                    if key in self.active:
                        self.active[key]["cancel"].set()
            return
        if method != "tools/call":
            self.emit(handle_request(request))
            return
        try:
            validate_tool_call(params)
        except ProtocolError as exc:
            if "id" in request:
                self.emit(error_response(request["id"], exc.code, exc.reason))
            return
        except Exception:
            if "id" in request:
                self.emit(error_response(request["id"], -32603, "internal_error"))
            return
        key = (type(request.get("id")), request.get("id")) if "id" in request else object()
        with self.lock:
            if key in self.active or self.queue.full():
                if "id" in request:
                    self.emit(error_response(request["id"], -32600 if key in self.active else -32000,
                                             "request_id_in_use" if key in self.active else "work_queue_full"))
                return
            timeout = min(self.request_timeout, params.get("_meta", {}).get("awx/executionTimeoutMs", 600000) / 1000)
            state = {"request": request, "cancel": threading.Event(), "workerStarted": False, "workerExited": False,
                     "timeout": timeout}
            self.active[key] = state
            self.queue.put_nowait((key, state))

    def work(self):
        while not self.closing.is_set() or not self.queue.empty():
            try:
                item = self.queue.get(timeout=.05)
            except queue.Empty:
                continue
            if item is None:
                self.queue.task_done()
                break
            key, state = item
            reply = None
            outcome = "cancelled"
            started = time.monotonic()
            try:
                if not state["cancel"].is_set() and not self.closing.is_set():
                    reply, outcome = self.execute(state)
            except Exception:
                reply = error_response(state["request"].get("id"), -32603, "worker_execution_failed")
                outcome = "internal_error"
            finally:
                # Cancellation/completion have one linearization point. A late
                # cancellation after removal cannot suppress an already sent result.
                with self.lock:
                    if state["cancel"].is_set() or self.closing.is_set():
                        reply, outcome = None, "cancelled"
                    self.active.pop(key, None)
                    if "id" in state["request"]:
                        self.emit(reply)
                self.queue.task_done()
                lifecycle_event("request_finished", outcome=outcome, elapsedMs=int((time.monotonic()-started)*1000),
                                workerStarted=state["workerStarted"], workerExited=state["workerExited"])

    def execute(self, state):
        owned = OwnedWorker(self.worker_command)
        self.current_owned = owned
        state["workerStarted"] = True
        process = owned.process
        parts, readers = [], []
        overflow = threading.Event()

        def collect_output():
            length = 0
            for chunk in iter(lambda: process.stdout.read(8192), ""):
                length += len(chunk)
                if length <= 4 * 1024 * 1024:
                    parts.append(chunk)
                else:
                    overflow.set()

        def collect_events():
            for line in iter(lambda: process.stderr.readline(4097), ""):
                # Worker logs never become protocol stdout. Forward only our
                # lifecycle marker, with no handler-supplied text or fields.
                try:
                    event = json.loads(line)
                    if event.get("service") == "awx-stdio" and event.get("event") == "handler_entered":
                        lifecycle_event("handler_entered")
                except (ValueError, AttributeError):
                    pass

        try:
            for target in (collect_output, collect_events):
                reader = threading.Thread(target=target, daemon=True)
                reader.start()
                readers.append(reader)
            worker_request = dict(state["request"], _awxWorkerBudgetSeconds=state["timeout"])
            process.stdin.write(json.dumps(worker_request) + "\n")
            process.stdin.flush()
            deadline = time.monotonic() + state["timeout"]
            outcome = "completed"
            while process.poll() is None:
                if state["cancel"].is_set() or self.closing.is_set() or time.monotonic() >= deadline or overflow.is_set():
                    outcome = "cancelled" if state["cancel"].is_set() or self.closing.is_set() else "timeout" if not overflow.is_set() else "output_limit"
                    try:
                        process.stdin.write('{"cancel":true}\n')
                        process.stdin.flush()
                    except (BrokenPipeError, OSError):
                        pass
                    try:
                        process.wait(timeout=1)
                    except subprocess.TimeoutExpired:
                        lifecycle_event("cancellation_escalated", reason="native_operation_blocked")
                    break
                time.sleep(.01)
        finally:
            state["workerExited"] = owned.close()
            self.current_owned = None
            for reader in readers:
                reader.join(timeout=2)
            for stream in (process.stdin, process.stdout, process.stderr):
                stream.close()
        if outcome == "timeout":
            return error_response(state["request"].get("id"), -32001, "request_timeout"), outcome
        if outcome == "cancelled":
            return None, outcome
        if outcome == "output_limit":
            return error_response(state["request"].get("id"), -32603, "worker_output_limit"), outcome
        text = "".join(parts).strip()
        if not text and ("id" in state["request"] or process.returncode != 0):
            return error_response(state["request"].get("id"), -32603, "worker_response_missing"), "internal_error"
        if process.returncode != 0:
            return error_response(state["request"].get("id"), -32603, "worker_execution_failed"), "internal_error"
        return json.loads(text) if text else None, outcome


class ProtocolError(Exception):
    def __init__(self, code: int, reason: str):
        super().__init__(reason)
        self.code = code
        self.reason = reason


def finite_number(value: Any) -> bool:
    # Python integers are finite without converting them to a bounded float.
    return type(value) is int or (type(value) is float and math.isfinite(value))


def valid_id(value: Any) -> bool:
    return value is None or isinstance(value, str) or finite_number(value)


def handle_request(request: Any) -> dict[str, Any] | None:
    if not isinstance(request, dict):
        return error_response(None, -32600, "request_must_be_object")
    request_id = request.get("id")
    notification = "id" not in request
    if (request.get("jsonrpc") != "2.0" or not isinstance(request.get("method"), str)
            or not valid_id(request_id)):
        return error_response(request_id if valid_id(request_id) else None, -32600, "invalid_request")
    try:
        result = dispatch(request["method"], request.get("params", {}))
        reply = ok_response(request_id, result, method=request["method"])
    except ProtocolError as exc:
        reply = error_response(request_id, exc.code, exc.reason)
    except Exception:
        # Handler/API errors are caught at the tool boundary. Never expose raw
        # exceptions or misclassify an internal failure as a JSON parse failure.
        reply = error_response(request_id, -32603, "internal_error")
    return None if notification else reply


def dispatch(method: str, params: Any) -> dict[str, Any]:
    methods = {"initialize", "ping", "tools/list", "tools/call", "resources/list",
               "resources/read", "prompts/list", "prompts/get", "awx/catalog",
               "notifications/initialized", "notifications/cancelled"}
    if method not in methods:
        raise ProtocolError(-32601, "unknown_method")
    if not isinstance(params, dict):
        raise ProtocolError(-32602, "params_must_be_object")
    if method.endswith("/list") and params.get("cursor") not in (None, ""):
        raise ProtocolError(-32602, "unsupported_cursor")
    if method == "initialize":
        if "protocolVersion" in params and not isinstance(params["protocolVersion"], str):
            raise ProtocolError(-32602, "invalid_protocol_version")
        for key in ("capabilities", "clientInfo"):
            if key in params and not isinstance(params[key], dict):
                raise ProtocolError(-32602, "invalid_initialize_metadata")
        return initialize_result()
    if method in ("ping", "notifications/initialized", "notifications/cancelled"):
        return {}
    if method == "awx/catalog":
        return registry()["health"]
    if method == "tools/list":
        return {"tools": list_tools()}
    if method == "tools/call":
        return call_tool(params)
    if method == "resources/list":
        return {"resources": list_resources()}
    if method == "resources/read":
        return read_resource(params)
    if method == "prompts/list":
        return {"prompts": list_prompts()}
    if method == "prompts/get":
        return get_prompt(params)
    raise ProtocolError(-32601, "unknown_method")


def initialize_result() -> dict[str, Any]:
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "capabilities": {
            "experimental": {"awx/executionTimeoutMs": {"onlyShortensServerLimit": True}},
            "tools": {},
            "resources": {},
            "prompts": {},
        },
        "serverInfo": {
            "name": SERVER_NAME,
            "version": "1.0.0",
        },
    }


def load_manifest() -> dict[str, Any]:
    return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))


def schema_valid(schema: Any) -> bool:
    """Fail closed on unsupported manifest assertions; allow extension arguments
    unless the schema explicitly forbids additionalProperties.
    """
    if isinstance(schema, bool):
        return True
    if not isinstance(schema, dict):
        return False
    supported = {"type", "properties", "required", "enum", "items", "minimum", "maximum",
                 "additionalProperties", "description", "title", "default", "$schema"}
    if set(schema) - supported:
        return False
    types = schema.get("type", [])
    types = [types] if isinstance(types, str) else types
    if not isinstance(types, list) or any(t not in ("object", "array", "string", "integer", "number", "boolean", "null") for t in types):
        return False
    properties = schema.get("properties", {})
    required = schema.get("required", [])
    if not isinstance(properties, dict) or not isinstance(required, list):
        return False
    # Repository contract: required fields have explicit property definitions.
    if any(not isinstance(k, str) or k not in properties for k in required):
        return False
    if any(not schema_valid(v) for v in properties.values()):
        return False
    if any(not schema_valid(schema[k]) for k in ("items", "additionalProperties") if k in schema):
        return False
    if "enum" in schema and (not isinstance(schema["enum"], list) or not schema["enum"]):
        return False
    return all(type(schema[k]) in (int, float) for k in ("minimum", "maximum") if k in schema)


def schema_matches(value: Any, schema: Any) -> bool:
    if isinstance(schema, bool):
        return schema
    predicates = {"object": lambda v: isinstance(v, dict), "array": lambda v: isinstance(v, list),
                  "string": lambda v: isinstance(v, str), "integer": lambda v: type(v) is int or (type(v) is float and math.isfinite(v) and v.is_integer()),
                  "number": finite_number,
                  "boolean": lambda v: type(v) is bool, "null": lambda v: v is None}
    types = schema.get("type", [])
    types = [types] if isinstance(types, str) else types
    if types and not any(predicates[t](value) for t in types):
        return False
    if "enum" in schema and not any(json_equal(value, v) for v in schema["enum"]):
        return False
    if isinstance(value, dict):
        props = schema.get("properties", {})
        if any(k not in value for k in schema.get("required", [])):
            return False
        if any(not schema_matches(v, props.get(k, schema.get("additionalProperties", True))) for k, v in value.items()):
            return False
    if isinstance(value, list) and "items" in schema:
        if any(not schema_matches(v, schema["items"]) for v in value):
            return False
    if type(value) in (int, float):
        if "minimum" in schema and value < schema["minimum"]:
            return False
        if "maximum" in schema and value > schema["maximum"]:
            return False
    return True


def json_equal(left: Any, right: Any) -> bool:
    """JSON numbers compare by value; booleans stay distinct at every depth."""
    if finite_number(left) and finite_number(right):
        return left == right
    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(json_equal(left[k], right[k]) for k in left)
    if isinstance(left, list):
        return len(left) == len(right) and all(json_equal(a, b) for a, b in zip(left, right))
    return left == right


_registry_cache: tuple[Any, Any] | None = None


def registry() -> dict[str, Any]:
    global _registry_cache
    stat = MANIFEST_PATH.stat()
    key = (str(MANIFEST_PATH.resolve()), stat.st_mtime_ns, stat.st_size, tuple(sorted(HANDLERS)), tuple(sorted(toolbox.TOOL_ALIASES.items())))
    if _registry_cache is not None and _registry_cache[0] == key:
        return _registry_cache[1]
    raw = MANIFEST_PATH.read_bytes()
    manifest = json.loads(raw.decode("utf-8"))
    result: dict[str, Any] = {"tools": [], "resources": [], "prompts": []}
    issues: list[dict[str, str]] = []
    duplicates = invalid = 0
    names = {r.get("name") for r in manifest.get("tools", []) if isinstance(r, dict)}
    mismatch = len(names.symmetric_difference(HANDLERS))
    alias_targets = {}
    alias_owners = {}
    for row in manifest.get("tools", []):
        if not isinstance(row, dict):
            continue
        for alias in row.get("aliases", []):
            if not isinstance(alias, str):
                invalid += 1
                continue
            alias_targets[alias] = row.get("name")
            alias_owners.setdefault(alias.casefold(), []).append(row.get("name"))
    alias_conflicts = {k for k, owners in alias_owners.items() if len(owners) > 1 or k in {str(n).casefold() for n in names}}
    bad_alias_tools = {owner for key in alias_conflicts for owner in alias_owners[key]}
    for alias, target in alias_targets.items():
        if toolbox.TOOL_ALIASES.get(alias) != target:
            bad_alias_tools.add(target)
    alias_collisions = len(alias_conflicts)
    alias_mismatch = len(set(alias_targets).symmetric_difference(toolbox.TOOL_ALIASES))
    alias_mismatch += sum(toolbox.TOOL_ALIASES.get(k) != v for k, v in alias_targets.items() if k in toolbox.TOOL_ALIASES)
    for family in ("tools", "resources", "prompts"):
        rows = manifest.get(family, [])
        if not isinstance(rows, list):
            rows = []
            invalid += 1
        invalid += sum(not isinstance(r, dict) for r in rows)
        rows = [r for r in rows if isinstance(r, dict)]
        counts = Counter(str(r.get("name", "")).casefold() for r in rows)
        uris = Counter(manifest_uri(r.get("path", "")) for r in rows if family == "resources" and isinstance(r.get("path"), str))
        collisions = {k for k, n in counts.items() if n > 1}
        uri_collisions = {k for k, n in uris.items() if n > 1}
        duplicates += len(collisions) + len(uri_collisions)
        for identity in sorted(collisions | uri_collisions):
            issues.append({"family": family, "reason": "registration_collision", "identityHash": hashlib.sha256(identity.encode()).hexdigest()})
        for row in rows:
            name = row.get("name")
            if not isinstance(name, str) or not name:
                invalid += 1
                continue
            if name.casefold() in collisions:
                continue
            if family == "tools":
                if name not in HANDLERS or name in bad_alias_tools:
                    continue
                if not isinstance(row.get("description"), str) or not row["description"].strip() or type(row.get("readOnly")) is not bool:
                    invalid += 1
                    continue
                if "output_schema" in row and not schema_valid(row["output_schema"]):
                    invalid += 1
                    continue
                if not isinstance(row.get("input_schema"), dict) or not schema_valid(row["input_schema"]):
                    invalid += 1
                    continue
            elif family == "resources":
                try:
                    uri = manifest_uri(row["path"])
                    path_from_manifest_uri(uri)
                except (KeyError, TypeError, AttributeError, ValueError, ProtocolError):
                    invalid += 1
                    continue
                if uri in uri_collisions:
                    continue
            elif family == "prompts":
                if not isinstance(row.get("flow"), list) or not row["flow"] or any(step not in HANDLERS for step in row["flow"]):
                    invalid += 1
                    continue
            result[family].append(row)
        result[family].sort(key=lambda r: r["name"])
    result["health"] = {"ok": duplicates == invalid == mismatch == alias_mismatch == alias_collisions == 0,
                        "protocolFamily": "MCP_STYLE", "protocolVersion": PROTOCOL_VERSION,
                        "modernSupported": False, "manifestHash": hashlib.sha256(raw).hexdigest(),
                        "toolCount": len(result["tools"]), "resourceCount": len(result["resources"]),
                        "promptCount": len(result["prompts"]), "duplicateCount": duplicates,
                        "schemaInvalidCount": invalid, "manifestMismatchCount": mismatch,
                        "aliasMismatchCount": alias_mismatch, "aliasCollisionCount": alias_collisions, "issues": issues}
    _registry_cache = (key, result)
    return result


def list_tools() -> list[dict[str, Any]]:
    manifest = registry()
    out: list[dict[str, Any]] = []
    for item in manifest.get("tools", []):
        if not isinstance(item, dict):
            continue
        if os.environ.get("AWX_MCP_SOURCE_ACCESS") == "shared-read" and item.get("readOnly") is not True:
            continue
        name = toolbox.safe_scalar(item.get("name", ""), 96)
        if not name:
            continue
        out.append({
            "name": name,
            "description": toolbox.safe_scalar(item.get("description", ""), 500),
            "inputSchema": item.get("input_schema", {"type": "object"}),
            # Repository MCP_STYLE extension; negotiation remains 2024-11-05.
            **({"outputSchema": item["output_schema"]} if "output_schema" in item else {}),
            "annotations": {
                "readOnlyHint": bool(item.get("readOnly", True)),
                "nodeRoles": [toolbox.safe_scalar(role, 32) for role in item.get("nodeRoles", [])],
                "aliases": [toolbox.safe_scalar(alias, 96) for alias in item.get("aliases", [])],
            },
        })
    return out


def validate_tool_call(params: Any) -> tuple[str, dict[str, Any]]:
    if not isinstance(params, dict):
        raise ProtocolError(-32602, "params_must_be_object")
    metadata = params.get("_meta", {})
    if not isinstance(metadata, dict):
        raise ProtocolError(-32602, "metadata_must_be_object")
    if "awx/executionTimeoutMs" in metadata:
        timeout = metadata["awx/executionTimeoutMs"]
        if type(timeout) is not int or not 1 <= timeout <= 600000:
            raise ProtocolError(-32602, "invalid_execution_timeout")
    requested_name = required_string(params, "name")
    tool_name = toolbox.TOOL_ALIASES.get(requested_name, requested_name)
    registration = next((r for r in registry()["tools"] if r["name"] == tool_name), None)
    if registration is None:
        raise ProtocolError(-32602, "unknown_tool")
    if os.environ.get('AWX_MCP_SOURCE_ACCESS') == 'shared-read' and registration.get('readOnly') is not True:
        raise ProtocolError(-32602, "shared_read_mutation_denied")
    arguments = params.get("arguments", {})
    if not isinstance(arguments, dict) or not schema_matches(arguments, registration["input_schema"]):
        raise ProtocolError(-32602, "invalid_tool_arguments")
    return tool_name, arguments


class OutputSchemaError(Exception):
    pass


def call_tool(params: dict[str, Any]) -> dict[str, Any]:
    tool_name, arguments = validate_tool_call(params)
    registration = next(row for row in registry()["tools"] if row["name"] == tool_name)
    # Runtime cwd is host-local; tools still default to this source checkout.
    if 'root' in registration['input_schema'].get('properties', {}) and not arguments.get('root'):
        arguments = {**arguments, 'root': str(ROOT)}
    output_schema = registration.get("output_schema")
    started = time.monotonic()
    try:
        raw = HANDLERS[tool_name](arguments)
        if (not isinstance(raw, dict) or ("ok" in raw and type(raw["ok"]) is not bool)
                or ((raw.get("ok") is not False or tool_name in ("codex_review_change", "grok_review_change", "kimi_review_change")) and output_schema is not None and not schema_matches(raw, output_schema))):
            raise OutputSchemaError()
        result = dict(raw)
        toolbox.finalize(tool_name, arguments, result, started)
        # Finalize owns audit metadata; its optional additions must not break a
        # valid closed output contract. Never drop handler-returned extra fields.
        if (raw.get("ok") is not False or tool_name in ("codex_review_change", "grok_review_change", "kimi_review_change")) and output_schema is not None and not schema_matches(result, output_schema):
            result = raw
        redacted = toolbox.redact(result)
        if (raw.get("ok") is not False or tool_name in ("codex_review_change", "grok_review_change", "kimi_review_change")) and output_schema is not None and not schema_matches(redacted, output_schema):
            raise OutputSchemaError()
    except OutputSchemaError:
        redacted = {"ok": False, "reason": "output_schema_validation_failed"}
    except Exception:
        redacted = {"ok": False, "reason": "tool_execution_failed"}
    text = json.dumps(redacted, ensure_ascii=True, separators=(",", ":"), allow_nan=False)
    response = {
        "content": [{"type": "text", "text": text}],
        "structuredContent": redacted,
        "isError": not bool(redacted.get("ok", True)),
    }
    # The legacy wire carries the payload twice; bounding only `text` misses
    # structuredContent and JSON escaping. Never truncate a semantic result or
    # retry an operation that may already have changed external state.
    response_bytes = len(json.dumps(response, ensure_ascii=True, separators=(",", ":"),
                                   allow_nan=False).encode("utf-8"))
    if response_bytes > MAX_TOOL_RESULT_BYTES:
        failure = {"ok": False, "reason": "tool_result_size_limit",
                   "toolInvoked": True, "automaticRetryAllowed": False,
                   "resultBytes": response_bytes, "limitBytes": MAX_TOOL_RESULT_BYTES}
        return {"content": [{"type": "text", "text": json.dumps(failure, separators=(",", ":"))}],
                "structuredContent": failure, "isError": True}
    return response


def list_resources() -> list[dict[str, Any]]:
    manifest = registry()
    resources = []
    for item in manifest.get("resources", []):
        if not isinstance(item, dict):
            continue
        path = toolbox.safe_scalar(item.get("path", ""), 500)
        name = toolbox.safe_scalar(item.get("name", ""), 96)
        if not name or not path:
            continue
        resources.append({
            "name": name,
            "uri": manifest_uri(path),
            "description": toolbox.safe_scalar(item.get("usage", ""), 500),
            "mimeType": "application/json" if path.endswith(".json") else "text/plain",
        })
    return resources


def read_resource(params: dict[str, Any]) -> dict[str, Any]:
    uri = required_string(params, "uri")
    resources = {item["uri"]: item for item in list_resources()}
    if uri not in resources:
        raise ProtocolError(-32002, "unknown_resource")
    path = path_from_manifest_uri(uri)
    if not path.exists() or path.is_dir():
        raise ProtocolError(-32002, "resource_unavailable")
    with path.open(encoding="utf-8", errors="replace") as stream:
        text = stream.read(262145)
    if len(text) > 262144:
        raise ProtocolError(-32002, "resource_size_limit")
    text = toolbox.redact_string(text)
    return {"contents": [{"uri": uri, "mimeType": resources[uri].get("mimeType", "text/plain"), "text": text}]}


def list_prompts() -> list[dict[str, Any]]:
    manifest = registry()
    prompts: list[dict[str, Any]] = []
    for item in manifest.get("prompts", []):
        if not isinstance(item, dict):
            continue
        name = toolbox.safe_scalar(item.get("name", ""), 96)
        if not name:
            continue
        prompts.append({
            "name": name,
            "description": " -> ".join(toolbox.safe_scalar(step, 64) for step in item.get("flow", [])),
            "arguments": [],
        })
    return prompts


def get_prompt(params: dict[str, Any]) -> dict[str, Any]:
    name = required_string(params, "name")
    if not isinstance(params.get("arguments", {}), dict) or params.get("arguments", {}):
        raise ProtocolError(-32602, "invalid_prompt_arguments")
    manifest = registry()
    for item in manifest.get("prompts", []):
        if isinstance(item, dict) and item.get("name") == name:
            flow = [toolbox.safe_scalar(step, 64) for step in item.get("flow", [])]
            optional_flows = normalize_optional_flows(item.get("optionalFlows", []))
            text = render_prompt_text(name, flow, optional_flows)
            return {"description": name, "messages": [{"role": "user", "content": {"type": "text", "text": text}}]}
    raise ProtocolError(-32602, "unknown_prompt")


def required_string(params: dict[str, Any], key: str) -> str:
    value = params.get(key)
    if not isinstance(value, str) or not value:
        raise ProtocolError(-32602, "missing_or_invalid_" + key)
    return value


def normalize_optional_flows(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    normalized: list[dict[str, Any]] = []
    for item in value:
        if not isinstance(item, dict):
            continue
        flow_name = toolbox.safe_scalar(item.get("name", ""), 64)
        condition = toolbox.safe_scalar(item.get("when", ""), 96)
        raw_steps = item.get("flow", [])
        if not isinstance(raw_steps, list):
            continue
        steps = [toolbox.safe_scalar(step, 64) for step in raw_steps]
        steps = [step for step in steps if step]
        if flow_name and condition and steps:
            normalized.append({"name": flow_name, "when": condition, "flow": steps})
    return normalized


def render_prompt_text(name: str, flow: list[str], optional_flows: list[dict[str, Any]] | None = None) -> str:
    flow_text = " -> ".join(flow)
    flow_label = "Default local flow" if name == "desktop_final_verifier" else "Tool flow"
    optional_flow_lines = [
        f"Optional flow [{item['name']}] when [{item['when']}]: {' -> '.join(item['flow'])}"
        for item in (optional_flows or [])
    ]
    common = [
        f"Prompt: {name}",
        f"{flow_label}: {flow_text}",
        *optional_flow_lines,
        "Use main/resources/mcp/awx-control-tower-mcp-client.sample.json for the stdio bridge config.",
        "Keep logs redacted: requestId, sessionId, nodeRole, toolName, inputHash, outputCount, elapsedMs, decision, failReason.",
        "Allowed environment references are NAVER_KEYS, NAVER_CLIENT_ID, and NAVER_CLIENT_SECRET by name only.",
    ]
    if name == "macmini_patch_producer":
        role = [
            "Role: Mac mini read-only investigator and PatchDrop patch producer.",
            "Set the MCP client cwd to a producer-local worktree or clone; never set it to the Desktop canonical source root.",
            "Run source_scan, archive_search, patch_plan, patch_render, boot_verify, and build_error_mine from the producer-local root.",
            "Submit only PatchDrop evidence, unified diffs, verification logs, SHA sidecars, and node-smoke JSON for Desktop intake.",
            "Do not write SMB canonical source paths; archive_restore targets must be local temp/worktree paths.",
            "Desktop final proof for external producer work remains evidence_needed until Desktop intake, audit, and Gradle verification.",
        ]
    elif name == "desktop_final_verifier":
        role = [
            "Role: Desktop canonical source owner and final verifier for C:/AbandonWare/demo-1/demo-1/src.",
            "Desktop-only proof: run the local flow plus Desktop Gradle/source-governance; optional producer proof does not block completion.",
            "External producer proof: explicit handoff only; remains evidence_needed through dispatch/intake/audit, PatchDrop diff/secret/git-apply review, and Desktop Gradle/source-governance before applied.",
        ]
    elif name == "notebook_support_reviewer":
        role = [
            "Role: Notebook supporting probe, documentation, and diff-review node.",
            "Use a local clone or worktree only; do not edit the Desktop canonical source through SMB.",
            "Return read-only findings, review notes, build-log classifications, and PatchDrop evidence for Desktop final review.",
            "Desktop final proof for Notebook evidence remains evidence_needed until Desktop intake, audit, and verification.",
        ]
    else:
        role = ["Role: follow the manifest tool flow while preserving Desktop final-proof ownership."]
    return "\n".join(role + common)


def manifest_uri(path: str) -> str:
    return "awx://control-tower/" + path.replace("\\", "/").lstrip("/")


def path_from_manifest_uri(uri: str) -> Path:
    prefix = "awx://control-tower/"
    if not uri.startswith(prefix):
        raise ProtocolError(-32602, "unsupported_resource_uri")
    rel = uri[len(prefix):]
    path = (ROOT / rel).resolve()
    if not path.is_relative_to(ROOT.resolve()) or path == ROOT.resolve():
        raise ProtocolError(-32602, "resource_path_outside_root")
    return path


def public_schema(value: Any) -> Any:
    # Property names describe public contracts, not runtime secret values.
    # Keep structural types intact while applying string/literal redaction.
    if isinstance(value, dict):
        return {key: toolbox.redact(item) if key in {"default", "examples"} else public_schema(item)
                for key, item in value.items()}
    if isinstance(value, list):
        return [public_schema(item) for item in value]
    return toolbox.redact_string(value) if isinstance(value, str) else value


def ok_response(request_id: Any, result: dict[str, Any], *, method: str = "") -> dict[str, Any]:
    if method == "tools/call":
        # call_tool already redacts and validates the final public payload.
        public = result
    elif method == "tools/list":
        public = {"tools": []}
        for row in result["tools"]:
            tool = toolbox.redact({key: value for key, value in row.items() if key not in {"inputSchema", "outputSchema"}})
            for key in ("inputSchema", "outputSchema"):
                if key in row:
                    tool[key] = public_schema(row[key])
            public["tools"].append(tool)
        if "nextCursor" in result:
            public["nextCursor"] = result["nextCursor"]
    else:
        public = toolbox.redact(result)
    return {"jsonrpc": "2.0", "id": request_id, "result": public}


def error_response(request_id: Any, code: int, message: str) -> dict[str, Any]:
    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {
            "code": code,
            "message": toolbox.safe_message(message, 240),
        },
    }


if __name__ == "__main__":
    raise SystemExit(main())
