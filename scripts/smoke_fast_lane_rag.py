#!/usr/bin/env python3
"""smoke_fast_lane_rag — 3-second fast-lane chat smoke for demo-1.

Standard library only. Resolves the test model through the agent test-model
policy (configs/agent-test-model-policy.yaml via scripts/test_model_policy.py),
sends ONE synthetic [devin-test] message to POST /api/chat/sync on a loopback
base, and proves which lane answered via the x-model-used header + modelUsed
body field. Public kro.kr sends are never implicit: pass --base explicitly.

  python -B scripts/smoke_fast_lane_rag.py                     # local 18180, policy smoke purpose
  python -B scripts/smoke_fast_lane_rag.py --no-send           # surface + resolve only
  python -B scripts/smoke_fast_lane_rag.py --self-test         # offline stub, no server needed
  AWX_TEST_MODEL=chatgpt-oauth:gpt-5.6-luna python -B scripts/smoke_fast_lane_rag.py

verdict: PASS | FAIL | UNREACHABLE | BLOCKED_API | POLICY_ERROR
exit 0 iff verdict==PASS, 1 on FAIL/UNREACHABLE, 6 on BLOCKED_API, 2 on usage
or policy errors. One JSON object is printed per step plus a final summary.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import ssl
import sys
import tempfile
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))

DEFAULT_BASE = "http://127.0.0.1:18180"
DEFAULT_DEADLINE_MS = 3000
DEFAULT_PURPOSE = "smoke"
USER_AGENT = "devin-fastlane-smoke/1.0"
TEST_MESSAGE = "[devin-test] fast-lane smoke — reply with the single word OK."
KST = timezone(timedelta(hours=9), "KST")

HELD_ANSWER = re.compile(
    r"응답 본문을 보류|검증된 근거가 추가로 필요|요청하신 문서·첨부 범위|"
    r"Assistant is preparing|Information unavailable|evidence_needed",
    re.IGNORECASE)
FAILURE_CODES = re.compile(
    r"backend_unavailable|model_unavailable|provider_unauthorized|"
    r"provider_not_configured|request_cancelled|timeout",
    re.IGNORECASE)

# Local Ollama lane inventory is informational evidence only: it can prove the
# answer did NOT come from a local GPU model, never that a remote one answered.
OLLAMA_PS_URLS = ("http://127.0.0.1:11434/api/ps", "http://127.0.0.1:11435/api/ps")


def now_kst():
    return datetime.now(KST).isoformat(timespec="seconds")


def http_json(req, timeout_s, read_limit=4 * 1024 * 1024):
    """Return (status, headers, parsed_json_or_text, error). Never raises."""
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            raw = resp.read(read_limit).decode("utf-8", errors="replace")
            try:
                return resp.status, dict(resp.headers), json.loads(raw), None
            except ValueError:
                return resp.status, dict(resp.headers), raw, None
    except urllib.error.HTTPError as exc:
        try:
            body = exc.read(read_limit).decode("utf-8", errors="replace")
        except OSError:
            body = ""
        try:
            parsed = json.loads(body) if body else body
        except ValueError:
            parsed = body
        return exc.code, dict(exc.headers or {}), parsed, f"http-{exc.code}"
    except (urllib.error.URLError, socket.timeout, TimeoutError, ssl.SSLError, OSError) as exc:
        return None, {}, None, type(exc).__name__ + ": " + str(getattr(exc, "reason", exc))


def probe_surface(base):
    """GET /chat — surface up and main chat-ui (not the interview screen)."""
    req = urllib.request.Request(base.rstrip("/") + "/chat", method="GET",
                                 headers={"User-Agent": USER_AGENT})
    status, _, body, error = http_json(req, 8)
    text = body if isinstance(body, str) else ""
    markers = sum(1 for m in ('id="chatWindow"', 'data-testid="chat-composer"',
                              'id="historyPanel"', "/js/chat.js") if m in text)
    return {"step": "surface", "http": status, "chatUiMarkers": markers,
            "ok": status == 200 and markers >= 2 and "INTERVIEW DEMO" not in text,
            "error": error}


def resolve_model(base, purpose, explicit_model, usage_log):
    """Policy-first model pick. AWX_TEST_MODEL overrides only the catalog pick."""
    if explicit_model:
        return {"verdict": "RESOLVED", "selected": explicit_model,
                "mode": "env-override", "source": "AWX_TEST_MODEL/--model"}
    try:
        import test_model_policy as tmp
    except ImportError as exc:
        return {"verdict": "POLICY_ERROR", "error": "test_model_policy import: " + str(exc)}
    try:
        catalog = tmp.load_catalog(None, base=base)
    except (OSError, ValueError, urllib.error.URLError) as exc:
        return {"verdict": "CATALOG_UNAVAILABLE", "error": type(exc).__name__}
    out = tmp.resolve(purpose, catalog=catalog, policy=tmp.load_policy(), usage_log=usage_log)
    return {"verdict": out.get("verdict"), "selected": out.get("selected"),
            "mode": out.get("mode"), "alternates": out.get("alternates"),
            "reason": out.get("reason")}


def local_gpu_note():
    """Read-only /api/ps inventory; proves a chat answer did not use local GPU."""
    rows = []
    for url in OLLAMA_PS_URLS:
        req = urllib.request.Request(url, method="GET",
                                     headers={"User-Agent": USER_AGENT})
        status, _, body, error = http_json(req, 1.5)
        models = []
        if isinstance(body, dict):
            models = [m.get("name") for m in body.get("models", []) if m.get("name")]
        rows.append({"url": url, "http": status, "residentModels": models,
                     "error": error})
    return {"step": "localGpu", "observationOnly": True, "lanes": rows}


def send_chat(base, model, deadline_ms):
    """One synthetic send. Elapsed + headers + body captured; never raises."""
    body = json.dumps({
        "message": TEST_MESSAGE, "question": TEST_MESSAGE,
        "model": model, "strictModelSelection": True,
        "useRag": False, "useWebSearch": False, "searchMode": "OFF",
        "maxTokens": 64,
    }).encode("utf-8")
    req = urllib.request.Request(
        base.rstrip("/") + "/api/chat/sync", data=body, method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json",
                 "User-Agent": USER_AGENT})
    import time
    started = time.monotonic()
    status, headers, data, error = http_json(req, deadline_ms / 1000.0)
    elapsed_ms = int((time.monotonic() - started) * 1000)
    content, model_used, reason_code = "", None, None
    if isinstance(data, dict):
        content = str(data.get("content") or "")
        model_used = data.get("modelUsed")
        reason_code = data.get("reasonCode") or data.get("error")
    elif isinstance(data, str):
        content = data
    if model_used is None:
        model_used = headers.get("X-Model-Used") or headers.get("x-model-used")
    held = bool(HELD_ANSWER.search(content))
    failure = FAILURE_CODES.search(content) or (
        FAILURE_CODES.search(str(reason_code)) if reason_code else None)
    return {"step": "send", "http": status, "elapsedMs": elapsed_ms,
            "deadlineMs": deadline_ms, "withinDeadline": elapsed_ms <= deadline_ms,
            "answerChars": len(content.strip()), "answerHeld": held,
            "failureCode": failure.group(0) if failure else reason_code,
            "modelUsed": model_used, "error": error}


def classify(surface, resolved, sent, checked):
    """verdict assembly — held answers and silent fallbacks are never PASS."""
    reasons = []
    if surface and not surface["ok"]:
        if surface.get("http") is None:
            return {"verdict": "UNREACHABLE", "reasons": ["surface_unreachable"]}
        reasons.append("surface_not_main_chat")
    if resolved and resolved.get("verdict") != "RESOLVED":
        verdict = "BLOCKED_API" if resolved.get("verdict") in (
            "BLOCKED_API", "NO_CANDIDATE", "CATALOG_UNAVAILABLE") else "POLICY_ERROR"
        return {"verdict": verdict, "reasons": [str(resolved.get("verdict"))]}
    if sent:
        if sent["http"] is None:
            return {"verdict": "UNREACHABLE", "reasons": ["sync_unreachable:" + str(sent.get("error"))]}
        if sent["http"] != 200:
            reasons.append("http_" + str(sent["http"]))
        if not sent["withinDeadline"]:
            reasons.append("deadline_exceeded")
        if sent["answerChars"] == 0:
            reasons.append("empty_answer")
        if sent["answerHeld"]:
            reasons.append("answer_held")
        if sent.get("failureCode"):
            reasons.append("failure_code:" + sent["failureCode"])
    if checked and checked.get("verdict") != "OK":
        reasons.append("model_check:" + str(checked.get("verdict")))
    return {"verdict": "FAIL" if reasons else "PASS", "reasons": reasons}


def check_observed(selected, observed, usage_log):
    try:
        import test_model_policy as tmp
    except ImportError as exc:
        return {"verdict": "POLICY_ERROR", "error": str(exc)}
    return tmp.check(selected, observed or "", policy=tmp.load_policy())


def record_send(agent, purpose, model, code, run, usage_log):
    try:
        import test_model_policy as tmp
        return tmp.record(agent, purpose, model, str(code), run=run, usage_log=usage_log)
    except Exception as exc:  # recording must never flip the verdict
        return {"recorded": False, "error": type(exc).__name__}


def run(base, args):
    steps, usage_log = [], args.usage_log
    surface = probe_surface(base)
    steps.append(surface)
    resolved = resolve_model(base, args.purpose, args.model, usage_log)
    steps.append({"step": "resolve", **resolved})
    sent = checked = None
    if not args.no_send and resolved.get("verdict") == "RESOLVED":
        sent = send_chat(base, resolved["selected"], args.deadline_ms)
        steps.append(sent)
        checked = check_observed(resolved["selected"], sent.get("modelUsed"), usage_log)
        steps.append({"step": "check", **checked})
        steps.append({"step": "record", **record_send(
            args.agent, args.purpose, resolved["selected"],
            sent["http"] if sent["http"] is not None else "error",
            args.run, usage_log)})
    steps.append(local_gpu_note())
    verdict = classify(surface, resolved, sent, checked)
    return {"tool": "smoke_fast_lane_rag", "base": base, "at": now_kst(),
            "purpose": args.purpose, "model": resolved.get("selected"),
            "verdict": verdict["verdict"], "reasons": verdict["reasons"],
            "steps": steps}


def self_test():
    """Offline loopback stub: proves classify/verdict paths without 18180."""
    import threading
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    chat_html = '<html><body><div id="chatWindow"></div>' \
        '<div data-testid="chat-composer"></div><div id="historyPanel"></div>' \
        '<script src="/js/chat.js"></script></body></html>'
    catalog = [{"id": "chatgpt-oauth:gpt-5.6-luna", "provider": "chatgpt_oauth",
                "endpointId": "chatgpt-oauth", "modelId": "gpt-5.6-luna",
                "status": "configured", "selectable": True}]

    class Stub(BaseHTTPRequestHandler):
        held = False

        def log_message(self, *a):
            pass

        def do_GET(self):
            if self.path == "/chat":
                body = chat_html.encode()
            elif self.path == "/api/chat/models":
                body = json.dumps(catalog).encode()
            else:
                self.send_response(404)
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/json" if "models" in self.path else "text/html")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            if self.path != "/api/chat/sync":
                self.send_response(404)
                self.end_headers()
                return
            content = ("검증된 근거가 추가로 필요해 응답 본문을 보류했습니다."
                       if Stub.held else "OK")
            body = json.dumps({"content": content,
                               "modelUsed": "chatgpt-oauth:gpt-5.6-luna"}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("X-Model-Used", "chatgpt-oauth:gpt-5.6-luna")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = ThreadingHTTPServer(("127.0.0.1", 0), Stub)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}"
    try:
        ns = argparse.Namespace(purpose=DEFAULT_PURPOSE, model=None, no_send=False,
                                deadline_ms=DEFAULT_DEADLINE_MS, usage_log=None,
                                agent="self-test", run=None)
        ok_run = run(base, ns)
        Stub.held = True
        held_run = run(base, ns)
        checks = {
            "ok_run_pass": ok_run["verdict"] == "PASS",
            "held_run_fail": held_run["verdict"] == "FAIL",
            "held_reason": "answer_held" in held_run["reasons"],
        }
        verdict = "SELF_TEST_OK" if all(checks.values()) else "SELF_TEST_FAIL"
        print(json.dumps({"verdict": verdict, "checks": checks,
                          "okRun": ok_run["verdict"], "heldRun": held_run["verdict"]},
                         ensure_ascii=False))
        return 0 if verdict == "SELF_TEST_OK" else 1
    finally:
        server.shutdown()
        server.server_close()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", default=DEFAULT_BASE,
                    help="loopback base URL (public sends are never implicit)")
    ap.add_argument("--purpose", default=DEFAULT_PURPOSE,
                    help="test-model-policy purpose: smoke|regression|quality|cross_provider|local_fallback")
    ap.add_argument("--model", default=os.environ.get("AWX_TEST_MODEL"),
                    help="explicit model id; default env AWX_TEST_MODEL else policy resolve")
    ap.add_argument("--deadline-ms", type=int, default=DEFAULT_DEADLINE_MS,
                    help="per-send wall-clock deadline (default 3000)")
    ap.add_argument("--no-send", action="store_true",
                    help="surface + catalog resolve only")
    ap.add_argument("--self-test", action="store_true",
                    help="offline loopback stub; no server needed")
    ap.add_argument("--run", default=None, help="usage-ledger run id")
    ap.add_argument("--agent", default="devin-fastlane", help="usage-ledger agent")
    ap.add_argument("--usage-log", default=None, help="usage.jsonl override")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    from urllib.parse import urlparse
    host = urlparse(args.base)
    if host.scheme not in ("http", "https") or not host.hostname:
        print(json.dumps({"verdict": "USAGE", "error": "bad --base"}))
        return 2
    if host.hostname not in ("127.0.0.1", "localhost", "::1"):
        print(json.dumps({"verdict": "USAGE",
                          "error": "non-loopback --base needs an explicit caller decision"}))
        return 2
    out = run(args.base.rstrip("/"), args)
    print(json.dumps(out, ensure_ascii=False))
    return {"PASS": 0, "BLOCKED_API": 6}.get(out["verdict"],
            2 if out["verdict"] == "POLICY_ERROR" else 1)


if __name__ == "__main__":
    sys.exit(main())
