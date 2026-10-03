#!/usr/bin/env python3
"""Loopback mock for one Jev evaluate POST.

Binds 127.0.0.1 only. Request bodies are counted and discarded. The
credential header is recorded as present or absent; its value is never logged.
--report-zdr records only whether providerOptions.gateway.zeroDataRetention
is present and, when that value is a boolean, the boolean itself.
A questions.routeDecision key selects a routeDecision answer. A routeProfile
key, or a body that does not parse, keeps the legacy routeProfile answer.
Does not call Vercel.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

STATUSES = (200, 401, 402, 403, 429, 503)
CHOICES = ("RECENT_ONLY", "SCOPED_RAG", "WEB", "HYBRID", "CLARIFY")
SCENARIOS = (
    "zdr_plan_gate",
    "model_substring",
    "model_bare",
    "model_missing",
    "choice_number",
    "choice_object",
    "route_missing",
    "provider_403",
    "billing_402",
    "upstream_503",
    "slow_body",
    "hang",
    "late_usage",
    "rate_429",
    "probs_missing",
    "probs_out_of_range",
    "probs_nonnumeric",
    "usage_missing",
    "cost_unparseable",
    "oversize_body",
)
SMOKE_KEY = "synthetic-local-mock"
PLAN_GATE_MESSAGE = (
    "Zero Data Retention (ZDR) is only available for Pro and Enterprise plans. "
    "Current plan: hobby. Please upgrade your plan."
)
PROVIDER_POLICY_MESSAGE = "Forbidden by provider project policy"


def response_body(status: int) -> bytes:
    if status == 200:
        payload = {
            "model": "typesafe-ai/jev",
            "answers": {
                "routeProfile": {
                    "choice": "KEEP_BASELINE",
                    "probabilities": {"KEEP_BASELINE": 1, "COST_SAVER": 0},
                },
                "freshnessNeeded": {"probability": 0},
            },
        }
    else:
        payload = {"error": "mock", "status": status}
    return json.dumps(payload).encode("utf-8")


def plan_gate_body() -> bytes:
    payload = {
        "error": {
            "type": "permission_denied",
            "message": PLAN_GATE_MESSAGE,
        }
    }
    return json.dumps(payload).encode("utf-8")


def route_decision_body(choice: str, model: str | None = "typesafe-ai/jev",
                        choice_value=None, include_route: bool = True) -> bytes:
    answers: dict = {}
    if include_route:
        answers["routeDecision"] = {
            "choice": choice if choice_value is None else choice_value,
        }
    payload: dict = {"answers": answers}
    if model is not None:
        payload = {"model": model, "answers": answers}
    return json.dumps(payload).encode("utf-8")


def scenario_response(scenario: str, choice: str) -> tuple[int, bytes, bool]:
    """Return HTTP status, body, and whether the body waits until after headers."""
    if scenario == "zdr_plan_gate":
        return 403, plan_gate_body(), False
    if scenario == "model_substring":
        return 200, route_decision_body(choice, model="evil-jev-proxy"), False
    if scenario == "model_bare":
        return 200, route_decision_body(choice, model="jev"), False
    if scenario == "model_missing":
        return 200, route_decision_body(choice, model=None), False
    if scenario == "choice_number":
        return 200, route_decision_body(choice, choice_value=1), False
    if scenario == "choice_object":
        return 200, route_decision_body(choice, choice_value={}), False
    if scenario == "route_missing":
        return 200, route_decision_body(choice, include_route=False), False
    if scenario == "provider_403":
        payload = {
            "error": {
                "message": PROVIDER_POLICY_MESSAGE,
                "type": "permission_denied",
            }
        }
        return 403, json.dumps(payload).encode("utf-8"), False
    if scenario == "billing_402":
        return 402, response_body(402), False
    if scenario == "upstream_503":
        return 503, response_body(503), False
    if scenario == "slow_body":
        return 200, route_decision_body(choice), True
    if scenario == "rate_429":
        return 429, response_body(429), False
    if scenario == "probs_missing":
        return 200, _prob_body(choice, include=False), False
    if scenario == "probs_out_of_range":
        return 200, _prob_body(choice, include=True, probability=1.2), False
    if scenario == "probs_nonnumeric":
        return 200, _prob_body(choice, include=True, probability="high"), False
    if scenario == "usage_missing":
        return 200, route_decision_body(choice), False
    if scenario == "cost_unparseable":
        return 200, _usage_body(choice, cost="n/a"), False
    if scenario == "late_usage":
        return 200, _usage_body(choice, cost="0.000019698"), False
    if scenario == "oversize_body":
        return 200, json.dumps({"oversize": "x" * 65537}).encode("utf-8"), False
    if scenario == "hang":
        return 200, b"", False
    raise ValueError("scenario-not-allowed")


def _prob_body(choice: str, include: bool, probability=None) -> bytes:
    answer = {"choice": choice}
    if include:
        answer["probabilities"] = {choice: probability}
    payload = {"model": "typesafe-ai/jev", "answers": {"routeDecision": answer}}
    return json.dumps(payload).encode("utf-8")


def _usage_body(choice: str, cost: str) -> bytes:
    payload = {
        "model": "typesafe-ai/jev",
        "answers": {"routeDecision": {"choice": choice}},
        "usage": {"inputTokens": 11, "outputTokens": 7},
        "providerMetadata": {"gateway": {"cost": cost}},
    }
    return json.dumps(payload).encode("utf-8")


def decorate_body(body: bytes, scenario: str | None, wire: str,
                  usage_in: int | None, usage_out: int | None,
                  cost_str: str | None, echo_model: str | None) -> bytes:
    """Add live-wire usage only when asked. Default bytes stay unchanged."""
    frozen = {
        "probs_missing", "probs_out_of_range", "probs_nonnumeric",
        "usage_missing", "oversize_body", "hang", "zdr_plan_gate",
        "provider_403", "billing_402", "upstream_503",
    }
    if (wire != "live" and not echo_model) or scenario in ("oversize_body", "hang"):
        return body
    if scenario in frozen and not echo_model:
        return body
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return body
    if not isinstance(payload, dict):
        return body
    if echo_model and scenario not in frozen:
        payload["model"] = echo_model
    elif echo_model and "error" not in payload:
        payload["model"] = echo_model
    live = wire == "live" or scenario == "late_usage"
    if live and scenario not in frozen and "error" not in payload:
        usage = payload.get("usage") if isinstance(payload.get("usage"), dict) else {}
        payload["usage"] = {
            "inputTokens": usage.get("inputTokens", 11) if usage_in is None else usage_in,
            "outputTokens": usage.get("outputTokens", 7) if usage_out is None else usage_out,
        }
        if scenario != "cost_unparseable":
            meta = payload.get("providerMetadata")
            if not isinstance(meta, dict):
                meta = {}
            gateway = meta.get("gateway")
            if not isinstance(gateway, dict):
                gateway = {}
            gateway["cost"] = "0.000019698" if cost_str is None else str(cost_str)
            meta["gateway"] = gateway
            payload["providerMetadata"] = meta
    return json.dumps(payload).encode("utf-8")


def write_billing_feed(path: str | None, call_id: str | None,
                       usage_in: int | None, usage_out: int | None,
                       cost_str: str | None) -> None:
    if not path:
        return
    row = {
        "callId": call_id,
        "usage": {
            "inputTokens": 11 if usage_in is None else usage_in,
            "outputTokens": 7 if usage_out is None else usage_out,
        },
        "cost": "0.000019698" if cost_str is None else str(cost_str),
    }
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(row, ensure_ascii=True) + "\n")


def question_shape(raw: bytes) -> str:
    """routeDecision or legacy. The parsed body is not retained."""
    parsed = None
    try:
        if raw:
            parsed = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return "legacy"
    shape = "legacy"
    if isinstance(parsed, dict):
        questions = parsed.get("questions")
        if isinstance(questions, dict) and "routeProfile" not in questions and "routeDecision" in questions:
            shape = "routeDecision"
    del parsed
    return shape


def ok_body(raw: bytes, choice: str) -> bytes:
    if question_shape(raw) == "routeDecision":
        return route_decision_body(choice)
    return response_body(200)


def zdr_observation(raw: bytes) -> dict:
    """Presence and boolean value only. The raw body is not retained."""
    present = False
    value = None
    parsed = None
    try:
        if raw:
            parsed = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        parsed = None
    gateway = None
    if isinstance(parsed, dict):
        provider = parsed.get("providerOptions")
        if isinstance(provider, dict):
            gateway = provider.get("gateway")
    if isinstance(gateway, dict) and "zeroDataRetention" in gateway:
        present = True
        raw_value = gateway.get("zeroDataRetention")
        if isinstance(raw_value, bool):
            value = raw_value
    del parsed, gateway
    return {"zdrKeyPresent": present, "zdrValue": value}


class MockServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def make_handler(status: int, delay_ms: int, retry_after: int | None,
                 records: list[dict], max_requests: int, *,
                 scenario: str | None = None,
                 report_zdr: bool = False,
                 zdr_requires_pro: bool = False,
                 choice: str = "HYBRID",
                 slow_body_ms: int = 0,
                 wire: str = "legacy",
                 usage_in: int | None = None,
                 usage_out: int | None = None,
                 cost_str: str | None = None,
                 hang_ms: int = 0,
                 billing_feed: str | None = None,
                 echo_model: str | None = None,
                 call_id: str | None = None):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            observed = zdr_observation(raw) if (report_zdr or zdr_requires_pro) else None
            record = {
                "path": self.path.split("?", 1)[0],
                "bodyBytes": len(raw),
                "authorizationPresent": self.headers.get("Authorization") is not None,
            }
            if report_zdr and observed is not None:
                record["zdrKeyPresent"] = observed["zdrKeyPresent"]
                record["zdrValue"] = observed["zdrValue"]
            records.append(record)
            if zdr_requires_pro and observed is not None and observed["zdrValue"] is True:
                resp_status, resp_body, slow, send_retry = 403, plan_gate_body(), False, None
            elif zdr_requires_pro:
                resp_status, resp_body, slow, send_retry = 200, ok_body(raw, choice), False, None
            elif scenario == "hang":
                resp_status, resp_body, slow, send_retry = 0, b"", False, None
            elif scenario:
                resp_status, resp_body, slow = scenario_response(scenario, choice)
                send_retry = retry_after if resp_status == 429 else None
                if scenario == "rate_429" and send_retry is None:
                    send_retry = 1
            elif status == 200:
                resp_status, resp_body, slow, send_retry = 200, ok_body(raw, choice), False, None
            else:
                resp_status, resp_body, slow = status, response_body(status), False
                send_retry = retry_after
            del raw
            if scenario == "hang":
                time.sleep((hang_ms if hang_ms > 0 else 1000) / 1000.0)
                self.close_connection = True
                if max_requests and len(records) >= max_requests:
                    threading.Thread(target=self.server.shutdown, daemon=True).start()
                return
            if scenario == "late_usage" and hang_ms > 0:
                time.sleep(hang_ms / 1000.0)
            resp_body = decorate_body(
                resp_body, scenario, wire, usage_in, usage_out, cost_str, echo_model)
            if scenario == "late_usage":
                write_billing_feed(billing_feed, call_id, usage_in, usage_out, cost_str)
            if delay_ms > 0 and not slow:
                time.sleep(delay_ms / 1000.0)
            self.send_response(resp_status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp_body)))
            if send_retry is not None:
                self.send_header("Retry-After", str(send_retry))
            self.end_headers()
            if slow and slow_body_ms > 0:
                try:
                    self.wfile.flush()
                except OSError:
                    return
                time.sleep(slow_body_ms / 1000.0)
            self.wfile.write(resp_body)
            if max_requests and len(records) >= max_requests:
                threading.Thread(target=self.server.shutdown, daemon=True).start()

        def log_message(self, fmt: str, *args) -> None:
            return

    return Handler


def serve(status: int, delay_ms: int, retry_after: int | None, port: int,
          max_requests: int, records: list[dict] | None = None, *,
          scenario: str | None = None,
          report_zdr: bool = False,
          zdr_requires_pro: bool = False,
          choice: str = "HYBRID",
          slow_body_ms: int = 0,
          wire: str = "legacy",
          usage_in: int | None = None,
          usage_out: int | None = None,
          cost_str: str | None = None,
          hang_ms: int = 0,
          billing_feed: str | None = None,
          echo_model: str | None = None,
          call_id: str | None = None) -> MockServer:
    if choice not in CHOICES:
        raise ValueError("choice-not-allowed")
    if slow_body_ms < 0:
        raise ValueError("slow-body-ms")
    bucket: list[dict] = records if records is not None else []
    server = MockServer(("127.0.0.1", port), make_handler(
        status, delay_ms, retry_after, bucket, max_requests,
        scenario=scenario, report_zdr=report_zdr, zdr_requires_pro=zdr_requires_pro,
        choice=choice, slow_body_ms=slow_body_ms, wire=wire,
        usage_in=usage_in, usage_out=usage_out, cost_str=cost_str,
        hang_ms=hang_ms, billing_feed=billing_feed, echo_model=echo_model,
        call_id=call_id))
    host = server.server_address[0]
    if host != "127.0.0.1":
        server.server_close()
        raise RuntimeError("bind-not-loopback")
    return server


def parse_smoke(stdout: str) -> dict:
    text = stdout.replace(SMOKE_KEY, "<redacted>")
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end < start:
        return {"parse": "absent"}
    try:
        payload = json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return {"parse": "invalid"}
    keep = {}
    for key in ("jevResult", "reason", "httpStatus", "latencyMs", "attempts", "endpointHost"):
        if key in payload:
            keep[key] = payload[key]
    return keep


def _run_delegate(server: MockServer, records: list[dict]) -> dict:
    root = Path(__file__).resolve().parents[1]
    smoke = root / "scripts" / "jev_gateway_smoke.mjs"
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    env = dict(os.environ)
    env["AI_GATEWAY_API_KEY"] = SMOKE_KEY
    env["AWX_JEV_ENDPOINT"] = f"http://127.0.0.1:{port}/v1/evaluate"
    env["AWX_JEV_ALLOW_HOST"] = "127.0.0.1"
    env["AWX_JEV_TIMEOUT_MS"] = "20000"
    proc = None
    try:
        proc = subprocess.run(
            ["node", str(smoke)], cwd=str(root), env=env,
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=30)
        parsed = parse_smoke(proc.stdout or "")
        exit_code = proc.returncode
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)
    observed = records[0] if records else {}
    return {
        "exitCode": None if proc is None else exit_code,
        "smoke": parsed,
        "bodyBytes": observed.get("bodyBytes"),
        "authorizationPresent": observed.get("authorizationPresent"),
        "zdrKeyPresent": observed.get("zdrKeyPresent"),
        "zdrValue": observed.get("zdrValue"),
        "reportZdr": observed.get("zdrValue") is True,
    }


def smoke_matrix(out_path: Path, delay_ms: int, retry_after: int) -> dict:
    rows = []
    for status in (401, 403, 429, 200):
        records: list[dict] = []
        server = serve(status, delay_ms if status != 200 else 0,
                       retry_after if status == 429 else None, 0, 1, records)
        row = _run_delegate(server, records)
        row["mockStatus"] = status
        row["retryAfterSent"] = status == 429
        rows.append(row)
    payload = {
        "schemaVersion": "awx.jev-mock-smoke.v1",
        "vercelCalls": 0,
        "delayMs": delay_ms,
        "rows": rows,
        "note": "Classification is the smoke script's HTTP mapping. It does not prove JevDecisionAdvisor remember/max-merge.",
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"out": str(out_path), "vercelCalls": 0, "rows": [
        {"mockStatus": row["mockStatus"], "exitCode": row["exitCode"],
         "reason": (row["smoke"] or {}).get("reason"),
         "jevResult": (row["smoke"] or {}).get("jevResult")}
        for row in rows]}, ensure_ascii=True))
    return payload


def smoke_zdr(out_path: Path) -> dict:
    """Hobby mimic plus one fixed plan-gate body. Loopback only."""
    rows = []
    hobby_records: list[dict] = []
    hobby = serve(200, 0, None, 0, 1, hobby_records,
                  report_zdr=True, zdr_requires_pro=True)
    hobby_row = _run_delegate(hobby, hobby_records)
    hobby_row["mode"] = "zdr-requires-pro"
    rows.append(hobby_row)

    gate_records: list[dict] = []
    gate = serve(200, 0, None, 0, 1, gate_records,
                 scenario="zdr_plan_gate", report_zdr=True)
    gate_row = _run_delegate(gate, gate_records)
    gate_row["mode"] = "scenario-zdr-plan-gate"
    rows.append(gate_row)

    hobby_pass = (
        hobby_row.get("exitCode") == 0
        and (hobby_row.get("smoke") or {}).get("jevResult") == "PASS"
        and (hobby_row.get("smoke") or {}).get("httpStatus") == 200
        and hobby_row.get("reportZdr") is False
    )
    gate_reason = (gate_row.get("smoke") or {}).get("reason")
    gate_classified = gate_reason == "plan_gate" and gate_row.get("exitCode") == 3
    if hobby_row.get("reportZdr") is True and (hobby_row.get("smoke") or {}).get("httpStatus") == 403:
        phase = "before-devin-zdr-removal"
    elif hobby_pass:
        phase = "after-devin-zdr-removal"
    else:
        phase = "observed"
    payload = {
        "schemaVersion": "awx.jev-mock-smoke-zdr.v1",
        "vercelCalls": 0,
        "phase": phase,
        "hobbyMimic": {
            "zdrOnExpect": "403 zdr_plan_gate",
            "zdrOffExpect": "200 PASS reportZdr=false",
            "observedReportZdr": hobby_row.get("reportZdr"),
            "observedHttpStatus": (hobby_row.get("smoke") or {}).get("httpStatus"),
            "observedReason": (hobby_row.get("smoke") or {}).get("reason"),
            "pass": hobby_pass,
        },
        "planGateClassification": {
            "expectReason": "plan_gate",
            "observedReason": gate_reason,
            "pass": gate_classified,
            "stillAuthBlocked": gate_reason == "auth-blocked",  # jev-vocab: legacy-alias
        },
        "rows": rows,
        "note": "Loopback mock only. Request bodies and credential values are not stored.",
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "out": str(out_path),
        "vercelCalls": 0,
        "phase": phase,
        "hobbyPass": hobby_pass,
        "planGateReason": gate_reason,
        "planGatePass": gate_classified,
    }, ensure_ascii=True))
    return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="127.0.0.1 Jev evaluate mock. Body is not stored. Credential header value is not logged.")
    parser.add_argument("--status", type=int, choices=STATUSES)
    parser.add_argument("--scenario", choices=SCENARIOS,
                        help="Named body. zdr_plan_gate stays the plan-gate 403. Plain --status 403 stays generic.")
    parser.add_argument("--choice", default="HYBRID", choices=CHOICES,
                        help="routeDecision choice when the request asks for that question.")
    parser.add_argument("--slow-body-ms", type=int, default=0,
                        help="slow_body only: pause after response headers before the body.")
    parser.add_argument("--wire", choices=("legacy", "live"), default="legacy",
                        help="live adds usage and a string gateway cost. legacy keeps the old body.")
    parser.add_argument("--usage-in", type=int)
    parser.add_argument("--usage-out", type=int)
    parser.add_argument("--cost-str", help="providerMetadata.gateway.cost string")
    parser.add_argument("--hang-ms", type=int, default=0)
    parser.add_argument("--billing-feed", help="late_usage appends one usage line to this file")
    parser.add_argument("--echo-model", help="Set the response model field to this value")
    parser.add_argument("--call-id", help="Optional id copied into the billing feed only")
    parser.add_argument("--report-zdr", action="store_true",
                        help="Record only zeroDataRetention key presence and its boolean value.")
    parser.add_argument("--zdr-requires-pro", action="store_true",
                        help="Hobby mimic: zeroDataRetention true returns 403 plan-gate; otherwise 200.")
    parser.add_argument("--delay-ms", type=int, default=0)
    parser.add_argument("--retry-after", type=int, default=None)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--max-requests", type=int, default=0)
    parser.add_argument("--report")
    parser.add_argument("--smoke-out", help="Run jev_gateway_smoke.mjs against this mock for 401/403/429/200")
    parser.add_argument("--smoke-delay-ms", type=int, default=800)
    parser.add_argument("--smoke-zdr-out",
                        help="Run jev_gateway_smoke.mjs against --zdr-requires-pro and a fixed plan-gate body")
    args = parser.parse_args(argv)
    if args.smoke_out:
        smoke_matrix(Path(args.smoke_out), args.smoke_delay_ms, args.retry_after or 3)
        return 0
    if args.smoke_zdr_out:
        smoke_zdr(Path(args.smoke_zdr_out))
        return 0
    if args.status is None and args.scenario is None and not args.zdr_requires_pro:
        parser.error("--status is required unless --scenario, --zdr-requires-pro, or a smoke output flag is set")
    status = 200 if args.status is None else args.status
    records: list[dict] = []
    server = serve(status, args.delay_ms, args.retry_after, args.port, args.max_requests, records,
                   scenario=args.scenario, report_zdr=args.report_zdr,
                   zdr_requires_pro=args.zdr_requires_pro, choice=args.choice,
                   slow_body_ms=args.slow_body_ms, wire=args.wire,
                   usage_in=args.usage_in, usage_out=args.usage_out,
                   cost_str=args.cost_str, hang_ms=args.hang_ms,
                   billing_feed=args.billing_feed, echo_model=args.echo_model,
                   call_id=args.call_id)
    port = server.server_address[1]
    print(json.dumps({
        "bind": "127.0.0.1",
        "port": port,
        "status": status,
        "scenario": args.scenario,
        "choice": args.choice,
        "slowBodyMs": args.slow_body_ms,
        "reportZdr": bool(args.report_zdr),
        "zdrRequiresPro": bool(args.zdr_requires_pro),
    }, ensure_ascii=True))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        if args.report:
            Path(args.report).write_text(
                json.dumps({"requests": records}, ensure_ascii=True, indent=2) + "\n",
                encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
