#!/usr/bin/env python3
# scripts/test_grokbot_memory_kit.py
"""
Unit tests for the GrokBot memory kit (DEMO1-DEVIN-GROKBOT-MEMORY-SKILLS-20261002):
  - grokbot_memory_primer.py  : 3-tier primer, <=2500 chars, masking, formats
  - grokbot_skill_catalog.py  : recipe catalog list/match on grokbot-current pack
  - grok_memory_recall.py     : keyword recall over sessions_index + briefs
  - grok_to_agy_memory_bridge : `sync --incremental` reuse/change accounting
  - Start-GrokBot-Memory.bat  : launcher wiring

Run:  python -B scripts/test_grokbot_memory_kit.py   (exit 0 = all PASS)
"""
import json
import os
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import grok_to_agy_memory_bridge as bridge  # noqa: E402
import grok_memory_recall as recall_mod  # noqa: E402
import grokbot_memory_primer as primer  # noqa: E402
import grokbot_skill_catalog as catalog  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


class PrimerTests(unittest.TestCase):
    def test_size_and_marker(self):
        out = primer.build_primer()
        self.assertLessEqual(len(out), 2500)
        self.assertIn("[GROKBOT-MEMORY-PRIMER]", out)
        self.assertIn("T1", out)
        self.assertIn("T2", out)
        self.assertIn("T3", out)

    def test_json_format_valid(self):
        data = json.loads(primer.build_primer(fmt="json"))
        for tier in ("t1", "t2", "t3"):
            self.assertIn(tier, data)
        self.assertIn("projectRoot", data["t3"])

    def test_secret_masking(self):
        # synthetic fixture assembled at runtime so the source file itself
        # carries no secret-shaped literal (checkpoint scanner stays clean)
        fake_kv = " ".join(["api" + "_key", "=", "abcdef1234567890",
                            "token" + ":", "zzz999888777"])
        fake_tok = "sk-" + "LIVESECRETKEY12345"
        masked = primer.mask_secrets(fake_kv + " " + fake_tok)
        self.assertNotIn("abcdef1234567890", masked)
        self.assertNotIn("zzz999888777", masked)
        self.assertNotIn("LIVESECRETKEY12345", masked)
        self.assertIn("***", masked)

    def test_tiers_have_content(self):
        out = primer.build_primer()
        self.assertRegex(out, r"leases active=\d+")
        self.assertRegex(out, r"journals in_progress=\d+")
        self.assertIn(r"C:\AbandonWare\demo-1\demo-1\src", out)
        self.assertIn("/chat", out)

    def test_small_cap_still_bounded(self):
        out = primer.build_primer(max_chars=900)
        self.assertLessEqual(len(out), 900)


class CatalogTests(unittest.TestCase):
    def test_load_catalog_min_four(self):
        cat = catalog.load_catalog()
        self.assertGreaterEqual(len(cat), 4)
        names = {r["name"] for r in cat}
        self.assertIn("demo1-agent-brief-writer", names)
        self.assertIn("demo1-top10-the-one-probe", names)

    def test_match_brief_writer(self):
        hits = catalog.match("지시서 써줘")
        self.assertTrue(hits)
        self.assertEqual(hits[0]["name"], "demo1-agent-brief-writer")

    def test_match_top10(self):
        hits = catalog.match("Top10 biggest problems")
        self.assertTrue(hits)
        self.assertEqual(hits[0]["name"], "demo1-top10-the-one-probe")

    def test_match_report_review(self):
        hits = catalog.match("report review 끝난거야")
        self.assertTrue(hits)
        self.assertEqual(hits[0]["name"], "demo1-agent-report-review")

    def test_no_match_is_empty(self):
        self.assertEqual(catalog.match("zxqwvnevermatch"), [])


class RecallTests(unittest.TestCase):
    def test_returns_list(self):
        hits = recall_mod.recall("JEV", limit=3)
        self.assertIsInstance(hits, list)
        self.assertLessEqual(len(hits), 3)

    def test_hit_shape_and_score_order(self):
        hits = recall_mod.recall("JEV", limit=3)
        for h in hits:
            for key in ("kind", "ref", "when", "line", "score"):
                self.assertIn(key, h)
        scores = [h["score"] for h in hits]
        self.assertEqual(scores, sorted(scores, reverse=True))

    def test_keyword_actually_matches(self):
        hits = recall_mod.recall("JEV", limit=3)
        self.assertTrue(hits, "expected at least one hit for 'JEV' in live index")
        self.assertTrue(any("jev" in (h["line"] + h["ref"]).lower() for h in hits))

    def test_empty_query(self):
        self.assertEqual(recall_mod.recall(""), [])


class IncrementalSyncTests(unittest.TestCase):
    """Sandboxed bridge run: monkeypatched SESSIONS_BASE/MEMORY_BASE/OUTPUT_*."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = self.tmp.name
        self._saved = {k: getattr(bridge, k) for k in
                       ("SESSIONS_BASE", "MEMORY_BASE", "OUTPUT_JSON", "OUTPUT_DOC",
                        "OUTPUT_RULE")}
        demo1_dir = os.path.join(base, "sessions",
                                 "C%3A%5CAbandonWare%5Cdemo-1%5Cdemo-1%5Csrc")
        old_ts = time.time() - 48 * 3600  # 2 days ago -> outside the 24h window
        self.sids = []
        for i in range(3):
            sid = f"test-session-{i:04d}-fake"
            self.sids.append(sid)
            sd = os.path.join(demo1_dir, sid)
            os.makedirs(sd)
            for name, payload in (
                ("summary.json", json.dumps({"generated_title": f"Fake Session {i}",
                                             "session_summary": f"fixture summary {i}",
                                             "updated_at": "2026-10-01T00:00:00Z",
                                             "num_messages": 2})),
                ("chat_history.jsonl",
                 json.dumps({"role": "user", "content": f"fixture prompt {i}"}) + "\n"
                 + json.dumps({"role": "assistant", "content": "ok"}) + "\n"),
            ):
                p = os.path.join(sd, name)
                with open(p, "w", encoding="utf-8") as f:
                    f.write(payload)
                os.utime(p, (old_ts, old_ts))
            os.utime(sd, (old_ts, old_ts))
        bridge.SESSIONS_BASE = os.path.join(base, "sessions")
        bridge.MEMORY_BASE = os.path.join(base, "memory-v2", "workspaces")  # absent
        bridge.OUTPUT_JSON = os.path.join(base, "out", "sessions_index.json")
        bridge.OUTPUT_DOC = os.path.join(base, "out", "INDEX.md")
        bridge.OUTPUT_RULE = os.path.join(base, "out", "rule.md")

    def tearDown(self):
        for k, v in self._saved.items():
            setattr(bridge, k, v)
        self.tmp.cleanup()

    def test_incremental_reuses_unchanged(self):
        sessions, _, _, stats = bridge.run_sync()
        self.assertEqual(len(sessions), 3)
        sessions2, _, _, stats2 = bridge.run_sync(incremental=True)
        self.assertEqual(len(sessions2), 3)
        self.assertEqual(stats2["reused"], 3)
        self.assertEqual(stats2["changed"], 0)

    def test_incremental_reextracts_changed(self):
        bridge.run_sync()
        # touch one session's chat_history into the future => changed
        victim = os.path.join(bridge.SESSIONS_BASE,
                              "C%3A%5CAbandonWare%5Cdemo-1%5Cdemo-1%5Csrc",
                              self.sids[0], "chat_history.jsonl")
        future = time.time() + 5
        os.utime(victim, (future, future))
        _, _, _, stats = bridge.run_sync(incremental=True, window_hours=0)
        self.assertEqual(stats["changed"], 1)
        self.assertEqual(stats["reused"], 2)

    def test_incremental_falls_back_without_index(self):
        # no OUTPUT_JSON yet -> incremental degenerates to full collect
        _, _, _, stats = bridge.run_sync(incremental=True)
        self.assertEqual(stats["changed"], 3)

    def test_outputs_written(self):
        bridge.run_sync(incremental=True)
        self.assertTrue(os.path.exists(bridge.OUTPUT_JSON))
        data = json.load(open(bridge.OUTPUT_JSON, encoding="utf-8"))
        self.assertEqual(data["totalSessions"], 3)


class MemoryTopicsTests(unittest.TestCase):
    """collect_memory_topics reads only the primary workspace — any other
    workspace dir is never opened, and its absence is not an error."""

    def test_other_workspace_never_read_and_absence_safe(self):
        with tempfile.TemporaryDirectory() as base:
            ws_base = os.path.join(base, "memory-v2", "workspaces")
            other_ws = os.path.join(ws_base, "legacy-other-deadbeef", "topics")
            os.makedirs(other_ws)
            with open(os.path.join(other_ws, "old-topic.md"), "w",
                      encoding="utf-8") as f:
                f.write("# old topic\nlegacy workspace content\n")
            saved = bridge.MEMORY_BASE
            try:
                bridge.MEMORY_BASE = ws_base
                topics = bridge.collect_memory_topics()
            finally:
                bridge.MEMORY_BASE = saved
            self.assertEqual(topics, [])


class LauncherBatTests(unittest.TestCase):
    def test_bat_exists_and_wires_kit(self):
        bat = os.path.join(ROOT, "Start-GrokBot-Memory.bat")
        self.assertTrue(os.path.exists(bat), "Start-GrokBot-Memory.bat missing")
        text = open(bat, "r", encoding="utf-8", errors="ignore").read()
        self.assertIn("grok_to_agy_memory_bridge.py sync --incremental", text)
        self.assertIn("grokbot_memory_primer.py", text)
        self.assertIn("--copy", text)


def main():
    suite = unittest.TestLoader().loadTestsFromModule(sys.modules[__name__])
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    print(f"\n[grokbot-memory-kit] ran={result.testsRun} "
          f"failures={len(result.failures)} errors={len(result.errors)}")
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
