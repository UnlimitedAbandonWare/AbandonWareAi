#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""chat_sse_single_send -- 단일 /api/chat/stream 전송 + SSE 캡처 (재시도 0).

목적: codex-gemini-chat-catalog-53b36b1f의 A6 잔여 증거 — strict 라우트로
요청 1회를 보내고 모델/fallback/terminal/final/본문 메타데이터를 잰다.
기존 scripts/settings_defaults_sse_observe.py 의 SSE 파서·화이트리스트 방문자를
재사용하고, 그 도구가 빠뜨리던 ChatStreamEvent.data 본문을 추가로 잰다
(finalBodyChars=0 버그의 수정점: token/final 이벤트의 data 필드).

비밀 정책: 본문·프롬프트·에러 원문은 출력에 쓰지 않는다 — 글자 수와 sha12만.
X-Request-Id는 우리가 만든 nonce며, requestHash=sha256(id)[:12]로 서버 로그
([plan9-request-phase] requestHash=hash:...)와 상관한다.

사용:
  python -B scripts/chat_sse_single_send.py --base http://127.0.0.1:18180 \
      --model llmrouter.gemini-pro --mode strict --message "..." \
      --surface local --out result.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.request
import urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import settings_defaults_sse_observe as obs

TERMINAL_TYPES = set(obs.TERMINAL_TYPES)
BODY_KEYS = ("data", "delta", "text", "content", "answer", "body", "chunk",
             "html", "message")
PROVIDER_KEYS = ("observedProvider", "actualProvider", "provider")
ERROR_CODE_RE = __import__("re").compile(r"^[a-zA-Z_][a-zA-Z0-9_.\-]{1,60}$")


def sha12(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]


def _walk(value, fn, depth=0):
    if depth > 8:
        return
    if isinstance(value, dict):
        for k, v in value.items():
            fn(k, v)
            if isinstance(v, (dict, list)):
                _walk(v, fn, depth + 1)
    elif isinstance(value, list):
        for v in value:
            _walk(v, fn, depth + 1)


def _event_text(payload):
    """이벤트 payload에서 본문 텍스트 — ChatStreamEvent.data가 1순위."""
    if isinstance(payload, dict):
        for k in BODY_KEYS:
            v = payload.get(k)
            if isinstance(v, str) and v:
                return v
    if isinstance(payload, str) and payload:
        return payload
    return None


def extract_stream(text: str, sent_model=None) -> dict:
    """저장된 SSE 텍스트 -> 측정 dict (네트워크 없음, 테스트도 이 경로)."""
    events = obs.parse_sse(text)
    diag = {"eventCount": len(events), "dataLineCount": 0,
            "jsonParseSucceeded": 0, "jsonParseFailed": 0,
            "truncatedEvents": 0, "eventTypes": {}}
    acc = {"observed": [], "declared": {}, "claimed": None, "aux": {},
           "fallbackCount": None, "fallbackReason": None, "routeId": None,
           "errors": []}
    provider = None
    token_parts, final_parts, error_parts = [], [], []
    terminal_count = final_count = error_count = 0
    terminal = None
    first_body_ms = None
    t0 = None

    for i, ev in enumerate(events):
        payload = None
        try:
            payload = json.loads(ev["data"])
            diag["jsonParseSucceeded"] += 1
        except json.JSONDecodeError:
            diag["jsonParseFailed"] += 1
        etype = ev["event"]
        if isinstance(payload, dict) and isinstance(payload.get("type"), str) \
                and payload["type"]:
            etype = payload["type"]
        diag["eventTypes"][etype] = diag["eventTypes"].get(etype, 0) + 1
        diag["dataLineCount"] += ev["data"].count("\n") + 1
        if ev.get("truncated"):
            diag["truncatedEvents"] += 1
        if payload is not None:
            _visit = obs._visit
            local = {"observed": [], "declared": {}, "claimed": None,
                     "aux": {}, "errors": [], "ts": None}
            _visit(payload, local)
            for k, v in local.items():
                if k == "observed":
                    acc["observed"].extend(v)
                elif k in ("declared", "aux"):
                    acc[k].update(v)
                elif k == "errors":
                    acc["errors"].extend(v)
                elif v is not None:
                    acc[k] = v
            for k, v in _collect(payload):
                if k in PROVIDER_KEYS and isinstance(v, str) and v:
                    provider = v
        if etype in TERMINAL_TYPES:
            terminal_count += 1
            terminal = {"event": etype, "eventIndex": i}
        if etype == "final":
            final_count += 1
        if etype == "error":
            error_count += 1
        body = _event_text(payload if payload is not None else ev["data"])
        if body:
            if etype == "token":
                token_parts.append(body)
            elif etype == "final":
                final_parts.append(body)
            elif etype == "error":
                error_parts.append(body)
        if etype == "error" and body:
            # ChatStreamEvent.error는 분류 문자열을 data에 넣는다 —
            # 코드형이면 그대로, 자유문장이면 sha12만 남긴다
            if ERROR_CODE_RE.match(body):
                acc["errors"].append({"data": body})
            else:
                acc["errors"].append({"dataSha12": sha12(body)})

    final_text = next((p for p in reversed(final_parts) if p), "")
    assembled = final_text if final_text else "".join(token_parts)
    observed = acc["observed"][-1] if acc["observed"] else "MISSING"
    out = {
        "schema": "awx.gemini-catalog-live.v1",
        "toolId": "chat_sse_single_send",
        "sentModel": sent_model,
        "observedModel": observed,
        "finalAnswerModel": observed if final_count else None,
        "claimedModel": acc["claimed"],
        "observedProvider": provider,
        "fallbackCount": acc["fallbackCount"],
        "fallbackReason": acc["fallbackReason"],
        "routeId": acc["routeId"],
        "terminalCount": terminal_count,
        "finalCount": final_count,
        "errorCount": error_count,
        "terminal": terminal,
        "tokenBodyChars": sum(len(p) for p in token_parts),
        "finalBodyChars": sum(len(p) for p in final_parts),
        "errorDataChars": sum(len(p) for p in error_parts),
        "bodyChars": len(assembled),
        "bodySha12": sha12(assembled) if assembled else None,
        "sseErrorCount": error_count,
        "sseErrors": [
            {k: (v if ERROR_CODE_RE.match(str(v))
                 else "sha12:" + sha12(str(v)))
             for k, v in e.items()}
            for e in acc["errors"]],
        "diagnostics": diag,
        "firstBodyMs": first_body_ms,
    }
    return out


def _collect(payload):
    items = []

    def fn(k, v):
        if isinstance(v, (str, int, float, bool)):
            items.append((k, v))
    _walk(payload, fn)
    return items


def send_once(base: str, message: str, model: str, mode: str, max_tokens: int,
              timeout: float) -> tuple[int, str, str, float]:
    """정확히 1회 POST. 반환: (httpStatus, rawSseText, requestId, elapsedMs)."""
    request_id = f"devin-gemini-{int(time.time() * 1000)}-{os.getpid()}"
    body = json.dumps({
        "message": message,
        "model": model,
        "modelSelectionMode": mode,
        "useRag": False,
        "useWebSearch": False,
        "maxTokens": max_tokens,
    }).encode("utf-8")
    req = urllib.request.Request(
        base.rstrip("/") + "/api/chat/stream", data=body, method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            "X-Request-Id": request_id,
        })
    started = time.monotonic()
    chunks = []
    status = 0
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status = resp.status
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                chunks.append(chunk)
    except urllib.error.HTTPError as e:
        status = e.code
        try:
            chunks.append(e.read())
        except OSError:
            pass
    elapsed = (time.monotonic() - started) * 1000.0
    raw = b"".join(chunks).decode("utf-8", errors="replace")
    return status, raw, request_id, elapsed


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="single chat SSE send+capture (no retry)")
    ap.add_argument("--base", default="http://127.0.0.1:18180")
    ap.add_argument("--message", required=True)
    ap.add_argument("--model", default="llmrouter.gemini-pro")
    ap.add_argument("--mode", default="strict",
                    choices=["strict", "preferred", "auto"])
    ap.add_argument("--max-tokens", type=int, default=256)
    ap.add_argument("--timeout", type=float, default=180.0)
    ap.add_argument("--surface", default="local")
    ap.add_argument("--out", default="")
    ap.add_argument("--input", default="",
                    help="네트워크 없이 저장된 SSE 텍스트만 분석")
    args = ap.parse_args(argv)

    if args.input:
        with open(args.input, encoding="utf-8", errors="replace") as f:
            text = f.read()
        out = extract_stream(text, sent_model=args.model)
        out["surface"] = args.surface
        out["requestCount"] = 0
    else:
        status, raw, request_id, elapsed = send_once(
            args.base, args.message, args.model, args.mode,
            args.max_tokens, args.timeout)
        out = extract_stream(raw, sent_model=args.model)
        out.update({
            "surface": args.surface,
            "httpStatus": status,
            "elapsedMs": round(elapsed, 1),
            "requestCount": 1,
            "requestHash": sha12(request_id),
            "requestIdHash": "hash:" + sha12(request_id),
            "modelSelectionMode": args.mode,
            "maxRetries": 0,
            "blockingHttpStatuses": [status] if status in (401, 403, 429) else [],
        })
    text = json.dumps(out, ensure_ascii=False, indent=2)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
