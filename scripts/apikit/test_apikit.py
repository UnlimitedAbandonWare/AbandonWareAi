#!/usr/bin/env python3
"""apikit classification unit tests — fixture-driven, no network.
Run: python -m unittest scripts/apikit/test_apikit.py   (from src root)"""
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


class FixtureClassification(unittest.TestCase):
    """Every fixture file carries its expected cls + optional official code."""

    def test_all_fixtures(self):
        files = sorted(FIXTURES.glob("*.json"))
        self.assertGreaterEqual(len(files), 11, "fixture set shrunk?")
        for f in files:
            data = json.loads(f.read_text(encoding="utf-8"))
            if "expect" not in data:
                continue  # non-classification fixtures (e.g. replay bodies)
            spec = PROVIDERS[data["provider"]].SPEC
            cls, code, detail = common.classify(spec, _result(data))
            with self.subTest(fixture=f.name):
                self.assertEqual(data["expect"], cls,
                                 "detail=%s code=%s" % (detail, code))
                if data.get("expect_code"):
                    self.assertEqual(data["expect_code"], code)


class SpendLine(unittest.TestCase):
    def test_spend_line_keeps_input_output_tokens(self):
        # D-2: gateway usage 키 이름을 그대로 복사하고 명시적 0도 유지한다.
        rec = common.spend_line({"stamp": "t"}, "jev", "typesafe-ai/jev",
                                "probe", 200,
                                usage={"inputTokens": 0, "outputTokens": 5},
                                cost=0.000001)
        self.assertEqual(rec["inputTokens"], 0)
        self.assertEqual(rec["outputTokens"], 5)
        self.assertEqual(rec["costUsd"], 0.000001)


class GenericRules(unittest.TestCase):
    def test_unknown_shape(self):
        spec = PROVIDERS["openai"].SPEC
        cls, code, detail = common.classify(spec, {
            "status": 418, "text": '{"weird": true}', "headers": {},
            "ms": 1, "error": None, "error_kind": None})
        self.assertEqual(common.UNKNOWN, cls)

    def test_redirect_refused_is_unknown(self):
        spec = PROVIDERS["jev"].SPEC
        cls, _, detail = common.classify(spec, {
            "status": 302, "text": "", "headers": {},
            "ms": 1, "error": None, "error_kind": None})
        self.assertEqual(common.UNKNOWN, cls)
        self.assertIn("redirect-refused", detail)

    def test_quota_code_beats_429(self):
        # insufficient_quota inside a 429 must be QUOTA, not RATE_LIMIT
        spec = PROVIDERS["openai"].SPEC
        cls, code, _ = common.classify(spec, {
            "status": 429,
            "text": json.dumps({"error": {"code": "insufficient_quota"}}),
            "headers": {}, "ms": 1, "error": None, "error_kind": None})
        self.assertEqual(common.QUOTA, cls)
        self.assertEqual("insufficient_quota", code)

    def test_5xx(self):
        spec = PROVIDERS["gemini"].SPEC
        cls, _, _ = common.classify(spec, {
            "status": 503, "text": "{}", "headers": {},
            "ms": 1, "error": None, "error_kind": None})
        self.assertEqual(common.SERVER_5XX, cls)

    def test_network_error(self):
        spec = PROVIDERS["ollama"].SPEC
        cls, _, detail = common.classify(spec, {
            "status": None, "text": None, "headers": {},
            "ms": 1, "error": "connection refused", "error_kind": "network"})
        self.assertEqual(common.NETWORK, cls)


class KeyHandling(unittest.TestCase):
    def test_mask_known_and_long_tokens(self):
        secret = "sk-" + "a" * 40
        out = common.mask("key=%s other=%s" % (secret, "b" * 40), (secret,))
        self.assertNotIn(secret, out)
        self.assertNotIn("b" * 40, out)
        self.assertIn("<key:", out)

    def test_resolve_precedence_and_mismatch(self):
        scopes = {"Process": {"X_K": "p-value"}, "User": {"X_K": "u-value"},
                  "Machine": {}}
        ki = common.resolve_key(["X_K"], secrets={}, scopes=scopes)
        self.assertEqual("Process", ki["src"])
        self.assertTrue(ki["mismatch"])
        self.assertEqual("p-value", ki["value"])

    def test_resolve_user_then_secrets(self):
        scopes = {"Process": {}, "User": {"X_K": "u"}, "Machine": {}}
        ki = common.resolve_key(["X_K"], secrets={"X_K": "s"}, scopes=scopes)
        self.assertEqual("User", ki["src"])
        self.assertTrue(ki["mismatch"])  # secrets copy differs
        ki2 = common.resolve_key(["X_K"], secrets={"X_K": "s"},
                                 scopes={"Process": {}, "User": {}, "Machine": {}})
        self.assertEqual(".secrets", ki2["src"])

    def test_missing_key_row(self):
        spec = PROVIDERS["openai"].SPEC
        empty = {"Process": {}, "User": {}, "Machine": {}}
        row = common.run_step(
            spec, {"secrets": {}, "scopes": empty, "timeout": 1,
                   "secret_values": (), "paid": False},
            step="models", auth="bearer",
            ki=common.resolve_key(spec["key_envs"], {}, empty),
            url="https://api.openai.com/v1/models")
        self.assertEqual(common.KEY_MISSING, row["cls"])
        self.assertIsNone(row["http"])

    def test_row_has_no_secret(self):
        scopes = {"Process": {"X_K": "real-secret-value-123"}, "User": {},
                  "Machine": {}}
        ki = common.resolve_key(["X_K"], secrets={}, scopes=scopes)
        row = common.key_row(PROVIDERS["openai"].SPEC, ki)
        self.assertNotIn("real-secret-value-123", json.dumps(row))


class PlanGate(unittest.TestCase):
    """403 splits: plan wording -> PLAN_GATE; permission/region/IP -> FORBIDDEN."""

    def test_plan_wording_upgrades_403(self):
        spec = PROVIDERS["jev"].SPEC
        cls, code, _ = common.classify(spec, {
            "status": 403, "headers": {}, "ms": 1,
            "error": None, "error_kind": None,
            "text": json.dumps({"error": {
                "type": "permission_denied",
                "message": "Zero Data Retention (ZDR) is only available for "
                           "Pro and Enterprise plans. Current plan: hobby."}})})
        self.assertEqual(common.PLAN_GATE, cls)
        self.assertEqual("permission_denied", code)

    def test_403_without_plan_wording_stays_forbidden(self):
        spec = PROVIDERS["jev"].SPEC
        cls, _, _ = common.classify(spec, {
            "status": 403, "headers": {}, "ms": 1,
            "error": None, "error_kind": None,
            "text": json.dumps({"error": {"type": "permission_denied",
                                          "message": "token lacks scope"}})})
        self.assertEqual(common.FORBIDDEN, cls)

    def test_plan_code_upgrades_without_message(self):
        spec = PROVIDERS["openai"].SPEC
        cls, code, _ = common.classify(spec, {
            "status": 403, "headers": {}, "ms": 1,
            "error": None, "error_kind": None,
            "text": json.dumps({"error": {"code": "plan_required"}})})
        self.assertEqual(common.PLAN_GATE, cls)
        self.assertEqual("plan_required", code)

    def test_delegate_reason_plan_gate_maps(self):
        from providers import jev
        self.assertEqual(common.PLAN_GATE,
                         jev._DELEGATE_REASON_CLASS["plan_gate"])


class DelegateReasonVocab(unittest.TestCase):
    """Jev delegate reasons follow docs/API_ROUTING_SPEC.md §6 vocabulary;
    retired names stay as aliases during the transition."""

    def test_ssot_vocabulary_present(self):
        from providers import jev
        m = jev._DELEGATE_REASON_CLASS
        self.assertEqual(m["auth_invalid"], common.KEY_INVALID)
        self.assertEqual(m["plan_gate"], common.PLAN_GATE)
        self.assertEqual(m["permission_denied"], common.FORBIDDEN)
        self.assertEqual(m["billing-blocked"], common.QUOTA)
        self.assertEqual(m["rate_limited"], common.RATE_LIMIT)
        self.assertEqual(m["upstream_error"], common.SERVER_5XX)

    def test_legacy_aliases_resolve(self):
        from providers import jev
        m = jev._DELEGATE_REASON_CLASS
        self.assertEqual(m["key_invalid_or_expired"], common.KEY_INVALID)
        self.assertEqual(m["forbidden"], common.FORBIDDEN)
        self.assertEqual(m["auth-blocked"], common.KEY_INVALID)


class KeyExpiryLedger(unittest.TestCase):
    """configs/api-key-expiry.json drives key_expiry — metadata only."""

    KI = {"env": "X_K", "value": "v", "src": "Process", "srcs": ["Process"],
          "len": 1, "sha8": "aa", "mismatch": False}
    SPEC = PROVIDERS["openai"].SPEC

    def test_absent_metadata_is_reported_not_guessed(self):
        self.assertEqual("not_recorded", common.expiry_status(
            self.SPEC, self.KI, ledger={})["state"])

    def test_expired_warn_ok_states(self):
        from datetime import datetime, timedelta, timezone
        now = datetime.now(timezone.utc)
        for offset, want in ((-1, "expired"), (3, "warn-d3"), (30, "ok")):
            entry = {"env": "X_K", "sha8": "aa",
                     "expiresAt": (now + timedelta(days=offset)).isoformat()}
            with self.subTest(offset=offset):
                st = common.expiry_status(self.SPEC, self.KI,
                                          ledger={"X_K": entry}, now=now)
                self.assertEqual(want, st["state"])

    def test_sha8_mismatch_is_other_key(self):
        entry = {"env": "X_K", "sha8": "zz",
                 "expiresAt": "2999-01-01T00:00:00+00:00"}
        self.assertEqual("other_key", common.expiry_status(
            self.SPEC, self.KI, ledger={"X_K": entry})["state"])

    def test_row_carries_expiry_field(self):
        row = common.key_row(self.SPEC, self.KI)
        self.assertEqual("not_recorded", row["key_expiry"])


class RunStepMock(unittest.TestCase):
    """run_step against a stubbed http_request — row shape + ok() override."""

    def test_ok_override_flags_missing_model(self):
        """jev /v1/models returns 200 but lacks typesafe-ai/jev -> MODEL_NOT_FOUND."""
        ki = {"env": "AI_GATEWAY_API_KEY", "value": "x", "src": "Process",
              "srcs": ["Process"], "len": 1, "sha8": "aa", "mismatch": False}
        ctx = {"secrets": {}, "scopes": {}, "timeout": 1,
               "secret_values": (), "paid": False}
        orig_http = common.http_request
        orig_resolve = common.resolve_key

        def _http(method, url, headers=None, body=None, timeout=None):
            if url.endswith("/credits"):
                text = {"balance": "10.00", "total_used": "1.00"}
            else:
                text = {"data": [{"id": "other/model"}]}
            return {"status": 200, "headers": {}, "ms": 5, "error": None,
                    "error_kind": None, "text": json.dumps(text)}
        try:
            common.http_request = _http
            common.resolve_key = lambda *a, **k: dict(ki)
            rows = PROVIDERS["jev"].check(ctx)
            credits = next(r for r in rows if r["step"] == "credits")
            models = next(r for r in rows if r["step"] == "models")
            self.assertEqual(common.OK, credits["cls"])
            self.assertEqual(common.MODEL_NOT_FOUND, models["cls"])
        finally:
            common.http_request = orig_http
            common.resolve_key = orig_resolve

    def test_snippet_masked_and_bounded(self):
        spec = PROVIDERS["openai"].SPEC
        orig = common.http_request
        secret = "sk-" + "z" * 40
        try:
            common.http_request = lambda *a, **k: {
                "status": 418, "headers": {}, "ms": 5, "error": None,
                "error_kind": None,
                "text": "odd " + secret + " " + ("y" * 500)}
            ki = {"env": "OPENAI_API_KEY", "value": secret, "src": "Process",
                  "srcs": ["Process"], "len": len(secret), "sha8": "aa",
                  "mismatch": False}
            ctx = {"secrets": {}, "scopes": {}, "timeout": 1,
                   "secret_values": (secret,), "paid": False}
            row = common.run_step(spec, ctx, ki=ki, step="models",
                                  auth="bearer",
                                  url="https://api.openai.com/v1/models")
            self.assertEqual(common.UNKNOWN, row["cls"])
            self.assertLessEqual(len(row["response_snippet"]), 300)
            self.assertNotIn(secret, row["response_snippet"])
        finally:
            common.http_request = orig


if __name__ == "__main__":
    unittest.main()
