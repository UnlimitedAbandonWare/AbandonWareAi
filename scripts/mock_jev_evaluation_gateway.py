#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Jev Evaluation 오프라인 Mock 및 계약 검증 픽스처.

PASTE_DEVIN_ABLATION_DIAGNOSTICS_ASSIST_20261007 / WP2.
외부 API 호출 0 — 포트 바인딩 없이 내장 fixture로 Vercel AI Gateway
`/v1/evaluate` 공식 응답 구조를 검증한다 (U2-(a) JSON/CLI 모의 모드).

기능:
  --smoke                내장 fixture 전수 계약 검증 (exit 0 = PASS)
  --write-samples PATH   jev_evaluation_contract_samples.json 산출
  --list                 fixture id 목록
  --emit ID              단일 fixture JSON 출력
  --replay FILE          외부 JSON 응답을 현재 클라이언트 규칙으로 재판정

검증 규칙은 JevGatewayClient.java:60-99/:102-140/:144-197 을 Python으로
미러한다: model 일치(alias 허용), answers object, choice∈criteria,
probability ∈ [0,1] 유한, probability vs probabilities[choice] 불일치
>1e-6 이면 invalid, 비용은 legacy `gateway.cost`만 읽는 현재 구현과
공식 `providerMetadata.gateway.cost`를 구분한다. 비용 누락은 UNKNOWN이며
0원으로 보정하지 않는다.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "awx.jev-evaluation-contract-samples.v1"
TASK_ID = "devin-ablation-diagnostics-assist-458cfecf"
ENDPOINT = "POST https://ai-gateway.vercel.sh/v1/evaluate"
MODEL = "typesafe-ai/jev"
CRITERIA = ["RECENT_ONLY", "SCOPED_RAG", "WEB", "HYBRID", "CLARIFY"]

# ---------------------------------------------------------------- fixtures
# 각 sample: {id, primitive, note, request, response, expect}
#   expect.verdict: ok | invalid_response | wrong_model
#   expect.legacyCost / officialCost: 읽히는 문자열 or "UNKNOWN"
FIXTURES = [
    {
        "id": "choice_web_official_cost",
        "primitive": "choice",
        "note": "공식 경로 providerMetadata.gateway.cost — "
                "현재 Java cost()는 읽지 못해 UNKNOWN이 정상",
        "request": {
            "model": MODEL,
            "state": {"query": "최근 Brave 검색 결과가 비어 있는 이유는?",
                      "surface": "chat",
                      "baselineRoute": "SCOPED_RAG",
                      "externalDecisionAllowed": True},
            "questions": {"routeDecision": {
                "type": "choice",
                "instructions": "Classify the confirmed question's retrieval route.",
                "criteria": {c: c + " criteria" for c in CRITERIA}}},
            "providerOptions": {"gateway": {"only": ["typesafe-ai"]}},
        },
        "response": {
            "model": MODEL,
            "answers": {"routeDecision": {
                "type": "choice",
                "choice": "WEB",
                "probability": 0.83,
                "probabilities": {"RECENT_ONLY": 0.05, "SCOPED_RAG": 0.09,
                                  "WEB": 0.83, "HYBRID": 0.02,
                                  "CLARIFY": 0.01}}},
            "providerMetadata": {"gateway": {"cost": "0.0000042"}},
            "usage": {"inputTokens": 412, "outputTokens": 9},
        },
        "expect": {"verdict": "ok", "legacyCost": "UNKNOWN",
                   "officialCost": "0.0000042"},
    },
    {
        "id": "choice_web_legacy_cost",
        "primitive": "choice",
        "note": "레거시 경로 gateway.cost — 현재 Java cost()가 읽는 유일 경로",
        "request": {"$ref": "choice_web_official_cost.request"},
        "response": {
            "model": MODEL,
            "answers": {"routeDecision": {
                "type": "choice",
                "choice": "WEB",
                "probability": 0.83,
                "probabilities": {"RECENT_ONLY": 0.05, "SCOPED_RAG": 0.09,
                                  "WEB": 0.83, "HYBRID": 0.02,
                                  "CLARIFY": 0.01}}},
            "gateway": {"cost": "0.0000042"},
            "usage": {"inputTokens": 412, "outputTokens": 9},
        },
        "expect": {"verdict": "ok", "legacyCost": "0.0000042",
                   "officialCost": "UNKNOWN"},
    },
    {
        "id": "boolean_flag",
        "primitive": "boolean",
        "note": "boolean primitive — choice criteria와 다른 스키마",
        "request": {
            "model": MODEL,
            "state": {"query": "방금 답변에 출처가 있었나?", "surface": "chat"},
            "questions": {"needsRetrieval": {
                "type": "boolean",
                "instructions": "Does the question need retrieval?"}}},
        "response": {
            "model": MODEL,
            "answers": {"needsRetrieval": {
                "type": "boolean", "value": True, "probability": 0.91}},
            "providerMetadata": {"gateway": {"cost": "0.0000031"}},
        },
        "expect": {"verdict": "ok", "legacyCost": "UNKNOWN",
                   "officialCost": "0.0000031"},
    },
    {
        "id": "score_confidence_distinct",
        "primitive": "score",
        "note": "probability와 confidence는 다른 필드 — 선택 확률과 "
                "분포 집중도 통계를 혼동 금지",
        "request": {
            "model": MODEL,
            "state": {"query": "근거가 충분한가?", "surface": "chat"},
            "questions": {"evidenceQuality": {
                "type": "score", "min": 0, "max": 1,
                "instructions": "Rate evidence sufficiency."}}},
        "response": {
            "model": MODEL,
            "answers": {"evidenceQuality": {
                "type": "score", "score": 0.72,
                "probability": 0.64, "confidence": 0.58}},
            "providerMetadata": {"gateway": {"cost": "0.0000038"}},
        },
        "expect": {"verdict": "ok", "legacyCost": "UNKNOWN",
                   "officialCost": "0.0000038",
                   "confidenceIsNotProbability": True},
    },
    {
        "id": "cjk_failsoft_clarify",
        "primitive": "choice",
        "note": "CJK(한국어) 질의 fail-soft — CLARIFY + 낮은 확률이면 "
                "baseline 유지, 비용 누락은 UNKNOWN",
        "request": {
            "model": MODEL,
            "state": {"query": "지금 화면에 추적 패널이 안 보여요",
                      "surface": "chat",
                      "baselineRoute": "SCOPED_RAG"},
            "questions": {"routeDecision": {
                "type": "choice",
                "instructions": "Classify the confirmed question's retrieval route.",
                "criteria": {c: c + " criteria" for c in CRITERIA}}},
        },
        "response": {
            "model": MODEL,
            "answers": {"routeDecision": {
                "type": "choice",
                "choice": "CLARIFY",
                "probability": 0.41,
                "probabilities": {"RECENT_ONLY": 0.18, "SCOPED_RAG": 0.30,
                                  "WEB": 0.07, "HYBRID": 0.04,
                                  "CLARIFY": 0.41}}},
        },
        "expect": {"verdict": "ok", "legacyCost": "UNKNOWN",
                   "officialCost": "UNKNOWN",
                   "failSoft": True, "costRendered": "UNKNOWN"},
    },
    {
        "id": "cost_missing_is_unknown",
        "primitive": "choice",
        "note": "비용 필드 완전 누락 — 0원 보정 금지, UNKNOWN 표기",
        "request": {"$ref": "choice_web_official_cost.request"},
        "response": {
            "model": MODEL,
            "answers": {"routeDecision": {
                "type": "choice", "choice": "WEB", "probability": 0.83}},
        },
        "expect": {"verdict": "ok", "legacyCost": "UNKNOWN",
                   "officialCost": "UNKNOWN", "costRendered": "UNKNOWN"},
    },
    {
        "id": "invalid_choice_label",
        "primitive": "choice",
        "note": "criteria 밖 label — 클라이언트는 invalid_response 처리",
        "request": {"$ref": "choice_web_official_cost.request"},
        "response": {
            "model": MODEL,
            "answers": {"routeDecision": {
                "type": "choice", "choice": "MAGIC_ROUTE",
                "probability": 0.99}},
        },
        "expect": {"verdict": "invalid_response"},
    },
    {
        "id": "distribution_mismatch",
        "primitive": "choice",
        "note": "scalar probability와 분포 값 불일치 >1e-6 — invalid",
        "request": {"$ref": "choice_web_official_cost.request"},
        "response": {
            "model": MODEL,
            "answers": {"routeDecision": {
                "type": "choice",
                "choice": "WEB",
                "probability": 0.83,
                "probabilities": {"RECENT_ONLY": 0.05, "SCOPED_RAG": 0.09,
                                  "WEB": 0.50, "HYBRID": 0.02,
                                  "CLARIFY": 0.01}}},
        },
        "expect": {"verdict": "invalid_response"},
    },
    {
        "id": "unknown_model",
        "primitive": "choice",
        "note": "응답 model이 요청과 불일치 — wrong_model 판정",
        "request": {"$ref": "choice_web_official_cost.request"},
        "response": {
            "model": "other-provider/other-model",
            "answers": {"routeDecision": {
                "type": "choice", "choice": "WEB", "probability": 0.83}},
        },
        "expect": {"verdict": "wrong_model"},
    },
]


def _resolve_req(sample):
    req = sample.get("request") or {}
    if "$ref" in req:
        ref = req["$ref"].split(".")[0]
        for s in FIXTURES:
            if s["id"] == ref:
                return s["request"]
    return req


# ------------------------------------------------ Java 클라이언트 규칙 미러
def validate_response(node, requested_model, criteria):
    """JevGatewayClient.java:109-129/:159-184 규칙의 Python 미러."""
    if not isinstance(node, dict):
        return "invalid_response"
    reported = node.get("model") or ""
    if not reported:
        return "model_unverified"
    alias = requested_model.rsplit("/", 1)[-1]
    if reported.lower() != requested_model.lower() \
            and reported.lower() != alias.lower():
        return "wrong_model"
    answers = node.get("answers")
    if not isinstance(answers, dict):
        return "invalid_response"
    for qid, ans in answers.items():
        if not isinstance(ans, dict):
            return "invalid_response"
        if ans.get("type") == "choice" or "choice" in ans:
            choice = ans.get("choice")
            if choice not in criteria:
                return "invalid_response"
            if "probability" in ans:
                p = ans["probability"]
                if not isinstance(p, (int, float)) or not (0 <= p <= 1):
                    return "invalid_response"
                probs = ans.get("probabilities")
                if probs is not None:
                    if not isinstance(probs, dict):
                        return "invalid_response"
                    for k, v in probs.items():
                        if k not in criteria \
                                or not isinstance(v, (int, float)) \
                                or not (0 <= v <= 1):
                            return "invalid_response"
                    d = probs.get(choice)
                    if d is None or abs(float(p) - float(d)) > 1e-6:
                        return "invalid_response"
    return "ok"


def extract_cost(node, path):
    """path: 'legacy' = 현재 Java 구현, 'official' = 공식 문서 경로."""
    cur = node
    keys = ("gateway", "cost") if path == "legacy" \
        else ("providerMetadata", "gateway", "cost")
    for k in keys:
        if not isinstance(cur, dict) or k not in cur:
            return None
        cur = cur[k]
    if not isinstance(cur, str):
        return None
    try:
        from decimal import Decimal
        return cur if Decimal(cur) >= 0 else None
    except Exception:
        return None


def render_cost(node):
    """표시 계약: 읽을 수 있는 비용 문자열 or UNKNOWN (0원 보정 금지)."""
    return extract_cost(node, "official") \
        or extract_cost(node, "legacy") or "UNKNOWN"


def smoke():
    rows, failures = [], []
    for s in FIXTURES:
        req = _resolve_req(s)
        node = s["response"]
        exp = s.get("expect") or {}
        criteria = list(
            (req.get("questions") or {}).get("routeDecision", {})
            .get("criteria", {}).keys()) or CRITERIA
        got = validate_response(node, req.get("model", MODEL), criteria)
        legacy = extract_cost(node, "legacy")
        official = extract_cost(node, "official")
        rendered = render_cost(node)
        ok = True
        if "verdict" in exp and got != exp["verdict"]:
            ok = False
        if "legacyCost" in exp and (legacy or "UNKNOWN") != exp["legacyCost"]:
            ok = False
        if "officialCost" in exp and (official or "UNKNOWN") != exp["officialCost"]:
            ok = False
        if "costRendered" in exp and rendered != exp["costRendered"]:
            ok = False
        if exp.get("confidenceIsNotProbability"):
            ans = next(iter(node["answers"].values()))
            if not ("confidence" in ans and "probability" in ans
                    and ans["confidence"] != ans["probability"]):
                ok = False
        if exp.get("failSoft") and got != "ok":
            ok = False
        rows.append((s["id"], got,
                     legacy or "UNKNOWN", official or "UNKNOWN",
                     rendered, "PASS" if ok else "FAIL"))
        if not ok:
            failures.append(s["id"])
    print("| fixture | verdict | legacyCost | officialCost | rendered | 판정 |")
    print("|---|---|---|---|---|---|")
    for r in rows:
        print("| %s | %s | %s | %s | %s | %s |" % r)
    print()
    print("fixtures=%d pass=%d fail=%d" % (len(rows), len(rows) - len(failures),
                                           len(failures)))
    if failures:
        print("FAIL: " + ", ".join(failures))
        return 1
    print("SMOKE PASS — 외부 호출 0, 비용 누락=UNKNOWN 계약 확인")
    return 0


def samples_doc():
    return {
        "schemaVersion": SCHEMA,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "generatedBy": TASK_ID,
        "officialEndpoint": ENDPOINT,
        "officialModel": MODEL,
        "officialCostPath": "providerMetadata.gateway.cost",
        "legacyCostPath": "gateway.cost",
        "note": "오프라인 fixture — 실제 dispatch/billing/CJK 품질 증명 아님. "
                "비용 누락은 UNKNOWN이며 0원 보정 금지. probability와 "
                "confidence는 별개 필드.",
        "samples": FIXTURES,
    }


def main(argv=None):
    for _s in (sys.stdout, sys.stderr):  # cp949 콘솔에서도 출력이 죽지 않게
        try:
            _s.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--smoke", action="store_true")
    ap.add_argument("--write-samples", metavar="PATH")
    ap.add_argument("--list", action="store_true", dest="list_ids")
    ap.add_argument("--emit", metavar="ID")
    ap.add_argument("--replay", metavar="FILE",
                    help="외부 JSON 응답 파일을 현재 규칙으로 재판정")
    args = ap.parse_args(argv)

    if args.list_ids:
        for s in FIXTURES:
            print("%s  (%s)" % (s["id"], s["primitive"]))
        return 0
    if args.emit:
        for s in FIXTURES:
            if s["id"] == args.emit:
                print(json.dumps(s, ensure_ascii=False, indent=2))
                return 0
        print("unknown fixture: %s" % args.emit, file=sys.stderr)
        return 2
    if args.write_samples:
        out = Path(args.write_samples)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(
            json.dumps(samples_doc(), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8")
        print(json.dumps({"samples": str(out), "count": len(FIXTURES)},
                         ensure_ascii=False))
        return 0
    if args.replay:
        node = json.loads(Path(args.replay).read_text(encoding="utf-8"))
        verdict = validate_response(node, MODEL, CRITERIA)
        print(json.dumps({
            "verdict": verdict,
            "legacyCost": extract_cost(node, "legacy") or "UNKNOWN",
            "officialCost": extract_cost(node, "official") or "UNKNOWN",
            "rendered": render_cost(node)}, ensure_ascii=False))
        return 0 if verdict == "ok" else 1
    if args.smoke or len(sys.argv) == 1:
        return smoke()
    return 0


if __name__ == "__main__":
    sys.exit(main())
