#!/usr/bin/env python3
"""f01b_admission_key_demo.py — F01-B idempotency 키 캐논 해시 헬퍼 (테스트·문서용).

Contract DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929 §4.3 +
Codex DEMO1-CODEX-F01B-NARROW-JDBC-UNDERSTANDING-20260929 §7.

키 정합 (두 키를 혼동하지 않음):
  - admission_key       = SHA-256(canonical(originalRunId + source scope inputs))
                          — 같은 논리 요청 재생(replay) 판별
  - effect_key          = SHA-256(ownerNamespace, sessionId, channel, consentEpoch,
                          userMessageId, userRevision, assistantMessageId,
                          assistantRevision, kind) — 새 task UUID 로 USUM 재기록 차단
  - request_fingerprint = 승인 payload 전체 fingerprint — 같은 admission_key에
                          payload가 바뀌면 conflict (조용한 덮어쓰기 금지)

직렬화: 버전 명시 JSON — {"v": "<도메인 버전>", "kind": ..., "fields": {...}}
를 sort_keys + compact separator 로 dumps 후 UTF-8 SHA-256.
모호한 문자열 concat 금지.

fixture 에 password/token/cookie 등 시크릿 키·토큰형 값이 있으면 exit 4.
제품 enqueue 호출 없음 (순수 해시 데모).

exit 0 OK / 1 usage·IO / 4 safety abort.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import sys

CONTRACT_ID = "DEMO1-DEVIN-SCRIPTS-F01B-TRACE-ACCESS-20260929"
SCHEMA = "awx.f01b-admission-key-demo.v1"
DEFAULT_OUT_DIR = "data/diagnostics/f01b-trace-access-0929"

ADMISSION_KEY_VERSION = "awx-admission-key.v1"
EFFECT_KEY_VERSION = "awx-effect-key.v1"
FINGERPRINT_VERSION = "awx-request-fingerprint.v1"

EFFECT_KEY_FIELDS = ("ownerNamespace", "sessionId", "channel", "consentEpoch",
                     "userMessageId", "userRevision", "assistantMessageId",
                     "assistantRevision", "kind")

# 시크릿 키명/값 패턴 — 존재만으로 abort (값 자체는 절대 출력하지 않음)
SECRET_KEY_RE = re.compile(
    r"(?:pass(?:word|wd|phrase)|secret|token|api[_-]?key|authorization|cookie|"
    r"credential|private[_-]?key|dsn|jdbc|bearer|session[_-]?key)", re.I)
SECRET_VALUE_RE = re.compile(
    r"(sk-[A-Za-z0-9_-]{16,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{16,}|"
    r"pcsk_[A-Za-z0-9_-]{16,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{8,}|"
    r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{8,}|"
    r"jdbc:[a-z0-9]+://\S+)", re.I)

# --demo 고정 비밀 아님 샘플 (unittest 가 같은 입력으로 해시 재현 검증)
DEMO_INPUT = {
    "originalRunId": "run-demo-0001",
    "ownerNamespace": "demo-owner",
    "sessionId": "sess-demo-1",
    "channel": "chat-ui",
    "consentEpoch": "2026-09-29T00:00:00Z",
    "userMessageId": "um-1001",
    "userRevision": "1",
    "assistantMessageId": "am-2002",
    "assistantRevision": "1",
    "kind": "understanding_summary_v1",
    "approvedPolicy": {"budgetMs": 30000, "model": "local-demo"},
    "modelConfigIds": ["local-demo"],
    "jobBudget": {"computeMs": 30000, "commitMs": 5000},
}


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def canonical_envelope(version: str, kind: str, fields: dict) -> str:
    """버전 명시 정규 직렬화 — sort_keys + compact. 필드 순서 의존 concat 금지."""
    envelope = {"v": version, "kind": kind, "fields": fields}
    return json.dumps(envelope, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=True)


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _walk_strings(node):
    """dict/list/string 트리의 (키,값) 문자열을 모두 순회."""
    if isinstance(node, dict):
        for k, v in node.items():
            yield str(k), None
            yield from _walk_strings(v)
    elif isinstance(node, list):
        for v in node:
            yield from _walk_strings(v)
    elif isinstance(node, str):
        yield None, node


def assert_secret_free(payload, label: str):
    """fixture/payload 내 시크릿 의심 키명·값 발견 시 SafetyAbort."""
    for key, value in _walk_strings(payload):
        if key is not None and SECRET_KEY_RE.search(key):
            raise SafetyAbort(f"{label}: secret-like key '{key}'")
        if value is not None and SECRET_VALUE_RE.search(value):
            raise SafetyAbort(f"{label}: secret-like value under key pattern")


class SafetyAbort(Exception):
    pass


def admission_key(original_run_id: str, source_scope_inputs: dict) -> str:
    fields = {"originalRunId": original_run_id,
              "sourceScope": source_scope_inputs}
    return sha256_hex(canonical_envelope(ADMISSION_KEY_VERSION,
                                         "admission_key", fields))


def effect_key(fields: dict) -> str:
    missing = [f for f in EFFECT_KEY_FIELDS if f not in fields]
    if missing:
        raise ValueError("missing effect_key fields: " + ",".join(missing))
    picked = {f: fields[f] for f in EFFECT_KEY_FIELDS}
    return sha256_hex(canonical_envelope(EFFECT_KEY_VERSION, "effect_key",
                                         picked))


def request_fingerprint(approved_payload: dict) -> str:
    return sha256_hex(canonical_envelope(FINGERPRINT_VERSION,
                                         "request_fingerprint",
                                         approved_payload))


def compute_all(payload: dict) -> dict:
    """입력 dict → 세 키 + 캐논 문자열 길이(내용은 기록하되 비밀 검사 후)."""
    missing = [f for f in EFFECT_KEY_FIELDS if f not in payload]
    if missing:
        raise ValueError("missing effect_key fields: " + ",".join(missing))
    if not payload.get("originalRunId"):
        raise ValueError("missing originalRunId")
    src_scope = {k: payload.get(k) for k in
                 ("ownerNamespace", "sessionId", "channel", "consentEpoch")}
    canonical_adm = canonical_envelope(
        ADMISSION_KEY_VERSION, "admission_key",
        {"originalRunId": payload.get("originalRunId"),
         "sourceScope": src_scope})
    canonical_eff = canonical_envelope(
        EFFECT_KEY_VERSION, "effect_key",
        {f: payload.get(f) for f in EFFECT_KEY_FIELDS})
    canonical_fp = canonical_envelope(FINGERPRINT_VERSION,
                                      "request_fingerprint", payload)
    return {
        "admissionKey": sha256_hex(canonical_adm),
        "effectKey": sha256_hex(canonical_eff),
        "requestFingerprint": sha256_hex(canonical_fp),
        "canonical": {"admissionKey": canonical_adm,
                      "effectKey": canonical_eff,
                      "requestFingerprint": canonical_fp},
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="F01-B idempotency key canonical hash demo (no product calls)")
    ap.add_argument("--root", default=".")
    ap.add_argument("--demo", action="store_true",
                    help="내장 비밀 아님 고정 벡터로 해시 출력")
    ap.add_argument("--fixture", default=None, help="입력 JSON 파일 경로")
    ap.add_argument("--algorithm", default="sha256", choices=["sha256"])
    ap.add_argument("--json-out",
                    default=f"{DEFAULT_OUT_DIR}/f01b_admission_key_demo.json")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    root = Path(args.root).resolve()
    if not args.demo and not args.fixture:
        print("usage: --demo 또는 --fixture <path.json> 필요", file=sys.stderr)
        return 1

    if args.demo:
        payload = dict(DEMO_INPUT)
        source = "builtin-demo"
    else:
        fx = Path(args.fixture)
        if not fx.is_absolute():
            fx = root / fx
        try:
            payload = json.loads(fx.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            print(f"fixture read/parse 실패: {type(exc).__name__}",
                  file=sys.stderr)
            return 1
        source = str(fx)
        if not isinstance(payload, dict):
            print("fixture 는 JSON object 여야 함", file=sys.stderr)
            return 1

    try:
        assert_secret_free(payload, "input")
        assert_secret_free(list(payload.keys()), "input-keys")
    except SafetyAbort as exc:
        # 키 이름만, 값은 절대 출력하지 않음
        print(f"SAFETY ABORT: {exc}", file=sys.stderr)
        return 4

    try:
        result = compute_all(payload)
    except ValueError as exc:
        print(f"입력 필드 부족: {exc}", file=sys.stderr)
        return 1

    out = {
        "schemaVersion": SCHEMA,
        "contractId": CONTRACT_ID,
        "generatedAtUtc": utcnow(),
        "source": source,
        "algorithm": "sha256",
        "versions": {"admissionKey": ADMISSION_KEY_VERSION,
                     "effectKey": EFFECT_KEY_VERSION,
                     "requestFingerprint": FINGERPRINT_VERSION},
        "keys": {k: result[k] for k in
                 ("admissionKey", "effectKey", "requestFingerprint")},
        "canonicalLength": {k: len(v) for k, v in result["canonical"].items()},
        "note": "equal hash still re-checks stored tuple equality; "
                "internal derived jobs never invent owner as system-job",
    }
    out_path = root / args.json_out
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2),
                        encoding="utf-8")
    if args.json:
        print(json.dumps(out, ensure_ascii=False))
    else:
        print("admission_key=" + out["keys"]["admissionKey"])
        print("effect_key=" + out["keys"]["effectKey"])
        print("request_fingerprint=" + out["keys"]["requestFingerprint"])
        print(f"json={out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
