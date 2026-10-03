#!/usr/bin/env python3
"""Tests for agy_web_fuse.py — fixtures only, no network, no ~/.gemini access.

Contract: DEMO1-DEVIN-AGY-UAW-WEB-OPTIMIZE-20261002 (DV5 items 1-10).
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import time
import unittest
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FUSE = ROOT / "scripts" / "agy_web_fuse.py"
PY = sys.executable

sys.path.insert(0, str(ROOT / "scripts"))
import agy_web_fuse as fuse_mod  # noqa: E402


def item(url, title="t", snippet="s", query_id="q1", rank=1,
         date=None, body=False):
    it = {"url": url, "title": title, "snippet": snippet,
          "query_id": query_id, "rank": rank}
    if date:
        it["date"] = date
    if body:
        it["body_checked"] = True
    return it


def fuse(payload):
    return fuse_mod.fuse(payload, ROOT)


class FuseTests(unittest.TestCase):

    def test_1_url_normalization_merges_www_utm(self):
        out = fuse({"plate": "W3_TECH", "items": [
            item("https://www.docs.python.org/3/library", query_id="q1", rank=2),
            item("https://docs.python.org/3/library?utm_source=x&utm_medium=y#frag",
                 query_id="q2", rank=1),
            item("http://docs.python.org/3/library/", query_id="q3", rank=4),
        ]})
        self.assertEqual(1, len(out["ranked"]))
        self.assertEqual(3, len(out["ranked"][0]["queries"]))

    def test_2_duplicate_articles_one_group_rep_highest_tier(self):
        out = fuse({"plate": "W2_FRESH", "items": [
            item("https://blog.naver.com/a/1",
                 title="OpenAI launches dot usage for ChatGPT",
                 snippet="OpenAI launches dot usage for ChatGPT users",
                 query_id="q1", rank=1),
            item("https://tistory.com/b/2",
                 title="OpenAI launches dot usage for ChatGPT (repost)",
                 snippet="OpenAI launches dot usage for ChatGPT users",
                 query_id="q1", rank=2),
            item("https://openai.com/news/dot",
                 title="OpenAI launches dot usage for ChatGPT",
                 snippet="OpenAI launches dot usage for ChatGPT users today",
                 query_id="q2", rank=1, body=True),
        ]})
        groups = {r["dup_group"] for r in out["ranked"]}
        self.assertEqual(1, len(groups))
        self.assertEqual(1, len(out["ranked"]))
        self.assertEqual("T1", out["ranked"][0]["tier"])
        self.assertIn("openai.com", out["ranked"][0]["url"])

    def test_3_t1_beats_t4_pack_and_gate_passes(self):
        items = [item("https://openai.com/x", title="official announcement",
                      query_id="q1", rank=4)]
        items += [item("https://medium.com/u%d" % i, title="blog %d" % i,
                       snippet="take %d" % i, query_id="q1", rank=i)
                  for i in range(1, 6)]
        out = fuse({"plate": "W3_TECH", "items": items})
        self.assertEqual("T1", out["ranked"][0]["tier"])
        self.assertTrue(out["gate"]["pass"])

    def test_4_only_t4_low_authority_fails_with_site_hint(self):
        out = fuse({"plate": "W1_AUTH", "items": [
            item("https://medium.com/a%d" % i, title="post%d" % i,
                 snippet="s%d" % i, query_id="q1", rank=i)
            for i in range(1, 5)]})
        self.assertIn("LOW_AUTHORITY", out["gate"]["flags"])
        self.assertFalse(out["gate"]["pass"])
        self.assertTrue(any("site:" in h for h in out["gate"]["next_query_hints"]))

    def test_5_sparse_single_source_w1(self):
        out = fuse({"plate": "W1_AUTH", "items": [
            item("https://openai.com/x", query_id="q1", rank=1)]})
        self.assertIn("SPARSE", out["gate"]["flags"])
        self.assertFalse(out["gate"]["pass"])

    def test_6_conflict_suspect_on_number_or_year_drift(self):
        out = fuse({"plate": "W1_AUTH", "items": [
            item("https://help.openai.com/a", title="dot limit",
                 snippet="무료 한도 월 40회 (2025)", query_id="q1", rank=1),
            item("https://openai.com/b", title="dot limit",
                 snippet="확장 한도 월 200회 (2026)", query_id="q1", rank=2),
        ]})
        self.assertIn("CONFLICT_SUSPECT", out["gate"]["flags"])

    def test_7_w2_stale_marks_and_ranks_below_fresh(self):
        today = date.today()
        old = (today - timedelta(days=400)).isoformat()
        fresh = (today - timedelta(days=10)).isoformat()
        out = fuse({"plate": "W2_FRESH", "items": [
            item("https://openai.com/old", title="Python 3.12 release notes",
                 snippet="release notes for the older interpreter",
                 query_id="q1", rank=1, date=old),
            item("https://openai.com/new", title="GPU driver changelog",
                 snippet="changelog for the newest graphics driver",
                 query_id="q1", rank=2, date=fresh),
        ]})
        by_url = {r["url"].rstrip("/").split("/")[-1]: r for r in out["ranked"]}
        self.assertTrue(by_url["old"]["stale"])
        self.assertFalse(by_url["new"]["stale"])
        self.assertLess(by_url["old"]["score"], by_url["new"]["score"])

    def test_8_rrf_overlap_beats_single_query_rank1(self):
        out = fuse({"plate": "W3_TECH", "items": [
            item("https://docs.python.org/x", title="shared",
                 snippet="s", query_id="q1", rank=3),
            item("https://docs.python.org/x?utm_source=a", title="shared",
                 snippet="s", query_id="q2", rank=3),
            item("https://medium.com/top", title="solo",
                 snippet="s", query_id="q1", rank=1),
        ]})
        self.assertIn("docs.python.org", out["ranked"][0]["url"])

    def test_9_perf_1000_items_under_1s_utf8(self):
        items = [item("https://example.com/p%d" % i,
                      title="한글 제목 %d" % i, snippet="본문 %d" % i,
                      query_id="q%d" % (i % 7), rank=(i % 10) + 1)
                 for i in range(1000)]
        start = time.monotonic()
        out = fuse({"plate": "W3_TECH", "question": "한국어 질문", "items": items})
        elapsed = time.monotonic() - start
        self.assertLess(elapsed, 1.0)
        text = json.dumps(out, ensure_ascii=False)
        self.assertIn("한국어", text)
        self.assertIn("웹:", out["breadcrumb"])

    def test_10_breadcrumb_format(self):
        out = fuse({"plate": "W9_LITE", "items": [
            item("https://openai.com/x", query_id="q1", rank=1, body=True)]})
        m = re.fullmatch(
            r"웹: W9_LITE · 검색 1회 · 본문확인 1 · 출처 \d+\(T1 \d+\) · 모순 (있음|없음)",
            out["breadcrumb"])
        self.assertIsNotNone(m, out["breadcrumb"])


class CliTests(unittest.TestCase):

    def test_cli_stdin_json_and_md(self):
        payload = json.dumps({"plate": "W9_LITE", "items": [
            item("https://openai.com/x", query_id="q1", rank=1)]},
            ensure_ascii=False).encode("utf-8")
        out = subprocess.run([PY, "-B", str(FUSE)], input=payload,
                             capture_output=True, timeout=20)
        self.assertEqual(0, out.returncode)
        body = json.loads(out.stdout.decode("utf-8"))
        self.assertIn("breadcrumb", body)
        out2 = subprocess.run([PY, "-B", str(FUSE), "--format", "md"],
                              input=payload, capture_output=True, timeout=20)
        self.assertEqual(0, out2.returncode)
        self.assertIn("gate: pass=", out2.stdout.decode("utf-8"))

    def test_cli_bad_input_fails_soft(self):
        out = subprocess.run([PY, "-B", str(FUSE)], input=b"{oops",
                             capture_output=True, timeout=20)
        self.assertEqual(0, out.returncode)
        body = json.loads(out.stdout.decode("utf-8"))
        self.assertIn("INPUT_ERROR", body["gate"]["flags"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
