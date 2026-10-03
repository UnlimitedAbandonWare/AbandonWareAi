"""Loopback Gemini mock modes. No outbound call."""
from __future__ import annotations

import importlib.util
import json
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

SCRIPT = Path(__file__).with_name("gemini_mock_gateway.py")
SPEC = importlib.util.spec_from_file_location("gemini_mock_gateway", SCRIPT)
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


def _request(port: int, method: str = "POST", timeout: float = 5):
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/v1beta/models/gemini:generateContent",
        data=b"{}" if method == "POST" else None,
        headers={"Content-Type": "application/json", "x-goog-api-key": "synthetic-local-mock"},
        method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


class GeminiMockTest(unittest.TestCase):
    def _serve(self, mode: str, hang_ms: int = 0):
        server = MOD.serve(mode, 0, hang_ms)
        self.assertEqual(server.server_address[0], "127.0.0.1")
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        return server, thread

    def _close(self, server, thread) -> None:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)

    def test_modes(self) -> None:
        expectations = {
            "ok": (200, "usageMetadata"),
            "ok_wrong": (200, "syntheticMeaning"),
            "quota_429": (429, "RESOURCE_EXHAUSTED"),
            "billing_403": (403, "PERMISSION_DENIED"),
            "key_invalid_400": (400, "API_KEY_INVALID"),
            "model_404": (404, "NOT_FOUND"),
            "malformed_json": (200, "{not-json"),
            "over_256_tokens": (200, "300"),
        }
        for mode, (status, marker) in expectations.items():
            server, thread = self._serve(mode)
            try:
                code, body = _request(server.server_address[1])
            finally:
                self._close(server, thread)
            self.assertEqual(code, status, mode)
            self.assertIn(marker, body.decode("utf-8", errors="replace"))

    def test_models_list(self) -> None:
        server, thread = self._serve("models_list")
        try:
            code, body = _request(server.server_address[1], method="GET")
        finally:
            self._close(server, thread)
        parsed = json.loads(body.decode("utf-8"))
        self.assertEqual(code, 200)
        self.assertIn("models", parsed)
        self.assertTrue(parsed["models"][0]["synthetic"])

    def test_hang_times_out(self) -> None:
        server, thread = self._serve("hang", hang_ms=700)
        try:
            with self.assertRaises((urllib.error.URLError, TimeoutError)):
                _request(server.server_address[1], timeout=0.15)
        finally:
            self._close(server, thread)

    def test_bind_loopback_only(self) -> None:
        server = MOD.serve("ok", 0, 0)
        try:
            self.assertEqual(server.server_address[0], "127.0.0.1")
        finally:
            server.server_close()


if __name__ == "__main__":
    unittest.main()
