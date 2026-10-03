#!/usr/bin/env python3
"""test_jev_gateway_smoke.py — 127.0.0.1 stub contract tests for
scripts/jev_gateway_smoke.mjs and the scripts/jev_api_smoke.py thin wrapper.

Contract DEMO1-DEVIN-SUB-RUNTIME-TRACE-R2-JEV-20260929. A ThreadingHTTPServer
on loopback stands in for the gateway (AWX_JEV_ALLOW_HOST=127.0.0.1, dummy
AI_GATEWAY_API_KEY). No real gateway call is ever made; the allowlist and
missing-env guards are asserted via their pre-connect fail reasons.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parent.parent
MJS = ROOT / "scripts" / "jev_gateway_smoke.mjs"
WRAPPER = ROOT / "scripts" / "jev_api_smoke.py"

OK_PAYLOAD = {
    "model": "typesafe-ai/jev",
    "answers": {
        "routeProfile": {"choice": "KEEP_BASELINE",
                         "probabilities": {"KEEP_BASELINE": 0.9, "COST_SAVER": 0.1}},
        "freshnessNeeded": {"probability": 0.85},
    },
    "usage": {"promptTokens": 10, "completionTokens": 4},
    "providerMetadata": {"gateway": {"cost": 0.000001}},
}

# 합성 Java 형태 body — JevGatewayClient.java가 보내는 routeDecision 스키마와 동일.
# 사용자 전사 금지 규칙 때문에 fixture가 없어도 이 합성 값으로만 테스트한다.
ROUTE_DECISION_BODY = {
    "model": "typesafe-ai/jev",
    "state": {
        "query": "합성 개발 질문: 방금 언급한 설정값을 다시 보여줘.",
        "surface": "smoke-test",
        "baselineRoute": "CLARIFY",
        "externalDecisionAllowed": True,
    },
    "questions": {
        "routeDecision": {
            "type": "choice",
            "instructions": "Classify the confirmed question's retrieval route.",
            "criteria": {
                "RECENT_ONLY": "Recent context alone can answer.",
                "SCOPED_RAG": "Scoped retrieval needed.",
                "WEB": "Current public web needed.",
                "HYBRID": "Context plus retrieval.",
                "CLARIFY": "Too ambiguous to route.",
            },
        }
    },
    "providerOptions": {"gateway": {"only": ["typesafe-ai"]}},
}

ROUTE_DECISION_PAYLOAD = {
    "model": "typesafe-ai/jev",
    "answers": {
        "routeDecision": {"choice": "HYBRID",
                          "probabilities": {"HYBRID": 0.6, "WEB": 0.4}},
    },
    "usage": {"inputTokens": 12, "outputTokens": 3},
    "providerMetadata": {"gateway": {"cost": 0.000001}},
}

# 실제 gateway usage는 inputTokens/outputTokens — 0 값도 spend 줄에서 살아야 한다.
# cost는 라이브 관측처럼 문자열로 둔다(수치 문자열 → costUsd float 환산 경로 검증).
USAGE_IO_PAYLOAD = {
    "model": "typesafe-ai/jev",
    "answers": {
        "routeProfile": {"choice": "KEEP_BASELINE"},
        "freshnessNeeded": {"probability": 0.5},
    },
    "usage": {"inputTokens": 0, "outputTokens": 5},
    "providerMetadata": {"gateway": {"cost": "0.000001"}},
}


class _Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        self.server.hits = getattr(self.server, "hits", 0) + 1
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        mode = self.server.mode
        if mode == "slow":
            time.sleep(3.0)
        status, payload, extra = _response(mode)
        data = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        for k, v in extra.items():
            self.send_header(k, v)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


def _response(mode):
    if mode == "ok":
        return 200, OK_PAYLOAD, {}
    if mode == "401":
        return 401, {"error": {"type": "authentication_error", "message": "bad key"}}, {}
    if mode == "402":
        return 402, {"error": {"type": "billing_error", "message": "payment required"}}, {}
    if mode == "403":
        # Wire type is deliberately not the retired reason token; the delegate
        # classifies a plain 403 (no plan wording) as permission_denied.
        return 403, {"error": {"type": "access_denied", "message": "not allowed"}}, {}
    if mode == "403gate":
        return 403, {"error": {"type": "permission_denied",
                               "message": "Zero Data Retention (ZDR) is only available for Pro and "
                                          "Enterprise plans. Current plan: hobby. Please upgrade "
                                          "your plan."}}, {}
    if mode == "403perm":
        # plan 문구가 없는 일반 403 — wire type permission_denied만으로 plan_gate가 되면 안 된다.
        return 403, {"error": {"type": "permission_denied", "message": "not allowed"}}, {}
    if mode == "routeDecision":
        return 200, ROUTE_DECISION_PAYLOAD, {}
    if mode == "ok-usage-io":
        return 200, USAGE_IO_PAYLOAD, {}
    if mode == "429":
        return 429, {"error": {"type": "rate_limit", "message": "slow down"}}, {"Retry-After": "17"}
    if mode == "slow":
        return 200, OK_PAYLOAD, {}
    return 500, {"error": "stub"}, {}


class JevGatewaySmokeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which("node") is None:
            raise unittest.SkipTest("node not on PATH")
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        cls.server.mode = "ok"
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join(timeout=5)
        cls.server.server_close()

    def _env(self, mode="ok", **extra):
        self.server.mode = mode
        self.server.hits = 0
        env = dict(os.environ)
        env["AI_GATEWAY_API_KEY"] = "test-dummy-key-not-real"
        env["AWX_JEV_ENDPOINT"] = "http://127.0.0.1:%d/evaluate" % self.port
        env["AWX_JEV_ALLOW_HOST"] = "127.0.0.1"
        env["AWX_JEV_TIMEOUT_MS"] = "1200"
        env.update(extra)
        return env

    def _run_mjs(self, env):
        return subprocess.run(["node", str(MJS)], env=env, capture_output=True,
                              text=True, timeout=30, cwd=str(ROOT))

    def _parse(self, proc):
        self.assertTrue(proc.stdout.strip(),
                        "mjs produced no stdout; stderr=%s" % proc.stderr[-300:])
        return json.loads(proc.stdout)

    def test_pass_200(self):
        proc = self._run_mjs(self._env("ok"))
        data = self._parse(proc)
        self.assertEqual(proc.returncode, 0, data)
        self.assertEqual(data["jevResult"], "PASS")
        self.assertEqual(data["httpStatus"], 200)
        self.assertEqual(data["questions"]["routeProfile"]["choice"], "KEEP_BASELINE")
        self.assertAlmostEqual(data["questions"]["freshnessNeeded"]["probability"], 0.85)
        self.assertEqual(data["model"], "typesafe-ai/jev")
        self.assertEqual(data["zdr"], "off")

    def test_auth_invalid_401(self):
        proc = self._run_mjs(self._env("401"))
        data = self._parse(proc)
        self.assertEqual(proc.returncode, 3, data)
        self.assertEqual(data["jevResult"], "FAIL")
        self.assertEqual(data["reason"], "auth_invalid")
        self.assertEqual(data["httpStatus"], 401)
        self.assertEqual(data["zdr"], "off")

    def test_plan_gate_403(self):
        proc = self._run_mjs(self._env("403gate"))
        data = self._parse(proc)
        self.assertEqual(proc.returncode, 3, data)
        self.assertEqual(data["jevResult"], "FAIL")
        self.assertEqual(data["reason"], "plan_gate")
        self.assertEqual(data["httpStatus"], 403)

    def test_plain_403_permission_denied(self):
        proc = self._run_mjs(self._env("403"))
        data = self._parse(proc)
        self.assertEqual(proc.returncode, 3, data)
        self.assertEqual(data["reason"], "permission_denied")
        self.assertEqual(data["httpStatus"], 403)

    def test_upstream_error_500(self):
        proc = self._run_mjs(self._env("500"))
        data = self._parse(proc)
        self.assertEqual(proc.returncode, 3, data)
        self.assertEqual(data["reason"], "upstream_error")
        self.assertEqual(data["httpStatus"], 500)

    def test_billing_blocked_402(self):
        proc = self._run_mjs(self._env("402"))
        data = self._parse(proc)
        self.assertEqual(proc.returncode, 3, data)
        self.assertEqual(data["reason"], "billing-blocked")
        self.assertEqual(data["httpStatus"], 402)

    def test_rate_limited_retry_after(self):
        proc = self._run_mjs(self._env("429"))
        data = self._parse(proc)
        self.assertEqual(proc.returncode, 3, data)
        self.assertEqual(data["reason"], "rate_limited")
        self.assertEqual(data["httpStatus"], 429)
        self.assertEqual(data["retryAfter"], "17")

    def test_timeout(self):
        proc = self._run_mjs(self._env("slow"))
        data = self._parse(proc)
        self.assertEqual(proc.returncode, 3, data)
        self.assertEqual(data["reason"], "timeout")

    def test_endpoint_not_allowlisted(self):
        env = self._env("ok")
        env["AWX_JEV_ENDPOINT"] = "https://gateway.example.invalid/v1/evaluate"
        env["AWX_JEV_ALLOW_HOST"] = ""
        proc = self._run_mjs(env)
        data = self._parse(proc)
        self.assertEqual(proc.returncode, 3, data)
        self.assertEqual(data["reason"], "endpoint-not-allowlisted")
        self.assertEqual(data["host"], "gateway.example.invalid")

    def test_missing_env(self):
        env = self._env("ok")
        env.pop("AI_GATEWAY_API_KEY", None)
        proc = self._run_mjs(env)
        data = self._parse(proc)
        self.assertEqual(proc.returncode, 3, data)
        self.assertEqual(data["reason"], "missing-env")

    # --- D-1: plan 문구 없는 일반 403은 plan_gate가 아니다 ---
    def test_permission_denied_type_without_plan_wording_is_not_plan_gate(self):
        proc = self._run_mjs(self._env("403perm"))
        data = self._parse(proc)
        self.assertEqual(proc.returncode, 3, data)
        self.assertEqual(data["reason"], "permission_denied")
        self.assertEqual(data["httpStatus"], 403)

    # --- D-3: AWX_JEV_BODY_FILE opt-in (기본 동작 불변) ---
    def _body_file(self, td, body):
        p = Path(td) / "jev-body.json"
        p.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
        return str(p)

    def test_body_file_route_decision_pass(self):
        with tempfile.TemporaryDirectory() as td:
            env = self._env("routeDecision",
                            AWX_JEV_BODY_FILE=self._body_file(td, ROUTE_DECISION_BODY))
            proc = self._run_mjs(env)
            data = self._parse(proc)
            self.assertEqual(proc.returncode, 0, data)
            self.assertEqual(data["questions"]["routeDecision"]["choice"], "HYBRID")
            self.assertEqual(data["bodySource"], "file")
            envl = data.get("envelope") or {}
            self.assertIn("routeDecision", envl.get("answerKeys", []))
            self.assertIn("inputTokens", envl.get("usageKeys", []))
            self.assertIn("providerMetadata.gateway.cost",
                          envl.get("providerMetadataPaths", []))

    def test_body_file_apikit_replay_envelope_uses_body(self):
        replay = {"method": "POST",
                  "url": "https://ai-gateway.vercel.sh/v1/evaluate",
                  "body": ROUTE_DECISION_BODY}
        with tempfile.TemporaryDirectory() as td:
            env = self._env("routeDecision",
                            AWX_JEV_BODY_FILE=self._body_file(td, replay))
            proc = self._run_mjs(env)
            data = self._parse(proc)
            self.assertEqual(proc.returncode, 0, data)
            self.assertEqual(data["questions"]["routeDecision"]["choice"], "HYBRID")

    def test_body_file_with_zdr_key_rejected_without_network(self):
        body = dict(ROUTE_DECISION_BODY)
        body["providerOptions"] = {"gateway": {"only": ["typesafe-ai"],
                                               "zeroDataRetention": True}}  # zdr-guard: allow — D-3 전송 전 거부를 검증하는 입력 픽스처
        with tempfile.TemporaryDirectory() as td:
            env = self._env("routeDecision",
                            AWX_JEV_BODY_FILE=self._body_file(td, body))
            proc = self._run_mjs(env)
            data = self._parse(proc)
            self.assertEqual(proc.returncode, 3, data)
            self.assertEqual(data["reason"], "body-rejected")
            self.assertEqual(data.get("field"), "zeroDataRetention")
            self.assertEqual(self.server.hits, 0)

    def test_body_file_wrong_only_rejected_without_network(self):
        body = dict(ROUTE_DECISION_BODY)
        body["providerOptions"] = {"gateway": {"only": ["typesafe-ai", "other"]}}
        with tempfile.TemporaryDirectory() as td:
            env = self._env("routeDecision",
                            AWX_JEV_BODY_FILE=self._body_file(td, body))
            proc = self._run_mjs(env)
            data = self._parse(proc)
            self.assertEqual(proc.returncode, 3, data)
            self.assertEqual(data["reason"], "body-rejected")
            self.assertEqual(data.get("field"), "only")
            self.assertEqual(self.server.hits, 0)

    def test_body_file_model_mismatch_rejected_without_network(self):
        body = dict(ROUTE_DECISION_BODY)
        body["model"] = "other/model"
        with tempfile.TemporaryDirectory() as td:
            env = self._env("routeDecision",
                            AWX_JEV_BODY_FILE=self._body_file(td, body))
            proc = self._run_mjs(env)
            data = self._parse(proc)
            self.assertEqual(proc.returncode, 3, data)
            self.assertEqual(data["reason"], "body-rejected")
            self.assertEqual(data.get("field"), "model")
            self.assertEqual(self.server.hits, 0)


class JevApiSmokeWrapperTest(unittest.TestCase):
    def _env(self, outdir):
        env = dict(os.environ)
        env["AWX_JEV_SMOKE_OUTDIR"] = str(outdir)
        env["AI_GATEWAY_API_KEY"] = "test-dummy-key-not-real"
        return env

    def test_wrapper_dry_run_makes_no_call(self):
        with tempfile.TemporaryDirectory() as td:
            env = self._env(td)
            env["AWX_JEV_ENDPOINT"] = "http://127.0.0.1:1/evaluate"
            proc = subprocess.run(
                [sys.executable, "-B", str(WRAPPER)], env=env,
                capture_output=True, text=True, timeout=20, cwd=str(ROOT))
            self.assertEqual(proc.returncode, 0, proc.stderr[-300:])
            self.assertIn("jev_gateway_smoke.mjs", proc.stdout)
            self.assertIn('"mode": "dry-run"', proc.stdout)
            self.assertEqual(os.listdir(td), [])

    def test_wrapper_live_delegates_and_writes_result(self):
        if shutil.which("node") is None:
            raise unittest.SkipTest("node not on PATH")
        with ThreadingHTTPServer(("127.0.0.1", 0), _Handler) as server:
            server.mode = "429"
            threading.Thread(target=server.serve_forever, daemon=True).start()
            with tempfile.TemporaryDirectory() as td:
                env = self._env(td)
                env["AWX_JEV_ENDPOINT"] = "http://127.0.0.1:%d/evaluate" % server.server_address[1]
                env["AWX_JEV_ALLOW_HOST"] = "127.0.0.1"
                env["AWX_JEV_TIMEOUT_MS"] = "1500"
                proc = subprocess.run(
                    [sys.executable, "-B", str(WRAPPER), "--live"], env=env,
                    capture_output=True, text=True, timeout=40, cwd=str(ROOT))
                self.assertEqual(proc.returncode, 3, proc.stdout[-500:] + proc.stderr[-300:])
                self.assertIn("[AWX][api-spend]", proc.stdout)
                files = os.listdir(td)
                self.assertEqual(len(files), 1, files)
                written = json.loads((Path(td) / files[0]).read_text(encoding="utf-8"))
                self.assertEqual(written["schemaVersion"], "awx.jev-api-smoke.v2")
                self.assertEqual(written["delegate"], "scripts/jev_gateway_smoke.mjs")
                self.assertEqual(written["jevResult"], "FAIL")
                self.assertEqual(written["reason"], "rate_limited")
                self.assertEqual(written["delegateResult"]["retryAfter"], "17")
                self.assertEqual(written["zdr"], "off")
            server.shutdown()

    def test_wrapper_live_spend_keeps_input_output_tokens_and_zero(self):
        # D-2: gateway usage 키(inputTokens/outputTokens)와 명시적 0이 spend 줄에 살아야 한다.
        if shutil.which("node") is None:
            raise unittest.SkipTest("node not on PATH")
        with ThreadingHTTPServer(("127.0.0.1", 0), _Handler) as server:
            server.mode = "ok-usage-io"
            threading.Thread(target=server.serve_forever, daemon=True).start()
            with tempfile.TemporaryDirectory() as td:
                env = self._env(td)
                env["AWX_JEV_KEY_SOURCE"] = "env"
                env["AWX_JEV_ENDPOINT"] = "http://127.0.0.1:%d/evaluate" % server.server_address[1]
                env["AWX_JEV_ALLOW_HOST"] = "127.0.0.1"
                env["AWX_JEV_SPEND_PURPOSE"] = "unit-test"
                proc = subprocess.run(
                    [sys.executable, "-B", str(WRAPPER), "--live"], env=env,
                    capture_output=True, text=True, timeout=40, cwd=str(ROOT))
                self.assertEqual(proc.returncode, 0, proc.stdout[-500:] + proc.stderr[-300:])
                spend = None
                for line in proc.stdout.splitlines():
                    if line.startswith("[AWX][api-spend] "):
                        spend = json.loads(line[len("[AWX][api-spend] "):])
                self.assertIsNotNone(spend, proc.stdout[-500:])
                self.assertEqual(spend.get("inputTokens"), 0)
                self.assertEqual(spend.get("outputTokens"), 5)
                # 수치 문자열 cost → float costUsd로 환산돼야 한다.
                self.assertEqual(spend.get("costUsd"), 0.000001)
                # 애덤덤 스키마: provider=jev + agent + purpose(env override) 노출.
                self.assertEqual(spend.get("provider"), "jev")
                self.assertEqual(spend.get("gateway"), "jev-smoke-target")
                self.assertEqual(spend.get("agent"), "devin")
                self.assertEqual(spend.get("purpose"), "unit-test")
            server.shutdown()


if __name__ == "__main__":
    unittest.main()
