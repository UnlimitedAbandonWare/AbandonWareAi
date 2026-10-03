#!/usr/bin/env python3
"""Synthetic tests for settings_defaults_db_snapshot.py - import level,
_query is stubbed; no live server or H2 file is touched."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

SCRIPT = Path(__file__).resolve().parent / "settings_defaults_db_snapshot.py"
spec = importlib.util.spec_from_file_location("sd_db_snapshot", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def _payload(columns, rows):
    return {"ok": True, "cmd": "query", "via": "live-http",
            "columns": columns, "rows": rows, "rowCount": len(rows)}


class DbSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.outdir = self.tmp.name
        self.calls = []
        self._orig_query = mod._query

    def tearDown(self):
        mod._query = self._orig_query
        self.tmp.cleanup()

    def _stub(self, mapping):
        def fake(base_url, sql, max_rows, timeout=90):
            self.calls.append((sql, max_rows))
            for needle, result in mapping.items():
                if needle in sql:
                    payload, err = result
                    return payload, err
            return None, {"reason": "unmapped-sql", "exit": 5}
        mod._query = fake

    def test_snapshot_happy_path_masks_owner_and_values(self):
        self._stub({
            "INFORMATION_SCHEMA.COLUMNS": (_payload(
                ["TABLE_NAME", "COLUMN_NAME"],
                [["user_preference_profile", "id"],
                 ["user_preference_profile", "owner_key"],
                 ["user_preference_profile", "profile_meta"],
                 ["configuration_setting", "setting_key"],
                 ["configuration_setting", "setting_value"],
                 ["current_model", "id"], ["current_model", "model"]]), None),
            "FROM user_preference_profile": (_payload(
                ["id", "owner_key", "profile_meta", "updated_at"],
                [[1, "owner-secret-value", '{"a":1,"b":2}', "2026-10-03"]]), None),
            "FROM configuration_setting": (_payload(
                ["setting_key", "setting_value"],
                [["k1", "super-secret"], ["k2", "v2"]]), None),
            "FROM current_model": (_payload(
                ["id", "model"], [[1, "m1"], [2, "m2"]]), None)})
        args = SimpleNamespace(label="t", out_dir=self.outdir,
                               base_url="http://x", max_rows=50)
        code = mod.cmd_snapshot(args)
        self.assertEqual(0, code)
        doc = json.loads((Path(self.outdir) / "snapshot-t.json")
                         .read_text(encoding="utf-8"))
        self.assertEqual("OK", doc["status"])
        upp = doc["tables"]["user_preference_profile"]
        self.assertEqual(1, upp["rowCount"])
        row = upp["rows"][0]
        self.assertNotIn("owner-secret-value", json.dumps(doc))
        self.assertNotIn("super-secret", json.dumps(doc))
        self.assertEqual(sorted(row["profileMetaTopKeys"]), ["a", "b"])
        cfg = doc["tables"]["configuration_setting"]
        self.assertEqual(["k1", "k2"], [i["key"] for i in cfg["items"]])
        self.assertTrue(all("valueSha256" in i for i in cfg["items"]))
        cm = doc["tables"]["current_model"]
        self.assertEqual(2, cm["rowCount"])
        self.assertIn("id1RowSha256", cm)

    def test_missing_table_marker_continues(self):
        self._stub({
            "INFORMATION_SCHEMA.COLUMNS": (_payload(
                ["TABLE_NAME", "COLUMN_NAME"],
                [["current_model", "id"], ["current_model", "model"]]), None),
            "FROM current_model": (_payload(
                ["id", "model"], [[1, "m"]]), None)})
        args = SimpleNamespace(label="t2", out_dir=self.outdir,
                               base_url="http://x", max_rows=50)
        self.assertEqual(0, mod.cmd_snapshot(args))
        doc = json.loads((Path(self.outdir) / "snapshot-t2.json")
                         .read_text(encoding="utf-8"))
        self.assertEqual("USER_PREFERENCE_PROFILE_MISSING",
                         doc["tables"]["user_preference_profile"]["status"])
        self.assertEqual("CONFIGURATION_SETTING_MISSING",
                         doc["tables"]["configuration_setting"]["status"])
        self.assertEqual("OK", doc["tables"]["current_model"]["status"])

    def test_unreachable_marks_not_run_exit_3(self):
        self._stub({
            "INFORMATION_SCHEMA.COLUMNS": (
                {"reason": "live-unreachable"},
                {"reason": "live-unreachable", "exit": 3})})
        args = SimpleNamespace(label="down", out_dir=self.outdir,
                               base_url="http://x", max_rows=50)
        self.assertEqual(3, mod.cmd_snapshot(args))
        doc = json.loads((Path(self.outdir) / "snapshot-down.json")
                         .read_text(encoding="utf-8"))
        self.assertEqual("NOT_RUN", doc["status"])

    def test_non_live_lane_reply_refused(self):
        """db_agent answering with via!=live-http must be rejected, not used."""
        import subprocess as sp
        orig_run = mod.subprocess.run
        def fake_run(argv, **kw):
            self.assertIn("--via", argv)
            self.assertIn("live", argv)
            return SimpleNamespace(
                returncode=0,
                stdout=json.dumps({"via": "file", "columns": [], "rows": []}),
                stderr="")
        mod.subprocess.run = fake_run
        try:
            payload, err = mod._query("http://x", "SELECT 1", 10)
        finally:
            mod.subprocess.run = orig_run
        self.assertIsNone(payload)
        self.assertEqual("non-live-lane-refused", err["reason"])

    def test_diff_reports_changes(self):
        a = {"label": "a", "status": "OK",
             "tables": {"t": {"status": "OK", "rowCount": 1,
                              "rows": [{"id": 1, "h": "x"}]}}}
        b = {"label": "b", "status": "OK",
             "tables": {"t": {"status": "OK", "rowCount": 2,
                              "rows": [{"id": 1, "h": "x"},
                                       {"id": 2, "h": "y"}]}}}
        pa = Path(self.outdir) / "a.json"
        pb = Path(self.outdir) / "b.json"
        pa.write_text(json.dumps(a), encoding="utf-8")
        pb.write_text(json.dumps(b), encoding="utf-8")
        args = SimpleNamespace(snap_a=str(pa), snap_b=str(pb), out=None)
        self.assertEqual(0, mod.cmd_diff(args))


if __name__ == "__main__":
    unittest.main()
