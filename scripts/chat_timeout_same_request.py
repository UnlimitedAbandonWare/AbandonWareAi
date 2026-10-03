#!/usr/bin/env python3
"""chat_timeout_same_request.py — 한 요청의 증거를 여러 로그에서 묶는다 (읽기 전용).

입력: --since/--until (ISO, naive=KST) 또는 --request-id, --log 반복, --trace-dir.
인식 소스:
  * Spring stdout: `[LLM_REQUEST_LIFECYCLE]`, `[LLM_REQUEST_PROOF]`,
    `stream-failed type=…`, `SSE stream detached`, `TRACE_SNAPSHOT`, `[HTTP-IN]`
  * NDJSON 디버그 이벤트: {"ts","probe","where","requestId","sid","traceId",...}
  * chat-session-traces JSON (awx.chat-session-trace.v1)
  * Ollama 로그: 로드 시작/실패/abort, GIN 요청 줄 (시간창 상관만)

출력: 같은 요청 기준 필드 — requestId, streamHttpStatus, reasonCode,
providerModel, fallbackAttempted, unconfirmedGuard, firstTokenAt, timeoutAt,
ollamaEvents. 관측 불가 필드는 반드시 "not_observed". 다른 요청 값으로 채우지 않는다.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

KST = timezone(timedelta(hours=9))
NOT_OBSERVED = "not_observed"

RE_APP_TS = re.compile(r'^(?P<ts>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+[+-]\d{4})\s+(?P<level>\w+)\s+\[(?P<ctx>[^\]]*)\]\s+(?P<logger>\S+)\s+-\s+(?P<msg>.*)$')
RE_KV = re.compile(r'(\w+)=([^\s]+)')
RE_GIN = re.compile(
    r'^\[GIN\]\s+(?P<d>\d{4}/\d{2}/\d{2})\s+-\s+(?P<t>\d{2}:\d{2}:\d{2})\s*\|\s*'
    r'(?P<status>\d{3})\s*\|\s*(?P<dur>[~\d.,a-zµμ]+?)\s*\|\s*\S+\s*\|\s*'
    r'(?P<method>[A-Z]+)\s+"(?P<path>[^"]*)"'
)
RE_OLLAMA_LOG = re.compile(
    r'^time=(?P<ts>\d{4}-\d{2}-\d{2}T[\d:.]+(?:[+-]\d{2}:?\d{2}|Z)?)\s+'
    r'level=(?P<level>\w+)\s+source=(?P<src>[\w.]+:\d+)\s+msg="(?P<msg>.*?)"'
)
RE_GIN_DUR = re.compile(r'^(?:(\d+)m)?([\d.]+)(ms|µs|us|s)?$')


def parse_ts(text: str) -> datetime | None:
    if not text:
        return None
    text = text.strip()
    try:
        if "/" in text:
            return datetime.strptime(text, "%Y/%m/%d - %H:%M:%S").replace(tzinfo=KST)
        if re.match(r'^\d{4}-\d{2}-\d{2}T', text):
            iso = text.replace("Z", "+00:00")
            # "+0900" → "+09:00"
            if re.search(r'[+-]\d{4}$', iso):
                iso = iso[:-2] + ":" + iso[-2:]
            dt = datetime.fromisoformat(iso)
            return dt if dt.tzinfo else dt.replace(tzinfo=KST)
    except ValueError:
        return None
    return None


def gin_duration_s(text: str) -> float | None:
    m = RE_GIN_DUR.match(text.strip())
    if not m:
        return None
    total = float(m.group(1) or 0) * 60.0 + float(m.group(2))
    unit = m.group(3) or "s"
    if unit == "ms":
        return total / 1000.0
    if unit in ("µs", "us"):
        return total / 1_000_000.0
    return total


def kv(text: str) -> dict:
    return {k: v for k, v in RE_KV.findall(text)}


def in_window(ts: datetime | None, since, until) -> bool:
    if ts is None:
        return since is None and until is None
    if since and ts < since:
        return False
    if until and ts > until:
        return False
    return True


class RequestEvidence:
    """같은 요청(requestHash/requestId 묶음)의 관측 필드만 담는다."""

    def __init__(self, rid: str):
        self.request_id = rid
        self.run_id = NOT_OBSERVED
        self.session_id = NOT_OBSERVED
        self.stream_http_status = NOT_OBSERVED
        self.reason_code = NOT_OBSERVED
        self.failure_class = NOT_OBSERVED
        self.provider_model = NOT_OBSERVED
        self.fallback_attempted = NOT_OBSERVED
        self.unconfirmed_guard = NOT_OBSERVED
        self.first_token_at = NOT_OBSERVED
        self.timeout_at = NOT_OBSERVED
        self.stream_failed_type = NOT_OBSERVED
        self.sse_detached = NOT_OBSERVED
        self.events: list[dict] = []
        self.ollama_events: list[dict] = []

    def add(self, ts, source, kind, detail):
        self.events.append({"ts": ts.isoformat() if ts else None,
                            "source": source, "kind": kind, "detail": detail})

    def to_dict(self) -> dict:
        seen = set()
        events = []
        for e in self.events:
            key = (e.get("ts"), e.get("kind"), e.get("detail"))
            if key in seen:
                continue
            seen.add(key)
            events.append(e)
        return {
            "requestId": self.request_id,
            "runId": self.run_id,
            "sessionId": self.session_id,
            "streamHttpStatus": self.stream_http_status,
            "reasonCode": self.reason_code,
            "failureClass": self.failure_class,
            "providerModel": self.provider_model,
            "fallbackAttempted": self.fallback_attempted,
            "unconfirmedGuard": self.unconfirmed_guard,
            "firstTokenAt": self.first_token_at,
            "timeoutAt": self.timeout_at,
            "streamFailedType": self.stream_failed_type,
            "sseDetachedByClient": self.sse_detached,
            "ollamaEvents": self.ollama_events,
            "events": events,
        }


def _norm_rid(raw: str | None) -> str | None:
    if not raw:
        return None
    return raw if raw.startswith("hash:") else f"hash:{raw}"


RE_CTX_UUID = re.compile(r'([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})')


def _ctx_uuid(ctx: str) -> str | None:
    m = RE_CTX_UUID.search(ctx or "")
    return m.group(1) if m else None


def scan_spring_log(path: Path, since, until, requests: dict[str, RequestEvidence],
                    want_rid: str | None) -> None:
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return
    src = path.name
    ctx2rid: dict[str, str] = {}  # 서블릿 요청 UUID → requestHash (스트림 종료 줄은 requestHash가 없다)
    for line in lines:
        m = RE_APP_TS.match(line)
        if not m:
            continue
        ts = parse_ts(m.group("ts"))
        if not in_window(ts, since, until):
            continue
        msg = m.group("msg")
        ctx_id = _ctx_uuid(m.group("ctx"))
        fields = kv(msg)

        if "[LLM_REQUEST_LIFECYCLE]" in msg or "[LLM_REQUEST_PROOF]" in msg:
            rid = _norm_rid(fields.get("requestHash"))
            if rid and ctx_id:
                ctx2rid[ctx_id] = rid

        if "[LLM_REQUEST_LIFECYCLE]" in msg:
            rid = _norm_rid(fields.get("requestHash"))
            if not rid or (want_rid and rid != want_rid):
                continue
            ev = requests.setdefault(rid, RequestEvidence(rid))
            if fields.get("runHash"):
                ev.run_id = fields["runHash"]
            kind = fields.get("event", "?")
            ev.add(ts, src, "lifecycle",
                   f"event={kind} boundary={fields.get('boundary','?')} "
                   f"providerReceiptObserved={fields.get('providerReceiptObserved','?')}")
            if kind == "http_client_failed":
                ev.timeout_at = ts.isoformat()
                if fields.get("providerReceiptObserved") == "false":
                    ev.unconfirmed_guard = "provider_receipt_not_observed"
            if "first_token" in kind or "firstToken" in kind:
                ev.first_token_at = ts.isoformat()
            continue

        if "[LLM_REQUEST_PROOF]" in msg:
            rid = _norm_rid(fields.get("requestHash"))
            if not rid or (want_rid and rid != want_rid):
                continue
            ev = requests.setdefault(rid, RequestEvidence(rid))
            if fields.get("failureClass"):
                ev.failure_class = fields["failureClass"]
            if fields.get("runHash"):
                ev.run_id = fields["runHash"]
            ev.add(ts, src, "proof",
                   f"outcome={fields.get('outcome','?')} "
                   f"failureClass={fields.get('failureClass','?')} "
                   f"responseUtf8Bytes={fields.get('responseUtf8Bytes','?')}")
            continue

        if "stream-failed" in msg:
            # 스트림 종료 줄은 requestHash가 없다 → 서블릿 ctx UUID로 같은 요청에 연결
            ev = requests.get(ctx2rid.get(ctx_id, "")) if ctx_id else None
            if ev is None:
                ev = _nearest_request(requests, ts, want_rid)
            ftype = fields.get("type") or (re.search(r'type=(\S+)', msg) or [None, "?"])[1]
            if ev:
                ev.stream_failed_type = ftype
                ev.timeout_at = ev.timeout_at if ev.timeout_at != NOT_OBSERVED else ts.isoformat()
                ev.add(ts, src, "stream_failed", msg[:160])
            continue

        if "SSE stream detached" in msg:
            ev = requests.get(ctx2rid.get(ctx_id, "")) if ctx_id else None
            if ev is None:
                ev = _nearest_request(requests, ts, want_rid)
            sess = re.search(r'sessionHash=(hash:\w+)', msg)
            if ev:
                ev.sse_detached = "yes"
                if sess:
                    ev.session_id = sess.group(1)
                ev.add(ts, src, "sse_detach", msg[:160])
            continue

        if "TRACE_SNAPSHOT" in msg and ("status=" in msg or "reason=" in msg):
            rid = _norm_rid(fields.get("reqHash"))
            if not rid or (want_rid and rid != want_rid):
                continue
            ev = requests.setdefault(rid, RequestEvidence(rid))
            if fields.get("sidHash") and fields["sidHash"] != "":
                ev.session_id = fields["sidHash"]
            if fields.get("status") and ev.stream_http_status == NOT_OBSERVED:
                ev.stream_http_status = fields["status"]
            # TRACE_SNAPSHOT의 reason=은 스냅샷 트리거(http_request 등)이지 실패 reasonCode가 아니다 → 기록만
            ev.add(ts, src, "trace_snapshot",
                   f"status={fields.get('status','?')} snapshotTrigger={fields.get('reason','?')}")
            continue


def _nearest_request(requests: dict[str, RequestEvidence],
                     ts: datetime, want_rid: str | None) -> RequestEvidence | None:
    if want_rid and want_rid in requests:
        return requests[want_rid]
    if len(requests) == 1:
        return next(iter(requests.values()))
    return None


def scan_ndjson(path: Path, since, until, requests: dict[str, RequestEvidence],
                want_rid: str | None) -> None:
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return
    for line in lines:
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            d = json.loads(line)
        except Exception:
            continue
        ts = parse_ts(str(d.get("ts", "")))
        if not in_window(ts, since, until):
            continue
        rid = _norm_rid(d.get("requestId") or d.get("traceId"))
        if not rid:
            continue
        if want_rid and rid != want_rid:
            continue
        ev = requests.setdefault(rid, RequestEvidence(rid))
        if d.get("sid"):
            ev.session_id = d["sid"]
        ev.add(ts, path.name, f"ndjson:{d.get('probe','?')}",
               f"{d.get('where','?')}|{(d.get('message') or '')[:80]}")


def scan_traces(trace_dir: Path, since, until,
                requests: dict[str, RequestEvidence], want_rid: str | None) -> None:
    if not trace_dir.is_dir():
        return
    for path in sorted(trace_dir.rglob("*.json")):
        try:
            d = json.loads(path.read_text(encoding="utf-8", errors="replace"))
        except Exception:
            continue
        if not isinstance(d, dict) or "schema" not in d:
            continue
        ts = parse_ts(str(d.get("ts", "")))
        if not in_window(ts, since, until):
            continue
        rid = _norm_rid(d.get("recordId") or d.get("runId") or d.get("sessionId"))
        if not rid:
            continue
        # trace의 recordId는 runHash에 대응한다 → run_id로 기존 요청에 붙인다
        target = None
        for ev in requests.values():
            if ev.run_id == rid:
                target = ev
                break
        if target is None:
            if want_rid and rid != want_rid:
                continue
            target = requests.setdefault(rid, RequestEvidence(rid))
        if d.get("sessionId"):
            target.session_id = d["sessionId"] if str(d["sessionId"]).startswith("hash:") else f"hash:{d['sessionId']}"
        if d.get("effectiveModel"):
            target.provider_model = d["effectiveModel"]
        elif d.get("requestedModel") and target.provider_model == NOT_OBSERVED:
            target.provider_model = f"requested:{d['requestedModel']}"
        if d.get("fallbackCount") is not None:
            target.fallback_attempted = "yes" if d["fallbackCount"] else "no"
        if d.get("errorClass"):
            target.add(ts, path.name, "trace",
                       f"outcome={d.get('outcome')} errorClass={d.get('errorClass')} "
                       f"fallbackCount={d.get('fallbackCount')} requestedModel={d.get('requestedModel')}")


def scan_ollama(path: Path, since, until, sink: list[dict]) -> None:
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return
    for line in lines:
        gin = RE_GIN.match(line)
        if gin:
            ts = parse_ts(f"{gin.group('d')} - {gin.group('t')}")
            if not in_window(ts, since, until):
                continue
            sink.append({"ts": ts.isoformat() if ts else None, "source": path.name,
                         "kind": "gin_request",
                         "detail": f"{gin.group('method')} {gin.group('path')} "
                                   f"status={gin.group('status')} "
                                   f"duration_s={gin_duration_s(gin.group('dur'))}"})
            continue
        m = RE_OLLAMA_LOG.match(line)
        if not m:
            continue
        ts = parse_ts(m.group("ts"))
        if not in_window(ts, since, until):
            continue
        msg = m.group("msg")
        for key in ("loading model via llama-server", "llama-server started in",
                    "Load failed", "client connection closed",
                    "aborting", "template selection"):
            if key in msg:
                detail = re.sub(r'model=[^\s]*blobs[^\s]*', 'model=<blob>', msg)[:160]
                sink.append({"ts": ts.isoformat() if ts else None,
                             "source": path.name, "kind": "ollama",
                             "detail": detail})
                break


def correlate(requests: dict[str, RequestEvidence], ollama: list[dict]) -> None:
    """같은 요청의 타임아웃±60s 안에 있는 Ollama 이벤트를 연결한다."""
    for ev in requests.values():
        anchor = None
        if ev.timeout_at != NOT_OBSERVED:
            anchor = parse_ts(ev.timeout_at)
        elif ev.events:
            anchor = parse_ts(ev.events[-1].get("ts") or "")
        if anchor is None:
            continue
        lo, hi = anchor - timedelta(seconds=90), anchor + timedelta(seconds=30)
        ev.ollama_events = [o for o in ollama
                            if o.get("ts") and lo <= parse_ts(o["ts"]) <= hi]


def build_result(requests: dict[str, RequestEvidence], ollama: list[dict],
                 since, until) -> dict:
    correlate(requests, ollama)
    return {
        "schemaVersion": "devin.chat-timeout.same-request.v1",
        "window": {"since": since.isoformat() if since else None,
                   "until": until.isoformat() if until else None},
        "requestCount": len(requests),
        "requests": [ev.to_dict() for ev in requests.values()],
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="같은 요청 증거 추출 (chat timeout)")
    ap.add_argument("--log", action="append", default=[], help="로그 파일 경로(반복)")
    ap.add_argument("--trace-dir", default="var/debug/chat-session-traces",
                    help="chat-session-traces 루트")
    ap.add_argument("--request-id", help="hash:xxxx 또는 bare id")
    ap.add_argument("--since", help="ISO 시각; naive=KST")
    ap.add_argument("--until", help="ISO 시각; naive=KST")
    ap.add_argument("--out", help="결과 JSON 경로")
    args = ap.parse_args(argv)

    since = parse_ts(args.since) if args.since else None
    until = parse_ts(args.until) if args.until else None
    want = _norm_rid(args.request_id)

    requests: dict[str, RequestEvidence] = {}
    ollama: list[dict] = []
    trace_dir = Path(args.trace_dir)

    for raw in args.log:
        p = Path(raw)
        name = p.name.lower()
        if "ollama" in name:
            scan_ollama(p, since, until, ollama)
        elif p.suffix == ".ndjson" or "ndjson" in name:
            scan_ndjson(p, since, until, requests, want)
        else:
            scan_spring_log(p, since, until, requests, want)
            scan_ndjson(p, since, until, requests, want)
    scan_traces(trace_dir, since, until, requests, want)

    doc = build_result(requests, ollama, since, until)
    text = json.dumps(doc, ensure_ascii=False, indent=1)
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
        print(f"out={args.out} requests={doc['requestCount']} ollamaEvents={len(ollama)}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
