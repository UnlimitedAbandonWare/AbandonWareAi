#!/usr/bin/env python3
"""Synthetic tests for settings_defaults_answer_grade.py - temp pack +
temp canary-sha fixture, right/wrong example answers."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "settings_defaults_answer_grade.py"
spec = importlib.util.spec_from_file_location("sd_answer_grade", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

CANARY_1 = "캐나리-값1"
CANARY_2 = "2031-02-03"

GOOD_QG = ("```java\nList<Integer> r = Arrays.stream(a).distinct().sorted()"
           ".boxed().toList();\n```\n결과는 [1, 2, 3] 입니다.")
BAD_QG_LIST = ("```java\n// code\n```\n결과는 [1, 2, 3, 4] 입니다.")
BAD_QG_NOCODE = "결과는 [1, 2, 3] 입니다."
BAD_QG_LONG = ("```\nc\n```\n[1,2,3]. " + "한 문장. " * 9)


def _pack(tmp: Path) -> Path:
    canary = {"canaries": [
        {"id": "c1", "sha256": hashlib.sha256(
            CANARY_1.encode("utf-8")).hexdigest()},
        {"id": "c2", "sha256": hashlib.sha256(
            CANARY_2.encode("utf-8")).hexdigest()}]}
    (tmp / "qr-canary.sha").write_text(json.dumps(canary), encoding="utf-8")
    pack = {"questions": {
        "QG": {"kind": "coding",
               "rules": {"expectedList": [1, 2, 3],
                         "requireCodeBlock": True, "maxSentences": 8}},
        "QR": {"kind": "rag-canary",
               "canaryShaFile": "qr-canary.sha",
               "expectedEvidence": "A_fixture.md"},
        "QW": {"kind": "web-fact", "expected": ["9.9.9", "2031"]},
        "QS": {"kind": "simple", "rules": {"exactSentences": 1}}}}
    p = tmp / "pack.json"
    p.write_text(json.dumps(pack), encoding="utf-8")
    return p


class AnswerGradeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.pack = _pack(Path(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def _g(self, q, answer):
        return mod.grade(q, answer, self.pack)

    def test_qg_good(self):
        r = self._g("QG", GOOD_QG)
        self.assertEqual("PASS", r["verdict"], r)

    def test_qg_wrong_list(self):
        r = self._g("QG", BAD_QG_LIST)
        self.assertEqual("FAIL", r["verdict"])
        self.assertIn("expected-list-[1,2,3]-absent", r["reasons"])

    def test_qg_missing_code_block(self):
        r = self._g("QG", BAD_QG_NOCODE)
        self.assertEqual("FAIL", r["verdict"])
        self.assertIn("code-block-absent", r["reasons"])

    def test_qg_too_many_sentences(self):
        r = self._g("QG", BAD_QG_LONG)
        self.assertEqual("FAIL", r["verdict"])
        self.assertTrue(any(s.startswith("sentences>") for s in r["reasons"]))

    def test_qr_canary_hash_match_and_evidence(self):
        answer = f"문서(A_fixture.md) 기준 답은 {CANARY_1} 이고 날짜는 {CANARY_2} 입니다."
        r = self._g("QR", answer)
        self.assertEqual("PASS", r["verdict"], r)
        self.assertEqual(2, r["canariesMatched"])

    def test_qr_canary_missing(self):
        r = self._g("QR", "아무 관련 없는 답변입니다. A_fixture.md")
        self.assertEqual("FAIL", r["verdict"])
        self.assertIn("canary-mismatch", r["reasons"])

    def test_qr_evidence_file_missing(self):
        answer = f"{CANARY_1} {CANARY_2}"
        r = self._g("QR", answer)
        self.assertEqual("FAIL", r["verdict"])
        self.assertIn("evidence-file-missing", r["reasons"])

    def test_qw_expected_substrings(self):
        self.assertEqual("PASS", self._g(
            "QW", "최신 버전은 9.9.9이며 2031년에 발표됐습니다.")["verdict"])
        r = self._g("QW", "버전은 9.9.8입니다.")
        self.assertEqual("FAIL", r["verdict"])

    def test_qs_exactly_one_sentence(self):
        self.assertEqual("PASS", self._g(
            "QS", "오늘도 멋진 하루 되세요!")["verdict"])
        self.assertEqual("FAIL", self._g(
            "QS", "힘내세요. 잘 될 겁니다!")["verdict"])

    def test_unknown_on_missing_answer(self):
        self.assertEqual("UNKNOWN", self._g("QG", "")["verdict"])
        self.assertEqual("UNKNOWN", self._g("QG", None)["verdict"])

    def test_unknown_on_missing_pack(self):
        r = mod.grade("QG", "x", Path(self.tmp.name) / "nope.json")
        self.assertEqual("UNKNOWN", r["verdict"])


if __name__ == "__main__":
    unittest.main()
