"""Synthetic failure texts only; no GPU, network, or secret access."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gpu_power_fallback as gpf

ROOT = Path(__file__).resolve().parent.parent


def _write_minimal_routing(root, oauth=True):
    cfg = Path(root) / "configs"
    cfg.mkdir(parents=True, exist_ok=True)
    oauth_block = ("    - id: chatgpt_oauth\n"
                   "      tier: subscription\n"
                   "      env: []\n"
                   "      enabledProp: CHATGPT_OAUTH_ENABLED\n") if oauth else ""
    (cfg / "api-routing.yaml").write_text(
        "routes:\n"
        "  llm:\n"
        "    - id: ollama_chat\n"
        "      tier: free_local\n"
        + oauth_block +
        "    - id: groq\n"
        "      tier: low_cost\n"
        "      env: [GROQ_API_KEY]\n"
        "  embed:\n"
        "    - id: ollama_embed\n"
        "      tier: free_local\n"
        "    - id: upstash_vector\n"
        "      tier: low_cost\n"
        "      env: [UPSTASH_VECTOR_URL]\n",
        encoding="utf-8")


def _write_incident_flag(root, state):
    d = Path(root) / "var" / "incident"
    d.mkdir(parents=True, exist_ok=True)
    (d / "gpu.json").write_text(json.dumps({
        "schema": "awx.gpu_incident.v1", "state": state,
        "since_kst": "2026-10-05T13:00:00+09:00"}), encoding="utf-8")


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
    """사고-플래그가 없는 임시 루트 — 라이브 flag 상태에 무관하게 고정."""

    def _flagfree_decide(self, purpose, text, attempts=0, oauth=True):
        with tempfile.TemporaryDirectory() as tmp:
            _write_minimal_routing(tmp, oauth=oauth)
            return gpf.decide(purpose, text, attempts=attempts, root=tmp)

    def test_first_timeout_auto_falls_back(self):
        d = self._flagfree_decide("embed", "Read timed out", attempts=0)
        self.assertEqual("timeout", d["reason"])
        self.assertEqual("fallback_to_api", d["action"])
        self.assertEqual("upstash_vector", d["nextRoute"]["id"])
        self.assertTrue(d["auto"])
        self.assertEqual("local_failover", d["apiSpend"]["why"])
        self.assertEqual(1, d["localRetryBudget"])  # optional single retry, not a loop

    def test_repeated_failure_leaves_no_retry_budget(self):
        d = self._flagfree_decide("llm", "Read timed out", attempts=1)
        self.assertEqual("fallback_to_api", d["action"])
        self.assertEqual(0, d["localRetryBudget"])
        self.assertEqual("groq", d["nextRoute"]["id"])

    def test_power_suspect_never_retries_local(self):
        d = self._flagfree_decide("embed", "hw_power_brake_slowdown_active",
                                  attempts=0)
        self.assertEqual("power_limit_suspect", d["reason"])
        self.assertEqual(0, d["localRetryBudget"])
        self.assertEqual("fallback_to_api", d["action"])

    def test_cable_trip_decide_never_retries_local(self):
        d = self._flagfree_decide(
            "llm", "hw_power_brake_slowdown_active cable trip", attempts=0)
        self.assertEqual("power_limit_suspect", d["reason"])
        self.assertEqual(0, d["localRetryBudget"])
        self.assertEqual("fallback_to_api", d["action"])
        self.assertEqual("groq", d["nextRoute"]["id"])
        self.assertTrue(d["auto"])

    def test_report_line_is_one_line_and_secret_free(self):
        d = self._flagfree_decide("llm", "connection refused")
        self.assertNotIn("\n", d["report"])
        self.assertNotRegex(d["report"], r"sk-|key=")


class IncidentTest(unittest.TestCase):
    """3090 사고: gpu_lost 텍스트 또는 활성 사고 플래그가 사고 트리거."""

    def test_gpu_lost_text_maps_to_new_reason(self):
        for text in ("GPU is lost. Reboot the system to recover this GPU",
                     "Unable to determine the device handle for GPU1",
                     "GPU has fallen off the bus"):
            self.assertEqual("gpu_lost",
                             gpf.classify_local_inference_failure(text))
        # 기존 매핑 보존: TDR/스로틀 텍스트는 gpu_lost가 아님
        self.assertEqual("driver_reset",
                         gpf.classify_local_inference_failure("nvlddmkm TDR"))
        self.assertEqual("power_limit_suspect",
                         gpf.classify_local_inference_failure("hw_power_brake"))

    def test_bare_gpu_lost_phrase_maps_gpu_lost(self):
        # "gpu lost" 짧은 구문도 소실 — driver_reset의 gpu.*lost보다 먼저 판정
        for text in ("gpu lost", "the GPU lost", "gpu  lost",
                     "nvidia gpu lost. check cables"):
            self.assertEqual("gpu_lost",
                             gpf.classify_local_inference_failure(text))
        # 소실과 무관한 lost는 그대로
        self.assertEqual("unknown",
                         gpf.classify_local_inference_failure("lost connection"))

    def test_llm_gpu_lost_routes_oauth_first(self):
        d = gpf.decide("llm", "Unable to determine the device handle: "
                              "GPU is lost", root=ROOT)
        self.assertEqual("gpu_lost", d["reason"])
        self.assertEqual("chatgpt_oauth", d["nextRoute"]["id"])
        self.assertEqual("subscription", d["nextRoute"]["tier"])
        self.assertEqual("groq", d["candidates"][1]["id"])  # 기존 순서 유지
        self.assertEqual(0, d["localRetryBudget"])
        self.assertTrue(d["incident"]["active"])
        self.assertEqual("fallback_to_api", d["action"])

    def test_llm_gpu_lost_without_oauth_in_ssot_keeps_groq(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write_minimal_routing(tmp, oauth=False)
            d = gpf.decide("llm", "GPU is lost", root=tmp)
        self.assertEqual("gpu_lost", d["reason"])
        self.assertEqual("groq", d["nextRoute"]["id"])

    def test_embed_incident_prefers_3060_lane(self):
        d = gpf.decide("embed", "GPU is lost", root=ROOT)
        self.assertEqual("ollama:11435", d["nextRoute"]["id"])
        self.assertEqual("lane_switch_then_api", d["action"])
        self.assertEqual("rtx3060", d["nextRoute"]["lane"])
        self.assertEqual("upstash_vector", d["candidates"][1]["id"])

    def test_from_incident_flag_drives_decision(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write_minimal_routing(tmp, oauth=True)
            _write_incident_flag(tmp, "GPU3090_LOST")
            d = gpf.decide("llm", "", from_incident=True, root=tmp)
            self.assertEqual("gpu_lost", d["reason"])
            self.assertEqual("chatgpt_oauth", d["nextRoute"]["id"])
            self.assertEqual("GPU3090_LOST", d["incident"]["state"])
            self.assertTrue(d["incident"]["active"])

    def test_from_incident_embed_uses_3060_then_api(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write_minimal_routing(tmp, oauth=True)
            _write_incident_flag(tmp, "GPU3090_LOST")
            d = gpf.decide("embed", "", from_incident=True, root=tmp)
            self.assertEqual("ollama:11435", d["nextRoute"]["id"])

    def test_from_incident_ok_flag_keeps_normal_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write_minimal_routing(tmp, oauth=True)
            _write_incident_flag(tmp, "OK")
            d = gpf.decide("llm", "", from_incident=True, root=tmp)
            self.assertEqual("groq", d["nextRoute"]["id"])
            self.assertFalse(d["incident"]["active"])
            self.assertEqual("OK", d["incident"]["state"])

    def test_oauth_disabled_env_falls_through_to_groq(self):
        with mock.patch.dict(os.environ, {"CHATGPT_OAUTH_ENABLED": "0"}):
            d = gpf.decide("llm", "GPU is lost", root=ROOT)
        self.assertEqual("groq", d["nextRoute"]["id"])

    def test_active_flag_auto_detected_without_from_incident(self):
        # var/incident/gpu.json 활성 → --from-incident 없이도 사고 판정
        with tempfile.TemporaryDirectory() as tmp:
            _write_minimal_routing(tmp, oauth=True)
            _write_incident_flag(tmp, "GPU3090_LOST")
            d = gpf.decide("llm", "Read timed out", root=tmp)
        self.assertEqual("gpu_lost", d["reason"])  # 순간신호는 소실로 대표
        self.assertEqual("chatgpt_oauth", d["nextRoute"]["id"])
        self.assertEqual(0, d["localRetryBudget"])  # 사고 중 로컬 재시도 0
        self.assertTrue(d["incident"]["active"])
        self.assertEqual("GPU3090_LOST", d["incident"]["state"])

    def test_active_flag_auto_detect_embed_uses_3060(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write_minimal_routing(tmp, oauth=True)
            _write_incident_flag(tmp, "GPU3090_LOST")
            d = gpf.decide("embed", "Read timed out", root=tmp)
        self.assertEqual("ollama:11435", d["nextRoute"]["id"])
        self.assertEqual("lane_switch_then_api", d["action"])
        self.assertEqual(0, d["localRetryBudget"])

    def test_active_flag_keeps_specific_reason(self):
        # 구체적 신호는 reason 유지, 라우팅만 사고 모드
        with tempfile.TemporaryDirectory() as tmp:
            _write_minimal_routing(tmp, oauth=True)
            _write_incident_flag(tmp, "GPU3090_DEGRADED")
            d = gpf.decide("llm", "hw_power_brake_slowdown_active", root=tmp)
        self.assertEqual("power_limit_suspect", d["reason"])
        self.assertEqual("chatgpt_oauth", d["nextRoute"]["id"])
        self.assertEqual(0, d["localRetryBudget"])
        self.assertTrue(d["incident"]["active"])

    def test_ok_flag_keeps_normal_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            _write_minimal_routing(tmp, oauth=True)
            _write_incident_flag(tmp, "OK")
            d = gpf.decide("llm", "Read timed out", root=tmp)
        self.assertEqual("groq", d["nextRoute"]["id"])
        self.assertEqual(1, d["localRetryBudget"])  # 정상 상태: 1회 허용
        self.assertFalse(d["incident"]["active"])

    def test_gpu_lost_text_without_flag_still_oauth(self):
        # 플래그 부재 + "gpu lost" 텍스트만으로도 사고 판정 (Acceptance 경로)
        with tempfile.TemporaryDirectory() as tmp:
            _write_minimal_routing(tmp, oauth=True)
            d = gpf.decide("llm", "gpu lost", root=tmp)
        self.assertEqual("gpu_lost", d["reason"])
        self.assertEqual("chatgpt_oauth", d["nextRoute"]["id"])
        self.assertEqual(0, d["localRetryBudget"])


TIER_ORDER_INDEX = {tier: i for i, tier in enumerate(gpf.TIER_ORDER)}


if __name__ == "__main__":
    unittest.main()
