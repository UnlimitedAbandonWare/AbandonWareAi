#!/usr/bin/env python3
"""apikit expansion tests (2026-09-30) — cerebras provider + env-name gap
coverage (KAKAO_REST_KEY, NAVER_APIHUB pair). Fixture-driven, no network, no
real keys. Run: python -m unittest scripts/apikit/test_apikit_expand.py"""
import json
import os
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
for p in (str(HERE), str(HERE.parent.parent)):
    if p not in sys.path:
        sys.path.insert(0, p)

import common  # noqa: E402
from providers import PROVIDERS  # noqa: E402

FIXTURES = HERE / "fixtures"


def _result(fixture):
    body = fixture.get("body")
    return {
        "status": fixture.get("http_status"),
        "headers": {},
        "text": json.dumps(body) if body is not None else None,
        "ms": 1,
        "error": fixture.get("error"),
        "error_kind": fixture.get("error_kind"),
    }


def _ctx(scopes=None, secrets=None):
    return {"secrets": secrets or {},
            "scopes": scopes or {"Process": {}, "User": {}, "Machine": {}},
            "timeout": 1, "secret_values": (), "paid": False}


class CerebrasSpec(unittest.TestCase):
    def test_registered(self):
        self.assertIn("cerebras", PROVIDERS)
        self.assertEqual(["CEREBRAS_API_KEY"],
                         PROVIDERS["cerebras"].SPEC["key_envs"])

    def test_ok_models_count(self):
        orig = common.http_request
        try:
            common.http_request = lambda *a, **k: {
                "status": 200, "headers": {}, "ms": 5, "error": None,
                "error_kind": None,
                "text": json.dumps({"data": [{"id": "llama-3.3-70b"}]})}
            scopes = {"Process": {"CEREBRAS_API_KEY": "csk-fake"},
                      "User": {}, "Machine": {}}
            rows = PROVIDERS["cerebras"].check(_ctx(scopes))
            self.assertEqual(common.OK, rows[-1]["cls"])
            self.assertEqual("models=1", rows[-1]["detail"])
            self.assertEqual(200, rows[-1]["http"])
        finally:
            common.http_request = orig

    def test_fixture_401(self):
        data = json.loads((FIXTURES / "cerebras_401.json").read_text(encoding="utf-8"))
        cls, code, _ = common.classify(PROVIDERS["cerebras"].SPEC, _result(data))
        self.assertEqual(common.KEY_INVALID, cls)
        self.assertEqual("invalid_api_key", code)

    def test_fixture_403_plan_gate(self):
        data = json.loads((FIXTURES / "cerebras_403_plan.json").read_text(encoding="utf-8"))
        cls, code, _ = common.classify(PROVIDERS["cerebras"].SPEC, _result(data))
        self.assertEqual(common.PLAN_GATE, cls)
        self.assertEqual("plan_required", code)

    def test_429_rate_limit(self):
        cls, _, _ = common.classify(PROVIDERS["cerebras"].SPEC, {
            "status": 429, "headers": {}, "ms": 1, "error": None,
            "error_kind": None, "text": '{"error":{"code":"rate_limit_exceeded"}}'})
        self.assertEqual(common.RATE_LIMIT, cls)

    def test_timeout_network(self):
        cls, _, detail = common.classify(PROVIDERS["cerebras"].SPEC, {
            "status": None, "headers": {}, "ms": 8000, "error": "TimeoutError",
            "error_kind": "timeout", "text": None})
        self.assertEqual(common.NETWORK, cls)
        self.assertIn("timeout", detail)


class KakaoKeyEnvAlias(unittest.TestCase):
    def test_kakao_rest_key_env_name(self):
        """KakaoPlacesClient reads KAKAO_REST_KEY; the probe must see it."""
        self.assertIn("KAKAO_REST_KEY", PROVIDERS["kakao"].SPEC["key_envs"])
        scopes = {"Process": {"KAKAO_REST_KEY": "kakao-fake-0001"},
                  "User": {}, "Machine": {}}
        ki = common.resolve_key(PROVIDERS["kakao"].SPEC["key_envs"], {}, scopes)
        self.assertEqual("KAKAO_REST_KEY", ki["env"])
        self.assertEqual("kakao-fake-0001", ki["value"])

    def test_check_uses_rest_key_alias(self):
        orig = common.http_request
        seen = {}

        def _http(method, url, headers=None, body=None, timeout=None):
            seen["headers"] = headers or {}
            return {"status": 200, "headers": {}, "ms": 3, "error": None,
                    "error_kind": None, "text": '{"documents": [{}]}'}
        try:
            common.http_request = _http
            scopes = {"Process": {"KAKAO_REST_KEY": "kakao-fake-0001"},
                      "User": {}, "Machine": {}}
            rows = PROVIDERS["kakao"].check(_ctx(scopes))
            self.assertEqual(common.OK, rows[-1]["cls"])
            self.assertEqual("KakaoAK kakao-fake-0001",
                             seen["headers"].get("Authorization"))
        finally:
            common.http_request = orig


class NaverApiHub(unittest.TestCase):
    def test_apihub_pair_sends_ncp_headers(self):
        """API HUB pair hits naverapihub.apigw.ntruss.com with NCP headers."""
        orig = common.http_request
        seen = {}

        def _http(method, url, headers=None, body=None, timeout=None):
            seen["url"] = url
            seen["headers"] = headers or {}
            return {"status": 200, "headers": {}, "ms": 3, "error": None,
                    "error_kind": None,
                    "text": json.dumps({"total": 42, "items": []})}
        try:
            common.http_request = _http
            scopes = {"Process": {"NAVER_APIHUB_CLIENT_ID": "hid-1",
                                  "NAVER_APIHUB_CLIENT_SECRET": "hsec-1"},
                      "User": {}, "Machine": {}}
            rows = PROVIDERS["naver"].check(_ctx(scopes))
            row = rows[-1]
            self.assertEqual(common.OK, row["cls"])
            self.assertEqual("NAVER_APIHUB_CLIENT_ID+SECRET", row["key_env"])
            self.assertTrue(seen["url"].startswith(
                "https://naverapihub.apigw.ntruss.com/search/v1/webkr"))
            self.assertIn("format=json", seen["url"])
            self.assertEqual("hid-1",
                             seen["headers"].get("X-NCP-APIGW-API-KEY-ID"))
            self.assertEqual("hsec-1",
                             seen["headers"].get("X-NCP-APIGW-API-KEY"))
            blob = json.dumps(row)
            self.assertNotIn("hsec-1", blob)
        finally:
            common.http_request = orig

    def test_apihub_base_url_override(self):
        """NAVER_APIHUB_BASE_URL redirects the probe (loopback mock)."""
        orig = common.http_request
        seen = {}

        def _http(method, url, headers=None, body=None, timeout=None):
            seen["url"] = url
            return {"status": 200, "headers": {}, "ms": 1, "error": None,
                    "error_kind": None,
                    "text": json.dumps({"total": 0, "items": []})}
        try:
            common.http_request = _http
            os.environ["NAVER_APIHUB_BASE_URL"] = "http://127.0.0.1:18299"
            scopes = {"Process": {"NAVER_APIHUB_CLIENT_ID": "hid",
                                  "NAVER_APIHUB_CLIENT_SECRET": "hsec"},
                      "User": {}, "Machine": {}}
            rows = PROVIDERS["naver"].check(_ctx(scopes))
            self.assertEqual(common.OK, rows[-1]["cls"])
            self.assertTrue(seen["url"].startswith(
                "http://127.0.0.1:18299/search/v1/webkr"))
        finally:
            common.http_request = orig
            os.environ.pop("NAVER_APIHUB_BASE_URL", None)

    def test_forced_apihub_ignores_legacy_names(self):
        """Explicit apihub must not authenticate with legacy credentials."""
        scopes = {"Process": {"NAVER_SEARCH_PROVIDER": "apihub", "NAVER_CLIENT_ID": "lid",
                              "NAVER_CLIENT_SECRET": "lsec",
                              "NAVER_KEYS": "lid:lsec"},
                  "User": {}, "Machine": {}}
        rows = PROVIDERS["naver"].check(_ctx(scopes))
        self.assertEqual(common.KEY_MISSING, rows[-1]["cls"])

    def test_naver_missing_everything(self):
        rows = PROVIDERS["naver"].check(_ctx())
        self.assertEqual(common.KEY_MISSING, rows[-1]["cls"])


if __name__ == "__main__":
    unittest.main()
