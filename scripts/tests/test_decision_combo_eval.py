"""Offline tests for decision_combo_eval — no network, recorded transports only.

Covered:
  - rules-floor precedence (prohibition + arithmetic force search=NONE)
  - Jev defer -> LLM escalation
  - low LLM confidence -> HYBRID + ANSWER_HEDGED default
  - no unnecessary LLM call when Jev is confident and agrees with rules
  - no retry on 401/403/429 (permanent disable, single send)
  - malformed provider JSON fails open safely
  - metrics math (accuracy, under/over, hold_over, p95)
"""
from __future__ import annotations

import json
import unittest
from pathlib import Path

from scripts import decision_combo_eval as ev

FLOOR = ("search_prohibition", "self_contained_arithmetic")
DEPTH_MAP = {"false": "NONE", "LIGHT": "WEB", "DEEP": "HYBRID"}
COMBO = {"confidence_floor": 0.55,
         "default_on_low_confidence": {"search": "HYBRID",
                                       "hold": "ANSWER_HEDGED"},
         "hold_requires_contradiction": True}


def _case(q, ctx=None, cid="x1"):
    return {"id": cid, "question": q, "context": ctx}


def _jev_ok(search="WEB", hold="ANSWER", ms=10.0):
    return {"ok": True, "defer": False, "reason": None, "ms": ms,
            "search": search, "search_p": 0.9, "hold": hold, "cost": 1.9e-05}


def _llm_ok(search="WEB", hold="ANSWER", conf=0.9, contra=False, ms=5.0):
    return {"ok": True, "search": search, "hold": hold, "confidence": conf,
            "contradiction": contra, "reason": "t", "ms": ms,
            "provider": "fake", "model": "fake-1"}


class FakeJev(ev.JevStage):
    """Jev stage with a scripted transport; bypasses the ledger gate."""

    def __init__(self, responses):
        super().__init__({"model": "typesafe-ai/jev"}, transport=object())
        self.responses = list(responses)
        self._gate_patched = True

    def _send(self, body):  # no ledger in tests
        if self.disabled_reason:
            return {"ok": False, "defer": True,
                    "reason": self.disabled_reason, "ms": 0.0}
        res = self.responses.pop(0) if self.responses else \
            {"ok": False, "defer": True, "reason": "exhausted", "ms": 0.0}
        self.sends += 1
        if res.get("no_retry"):
            self.disabled_reason = res["reason"]
        return res


class FakeLlm(ev.LlmJudge):
    def __init__(self, responses, cap=40):
        super().__init__({}, transport=object(), cap=cap)
        self.responses = list(responses)

    def judge(self, case, rules_label, jev_label, recorder=None):
        if case["id"] in self._cache:
            return self._cache[case["id"]]
        if self.disabled_reason:
            res = {"ok": False, "reason": self.disabled_reason, "ms": 0.0}
        elif self.sends >= self.cap:
            res = {"ok": False, "reason": "call-cap", "ms": 0.0}
        elif self.transport is None:
            res = {"ok": False, "reason": "offline-no-recording", "ms": 0.0}
        else:
            res = self.responses.pop(0) if self.responses else \
                {"ok": False, "reason": "exhausted", "ms": 0.0}
            self.sends += 1
            if res.get("no_retry"):
                self.disabled_reason = res["reason"]
        self._cache[case["id"]] = res
        return res


def _combo(case, jev, llm):
    return ev.decide_case(case, "combo", ev.RulesBaseline(), FLOOR,
                          DEPTH_MAP, "ANSWER", jev, llm, COMBO, None)


class TestRulesPort(unittest.TestCase):
    def setUp(self):
        self.rules = ev.RulesBaseline()

    def test_arithmetic_no_search(self):
        searched, depth, _ = self.rules.decide("12345 + 67890은 얼마야?")
        self.assertFalse(searched)

    def test_explicit_search_command(self):
        searched, depth, _ = self.rules.decide("최신 툴 비교를 웹 검색해줘")
        self.assertTrue(searched)

    def test_recency_light(self):
        searched, depth, _ = self.rules.decide("최근 뉴스 알려줘")
        self.assertTrue(searched)
        self.assertEqual(depth, "LIGHT")

    def test_local_update_not_recency(self):
        searched, _, _ = self.rules.decide("please update my profile")
        self.assertFalse(searched)

    def test_greeting_no_search(self):
        searched, _, _ = self.rules.decide("안녕하세요!")
        self.assertFalse(searched)


class TestFloor(unittest.TestCase):
    def test_prohibition_ko(self):
        self.assertEqual(ev.floor_check("판옥선 설명해줘. 추가 검색은 하지 마."),
                         "search_prohibition")

    def test_prohibition_en(self):
        self.assertEqual(ev.floor_check("Do not search the web. Draft it."),
                         "search_prohibition")

    def test_search_command_not_prohibition(self):
        self.assertIsNone(ev.floor_check("웹 검색해줘: 최신 툴 목록"))

    def test_arithmetic(self):
        self.assertEqual(ev.floor_check("100의 15%는 얼마야?"),
                         "self_contained_arithmetic")

    def test_not_arithmetic(self):
        self.assertIsNone(ev.floor_check("파리의 인구는?"))


class TestComboPipeline(unittest.TestCase):
    def test_floor_precedence_no_jev_needed_for_search(self):
        pred = _combo(_case("설명해줘. 추가 검색은 하지 마."),
                      FakeJev([_jev_ok("WEB")]), FakeLlm([]))
        self.assertEqual(pred["search"], "NONE")
        self.assertTrue(pred["floor_confirmed"])

    def test_jev_defer_escalates_to_llm(self):
        jev = FakeJev([{"ok": False, "defer": True, "reason": "timeout",
                        "ms": 3.0}])
        llm = FakeLlm([_llm_ok("WEB", "ANSWER", 0.9)])
        pred = _combo(_case("최신 Node.js LTS?"), jev, llm)
        self.assertTrue(pred["escalated"])
        self.assertEqual(pred["search"], "WEB")

    def test_jev_clarify_escalates(self):
        jev = FakeJev([_jev_ok("CLARIFY", "ANSWER")])
        llm = FakeLlm([_llm_ok("NONE", "ANSWER", 0.9)])
        pred = _combo(_case("그거 어떻게 생각해?"), jev, llm)
        self.assertTrue(pred["escalated"])

    def test_disagreement_escalates(self):
        # rules: greeting -> NONE; jev says WEB -> referee
        jev = FakeJev([_jev_ok("WEB", "ANSWER")])
        llm = FakeLlm([_llm_ok("NONE", "ANSWER", 0.9)])
        pred = _combo(_case("안녕하세요"), jev, llm)
        self.assertTrue(pred["escalated"])
        self.assertEqual(pred["search"], "NONE")

    def test_low_confidence_defaults(self):
        jev = FakeJev([_jev_ok("CLARIFY", "ANSWER")])
        llm = FakeLlm([_llm_ok("NONE", "ANSWER", conf=0.3)])
        pred = _combo(_case("요즘 어때?"), jev, llm)
        self.assertEqual(pred["search"], "HYBRID")
        self.assertEqual(pred["hold"], "ANSWER_HEDGED")

    def test_confident_jev_no_llm_call(self):
        jev = FakeJev([_jev_ok("WEB", "ANSWER")])
        llm = FakeLlm([])
        # rules also says WEB (recency) -> no disagreement -> no escalate
        pred = _combo(_case("최근 뉴스 알려줘"), jev, llm)
        self.assertFalse(pred["escalated"])
        self.assertIsNone(pred["llm"])
        self.assertEqual(llm.sends, 0)

    def test_hold_demoted_without_contradiction(self):
        jev = FakeJev([_jev_ok("CLARIFY", "ANSWER")])
        llm = FakeLlm([_llm_ok("WEB", "HOLD", conf=0.9, contra=False)])
        pred = _combo(_case("무언가"), jev, llm)
        self.assertEqual(pred["hold"], "ANSWER_HEDGED")

    def test_hold_kept_with_contradiction(self):
        jev = FakeJev([_jev_ok("CLARIFY", "ANSWER")])
        llm = FakeLlm([_llm_ok("WEB", "HOLD", conf=0.9, contra=True)])
        pred = _combo(_case("모순 확인"), jev, llm)
        self.assertEqual(pred["hold"], "HOLD")

    def test_all_deferred_defaults(self):
        jev = FakeJev([{"ok": False, "defer": True, "reason": "timeout",
                        "ms": 1.0}])
        llm = FakeLlm([{"ok": False, "reason": "offline-no-recording",
                        "ms": 0.0}])
        pred = _combo(_case("최신 뉴스?"), jev, llm)
        self.assertEqual(pred["search"], "HYBRID")
        self.assertEqual(pred["hold"], "ANSWER_HEDGED")


class TestNoRetry(unittest.TestCase):
    def test_jev_403_disables_no_retry(self):
        jev = FakeJev([
            {"ok": False, "defer": True, "reason": "http-403-no-retry",
             "ms": 5.0, "no_retry": True}])
        jev._send({})                      # first send -> disables
        self.assertIsNotNone(jev.disabled_reason)
        # second call must NOT send
        res = jev.evaluate(_case("q2", cid="y2"), "NONE")
        self.assertEqual(jev.sends, 1)
        self.assertTrue(res["defer"])

    def test_llm_429_disables_no_retry(self):
        llm = FakeLlm([{"ok": False, "reason": "http-429-no-retry",
                        "ms": 2.0, "no_retry": True}])
        llm.judge(_case("a", cid="z1"), "NONE", None)
        self.assertEqual(llm.sends, 1)
        res = llm.judge(_case("b", cid="z2"), "NONE", None)
        self.assertEqual(llm.sends, 1)
        self.assertFalse(res["ok"])


class TestParsers(unittest.TestCase):
    def test_jev_parse_ok(self):
        payload = {"model": "typesafe-ai/jev", "answers": {
            "searchRoute": {"choice": "web",
                            "probabilities": {"WEB": 0.8}},
            "releaseGate": {"choice": "ANSWER"}}}
        out, err = ev.parse_jev_response(payload)
        self.assertIsNone(err)
        self.assertEqual(out["searchRoute"]["choice"], "WEB")
        self.assertEqual(out["releaseGate"]["choice"], "ANSWER")

    def test_jev_parse_missing_question(self):
        out, err = ev.parse_jev_response({"answers": {}})
        self.assertIsNone(out)
        self.assertEqual(err, "invalid-response")

    def test_jev_parse_bad_choice(self):
        out, err = ev.parse_jev_response({"answers": {
            "searchRoute": {"choice": "MAYBE"},
            "releaseGate": {"choice": "ANSWER"}}})
        self.assertIsNone(out)

    def test_llm_parse_wrapped_json(self):
        obj = ev.parse_llm_json(
            'sure! {"search":"NONE","hold":"ANSWER","confidence":0.9}')
        self.assertEqual(obj["search"], "NONE")

    def test_llm_parse_bad_label_fails_open(self):
        self.assertIsNone(ev.parse_llm_json('{"search":"MAGIC","hold":"ANSWER"}'))

    def test_llm_parse_garbage(self):
        self.assertIsNone(ev.parse_llm_json("not json at all"))


class TestMetrics(unittest.TestCase):
    def test_summarize_counts(self):
        cases = {
            "a": {"expect": {"search": "WEB", "hold": "ANSWER"}},
            "b": {"expect": {"search": "NONE", "hold": "ANSWER"}},
            "c": {"expect": {"search": "WEB", "hold": "ANSWER"}},
            "d": {"expect": {"search": "NONE", "hold": "HOLD"}},
        }
        recs = [
            {"id": "a", "pred": {"search": "WEB", "hold": "ANSWER", "ms": 10}},
            {"id": "b", "pred": {"search": "WEB", "hold": "ANSWER", "ms": 20}},
            {"id": "c", "pred": {"search": "NONE", "hold": "ANSWER", "ms": 30}},
            {"id": "d", "pred": {"search": "NONE", "hold": "ANSWER", "ms": 40}},
        ]
        s = ev.summarize(recs, cases, 0.000019)
        self.assertEqual(s["search_acc"], 0.5)          # a,d correct
        self.assertEqual(s["hold_over"], 0)             # no HOLD predicted
        self.assertEqual(s["search_over"], 1)           # b over
        self.assertEqual(s["search_under"], 1)          # c under
        self.assertAlmostEqual(s["ms_mean"], 25.0)
        self.assertEqual(s["ms_p95"], 40)


class TestOfflineReplay(unittest.TestCase):
    def test_offline_recorder_replays(self):
        rec_dir = {"jev": _jev_ok("WEB", "ANSWER_HEDGED")}
        jev = ev.JevStage({"model": "m"}, transport=None)
        out = jev.evaluate(_case("q", cid="zz"), "NONE", recorder=rec_dir)
        self.assertTrue(out["ok"])
        self.assertEqual(out["search"], "WEB")

    def test_offline_missing_recording_defers(self):
        jev = ev.JevStage({"model": "m"}, transport=None)
        out = jev.evaluate(_case("q", cid="zz"), "NONE", recorder={})
        self.assertTrue(out["defer"])
        self.assertEqual(out["reason"], "offline-no-recording")


class TestLoadCases(unittest.TestCase):
    def test_real_cases_file_loads(self):
        p = Path(ev.ROOT) / "data" / "eval" / "decision-combo" / "cases.jsonl"
        if not p.exists():
            self.skipTest("cases file absent")
        cases = ev.load_cases(str(p))
        self.assertGreaterEqual(len(cases), 40)


if __name__ == "__main__":
    unittest.main()
