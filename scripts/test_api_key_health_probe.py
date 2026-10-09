#!/usr/bin/env python3
# Fixture tests for scripts/api_key_health_probe.py — classification logic only.
# No network: every check runs against canned Resp objects via a stub http.
# Contract: DEMO1-DEVIN-API-KEY-HEALTH-PROBE-20260929.

import json
import sys
import unittest
from unittest import mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import api_key_health_probe as m  # noqa: E402


def resp(status=None, body=None, error=None, headers=None):
    raw = json.dumps(body).encode("utf-8") if isinstance(body, (dict, list)) else (body or b"")
    return m.Resp(status=status, headers=headers or {}, body=raw,
                  latency_ms=7, error=error)


KEY = "fixture-key-value-not-real"
KEYCTX = ("AI_GATEWAY_API_KEY", {"process": KEY}, KEY)


def stub_http(mapping):
    """mapping: {(method,url-prefix): Resp} — first prefix match wins."""
    def call(method, url, headers=None, body=None, timeout=10):
        for (mm, prefix), r in mapping.items():
            if method == mm and url.startswith(prefix):
                return r
        return resp(error="unstubbed-url:%s" % url)
    return call


class GenericClassification(unittest.TestCase):
    def test_200_ok(self):
        cls, _ = m.classify_generic(resp(200, {"data": []}))
        self.assertEqual(cls, m.CLS_OK)

    def test_401_invalid(self):
        cls, _ = m.classify_generic(resp(401, {"error": {"code": "invalid_api_key"}}))
        self.assertEqual(cls, m.CLS_INVALID)

    def test_402_quota(self):
        cls, _ = m.classify_generic(resp(402, {"error": {"code": "payment_required"}}))
        self.assertEqual(cls, m.CLS_QUOTA)

    def test_403_forbidden(self):
        cls, _ = m.classify_generic(resp(403, {"error": {"message": "region blocked"}}))
        self.assertEqual(cls, m.CLS_FORBIDDEN)

    def test_429_rate(self):
        cls, _ = m.classify_generic(resp(429, {"error": {"code": "rate_limit_exceeded"}}))
        self.assertEqual(cls, m.CLS_RATE)

    def test_429_quota(self):
        cls, _ = m.classify_generic(resp(429, {"error": {"code": "insufficient_quota"}}))
        self.assertEqual(cls, m.CLS_QUOTA)

    def test_timeout_network(self):
        cls, d = m.classify_generic(resp(error="TimeoutError: timed out"))
        self.assertEqual(cls, m.CLS_NETWORK)
        self.assertIn("Timeout", d)

    def test_redirect_not_followed(self):
        cls, d = m.classify_generic(resp(302, b""))
        self.assertEqual(cls, m.CLS_UNKNOWN)
        self.assertIn("redirect", d)

    def test_404_model(self):
        cls, _ = m.classify_generic(resp(404, {"error": {"code": "model_not_found"}}))
        self.assertEqual(cls, m.CLS_MODEL)


class VendorClassification(unittest.TestCase):
    def test_jev_200_balance(self):
        cls, d = m.classify_jev(resp(200, {"balance": 12.5, "total_used": 3}))
        self.assertEqual(cls, m.CLS_OK)
        self.assertIn("12.5", d)

    def test_jev_200_zero_balance_quota(self):
        cls, _ = m.classify_jev(resp(200, {"balance": 0.0}))
        self.assertEqual(cls, m.CLS_QUOTA)

    def test_jev_401_auth_failed(self):
        cls, d = m.classify_jev(resp(401, {"error": {"message": "Authentication failed"}}))
        self.assertEqual(cls, m.CLS_INVALID)
        self.assertIn("expired", d)

    def test_gemini_key_invalid(self):
        body = {"error": {"code": 400, "status": "INVALID_ARGUMENT",
                          "details": [{"reason": "API_KEY_INVALID"}]}}
        cls, _ = m.classify_gemini(resp(400, body))
        self.assertEqual(cls, m.CLS_INVALID)

    def test_gemini_key_expired(self):
        body = {"error": {"code": 400, "status": "INVALID_ARGUMENT",
                          "details": [{"reason": "API_KEY_EXPIRED"}]}}
        cls, _ = m.classify_gemini(resp(400, body))
        self.assertEqual(cls, m.CLS_INVALID)

    def test_gemini_permission_denied(self):
        body = {"error": {"code": 403, "status": "PERMISSION_DENIED",
                          "details": [{"reason": "PERMISSION_DENIED"}]}}
        cls, _ = m.classify_gemini(resp(403, body))
        self.assertEqual(cls, m.CLS_FORBIDDEN)

    def test_gemini_resource_exhausted(self):
        body = {"error": {"code": 429, "status": "RESOURCE_EXHAUSTED",
                          "details": [{"reason": "RESOURCE_EXHAUSTED"}]}}
        cls, _ = m.classify_gemini(resp(429, body))
        self.assertEqual(cls, m.CLS_QUOTA)

    def test_soniox_unauthenticated(self):
        body = {"status_code": 401, "error_type": "unauthenticated",
                "message": "Incorrect API key provided."}
        cls, _ = m.classify_soniox(resp(401, body))
        self.assertEqual(cls, m.CLS_INVALID)

    def test_openai_l1_insufficient_quota(self):
        cls, d, _ = m.classify_openai_l1(resp(429, {"error": {"code": "insufficient_quota"}}))
        self.assertEqual(cls, m.CLS_QUOTA)

    def test_openai_l1_invalid_key(self):
        cls, _, _ = m.classify_openai_l1(resp(401, {"error": {"code": "invalid_api_key"}}))
        self.assertEqual(cls, m.CLS_INVALID)


class ModelPicker(unittest.TestCase):
    def test_prefers_nano_mini(self):
        ids = ["gpt-5.2", "gpt-5.2-mini", "gpt-5-nano", "o3", "gpt-4o"]
        self.assertEqual(m.pick_cheapest_openai_model(ids), "gpt-5-nano")

    def test_skips_nonchat(self):
        ids = ["gpt-4o-audio-preview", "whisper-1", "text-embedding-3-small", "gpt-4o-mini"]
        self.assertEqual(m.pick_cheapest_openai_model(ids), "gpt-4o-mini")

    def test_empty(self):
        self.assertIsNone(m.pick_cheapest_openai_model([]))
        self.assertIsNone(m.pick_cheapest_openai_model(["dall-e-3", "whisper-1"]))


class SourceMatch(unittest.TestCase):
    def test_match(self):
        self.assertEqual(m.source_match({"process": "a", "secrets": "a"})[0], "match")

    def test_mismatch(self):
        self.assertEqual(m.source_match({"process": "a", "secrets": "b"})[0], "MISMATCH")

    def test_single(self):
        self.assertEqual(m.source_match({"user": "a"})[0], "single:user")

    def test_absent(self):
        self.assertEqual(m.source_match({})[0], "absent")


class ProviderChecksWithStubHttp(unittest.TestCase):
    def test_jev_full_ok(self):
        http = stub_http({
            ("GET", "https://ai-gateway.vercel.sh/v1/credits"):
                resp(200, {"balance": 9.9, "total_used": 1}),
            ("GET", "https://ai-gateway.vercel.sh/v1/models"):
                resp(200, {"data": [{"id": "typesafe-ai/jev"}, {"id": "openai/gpt-5"}]}),
        })
        row = m.check_jev(KEYCTX, http, paid=False)
        self.assertEqual(row["l0_status"], "ok")
        self.assertEqual(row["classification"], m.CLS_OK)

    def test_jev_401_skips_models_step(self):
        calls = []
        def http(method, url, headers=None, body=None, timeout=10):
            calls.append(url)
            return resp(401, {"error": {"message": "Authentication failed"}})
        row = m.check_jev(KEYCTX, http, paid=False)
        self.assertEqual(row["classification"], m.CLS_INVALID)
        self.assertEqual(len(calls), 1)  # 401 → remaining steps skipped

    def test_jev_model_missing(self):
        http = stub_http({
            ("GET", "https://ai-gateway.vercel.sh/v1/credits"): resp(200, {"balance": 5}),
            ("GET", "https://ai-gateway.vercel.sh/v1/models"):
                resp(200, {"data": [{"id": "openai/gpt-5"}]}),
        })
        row = m.check_jev(KEYCTX, http, paid=False)
        self.assertEqual(row["classification"], m.CLS_MODEL)

    def test_openai_models_401(self):
        http = stub_http({("GET", "https://api.openai.com/v1/models"):
                          resp(401, {"error": {"code": "invalid_api_key"}})})
        row = m.check_openai(KEYCTX, http, paid=False)
        self.assertEqual(row["classification"], m.CLS_INVALID)

    def test_openai_l1_only_when_paid(self):
        calls = []
        def http(method, url, headers=None, body=None, timeout=10):
            calls.append(url)
            if url.startswith("https://api.openai.com/v1/models"):
                return resp(200, {"data": [{"id": "gpt-5-nano"}]})
            return resp(200, {"choices": []})
        row = m.check_openai(KEYCTX, http, paid=False)
        self.assertIsNone(row["l1"])
        self.assertEqual(len(calls), 1)
        row = m.check_openai(KEYCTX, http, paid=True)
        self.assertTrue(row["l1"]["ran"])
        self.assertEqual(row["l1"]["model"], "gpt-5-nano")
        self.assertEqual(len(calls), 3)  # +1 models +1 chat/completions
        self.assertIn("chat/completions", calls[-1])

    def test_gemini_counttokens_access(self):
        def http(method, url, headers=None, body=None, timeout=10):
            if ":countTokens" in url:
                return resp(404, {"error": {"status": "NOT_FOUND"}})
            return resp(200, {"models": []})
        row = m.check_gemini(("GEMINI_API_KEY", {"process": KEY}, KEY), http, paid=False)
        self.assertEqual(row["classification"], m.CLS_MODEL)
        self.assertIn("countTokens", row["detail"])

    def test_missing_key_row(self):
        row = m.check_openai(("OPENAI_API_KEY", {}, None), stub_http({}), paid=False)
        self.assertEqual(row["classification"], m.CLS_MISSING)
        self.assertIsNone(row["latency_ms"])

    def test_mismatch_overrides(self):
        http = stub_http({("GET", "https://api.openai.com/v1/models"):
                          resp(200, {"data": [{"id": "gpt-5-nano"}]})})
        row = m.check_openai(("OPENAI_API_KEY", {"user": "a", "secrets": "b"}, "a"),
                             http, paid=False)
        self.assertEqual(row["classification"], m.CLS_MISMATCH)

    def test_no_secret_in_row(self):
        http = stub_http({("GET", "https://ai-gateway.vercel.sh/v1/credits"):
                          resp(200, {"balance": 5}),
                          ("GET", "https://ai-gateway.vercel.sh/v1/models"):
                          resp(200, {"data": [{"id": "typesafe-ai/jev"}]})})
        row = m.check_jev(KEYCTX, http, paid=False)
        blob = json.dumps(row)
        self.assertNotIn(KEY, blob)
        self.assertNotIn("Bearer", blob)


class CompositeKeysAndAltProbe(unittest.TestCase):
    def test_merged_row_per_name_match(self):
        row = m.merged_key_row("naver", "NAVER_APIHUB_CLIENT_ID+NAVER_APIHUB_CLIENT_SECRET",
                               [("NAVER_APIHUB_CLIENT_ID",
                                 {"process": "cid", "secrets": "cid"}, "cid"),
                                ("NAVER_APIHUB_CLIENT_SECRET",
                                 {"process": "sec", "secrets": "sec"}, "sec")])
        self.assertEqual(row["source_match"], "match")
        self.assertTrue(row["key_present"])

    def test_merged_row_one_name_mismatch(self):
        row = m.merged_key_row("upstash_redis", "URL+TOKEN",
                               [("URL", {"process": "u1", "secrets": "u2"}, "u1"),
                                ("TOKEN", {"process": "t", "secrets": "t"}, "t")])
        self.assertEqual(row["source_match"], "MISMATCH")
        self.assertIn("URL:MISMATCH", row["match_detail"])

    def test_merged_row_incomplete_pair(self):
        row = m.merged_key_row("upstash_vector", "URL+TOKEN",
                               [("URL", {}, None),
                                ("TOKEN", {"process": "t"}, "t")])
        self.assertEqual(row["classification"], m.CLS_MISSING)
        self.assertFalse(row["key_present"])

    def test_gemini_404_model_listed_is_unknown(self):
        def http(method, url, headers=None, body=None, timeout=10):
            if ":countTokens" in url:
                return resp(404, {"error": {"status": "NOT_FOUND"}})
            return resp(200, {"models": [{"name": "models/gemini-2.5-flash"}]})
        row = m.check_gemini(("GEMINI_API_KEY", {"process": KEY}, KEY),
                             http, paid=False)
        self.assertEqual(row["classification"], m.CLS_UNKNOWN)

    def test_alt_probe_on_mismatch_failure(self):
        calls = []
        def http(method, url, headers=None, body=None, timeout=10):
            auth = headers.get("Authorization", "")
            calls.append(auth)
            if auth.endswith("good-key"):
                return resp(200, {"data": [{"id": "gpt-5-nano"}]})
            return resp(401, {"error": {"code": "invalid_api_key"}})
        keyctx = ("OPENAI_API_KEY",
                  {"process": "stale-key", "secrets": "good-key"}, "stale-key")
        with mock.patch.object(m, "resolve_key", return_value=keyctx):
            row = m.probe_provider("openai", ["OPENAI_API_KEY"], m.check_openai,
                                   False, http)
        self.assertEqual(row["classification"], m.CLS_MISMATCH)
        self.assertEqual(row["alt_probe"]["source"], "secrets")
        self.assertEqual(row["alt_probe"]["l0_status"], "ok")
        self.assertIn("alt[secrets]", row["detail"])
        self.assertEqual(len(calls), 2)
        self.assertNotIn("stale-key", json.dumps(row))
        self.assertNotIn("good-key", json.dumps(row))

    def test_alt_probe_skipped_when_primary_ok(self):
        def http(method, url, headers=None, body=None, timeout=10):
            return resp(200, {"data": [{"id": "gpt-5-nano"}]})
        keyctx = ("OPENAI_API_KEY", {"process": "a", "secrets": "b"}, "a")
        with mock.patch.object(m, "resolve_key", return_value=keyctx):
            row = m.probe_provider("openai", ["OPENAI_API_KEY"], m.check_openai,
                                   False, http)
        self.assertNotIn("alt_probe", row)


class OllamaCheck(unittest.TestCase):
    def test_missing_expected_model(self):
        http = stub_http({("GET", "http://127.0.0.1:11434/api/tags"):
                          resp(200, {"models": [{"name": "qwen3.5:9b"}]})})
        row = m.check_ollama(None, http, False)
        self.assertEqual(row["classification"], m.CLS_MODEL)
        self.assertIn("missing", row["detail"])

    def test_down(self):
        http = stub_http({("GET", "http://127.0.0.1:11434/api/tags"):
                          resp(error="URLError: refused")})
        row = m.check_ollama(None, http, False)
        self.assertEqual(row["classification"], m.CLS_NETWORK)


if __name__ == "__main__":
    unittest.main(verbosity=1)
