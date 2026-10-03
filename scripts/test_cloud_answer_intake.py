#!/usr/bin/env python3
"""Focused tests for scripts/cloud_answer_intake.py — small fixtures only,
no repo product source, no network. Run: pytest scripts/test_cloud_answer_intake.py -q
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import cloud_answer_intake as cai  # noqa: E402


ANSWER_FIXTURE = """**(AbandonWare), 첫 번째 주제입니다.**
## 전달 파일
| f | d |
|---|---|
새 ZIP 기준 sampling 얘기.
```text
main/java/com/example/lms/api/Merger.java
  merge(): 10–20, 30–40
```
`Merger.java:50–60` 도 봐야 한다.
데모 스냅샷 demo1_core_20261003-1503_4150b28-dirty.zip 기준.
결론.**확신:** 높음.| 입력 | 확인 상태 | 기준 |
|---|---|---|
| demo1_core zip | 열림 | 15:03 |
**(AbandonWare), 두 번째 주제입니다.**
## 1. 역할 분리
에이전트 파티 얘기. 전부 NOT_RUN.
결정요인: 단일 책임.**확신:** 높음.| 근거 | 확인 범위 | 기준 |
|---|---|---|
| 소스 ZIP | 일부 | 15:03 |
**(AbandonWare), 세 번째 주제입니다.**
## 1. Display 설계
GraphRAG와 Gemini 검색. sandbox:/mnt/data/pack_a/x.md 참고.
"""


def test_split_three_segments():
    segs = cai.split_segments(ANSWER_FIXTURE)
    assert len(segs) == 3
    assert segs[0]["id"] == "A" and segs[1]["id"] == "B" and segs[2]["id"] == "C"
    assert segs[0]["lineStart"] == 1
    # shared boundary line: B starts on the line that holds A's trailer table
    assert segs[1]["lineStart"] == segs[0]["lineEnd"]
    assert segs[2]["lineEnd"] == len(ANSWER_FIXTURE.splitlines())
    assert "demo1_core_20261003-1503_4150b28-dirty.zip" in segs[0]["snapshots"]


def test_claim_extraction_forms():
    body = """```text
main/java/com/example/lms/api/Merger.java
  merge(): 10–20, 30–40
```
`Merger.java:50–60` 참고. ChatApiController.java:4798–4806 호출.
Foo.java 5~8행 도 확인.
"""
    claims = cai.extract_claims(body, 1)
    by = {(c["path"], c["start"], c["end"]): c for c in claims}
    assert ("main/java/com/example/lms/api/Merger.java", 10, 20) in by
    assert ("main/java/com/example/lms/api/Merger.java", 30, 40) in by
    assert any(c["symbol"] == "merge" for c in claims)
    assert ("Merger.java", 50, 60) in by
    assert ("ChatApiController.java", 4798, 4806) in by
    assert ("Foo.java", 5, 8) in by


def _mk_tree(tmp_path: Path) -> Path:
    src = tmp_path / "main" / "java" / "com" / "example"
    src.mkdir(parents=True)
    lines = ["package com.example;", ""] + [
        f"// filler {i}" for i in range(88)]
    lines += [
        "public class Merger {",
        "    double merge() {",
        "        return ModelCapabilities.sanitizeTemperature(m, t) + 0.3;",
        "    }",
        "}",
    ]
    (src / "Merger.java").write_text("\n".join(lines), encoding="utf-8")
    (src / "Ghost.java").write_text("class Ghost {}\n", encoding="utf-8")
    return tmp_path


def test_judge_live_ok_and_reanchored(tmp_path):
    root = _mk_tree(tmp_path)
    seg = 'main/java/com/example/Merger.java 의 `merge()`는 보정.\n```java\ndouble x = ModelCapabilities.sanitizeTemperature(m, t);\n```'
    ok_claim = {"line": 1, "path": "main/java/com/example/Merger.java",
                "symbol": "merge", "start": 80, "end": 95, "raw": "x"}
    toks = cai._evidence_tokens(seg.splitlines(), ok_claim, 1)
    v = cai.judge_claim(root, ok_claim, toks)
    assert v["verdict"] in ("LIVE_OK", "REANCHORED")
    drift_claim = {"line": 1, "path": "main/java/com/example/Merger.java",
                   "symbol": "merge", "start": 10, "end": 15, "raw": "x"}
    toks = cai._evidence_tokens(seg.splitlines(), drift_claim, 1)
    v = cai.judge_claim(root, drift_claim, toks)
    assert v["verdict"] == "REANCHORED"
    assert v["liveLine"] and v["liveLine"] != 10


def test_judge_refuted_and_unverifiable(tmp_path):
    root = _mk_tree(tmp_path)
    claim = {"line": 1, "path": "main/java/com/example/Merger.java",
             "symbol": "noSuchMethod", "start": 90, "end": 95, "raw": "x"}
    v = cai.judge_claim(root, claim, {"strong": ["noSuchMethod"], "weak": []})
    assert v["verdict"] == "REFUTED"
    tclaim = {"line": 1, "path": "src/test/java/FooTest.java",
              "symbol": None, "start": 1, "end": 5, "raw": "x"}
    v = cai.judge_claim(root, tclaim, {"strong": ["t"], "weak": []})
    assert v["verdict"] == "UNVERIFIABLE"
    aclaim = {"line": 1, "path": "app/build.gradle.kts",
              "symbol": None, "start": 1, "end": 5, "raw": "x"}
    v = cai.judge_claim(root, aclaim, {"strong": ["t"], "weak": []})
    assert v["verdict"] == "UNVERIFIABLE"


def test_sandbox_links():
    text = "see [a](sandbox:/mnt/data/pack/x.md) and sandbox:/mnt/data/y_bundle.zip plus sandbox:/mnt/data/pack/x.md again"
    links = cai.extract_sandbox_links(text)
    assert links == ["pack/x.md", "y_bundle.zip"]


def test_scope_widen_and_brief_parse():
    brief = ("수정 허용: 제품 파일 3개(OpenAiSamplingContract.java, "
             "DynamicChatModelFactory.java, LlmRouterAspect.java), 테스트 파일 "
             "OpenAiSamplingContractTest.java / *SdkBodyContractTest.java.\n"
             "변경 금지: ModelCapabilities.java, ChatWorkflow.java, chat.js.")
    scope = cai.parse_brief_scope(brief)
    assert "OpenAiSamplingContract.java" in scope["allowed"]
    assert "ModelCapabilities.java" in scope["forbidden"]
    targets = ["OpenAiSamplingContract.java", "DynamicChatModelFactory.java",
               "ModelCapabilities.java", "ChatRequestSettingsMerger.java",
               "OpenAiSamplingHttpClientBuilder.java"]
    widen = cai.scope_widen(targets, scope["allowed"], scope["forbidden"])
    names = {w["file"] for w in widen}
    assert names == {"ModelCapabilities.java", "ChatRequestSettingsMerger.java",
                     "OpenAiSamplingHttpClientBuilder.java"}
    forbidden_hit = {w["file"] for w in widen if w["inBriefForbidden"]}
    assert "ModelCapabilities.java" in forbidden_hit


def test_answer_target_table():
    seg = ("| 제품 파일 | 맡길 수정 |\n|---|---|\n"
           "| `ModelCapabilities.java` | 정책 추가 |\n"
           "| 신규 `OpenAiSamplingHttpClientBuilder.java` | 키 제거 |\n"
           "분석 표: | `ChatApiController.java:4798–4806` | 호출 |")
    targets = cai.extract_answer_targets(seg)
    assert "ModelCapabilities.java" in targets
    assert "OpenAiSamplingHttpClientBuilder.java" in targets


def test_cloud_claims_vs_notrun():
    text = ("모든 테스트가 통과했습니다.\n"
            "실제 빌드·실행은 수행하지 않았습니다.\n"
            "NOT_RUN: 라이브 검증.\n")
    d = cai.detect_cloud_claims(text)
    assert d["cloudClaimCount"] >= 1
    assert d["notRunCount"] >= 2
