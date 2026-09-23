"""Bounded packaged-JAR smoke using owned H2 memory, temp files and loopback HTTP fixtures.

Preserves application authentication. No inherited provider credentials or JVM options.
Fixture receipt proves only this local server received an HTTP request.
"""
import argparse
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request
import urllib.parse
import uuid


def now():
    return datetime.now(timezone.utc).isoformat()


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def read_proof(log_path):
    fields = {"requestHash", "executionHash", "runHash", "logicalCallOrdinal", "attemptSequence",
              "eventSequence", "event", "boundary", "observedAtEpochMs", "afterCancel", "dropped",
              "providerReceiptObserved", "sequence", "role", "outcome", "failureClass", "terminalClass"}
    result = {"lifecycle": [], "attempts": [], "retrieval": [], "transportCancellation": []}
    for line in Path(log_path).read_text(encoding="utf-8", errors="replace").splitlines():
        if "[LLM_TRANSPORT_CANCEL]" in line:
            result["transportCancellation"].append({k: v for k, v in re.findall(r"(\w+)=([^\s,]+)", line)
                if k in {"runHash", "transport", "forwarding", "providerCompletion"}})
        if line.startswith('{'):
            try:
                event = json.loads(line)
                data = event.get('data', {})
                if data.get('stage') == 'RETRIEVAL' or event.get('where') == 'ChatWorkflow.promptBuild':
                    result['retrieval'].append(dict(requestHash=event.get('requestId'),
                        data={k: v for k, v in data.items() if k in {
                            'stage', 'failureClass', 'reasonCode', 'webCount', 'ragCount', 'citableEvidenceCount'}}))
            except (ValueError, TypeError, AttributeError):
                pass
        kind = "lifecycle" if "[LLM_REQUEST_LIFECYCLE]" in line else "attempts" if "[LLM_REQUEST_PROOF]" in line else None
        if kind:
            result[kind].append({k: v for k, v in re.findall(r"(\w+)=([^\s,]+)", line) if k in fields})
    return result


def validate_case(case, receipts, proof):
    """Join actual request hashes; receipt association is explicitly serialized-harness scope."""
    request_hash = case["requestHash"]
    lifecycle = [r for r in proof["lifecycle"] if r.get("requestHash") == request_hash]
    attempts = [r for r in proof["attempts"] if r.get("requestHash") == request_hash]
    case["lifecycle"] = lifecycle[:128]
    case["attemptProof"] = attempts[:128]
    case["lifecycleDropped"] = max([int(r.get("dropped", 0)) for r in lifecycle] + [max(0, len(lifecycle)-128)])
    case["attemptProofDropped"] = max(0, len(attempts)-128)
    rows = [r for r in receipts if r.get("harnessCaseRequestHash") == request_hash]
    models = [r for r in rows if r["kind"] == "model"]
    searches = [r for r in rows if r["kind"] == "search"]
    starts = [r for r in lifecycle if r.get("event") == "http_client_started"]
    errors = []
    if case.get("httpStatus") != 200 or case.get("errorType"):
        errors.append("http_stream_incomplete")
    if not starts or case["lifecycleDropped"] or case["attemptProofDropped"]:
        errors.append("request_lifecycle_missing_or_dropped")
    if case["mode"] == "normal":
        if not models or not case.get("fixtureAnswerObserved"):
            errors.append("normal_fixture_answer_missing")
    elif case["mode"] == "zero":
        if not searches or any(r.get("returnedItemsCount") != 0 for r in searches):
            errors.append("actual_zero_receipt_missing")
        retrieval = [r['data'] for r in proof.get('retrieval', []) if r.get('requestHash') == request_hash]
        case['retrievalProof'] = retrieval[:32]
        if not any(r.get('stage') == 'RETRIEVAL' and r.get('failureClass') == 'ZERO_RESULT' for r in retrieval):
            errors.append('final_retrieval_zero_missing')
        if not any(r.get('webCount') == 0 and r.get('ragCount') == 0 for r in retrieval):
            errors.append('final_prompt_zero_counts_missing')
    elif case["mode"] == "failure":
        primary = [r for r in attempts if r.get("role") == "primary" and r.get("outcome") == "failed"]
        fallback = [r for r in attempts if r.get("role") == "fallback" and r.get("outcome") == "success"]
        ordered = any(int(p.get("sequence", 0)) < int(f.get("sequence", 0)) for p in primary for f in fallback)
        failed = [r for r in models if r.get("status") == 503]
        succeeded = [r for r in models if r.get("status") == 200]
        receipt_ordered = any(p["ordinal"] < f["ordinal"] and p["modelHash"] != f["modelHash"] for p in failed for f in succeeded)
        if not ordered or not receipt_ordered or not case.get("fixtureAnswerObserved"):
            errors.append("primary_failure_to_fallback_success_missing")
    elif case["mode"] == "hold":
        accepted = [r for r in lifecycle if r.get("event") == "cancel_accepted" and r.get("runHash") == case.get("runHash")]
        after_starts = [r for r in lifecycle if r.get("afterCancel") == "true" and r.get("event") in ("application_call_intent", "http_client_started")]
        case["postCancelNewStarts"] = len(after_starts)
        case["lateCompletions"] = sum(r.get("afterCancel") == "true" and r.get("event") == "http_client_completed" for r in lifecycle)
        case["serverProcessingStopped"] = "not_observed"
        case["fixturePeerDisconnectObserved"] = any(r.get("peerDisconnectedObserved", False) for r in models)
        case["transportCancellation"] = [r for r in proof.get("transportCancellation", []) if r.get("runHash") == case.get("runHash")]
        if not accepted or after_starts or not models or case.get("stopHttpStatus") != 200 or not case.get("streamEndedBeforeFixtureRelease"):
            errors.append("accepted_cancel_and_no_new_start_missing")
    case["validationErrors"] = errors
    case["assertionsPassed"] = not errors
    return not errors


def identity(pids):
    if os.name != "nt":
        return []
    ids = ",".join(str(int(pid)) for pid in pids)
    command = "$r=@();foreach($n in @("+ids+")){$p=Get-Process -Id $n -ErrorAction SilentlyContinue;if($p){$ports=@(Get-NetTCPConnection -State Listen -OwningProcess $n -ErrorAction SilentlyContinue|Select-Object -ExpandProperty LocalPort -Unique|Sort-Object);$r+=@{pid=$p.Id;name=$p.ProcessName;startedAt=$p.StartTime.ToUniversalTime().ToString('o');ports=$ports}}};ConvertTo-Json -InputObject @($r) -Depth 4 -Compress"
    result = subprocess.run(["powershell", "-NoProfile", "-Command", command], capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


class Fixture:
    def __init__(self):
        self.mode = "normal"
        self.case_request_hash = None
        self.rows = []
        self.lock = threading.Lock()
        self.received = threading.Event()
        self.release = threading.Event()
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                if self.path.startswith("/control/"):
                    owner.mode = self.path.rsplit("/", 1)[-1]
                    owner.received.clear()
                    owner.release.clear()
                    return self.send_json(200, {"mode": owner.mode})
                self.handle_call({})

            def do_POST(self):
                size = int(self.headers.get("Content-Length", "0"))
                if size > 2_000_000:
                    return self.send_json(413, {})
                try:
                    request = json.loads(self.rfile.read(size))
                except ValueError:
                    return self.send_json(400, {})
                self.handle_call(request)

            def handle_call(self, request):
                model = str(request.get("model", ""))
                path = urllib.parse.urlparse(self.path).path
                is_search = path == "/res/v1/web/search"
                if not is_search and path not in ("/v1/chat/completions", "/api/chat"):
                    with owner.lock:
                        owner.rows.append(dict(kind="unsupported_endpoint", path=path, startedAt=now()))
                    return self.send_json(404, {})
                row = dict(kind="search" if is_search else "model", startedAt=now(),
                           modelHash=digest(model), mode=owner.mode, receiptScope="local_fixture",
                           harnessCaseRequestHash=owner.case_request_hash,
                           correlationScope="serialized_harness_case_not_forwarded_header")
                with owner.lock:
                    row["ordinal"] = len(owner.rows)+1
                    owner.rows.append(row)
                if not is_search and owner.mode == "hold":
                    owner.received.set()
                    owner.release.wait(60)
                if is_search:
                    results = [] if owner.mode == "zero" else [
                        {"title": "Fixture document "+str(n), "description": "Synthetic verification evidence.",
                         "url": owner.origin+"/document/"+str(n)} for n in range(3)]
                    status, body = 200, {"web": {"results": results}}
                    row["returnedItemsCount"] = len(results)
                elif owner.mode == "failure" and "primary" in model:
                    status, body = 503, {"error": {"message": "synthetic unavailable", "type": "fixture"}}
                elif self.path.startswith("/api/chat"):
                    status, body = 200, {"model": model, "message": {"role": "assistant", "content": "Fixture verification answer."}, "done": True, "prompt_eval_count": 3, "eval_count": 3}
                else:
                    status, body = 200, {"id": "fixture-completion", "object": "chat.completion", "created": 1,
                        "model": model, "choices": [{"index": 0, "message": {"role": "assistant", "content": "Fixture verification answer."}, "finish_reason": "stop"}],
                        "usage": {"prompt_tokens": 3, "completion_tokens": 3, "total_tokens": 6}}
                row["status"] = status
                try:
                    self.send_json(status, body)
                    row["responseWrittenAt"] = now()
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    row["peerDisconnectedObserved"] = True
                finally:
                    row["finishedAt"] = now()

            def send_json(self, status, body):
                data = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.origin = "http://127.0.0.1:"+str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)


def smoke(jar, output, java="java", browser_seconds=0, protected_pids=(), local_llm_budget=False):
    jar, output = Path(jar).resolve(), Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    jar_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
    run_id = uuid.uuid4().hex
    temp_parent = Path(tempfile.gettempdir()).resolve()
    owned = Path(tempfile.mkdtemp(prefix="awx-jar-verify-", dir=temp_parent)).resolve()
    (owned / ".verification-owner").write_text(run_id)
    fixture = Fixture()
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    report = dict(runId=run_id, startedAt=now(), status="starting", jarSha256=jar_hash,
                  execution="packaged_jar", evidenceScope="local_http_fixture", serverPort=port,
                  ownedWorkingDirectory=str(owned), dataSource="jdbc:h2:mem:verification_"+run_id,
                  fixturePort=fixture.server.server_port, protectedBefore=identity(protected_pids), cases=[])
    app = None

    def persist():
        (output / "runtime.json").write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")

    cookies = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    base = "http://127.0.0.1:"+str(port)

    def request(path, body=None, headers=None, timeout=45):
        headers = dict(headers or {})
        if body is not None:
            headers.setdefault("Content-Type", "application/json")
            if not isinstance(body, bytes):
                body = json.dumps(body).encode()
        csrf = next((c.value for c in cookies if c.name == "XSRF-TOKEN"), None)
        if csrf:
            headers["X-XSRF-TOKEN"] = csrf
        return client.open(urllib.request.Request(base+path, data=body, headers=headers), timeout=timeout)

    try:
        env = {key: value for key, value in os.environ.items() if key.upper() in {
            "SYSTEMROOT", "WINDIR", "PATH", "PATHEXT", "COMSPEC", "TEMP", "TMP", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "PROGRAMDATA", "JAVA_HOME"}}
        password = secrets.token_urlsafe(24)
        env.update(APP_CONFIG_IMPORT="optional:classpath:application-llm.yaml", LMS_ADMIN_BOOTSTRAP_PASSWORD=password,
                   SECURITY_ADMIN_SECRET=secrets.token_urlsafe(24), SECURITY_REMEMBER_ME_KEY=secrets.token_urlsafe(24))
        profiles = ["local", "local-llm", "verification"] if local_llm_budget else ["local", "verification"]
        command = [java, "-Xms128m", "-Xmx1024m", "-jar", str(jar), "--spring.profiles.active="+",".join(profiles),
            "--spring.config.location=classpath:/", "--verification.run-id="+run_id,
            "--verification.root="+str(owned), "--verification.fixture-port="+str(fixture.server.server_port),
            "--verification.server-port="+str(port)]
        report["command"] = {"mode": "java_-jar", "profiles": profiles,
            "argumentCount": len(command), "argumentSha256": digest(json.dumps(command)), "inheritedProviderCredentials": False,
            "workingDirectory": "owned_temporary", "database": "unique_h2_memory", "vectorStore": "memory", "embedding": "none"}
        with (output / "application.log").open("wb") as log:
            app = subprocess.Popen(command, cwd=owned, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        report["pid"] = app.pid
        report["process"] = identity([app.pid])
        persist()
        deadline = time.monotonic()+180
        while time.monotonic() < deadline:
            if app.poll() is not None:
                raise RuntimeError("application_exited_before_ready:"+str(app.returncode))
            try:
                with request("/login", timeout=2) as response:
                    page = response.read().decode("utf-8", "replace")
                    if response.status == 200:
                        break
            except (OSError, urllib.error.URLError):
                threading.Event().wait(0.5)
        else:
            raise RuntimeError("application_startup_timeout")
        report["process"] = identity([app.pid])
        token = re.search(r'name="_csrf"[^>]*value="([^"]+)"', page)
        fields = {"username": "admin", "password": password}
        if token:
            fields["_csrf"] = token.group(1)
        with request("/login", urllib.parse.urlencode(fields).encode(), {"Content-Type": "application/x-www-form-urlencoded"}) as response:
            report["loginRedirectPath"] = urllib.parse.urlparse(response.url).path
            if "error" in response.url:
                raise RuntimeError("isolated_admin_login_failed")
        password = None
        report["status"] = "running_cases"
        persist()

        for mode in ("normal", "zero", "failure", "hold"):
            fixture.mode = mode
            fixture.received.clear()
            fixture.release.clear()
            case = dict(mode=mode, startedAt=now(), fixtureStartOrdinal=len(fixture.rows)+1, events=[])
            report["cases"].append(case)
            session = {}
            ready = threading.Event()
            ended = threading.Event()
            request_id = uuid.uuid4().hex
            case["requestIdHash"] = digest(request_id)
            case["requestHash"] = "hash:"+digest(request_id)[:12]
            fixture.case_request_hash = case["requestHash"]

            def consume():
                try:
                    body = {"message": "합성 검증 문서 "+mode, "model": "llmrouter.gemma",
                            "useRag": mode == "zero", "useWebSearch": mode == "zero",
                            "searchMode": "FORCE_LIGHT" if mode == "zero" else "OFF"}
                    with request("/api/chat/stream", body, {"X-Request-Id": request_id}, timeout=60) as response:
                        case["httpStatus"] = response.status
                        event = "message"
                        answer_parts = []
                        for raw in response:
                            line = raw.decode("utf-8", "replace").strip()
                            if line.startswith("event:"):
                                event = line[6:].strip()
                            if line.startswith("data:"):
                                value = line[5:].strip()
                                case["events"].append(event)
                                try:
                                    data = json.loads(value)
                                except ValueError:
                                    data = {}
                                if isinstance(data, dict) and (event == "session" or data.get("type") == "session"):
                                    session.update(sessionId=data.get("sessionId"), runToken=data.get("data"))
                                    ready.set()
                                elif isinstance(data, dict) and isinstance(data.get("data"), str):
                                    answer_parts.append(data["data"])
                                if "Fixture verification answer." in value or "Fixture verification answer." in "".join(answer_parts):
                                    case["fixtureAnswerObserved"] = True
                except Exception as failure:
                    case["errorType"] = type(failure).__name__
                finally:
                    ended.set()

            consumer = threading.Thread(target=consume, daemon=True)
            consumer.start()
            if mode == "hold":
                if not ready.wait(30) or not fixture.received.wait(30):
                    raise RuntimeError("in_flight_request_not_observed")
                case["stopRequestedAt"] = now()
                with request("/api/chat/cancel", {"sessionId": session.get("sessionId"), "runToken": session.get("runToken")}) as response:
                    case["stopHttpStatus"] = response.status
                    response.read()
                case["streamEndedBeforeFixtureRelease"] = ended.wait(5)
                case["fixtureCountAfterStop"] = len(fixture.rows)
                fixture.release.set()
            if not ended.wait(65):
                raise RuntimeError("stream_not_terminated")
            consumer.join(5)
            case["endedAt"] = now()
            case["fixtureEndOrdinal"] = len(fixture.rows)
            case["runTokenHash"] = digest(str(session.get("runToken", "")))
            case["runHash"] = "hash:"+case["runTokenHash"][:12]
            persist()
        proof = read_proof(output / "application.log")
        for case in report["cases"]:
            validate_case(case, fixture.rows, proof)
        report["status"] = "passed" if len(report["cases"]) == 4 and all(c["assertionsPassed"] for c in report["cases"]) else "evidence_incomplete"
        if browser_seconds:
            report["browserUntilSeconds"] = browser_seconds
            fixture.mode = "hold"
            fixture.case_request_hash = None
            fixture.release.clear()
            persist()
            deadline = time.monotonic()+min(browser_seconds, 300)
            while time.monotonic() < deadline and not (output / "browser-done").exists():
                if app.poll() is not None:
                    break
                threading.Event().wait(0.5)
    except Exception as failure:
        report["status"] = "incomplete"
        report["failureType"] = type(failure).__name__
        report["reason"] = str(failure) if isinstance(failure, RuntimeError) else "inspect_owned_application_log"
    finally:
        fixture.release.set()
        if app is not None:
            if app.poll() is None:
                app.terminate()
                try:
                    app.wait(15)
                except subprocess.TimeoutExpired:
                    app.kill()
                    app.wait(10)
            report["ownedProcessExited"] = app.poll() is not None
        fixture.close()
        report["fixtureRequests"] = fixture.rows
        proof = read_proof(output / "application.log")
        if len(report["cases"]) == 4 and all("endedAt" in case for case in report["cases"]):
            passed = [validate_case(case, fixture.rows, proof) for case in report["cases"]]
            report["status"] = "passed" if all(passed) else "evidence_incomplete"
        report["lifecycleProof"] = proof["lifecycle"][-256:]
        report["lifecycleProofDropped"] = max(0, len(proof["lifecycle"])-256)
        report["jarUnchanged"] = hashlib.sha256(jar.read_bytes()).hexdigest() == jar_hash
        report["protectedAfter"] = identity(protected_pids)
        report["protectedUnchanged"] = report["protectedBefore"] == report["protectedAfter"]
        report["endedAt"] = now()
        if owned.parent != temp_parent or owned.is_symlink() or (owned / ".verification-owner").read_text() != run_id:
            raise RuntimeError("cleanup_ownership_mismatch")
        shutil.rmtree(owned)
        report["ownedTemporaryRootRemoved"] = not owned.exists()
        persist()
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--browser-seconds", type=int, default=0)
    parser.add_argument("--protected-pid", type=int, action="append", default=[])
    parser.add_argument("--local-llm-budget", action="store_true", help="Activate local request-budget profile before restrictive verification overrides")
    args = parser.parse_args()
    result = smoke(args.jar, args.output, args.java, args.browser_seconds, args.protected_pid, args.local_llm_budget)
    print(json.dumps({key: result.get(key) for key in ("runId", "status", "reason", "ownedProcessExited", "protectedUnchanged")}))
    raise SystemExit(0 if result["status"] == "passed" and result["protectedUnchanged"] else 1)
