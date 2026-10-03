"""Fixture tests for jev_mock_gateway.py. No outbound provider call.

zdr-guard: allow-file — loopback mock fixtures construct zeroDataRetention
payloads to verify the stub's plan-gate detection; nothing here reaches the
real gateway (docs/API_ROUTING_SPEC.md ZDR rule).
"""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

SCRIPT = Path(__file__).with_name("jev_mock_gateway.py")
SPEC = importlib.util.spec_from_file_location("jev_mock_gateway", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


def _post(port: int, payload: dict, secret: str):
    raw = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/v1/evaluate",
        data=raw,
        headers={"Authorization": f"Bearer {secret}", "Content-Type": "application/json"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


class JevMockTest(unittest.TestCase):
    def test_records_size_and_header_presence_only(self) -> None:
        records = []
        server = MOD.serve(401, 0, None, 0, 1, records)
        self.assertEqual(server.server_address[0], "127.0.0.1")
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        port = server.server_address[1]
        secret = "synthetic-header-value-not-a-provider-key"
        try:
            req = urllib.request.Request(
                f"http://127.0.0.1:{port}/v1/evaluate",
                data=b'{"question":"local"}',
                headers={"Authorization": f"Bearer {secret}", "Content-Type": "application/json"},
                method="POST")
            try:
                urllib.request.urlopen(req, timeout=5).read()
            except urllib.error.HTTPError as error:
                self.assertEqual(error.code, 401)
                error.read()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
        self.assertEqual(records[0]["bodyBytes"], len(b'{"question":"local"}'))
        self.assertTrue(records[0]["authorizationPresent"])
        dumped = json.dumps(records)
        self.assertNotIn(secret, dumped)
        self.assertNotIn("local", dumped)

    def test_serve_binds_loopback_only(self) -> None:
        server = MOD.serve(200, 0, None, 0, 1, [])
        try:
            self.assertEqual(server.server_address[0], "127.0.0.1")
        finally:
            server.server_close()

    def test_scenario_plan_gate_and_plain_403(self) -> None:
        secret = "synthetic-header-value-not-a-provider-key"
        plain_records = []
        plain = MOD.serve(403, 0, None, 0, 1, plain_records)
        thread = threading.Thread(target=plain.serve_forever, daemon=True)
        thread.start()
        try:
            status, body = _post(plain.server_address[1], {"question": "plain-403-marker"}, secret)
        finally:
            plain.shutdown()
            plain.server_close()
            thread.join(timeout=3)
        self.assertEqual(status, 403)
        self.assertNotIn(b"Zero Data Retention", body)
        self.assertIn(b'"status": 403', body)

        gate_records = []
        gate = MOD.serve(200, 0, None, 0, 1, gate_records, scenario="zdr_plan_gate")
        thread = threading.Thread(target=gate.serve_forever, daemon=True)
        thread.start()
        try:
            status, body = _post(gate.server_address[1], {"question": "plan-gate-marker"}, secret)
        finally:
            gate.shutdown()
            gate.server_close()
            thread.join(timeout=3)
        self.assertEqual(status, 403)
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(parsed["error"]["type"], "permission_denied")
        self.assertIn("Current plan: hobby", parsed["error"]["message"])
        self.assertIn("Zero Data Retention (ZDR)", parsed["error"]["message"])
        dumped = json.dumps(gate_records)
        self.assertNotIn(secret, dumped)
        self.assertNotIn("plan-gate-marker", dumped)

    def test_report_zdr_records_boolean_only(self) -> None:
        secret = "synthetic-header-value-not-a-provider-key"
        marker = "do-not-store-question-text"
        records = []
        server = MOD.serve(200, 0, None, 0, 0, records, report_zdr=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        port = server.server_address[1]
        try:
            _post(port, {"state": {"query": marker}, "providerOptions": {
                "gateway": {"zeroDataRetention": True}}}, secret)
            _post(port, {"state": {"query": marker}, "providerOptions": {"gateway": {}}}, secret)
            _post(port, {"state": {"query": marker}, "providerOptions": {
                "gateway": {"zeroDataRetention": False}}}, secret)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
        self.assertEqual(
            [(row["zdrKeyPresent"], row["zdrValue"]) for row in records],
            [(True, True), (False, None), (True, False)])
        dumped = json.dumps(records)
        self.assertNotIn(secret, dumped)
        self.assertNotIn(marker, dumped)

    def test_zdr_requires_pro_hobby_mimic(self) -> None:
        secret = "synthetic-header-value-not-a-provider-key"
        marker = "hobby-question-must-not-leak"
        records = []
        server = MOD.serve(401, 0, None, 0, 0, records,
                           report_zdr=True, zdr_requires_pro=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        port = server.server_address[1]
        try:
            on_status, on_body = _post(port, {
                "state": {"query": marker},
                "providerOptions": {"gateway": {"zeroDataRetention": True}},
            }, secret)
            off_status, off_body = _post(port, {
                "state": {"query": marker},
                "providerOptions": {"gateway": {"only": ["typesafe-ai"]}},
            }, secret)
            false_status, false_body = _post(port, {
                "state": {"query": marker},
                "providerOptions": {"gateway": {"zeroDataRetention": False}},
            }, secret)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
        self.assertEqual(on_status, 403)
        self.assertEqual(json.loads(on_body.decode("utf-8"))["error"]["type"], "permission_denied")
        self.assertEqual(off_status, 200)
        self.assertEqual(json.loads(off_body.decode("utf-8"))["answers"]["routeProfile"]["choice"],
                         "KEEP_BASELINE")
        self.assertEqual(false_status, 200)
        self.assertIn(b"KEEP_BASELINE", false_body)
        self.assertEqual(records[0]["zdrValue"], True)
        self.assertEqual(records[1]["zdrKeyPresent"], False)
        self.assertEqual(records[2]["zdrValue"], False)
        dumped = json.dumps(records)
        self.assertNotIn(secret, dumped)
        self.assertNotIn(marker, dumped)

    def test_help_lists_zdr_flags(self) -> None:
        proc = subprocess.run(
            [sys.executable, "-B", str(SCRIPT), "--help"],
            capture_output=True, text=True, timeout=20)
        self.assertEqual(proc.returncode, 0)
        for flag in ("--scenario", "--report-zdr", "--zdr-requires-pro", "zdr_plan_gate"):
            self.assertIn(flag, proc.stdout)



    def _once(self, scenario=None, payload=None, choice="HYBRID", slow_body_ms=0, status=200):
        records = []
        server = MOD.serve(status, 0, None, 0, 1, records, scenario=scenario,
                           choice=choice, slow_body_ms=slow_body_ms)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            code, body = _post(
                server.server_address[1],
                payload if payload is not None else {"questions": {"routeDecision": {"type": "choice"}}},
                "synthetic-header-value-not-a-provider-key")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
        return code, body, records

    def test_route_decision_request_gets_route_decision_answer(self) -> None:
        code, body, _records = self._once()
        self.assertEqual(code, 200)
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(parsed["model"], "typesafe-ai/jev")
        self.assertEqual(parsed["answers"]["routeDecision"]["choice"], "HYBRID")
        self.assertNotIn("routeProfile", parsed["answers"])
        code, body, _records = self._once(choice="WEB")
        self.assertEqual(json.loads(body.decode("utf-8"))["answers"]["routeDecision"]["choice"], "WEB")

    def test_route_profile_request_keeps_legacy_answer(self) -> None:
        code, body, _records = self._once(payload={
            "questions": {"routeProfile": {"type": "choice"}, "routeDecision": {"type": "choice"}},
        })
        self.assertEqual(code, 200)
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(parsed["answers"]["routeProfile"]["choice"], "KEEP_BASELINE")
        self.assertNotIn("routeDecision", parsed["answers"])

    def test_choice_rejects_unknown(self) -> None:
        with self.assertRaises(ValueError):
            MOD.serve(200, 0, None, 0, 1, [], choice="KEEP_BASELINE")

    def test_body_not_retained(self) -> None:
        marker = "retain-check-unique-marker"
        code, body, records = self._once(payload={
            "state": {"query": marker},
            "questions": {"routeDecision": {"type": "choice"}},
        })
        self.assertEqual(code, 200)
        dumped = json.dumps(records)
        self.assertNotIn(marker, dumped)
        self.assertNotIn(marker.encode("utf-8"), body)
        self.assertIn(b"routeDecision", body)

    def test_scenario_zdr_plan_gate(self) -> None:
        code, body, _records = self._once(scenario="zdr_plan_gate")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 403)
        self.assertEqual(parsed["error"]["type"], "permission_denied")
        self.assertIn("message", parsed["error"])

    def test_scenario_model_substring(self) -> None:
        code, body, _records = self._once(scenario="model_substring")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 200)
        self.assertEqual(parsed["model"], "evil-jev-proxy")
        self.assertIn(parsed["answers"]["routeDecision"]["choice"], MOD.CHOICES)

    def test_scenario_model_bare(self) -> None:
        code, body, _records = self._once(scenario="model_bare")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 200)
        self.assertEqual(parsed["model"], "jev")
        self.assertIn("routeDecision", parsed["answers"])

    def test_scenario_model_missing(self) -> None:
        code, body, _records = self._once(scenario="model_missing")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 200)
        self.assertNotIn("model", parsed)
        self.assertIn("routeDecision", parsed["answers"])

    def test_scenario_choice_number(self) -> None:
        code, body, _records = self._once(scenario="choice_number")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 200)
        self.assertEqual(parsed["answers"]["routeDecision"]["choice"], 1)

    def test_scenario_choice_object(self) -> None:
        code, body, _records = self._once(scenario="choice_object")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 200)
        self.assertEqual(parsed["answers"]["routeDecision"]["choice"], {})

    def test_scenario_route_missing(self) -> None:
        code, body, _records = self._once(scenario="route_missing")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 200)
        self.assertNotIn("routeDecision", parsed["answers"])

    def test_scenario_provider_403(self) -> None:
        code, body, _records = self._once(scenario="provider_403")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 403)
        self.assertEqual(parsed["error"]["type"], "permission_denied")
        self.assertIn("message", parsed["error"])

    def test_scenario_billing_402(self) -> None:
        code, body, _records = self._once(scenario="billing_402")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 402)
        self.assertEqual(parsed["status"], 402)
        self.assertIn("error", parsed)

    def test_scenario_upstream_503(self) -> None:
        code, body, _records = self._once(scenario="upstream_503")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 503)
        self.assertEqual(parsed["status"], 503)

    def test_scenario_slow_body(self) -> None:
        import time
        records = []
        server = MOD.serve(200, 0, None, 0, 1, records, scenario="slow_body", slow_body_ms=150)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            started = time.monotonic()
            code, body = _post(server.server_address[1], {"questions": {"routeDecision": {}}},
                               "synthetic-header-value-not-a-provider-key")
            elapsed = time.monotonic() - started
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
        self.assertEqual(code, 200)
        self.assertGreaterEqual(elapsed, 0.12)
        self.assertIn("routeDecision", json.loads(body.decode("utf-8"))["answers"])


    def test_scenario_hang(self) -> None:
        records = []
        server = MOD.serve(200, 0, None, 0, 1, records, scenario="hang", hang_ms=700)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            req = urllib.request.Request(
                f"http://127.0.0.1:{server.server_address[1]}/v1/evaluate",
                data=b'{"questions":{"routeDecision":{}}}',
                headers={"Content-Type": "application/json"},
                method="POST")
            with self.assertRaises((urllib.error.URLError, TimeoutError)):
                urllib.request.urlopen(req, timeout=0.15)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
        self.assertEqual(server.server_address[0], "127.0.0.1")
        self.assertEqual(len(records), 1)

    def test_scenario_late_usage(self) -> None:
        code, body, _records = self._once(scenario="late_usage")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 200)
        self.assertEqual(parsed["usage"], {"inputTokens": 11, "outputTokens": 7})
        self.assertIsInstance(parsed["providerMetadata"]["gateway"]["cost"], str)
        self.assertEqual(parsed["providerMetadata"]["gateway"]["cost"], "0.000019698")

    def test_scenario_rate_429(self) -> None:
        records = []
        server = MOD.serve(200, 0, None, 0, 1, records, scenario="rate_429")
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            req = urllib.request.Request(
                f"http://127.0.0.1:{server.server_address[1]}/v1/evaluate",
                data=b"{}",
                headers={"Content-Type": "application/json"},
                method="POST")
            with self.assertRaises(urllib.error.HTTPError) as caught:
                urllib.request.urlopen(req, timeout=5)
            self.assertEqual(caught.exception.code, 429)
            self.assertEqual(caught.exception.headers.get("Retry-After"), "1")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)

    def test_scenario_probs_missing(self) -> None:
        code, body, _records = self._once(scenario="probs_missing")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 200)
        self.assertNotIn("probabilities", parsed["answers"]["routeDecision"])

    def test_scenario_probs_out_of_range(self) -> None:
        code, body, _records = self._once(scenario="probs_out_of_range")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 200)
        self.assertEqual(parsed["answers"]["routeDecision"]["probabilities"]["HYBRID"], 1.2)

    def test_scenario_probs_nonnumeric(self) -> None:
        code, body, _records = self._once(scenario="probs_nonnumeric")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 200)
        value = parsed["answers"]["routeDecision"]["probabilities"]["HYBRID"]
        self.assertEqual(value, "high")
        self.assertNotEqual(value, 1.0)

    def test_scenario_usage_missing(self) -> None:
        code, body, _records = self._once(scenario="usage_missing")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 200)
        self.assertNotIn("usage", parsed)
        self.assertNotIn("providerMetadata", parsed)

    def test_scenario_cost_unparseable(self) -> None:
        code, body, _records = self._once(scenario="cost_unparseable")
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 200)
        self.assertEqual(parsed["providerMetadata"]["gateway"]["cost"], "n/a")

    def test_scenario_oversize_body(self) -> None:
        code, body, _records = self._once(scenario="oversize_body")
        self.assertEqual(code, 200)
        self.assertGreater(len(body), 65536)

    def test_live_wire_cost_is_string(self) -> None:
        code, body, _records = self._once()
        legacy = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 200)
        self.assertNotIn("usage", legacy)
        self.assertNotIn("providerMetadata", legacy)
        records = []
        server = MOD.serve(200, 0, None, 0, 1, records, wire="live",
                           usage_in=3, usage_out=4, cost_str="0.000019698")
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            live_code, live_body = _post(
                server.server_address[1],
                {"questions": {"routeDecision": {"type": "choice"}}},
                "synthetic-header-value-not-a-provider-key")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
        parsed = json.loads(live_body.decode("utf-8"))
        self.assertEqual(live_code, 200)
        self.assertEqual(parsed["usage"], {"inputTokens": 3, "outputTokens": 4})
        self.assertIsInstance(parsed["providerMetadata"]["gateway"]["cost"], str)

    def test_billing_feed_written_once(self) -> None:
        import tempfile
        feed = Path(tempfile.mkdtemp()) / "feed.jsonl"
        records = []
        server = MOD.serve(200, 0, None, 0, 1, records, scenario="late_usage",
                           billing_feed=str(feed), call_id="c-feed")
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            code, _body = _post(server.server_address[1],
                                {"questions": {"routeDecision": {}}},
                                "synthetic-header-value-not-a-provider-key")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
        self.assertEqual(code, 200)
        lines = [line for line in feed.read_text(encoding="utf-8").splitlines() if line.strip()]
        self.assertEqual(len(lines), 1)
        row = json.loads(lines[0])
        self.assertEqual(row["callId"], "c-feed")
        self.assertIn("usage", row)
        self.assertIsInstance(row["cost"], str)

    def test_echo_model(self) -> None:
        for model in ("other-vendor/jev", "typesafe-ai/jevx"):
            records = []
            server = MOD.serve(200, 0, None, 0, 1, records, echo_model=model)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                code, body = _post(
                    server.server_address[1],
                    {"questions": {"routeDecision": {"type": "choice"}}},
                    "synthetic-header-value-not-a-provider-key")
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=3)
            parsed = json.loads(body.decode("utf-8"))
            self.assertEqual(code, 200)
            self.assertEqual(parsed["model"], model)



if __name__ == "__main__":
    unittest.main()
