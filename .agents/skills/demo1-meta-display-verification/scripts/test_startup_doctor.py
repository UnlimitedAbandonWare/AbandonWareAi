"""Offline regression tests for the bounded Display startup diagnostic."""

import importlib.util
import io
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
import urllib.error


_MODULE_PATH = Path(__file__).with_name("startup_doctor.py")
_SPEC = importlib.util.spec_from_file_location("startup_doctor_under_test", _MODULE_PATH)
doctor = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(doctor)


class FakeResponse:
    def __init__(self, body, status=200):
        self.status = status
        self.headers = {"Content-Type": "application/json"}
        self._body = io.BytesIO(body)

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self._body.close()

    def read(self, amount=-1):
        return self._body.read(amount)

    def getcode(self):
        return self.status


class StartupDoctorTests(unittest.TestCase):
    def test_default_endpoint_matches_current_source_contract(self):
        plan = doctor.endpoint_plan({})
        self.assertTrue(plan["coherent"])
        self.assertEqual("loopback", plan["scope"])
        self.assertEqual("http://127.0.0.1:11435/api/version", plan["probeUrl"])

    def test_explicit_matching_literal_loopback_endpoints(self):
        for host in ("127.0.0.1:11435", "localhost:11435", "[::1]:11435"):
            with self.subTest(host=host):
                plan = doctor.endpoint_plan({
                    "OLLAMA_HOST": host,
                    "LOCAL_LLM_HEALTH_CHECK_URL": "http://" + host + "/api/version",
                })
                self.assertTrue(plan["coherent"])
                self.assertEqual("loopback", plan["scope"])

    def test_custom_host_requires_matching_health_port(self):
        plan = doctor.endpoint_plan({"OLLAMA_HOST": "127.0.0.1:11436"})
        self.assertFalse(plan["coherent"])
        self.assertEqual("endpoint-mismatch", plan["reason"])
        self.assertEqual("loopback", plan["scope"])

    def test_remote_and_lookalike_hosts_are_external(self):
        for host in ("example.invalid:11435", "192.0.2.1:11435", "localhost.example.invalid:11435"):
            with self.subTest(host=host):
                plan = doctor.endpoint_plan({
                    "OLLAMA_HOST": host,
                    "LOCAL_LLM_HEALTH_CHECK_URL": "http://" + host + "/api/version",
                })
                self.assertFalse(plan["coherent"])
                self.assertEqual("external", plan["scope"])

    def test_health_url_rejects_unsafe_or_nonexact_shapes(self):
        bad_urls = (
            "https://127.0.0.1:11435/api/version",
            "http://user:private-fixture@127.0.0.1:11435/api/version",
            "http://127.0.0.1:11435/api/version?token=private-fixture",
            "http://127.0.0.1:11435/api/version#private-fixture",
            "http://127.0.0.1:11435/api/version/",
            "http://127.0.0.1:11435/api/tags",
            "http://127.0.0.1:11435/api/%76ersion",
            "file:///api/version",
        )
        for url in bad_urls:
            with self.subTest(case=bad_urls.index(url)):
                plan = doctor.endpoint_plan({"LOCAL_LLM_HEALTH_CHECK_URL": url})
                self.assertFalse(plan["coherent"])
                self.assertEqual("invalid", plan["scope"])

    def _probe(self, body, status=200):
        response = FakeResponse(body, status)
        with patch.object(doctor.urllib.request, "build_opener") as build:
            build.return_value.open.return_value = response
            result = doctor._probe_version_http("http://127.0.0.1:11435/api/version", timeout=2)
            build.return_value.open.assert_called_once()
            self.assertEqual(2, build.return_value.open.call_args.kwargs["timeout"])
        return result

    def test_valid_version_is_healthy_without_exposing_body(self):
        result = self._probe(b'{"version":"fixture-version-private"}')
        self.assertEqual("healthy", result["status"])
        self.assertNotIn("fixture-version-private", json.dumps(result))

    def test_html_empty_or_wrong_type_version_is_not_healthy(self):
        bodies = (
            b"<html>ready</html>", b"{}", b'{"version":""}',
            b'{"version":"   "}', b'{"version":1}',
            b'{"version":null}', b'["version"]',
        )
        for case, body in enumerate(bodies):
            with self.subTest(case=case):
                self.assertNotEqual("healthy", self._probe(body)["status"])

    def test_oversized_valid_json_is_not_healthy(self):
        body = json.dumps({"version": "1", "padding": "x" * 16384}).encode()
        self.assertGreater(len(body), 16384)
        self.assertNotEqual("healthy", self._probe(body)["status"])

    def test_non200_and_redirect_are_not_healthy(self):
        for status in (201, 204, 301, 302, 307, 403, 500):
            with self.subTest(status=status):
                self.assertNotEqual("healthy", self._probe(b'{"version":"1"}', status)["status"])
        with patch.object(doctor.urllib.request, "build_opener") as build:
            build.return_value.open.side_effect = urllib.error.HTTPError(
                "http://127.0.0.1:11435/api/version", 302, "private-redirect-fixture", {}, None,
            )
            result = doctor._probe_version_http("http://127.0.0.1:11435/api/version")
            self.assertNotEqual("healthy", result["status"])
            self.assertNotIn("private-redirect-fixture", json.dumps(result))

    def test_transport_error_is_redacted(self):
        with patch.object(doctor.urllib.request, "build_opener") as build:
            build.return_value.open.side_effect = urllib.error.URLError("private-error-fixture")
            result = doctor._probe_version_http("http://127.0.0.1:11435/api/version")
        self.assertNotEqual("healthy", result["status"])
        self.assertNotIn("private-error-fixture", json.dumps(result))

    def test_public_probe_rejects_external_endpoint_before_child(self):
        with patch.object(doctor.subprocess, "run") as run:
            result = doctor.probe_version("http://example.invalid:11435/api/version")
        run.assert_not_called()
        self.assertNotEqual("healthy", result["status"])
        self.assertNotIn("example.invalid", json.dumps(result))

    def test_public_probe_deadline_returns_fixed_redacted_result(self):
        with patch.object(doctor.subprocess, "run") as run:
            run.side_effect = subprocess.TimeoutExpired(
                ["private-command-fixture"], 2, output=b"private-output-fixture",
                stderr=b"private-stderr-fixture",
            )
            result = doctor.probe_version("http://127.0.0.1:11435/api/version", timeout=2)
        self.assertEqual({"status": "unavailable", "reason": "version-probe-deadline"}, result)

    def test_public_probe_accepts_valid_worker_result_with_bounded_child(self):
        expected = {"status": "healthy", "reason": "version-response-valid"}
        with patch.object(doctor.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, json.dumps(expected).encode(), b"")
            result = doctor.probe_version("http://127.0.0.1:11435/api/version", timeout=20)
        self.assertEqual(expected, result)
        args, kwargs = run.call_args
        self.assertEqual(2, kwargs["timeout"])
        self.assertTrue(kwargs["capture_output"])
        self.assertEqual(b"http://127.0.0.1:11435/api/version", kwargs["input"])
        self.assertIn("-B", args[0])
        self.assertIn("--version-worker", args[0])
        self.assertFalse(kwargs.get("shell", False))

    def test_public_probe_rejects_failed_or_malformed_worker(self):
        healthy = b'{"status":"healthy","reason":"version-response-valid"}'
        cases = (
            (1, healthy),
            (0, b"private-worker-output-fixture"),
            (0, b"[]"),
            (0, b'{"status":"healthy"}'),
            (0, b'{"status":"healthy","reason":"private-worker-reason-fixture"}'),
            (0, b'{"status":"healthy","reason":"version-response-valid","raw":"private-worker-secret-fixture"}'),
        )
        for case, (returncode, stdout) in enumerate(cases):
            with self.subTest(case=case), patch.object(doctor.subprocess, "run") as run:
                run.return_value = subprocess.CompletedProcess([], returncode, stdout, b"private-stderr-fixture")
                result = doctor.probe_version("http://127.0.0.1:11435/api/version")
            self.assertNotEqual("healthy", result["status"])
            self.assertNotIn("private-", json.dumps(result))

    def test_no_probe_environment_report_preserves_explicit_false(self):
        env = {
            "LOCAL_LLM_ENABLED": "false", "LOCAL_LLM_AUTOSTART": "false",
            "OLLAMA_HOST": "192.0.2.1:11435",
            "LOCAL_LLM_HEALTH_CHECK_URL": "http://192.0.2.1:11435/api/version",
            "UPSTASH_REDIS_REST_TOKEN": "private-token-fixture",
            "JAVA_HOME": "C:/private-java-fixture",
            "PATH": "C:/private-path-fixture",
        }
        with patch.object(doctor, "java_probe", return_value={"java17": False, "reason": "java-unavailable"}), \
                patch.object(doctor, "find_executable", return_value=None), \
                patch.object(doctor, "probe_version") as probe:
            report = doctor.inspect_environment(env, probe=False)
        probe.assert_not_called()
        self.assertIs(report["localLlmEnabled"], False)
        self.assertIs(report["localLlmAutostart"], False)
        serialized = json.dumps(report)
        for private in ("192.0.2.1", "private-token-fixture", "private-java-fixture", "private-path-fixture"):
            self.assertNotIn(private, serialized)

    def test_unspecified_flags_are_unknown_and_external_endpoint_is_never_probed(self):
        env = {"OLLAMA_HOST": "example.invalid:11435",
               "LOCAL_LLM_HEALTH_CHECK_URL": "http://example.invalid:11435/api/version"}
        with patch.object(doctor, "java_probe", return_value={"java17": False, "reason": "java-unavailable"}), \
                patch.object(doctor, "find_executable", return_value=None), \
                patch.object(doctor, "probe_version") as probe:
            report = doctor.inspect_environment(env, probe=True)
        probe.assert_not_called()
        self.assertIsNone(report["localLlmEnabled"])
        self.assertIsNone(report["localLlmAutostart"])
        self.assertNotIn("example.invalid", json.dumps(report))


if __name__ == "__main__":
    unittest.main()
