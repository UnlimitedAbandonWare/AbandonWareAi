"""Offline checks for the Java evaluate fixture. No provider call."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = Path(__file__).with_name("jev_java_contract_fixture.py")
MOCK = Path(__file__).with_name("jev_mock_gateway.py")
ADVISOR = ROOT / "main" / "java" / "com" / "example" / "lms" / "assist" / "JevDecisionAdvisor.java"


def _load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


FIX = _load(FIXTURE, "jev_java_contract_fixture")
MOD = _load(MOCK, "jev_mock_gateway_for_fixture")


def _verdict_names(text: str) -> set[str]:
    match = __import__("re").search(r"enum\s+Verdict\s*\{([^}]+)\}", text)
    if not match:
        raise AssertionError("verdict-enum-absent")
    return set(__import__("re").findall(r"\b([A-Z][A-Z0-9_]*)\b", match.group(1)))


class JavaContractFixtureTest(unittest.TestCase):
    def test_extracts_five_criteria_from_java(self) -> None:
        extracted = FIX.extract_client(FIX.DEFAULT_CLI.read_text(encoding="utf-8"))
        self.assertEqual(set(extracted["criteria"]), _verdict_names(ADVISOR.read_text(encoding="utf-8")))
        self.assertEqual(len(extracted["criteria"]), 5)

    def test_no_zdr_key_and_only_typesafe(self) -> None:
        extracted = FIX.extract_client(FIX.DEFAULT_CLI.read_text(encoding="utf-8"))
        request = FIX.build_request(extracted)
        dumped = json.dumps(request)
        self.assertNotIn("zeroDataRetention", dumped)
        self.assertEqual(request["body"]["providerOptions"]["gateway"]["only"], ["typesafe-ai"])
        self.assertNotIn("Authorization", request["headers"])

    def test_worst_case_payload_under_max_state_bytes(self) -> None:
        extracted = FIX.extract_client(FIX.DEFAULT_CLI.read_text(encoding="utf-8"))
        body = json.loads(json.dumps(FIX.build_request(extracted)["body"]))
        body["state"]["query"] = "가" * 1200
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.assertLessEqual(len(raw), 8192)

    def test_fixture_round_trips_through_mock(self) -> None:
        extracted = FIX.extract_client(FIX.DEFAULT_CLI.read_text(encoding="utf-8"))
        payload = FIX.build_request(extracted)["body"]
        records = []
        server = MOD.serve(200, 0, None, 0, 1, records)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            req = urllib.request.Request(
                "http://127.0.0.1:%d/v1/evaluate" % server.server_address[1],
                data=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST")
            with urllib.request.urlopen(req, timeout=5) as response:
                parsed = json.loads(response.read().decode("utf-8"))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
        self.assertIn(parsed["answers"]["routeDecision"]["choice"], MOD.CHOICES)
        self.assertNotIn(FIX.SYNTHETIC_QUERY, json.dumps(records))

    def test_drift_exits_2(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            cli = Path(tmp) / "JevGatewayClient.java"
            cli.write_text("class JevGatewayClient { void evaluate() {} }\n", encoding="utf-8")
            code = FIX.main(["--cli", str(cli), "--out-dir", str(Path(tmp) / "out")])
            self.assertEqual(code, 2)
            self.assertFalse((Path(tmp) / "out" / "java_evaluate_request.json").exists())


if __name__ == "__main__":
    unittest.main()
