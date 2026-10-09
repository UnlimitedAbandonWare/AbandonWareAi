"""NAVER probe/runtime mode contracts; synthetic sources and HTTP only."""
import json
import os
import unittest
from pathlib import Path
from unittest.mock import patch
from scripts.apikit.providers import naver
from scripts import api_key_health_probe as health

HUB = {"NAVER_APIHUB_CLIENT_ID": "synthetic-hub-client-id", "NAVER_APIHUB_CLIENT_SECRET": "synthetic-hub-client-secret"}
LEGACY = {"NAVER_CLIENT_ID": "synthetic-legacy-client-id", "NAVER_CLIENT_SECRET": "synthetic-legacy-client-secret"}


class NaverProbeModeContract(unittest.TestCase):
    def exercise(self, adapter, values, *, user=None, machine=None, status=200):
        calls = []
        def wire(method, url, headers=None, *args, **kwargs):
            calls.append((method, url, dict(headers or {})))
            body = {"total": 1, "items": [{"title": "synthetic observation"}]} if status == 200 else {"errorCode": "SE01"}
            if adapter == "apikit":
                return {"status": status, "headers": {}, "text": json.dumps(body), "ms": 1, "error": None, "error_kind": None}
            return health.Resp(status, {}, json.dumps(body).encode(), 1)
        with patch.dict(os.environ, values, clear=True):
            if adapter == "apikit":
                ctx = {"secrets": {}, "scopes": {"Process": values, "User": user or {}, "Machine": machine or {}}, "timeout": 1, "paid": False}
                with patch.object(naver.common, "http_request", side_effect=wire), patch.object(naver.common, "expiry_status", return_value={"state": "not_recorded"}):
                    row = naver.check(ctx)[-1]
            else:
                def sources(name):
                    return {s: v for s, v in [("process", values.get(name)), ("user", (user or {}).get(name)), ("machine", (machine or {}).get(name))] if v is not None}
                with patch.object(health, "key_sources", side_effect=sources):
                    row = health.check_naver(None, wire, False)
        serialized = json.dumps(row)
        for value in list(HUB.values()) + list(LEGACY.values()):
            self.assertNotIn(value, serialized, "public result must omit synthetic credential values")
        return row, calls

    def both(self, values, **kwargs):
        for adapter in ("apikit", "health"):
            with self.subTest(adapter=adapter):
                yield adapter, self.exercise(adapter, values, **kwargs)

    def test_auto_prefers_complete_hub(self):
        for _, (row, calls) in self.both(HUB | LEGACY):
            self.assertEqual("auto", row["provider_mode"])
            self.assertEqual("apihub", row["selected_provider"])
            self.assertEqual(1, len(calls))
            self.assertIn("naverapihub.apigw.ntruss.com/search/v1/webkr", calls[0][1])
            self.assertEqual(HUB["NAVER_APIHUB_CLIENT_ID"], calls[0][2]["X-NCP-APIGW-API-KEY-ID"])

    def test_auto_preserves_legacy(self):
        for _, (row, calls) in self.both(LEGACY):
            self.assertEqual("openapi", row["selected_provider"])
            self.assertEqual(1, len(calls))
            self.assertIn("openapi.naver.com/v1/search/webkr.json", calls[0][1])
            self.assertEqual(LEGACY["NAVER_CLIENT_SECRET"], calls[0][2]["X-Naver-Client-Secret"])

    def test_forced_openapi_ignores_hub(self):
        for _, (row, calls) in self.both(HUB | LEGACY | {"NAVER_SEARCH_PROVIDER": "openapi"}):
            self.assertEqual("openapi", row["selected_provider"])
            self.assertEqual(1, len(calls))
            self.assertNotIn("X-NCP-APIGW-API-KEY", calls[0][2])

    def test_partial_hub_auto_falls_back_without_mixing(self):
        for _, (row, calls) in self.both(LEGACY | {"NAVER_APIHUB_CLIENT_ID": HUB["NAVER_APIHUB_CLIENT_ID"]}):
            self.assertEqual("openapi", row["selected_provider"])
            self.assertEqual(1, len(calls))
            self.assertEqual(LEGACY["NAVER_CLIENT_ID"], calls[0][2]["X-Naver-Client-Id"])

    def test_forced_hub_missing_never_uses_legacy(self):
        for _, (row, calls) in self.both(LEGACY | {"NAVER_SEARCH_PROVIDER": "apihub"}):
            self.assertEqual([], calls)
            self.assertEqual("missing_naver_apihub_credentials", row["disabledReason"])

    def test_forced_openapi_missing_never_uses_hub(self):
        for _, (row, calls) in self.both(HUB | {"NAVER_SEARCH_PROVIDER": "openapi"}):
            self.assertEqual([], calls)
            self.assertEqual("missing_naver_client_credentials", row["disabledReason"])

    def test_invalid_mode_stops_before_http(self):
        for _, (row, calls) in self.both(HUB | LEGACY | {"NAVER_SEARCH_PROVIDER": "unsupported"}):
            self.assertEqual([], calls)
            self.assertEqual("invalid_naver_search_provider", row["disabledReason"])

    def test_explicit_blank_or_unresolved_mode_is_invalid_in_both_adapters(self):
        for mode in ("", "  ", "${UNRESOLVED_MODE:}"):
            for adapter in ("apikit", "health"):
                with self.subTest(adapter=adapter, mode_kind="blank" if not mode.strip() else "unresolved"):
                    row, calls = self.exercise(adapter, HUB | LEGACY | {"NAVER_SEARCH_PROVIDER": mode})
                    self.assertEqual(0, len(calls), "explicit invalid mode must stop before HTTP")
                    self.assertEqual("invalid_naver_search_provider", row["disabledReason"])

    def test_invalid_higher_priority_mode_cannot_fall_through_to_valid_lower_mode(self):
        cases = [
            (HUB | LEGACY | {"NAVER_SEARCH_PROVIDER": ""}, {"NAVER_SEARCH_PROVIDER": "apihub"}, {}),
            (HUB | LEGACY | {"NAVER_SEARCH_PROVIDER": "${UNRESOLVED_MODE:}"}, {"NAVER_SEARCH_PROVIDER": "openapi"}, {}),
            (HUB | LEGACY, {"NAVER_SEARCH_PROVIDER": ""}, {"NAVER_SEARCH_PROVIDER": "apihub"}),
            (HUB | LEGACY, {"NAVER_SEARCH_PROVIDER": "${UNRESOLVED_MODE:}"}, {"NAVER_SEARCH_PROVIDER": "openapi"}),
        ]
        for index, (values, user, machine) in enumerate(cases):
            for adapter in ("apikit", "health"):
                with self.subTest(adapter=adapter, case=index):
                    row, calls = self.exercise(adapter, values, user=user, machine=machine)
                    self.assertEqual(0, len(calls), "configured invalid higher mode must stop before HTTP")
                    self.assertEqual("invalid_naver_search_provider", row["disabledReason"])

    def test_conflicting_alias_values_stop_before_http(self):
        for _, (row, calls) in self.both(HUB, user={"NAVER_APIHUB_CLIENT_ID": "different-synthetic-id"}):
            self.assertEqual([], calls)
            self.assertEqual("conflicting-credential-aliases", row["disabledReason"])

    def test_equal_alias_values_are_accepted(self):
        for _, (row, calls) in self.both(HUB, user=HUB):
            self.assertEqual(1, len(calls))
            self.assertNotIn("disabledReason", row)

    def test_placeholder_hub_does_not_hide_legacy(self):
        for _, (row, calls) in self.both(LEGACY | HUB | {"NAVER_APIHUB_CLIENT_SECRET": "__MISSING__"}):
            self.assertEqual("openapi", row["selected_provider"])
            self.assertEqual(1, len(calls))

    def test_legacy_csv_remains_usable(self):
        values = {"NAVER_KEYS": LEGACY["NAVER_CLIENT_ID"] + ":" + LEGACY["NAVER_CLIENT_SECRET"]}
        for _, (row, calls) in self.both(values):
            self.assertEqual("openapi", row["selected_provider"])
            self.assertEqual(1, len(calls))
            self.assertEqual(LEGACY["NAVER_CLIENT_ID"], calls[0][2]["X-Naver-Client-Id"])

    def test_valid_csv_ignores_conflict_in_unused_incomplete_pair(self):
        values = {"NAVER_SEARCH_PROVIDER": "openapi", "NAVER_KEYS": "synthetic-csv-id:synthetic-csv-secret",
                  "NAVER_CLIENT_ID": "synthetic-incomplete-id-A"}
        for adapter in ("apikit", "health"):
            with self.subTest(adapter=adapter):
                row, calls = self.exercise(adapter, values, user={"NAVER_CLIENT_ID": "synthetic-incomplete-id-B"})
                self.assertEqual(1, len(calls), "valid selected CSV must match Java admission")
                self.assertNotIn("disabledReason", row)
                self.assertTrue(calls[0][2]["X-Naver-Client-Id"] == "synthetic-csv-id")
                self.assertTrue(calls[0][2]["X-Naver-Client-Secret"] == "synthetic-csv-secret")
                self.assertNotIn("synthetic-csv-secret", json.dumps(row))

    def test_selected_csv_conflict_remains_blocked_with_incomplete_pair(self):
        values = {"NAVER_SEARCH_PROVIDER": "openapi", "NAVER_KEYS": "synthetic-csv-id:synthetic-csv-secret",
                  "NAVER_CLIENT_ID": "synthetic-incomplete-id-A"}
        user = {"NAVER_KEYS": "different-csv-id:different-csv-secret", "NAVER_CLIENT_ID": "synthetic-incomplete-id-B"}
        for adapter in ("apikit", "health"):
            with self.subTest(adapter=adapter):
                row, calls = self.exercise(adapter, values, user=user)
                self.assertEqual([], calls)
                self.assertEqual("conflicting-credential-aliases", row["disabledReason"])
                self.assertNotIn("synthetic-csv-secret", json.dumps(row))
                self.assertNotIn("different-csv-secret", json.dumps(row))

    def test_app_name_is_metadata_not_a_credential(self):
        for _, (row, calls) in self.both({"NAVER_APIHUB_APP_NAME": "synthetic-application"}):
            self.assertEqual([], calls)
            self.assertEqual("synthetic-application", row["app_name"])
        for _, (row, calls) in self.both(HUB | {"NAVER_APIHUB_APP_NAME": "synthetic-application"}):
            self.assertEqual(1, len(calls))
            self.assertEqual("synthetic-application", row["app_name"])
            self.assertNotIn("NAVER_APIHUB_APP_NAME", row["key_env"])

    def test_auth_quota_failures_are_one_attempt(self):
        for status in (401, 403, 429):
            for adapter, (row, calls) in self.both(HUB, status=status):
                with self.subTest(status=status, adapter=adapter):
                    self.assertEqual(1, len(calls))
                    self.assertNotEqual("OK", row.get("cls", row.get("classification")))

    def test_invalid_endpoint_is_not_called(self):
        for _, (row, calls) in self.both(HUB | {"NAVER_APIHUB_BASE_URL": "https://example.org?invalid=value"}):
            self.assertEqual([], calls)
            self.assertEqual("invalid_naver_search_endpoint", row["disabledReason"])

    def test_catalog_covers_runtime_credentials_and_metadata(self):
        from scripts.awx_project_secrets import catalog
        data = catalog(Path(__file__).resolve().parents[1])
        keys = set(data["providers"]["naver"])
        self.assertTrue(set(HUB | LEGACY | {"NAVER_KEYS": ""}).issubset(keys))
        self.assertNotIn("NAVER_APIHUB_APP_NAME", keys)
        self.assertIn("NAVER_APIHUB_APP_NAME", data["runtimeEnvironmentNames"])
        self.assertIn("NAVER_SEARCH_PROVIDER", data["runtimeEnvironmentNames"])


    def test_catalog_keeps_unknown_and_protected_runtime_names_blocked(self):
        import tempfile
        from scripts.awx_project_secrets import catalog, Conflict
        original = json.loads((Path(__file__).resolve().parents[1] / "config/project-resources.json").read_text())
        for name in ("NAVER_UNDECLARED_SETTING", "NAVER_APIHUB_CLIENT_SECRET", "CONVERSATE_OPENSSL"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "config").mkdir()
                data = dict(original, runtimeEnvironmentNames=original["runtimeEnvironmentNames"] + [name])
                (root / "config/project-resources.json").write_text(json.dumps(data), encoding="utf-8")
                with self.assertRaises(Conflict):
                    catalog(root)

    def test_legacy_csv_preserves_bare_token_positions_and_explicit_pair_priority(self):
        cases = [
            ("__MISSING__,synthetic-secret-A,synthetic-orphan-id", None),
            ("synthetic-id-A,__MISSING__,synthetic-orphan-secret", None),
            ("__MISSING__,synthetic-secret-A,valid-id,valid-secret", "valid-id"),
            ("bare-id,explicit-id:explicit-secret,bare-secret", "explicit-id"),
        ]
        for raw, expected in cases:
            for adapter, (row, calls) in self.both({"NAVER_KEYS": raw}):
                with self.subTest(adapter=adapter, valid_pair=expected is not None):
                    self.assertEqual(0 if expected is None else 1, len(calls))
                    if expected is None:
                        self.assertEqual("missing_naver_client_credentials", row["disabledReason"])
                    else:
                        self.assertEqual(expected, calls[0][2]["X-Naver-Client-Id"])

    def test_malformed_csv_cannot_bypass_credential_alias_conflict(self):
        for _, (row, calls) in self.both(LEGACY | {"NAVER_SEARCH_PROVIDER": "openapi", "NAVER_KEYS": "not-a-pair"}):
            self.assertEqual(0, len(calls))
            self.assertEqual("conflicting-credential-aliases", row["disabledReason"])

    def test_health_retains_per_name_hashed_source_diagnostics(self):
        row, calls = self.exercise("health", HUB, user=HUB)
        self.assertEqual(1, len(calls))
        self.assertEqual("NAVER_APIHUB_CLIENT_ID+NAVER_APIHUB_CLIENT_SECRET", row["key_env"])
        self.assertEqual(len(HUB["NAVER_APIHUB_CLIENT_ID"]), row["key_len"])
        for name in HUB:
            self.assertIn(name + ":match", row["match_detail"])
            self.assertEqual(health.sha8(HUB[name]), row["source_sha8"][name]["process"])
            self.assertEqual(row["source_sha8"][name]["process"], row["source_sha8"][name]["user"])

if __name__ == "__main__":
    unittest.main()
