"""Synthetic failure texts only; no GPU, network, or secret access."""
import os
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gpu_power_fallback as gpf

ROOT = Path(__file__).resolve().parent.parent


class ClassifyTest(unittest.TestCase):
    def test_timeout_texts(self):
        for text in ("Read timed out", "ollama_timeout_streak",
                     "TIMEOUT_SOFT after 30s", "deadline exceeded"):
            self.assertEqual("timeout", gpf.classify_local_inference_failure(text))

    def test_power_limit_and_driver_signals(self):
        self.assertEqual("power_limit_suspect",
                         gpf.classify_local_inference_failure("hw_power_brake_slowdown_active"))
        self.assertEqual("power_limit_suspect",
                         gpf.classify_local_inference_failure("전력 제한 위이잉"))
        self.assertEqual("driver_reset",
                         gpf.classify_local_inference_failure("nvlddmkm TDR new_error_events"))

    def test_cable_and_transient_power_signals(self):
        for text in ("cable_power_trip", "transient_spike",
                     "transient power excursion", "cable trip", "power_trip"):
            self.assertEqual("power_limit_suspect",
                             gpf.classify_local_inference_failure(text))
        self.assertEqual("unknown",
                         gpf.classify_local_inference_failure("field trip report"))

    def test_oom_no_response_unknown(self):
        self.assertEqual("oom_suspect",
                         gpf.classify_local_inference_failure("CUDA out of memory"))
        self.assertEqual("no_response",
                         gpf.classify_local_inference_failure("connection refused"))
        self.assertEqual("unknown", gpf.classify_local_inference_failure(""))
        self.assertEqual("unknown", gpf.classify_local_inference_failure("some other failure"))


class RouteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.config = gpf.load_routing(ROOT)

    def test_embed_prefers_low_cost_before_paid(self):
        candidates = gpf.suggest_fallback_route("embed", self.config)
        ids = [c["id"] for c in candidates]
        self.assertNotIn("ollama_embed", ids)          # failed local lane skipped
        self.assertLess(ids.index("upstash_vector"),   # low_cost before paid_quality
                        ids.index("openai_embed"))

    def test_llm_candidates_follow_tier_order(self):
        candidates = gpf.suggest_fallback_route("llm", self.config)
        tiers = [c["tier"] for c in candidates]
        self.assertEqual(sorted(tiers, key=TIER_ORDER_INDEX.get), tiers)
        self.assertEqual("groq", candidates[0]["id"])
        self.assertFalse(any(c["tier"] == "free_local" for c in candidates))

    def test_paid_gate_blocks_paid_without_env(self):
        candidates = gpf.suggest_fallback_route("embed", self.config, allow_paid=False)
        paid = {c["id"]: c for c in candidates if c["tier"] == "paid_quality"}
        self.assertTrue(paid)
        self.assertTrue(all(not c["allowed"] for c in paid.values()))
        self.assertTrue(all(c["paidGate"] == "AWX_AGENT_ALLOW_PAID_MODELS"
                            for c in paid.values()))
        self.assertTrue(all(c["allowed"] for c in candidates
                            if c["tier"] == "low_cost"))

    def test_env_entries_are_names_not_char_explosion(self):
        candidates = gpf.suggest_fallback_route("embed", self.config)
        for c in candidates:
            for name in c["env"]:
                self.assertIsInstance(name, str)
                self.assertRegex(name, r"^[A-Z0-9_]+$")  # env NAME only


class DecideTest(unittest.TestCase):
    def test_first_timeout_auto_falls_back(self):
        d = gpf.decide("embed", "Read timed out", attempts=0, root=ROOT)
        self.assertEqual("timeout", d["reason"])
        self.assertEqual("fallback_to_api", d["action"])
        self.assertEqual("upstash_vector", d["nextRoute"]["id"])
        self.assertTrue(d["auto"])
        self.assertEqual("local_failover", d["apiSpend"]["why"])
        self.assertEqual(1, d["localRetryBudget"])  # optional single retry, not a loop

    def test_repeated_failure_leaves_no_retry_budget(self):
        d = gpf.decide("llm", "Read timed out", attempts=1, root=ROOT)
        self.assertEqual("fallback_to_api", d["action"])
        self.assertEqual(0, d["localRetryBudget"])
        self.assertEqual("groq", d["nextRoute"]["id"])

    def test_power_suspect_never_retries_local(self):
        d = gpf.decide("embed", "hw_power_brake_slowdown_active",
                       attempts=0, root=ROOT)
        self.assertEqual("power_limit_suspect", d["reason"])
        self.assertEqual(0, d["localRetryBudget"])
        self.assertEqual("fallback_to_api", d["action"])

    def test_cable_trip_decide_never_retries_local(self):
        d = gpf.decide("llm", "hw_power_brake_slowdown_active cable trip",
                       attempts=0, root=ROOT)
        self.assertEqual("power_limit_suspect", d["reason"])
        self.assertEqual(0, d["localRetryBudget"])
        self.assertEqual("fallback_to_api", d["action"])
        self.assertEqual("groq", d["nextRoute"]["id"])
        self.assertTrue(d["auto"])

    def test_report_line_is_one_line_and_secret_free(self):
        d = gpf.decide("llm", "connection refused", root=ROOT)
        self.assertNotIn("\n", d["report"])
        self.assertNotRegex(d["report"], r"sk-|key=")


TIER_ORDER_INDEX = {tier: i for i, tier in enumerate(gpf.TIER_ORDER)}


if __name__ == "__main__":
    unittest.main()
