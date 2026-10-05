#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe_concurrency_resilience.py — 다중 세션 동시성·장애 시뮬레이션 프로브.

PASTE_DEVIN_CONCURRENCY_RESILIENCE_20261005 WP1.
POST {base}/api/chat/stream SSE 엔드포인트에 1~20개의 동시 모의 요청을 보내
PublicChatAdmissionGuard(글로벌 세마포어 + per-owner 제한)의 429 거절,
스트림 시작 지연, 타임아웃을 관찰한다.

모드:
  --dry-run  : 네트워크 없이 정책 모델(글로벌/per-owner 세마포어)과
               고정 시드 지연 분포로 세션 결과를 시뮬레이션한다. exit=0.
  --live     : 실제 HTTP POST 전송. 기본은 loopback(127.0.0.1/localhost)만
               허용. 실제 생성이 트리거되므로 maxTokens=8 로 최소화.
               비-loopback은 --allow-remote 필요(지시서상 사용 금지).

산출: 콘솔 표 + 요약 JSON(stdout, --json-out 파일 선택).
종료 코드: 0=프로브 완료, 1=내부 오류, 2=인자/정책 거부.
"""
from __future__ import annotations

import argparse
import json
import random
import socket
import sys
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse

SCHEMA = "awx.probe.concurrency-resilience.v1"
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}
DEFAULT_SEED = 20261005
SLOW_THRESHOLD_MS = 10_000  # 스트림 시작/생성이 이 이상 걸리면 'slow'


def _now_ms() -> float:
    return time.monotonic() * 1000.0


def classify_http(exc) -> tuple[str, int | None]:
    """HTTPError/URLError/timeout을 outcome 클래스로 매핑."""
    if isinstance(exc, urllib.error.HTTPError):
        if exc.code == 429:
            return "rejected_429", 429
        return "http_error", exc.code
    if isinstance(exc, (socket.timeout, TimeoutError)):
        return "timeout", None
    if isinstance(exc, urllib.error.URLError):
        reason = str(getattr(exc, "reason", exc))
        if "timed out" in reason.lower():
            return "timeout", None
        return "conn_error", None
    return "error", None


def dry_run(args) -> dict:
    """정책 모델로 admission 결과 + 시드 기반 지연 분포를 시뮬레이션."""
    rng = random.Random(DEFAULT_SEED)
    # same-owner 세션 수만큼 owner-0을 공유하게 해 per-owner 거절 사례를 만든다.
    same_owner = max(0, min(args.same_owner_count, args.concurrency))
    owners = []
    for i in range(args.concurrency):
        owners.append("devin-probe-owner-0" if i >= args.concurrency - same_owner
                      else f"devin-probe-owner-{i}")

    global_active = 0
    per_owner: dict[str, int] = {}
    sessions = []
    for i in range(args.concurrency):
        owner = owners[i]
        outcome, detail, http_status = None, "", 200
        if global_active >= args.global_limit:
            outcome, detail, http_status = "rejected_429", "global_semaphore_exhausted", 429
        elif per_owner.get(owner, 0) >= args.per_owner_limit:
            outcome, detail, http_status = "rejected_429", "per_owner_limit_exceeded", 429
        else:
            global_active += 1
            per_owner[owner] = per_owner.get(owner, 0) + 1
            # 시드 기반 모의 지연: 대부분 정상, 일부 slow/timeout.
            latency_ms = rng.uniform(600.0, args.timeout_seconds * 1000.0 * 1.3)
            if latency_ms >= args.timeout_seconds * 1000.0:
                outcome, detail = "timeout", f"simulated_latency_ms={latency_ms:.0f}>=timeout"
            elif latency_ms >= SLOW_THRESHOLD_MS:
                outcome, detail = "slow", f"simulated_latency_ms={latency_ms:.0f}"
            else:
                outcome, detail = "ok", f"simulated_latency_ms={latency_ms:.0f}"
            sessions.append({"id": i, "owner": owner, "outcome": outcome,
                             "http_status": http_status, "detail": detail})
            continue
        sessions.append({"id": i, "owner": owner, "outcome": outcome,
                         "http_status": http_status, "detail": detail})
    return {
        "schema": SCHEMA, "mode": "dry-run", "seed": DEFAULT_SEED,
        "concurrency": args.concurrency,
        "policy": {"global_limit": args.global_limit,
                   "per_owner_limit": args.per_owner_limit,
                   "same_owner_count": same_owner},
        "sessions": sessions,
        "counts": _counts(sessions),
    }


def _live_worker(idx: int, args, results: list, started_at: list):
    url = args.base_url.rstrip("/") + args.path
    body = json.dumps({
        "message": f"[devin-test] concurrency resilience probe {idx}",
        "useRag": False,
        "maxTokens": 8,
    }).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        "X-Session-Id": f"devin-probe-{idx}",
        "X-Request-Id": f"devin-probe-{idx}-{int(time.time())}",
    }
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    t0 = _now_ms()
    try:
        with urllib.request.urlopen(req, timeout=args.timeout_seconds) as resp:
            status = resp.status
            first_ms = _now_ms() - t0
            events = 0
            saw_terminal = False
            deadline = _now_ms() + args.timeout_seconds * 1000.0
            # 첫 토큰/종료 이벤트까지만 관찰 — 본 프로브는 생성 품질이 아니라
            # admission + 스트림 시작의 장애내성만 측정한다.
            while _now_ms() < deadline:
                line = resp.readline()
                if not line:
                    break
                text = line.decode("utf-8", errors="replace").strip()
                if text.startswith("event:"):
                    events += 1
                    if any(t in text for t in ("final", "done", "error")):
                        saw_terminal = True
                        break
            outcome = "ok" if saw_terminal else "timeout"
            detail = f"events={events} terminal={saw_terminal}"
            results[idx] = {"id": idx, "outcome": outcome, "http_status": status,
                            "first_byte_ms": round(first_ms, 1), "detail": detail}
    except Exception as exc:  # noqa: BLE001 - 프로브는 모든 실패를 분류해 보고한다.
        outcome, status = classify_http(exc)
        results[idx] = {"id": idx, "outcome": outcome, "http_status": status,
                        "first_byte_ms": round(_now_ms() - t0, 1),
                        "detail": type(exc).__name__}
    finally:
        started_at[idx] = _now_ms() - t0


def live_run(args) -> dict:
    results: list = [None] * args.concurrency
    started_at: list = [0.0] * args.concurrency
    threads = [threading.Thread(target=_live_worker, args=(i, args, results, started_at),
                              daemon=True, name=f"probe-{i}")
               for i in range(args.concurrency)]
    t0 = _now_ms()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=args.timeout_seconds + 5.0)
    wall_ms = _now_ms() - t0
    sessions = [r if r is not None else
                {"id": i, "outcome": "timeout", "http_status": None,
                 "detail": "worker_no_result"} for i, r in enumerate(results)]
    return {
        "schema": SCHEMA, "mode": "live",
        "base_url": args.base_url, "path": args.path,
        "concurrency": args.concurrency, "wall_ms": round(wall_ms, 1),
        "sessions": sessions, "counts": _counts(sessions),
    }


def _counts(sessions: list) -> dict:
    counts: dict[str, int] = {}
    for s in sessions:
        counts[s["outcome"]] = counts.get(s["outcome"], 0) + 1
    return counts


def print_table(summary: dict) -> None:
    print(f"[probe] mode={summary['mode']} concurrency={summary['concurrency']} "
          f"policy={summary.get('policy', '')}")
    print(f"{'id':>4} {'outcome':<14} {'http':>5} {'detail'}")
    for s in summary["sessions"]:
        print(f"{s['id']:>4} {s['outcome']:<14} {str(s.get('http_status')):>5} {s['detail']}")
    counts = summary["counts"]
    print("[probe] counts: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base-url", default="http://127.0.0.1:18180")
    ap.add_argument("--path", default="/api/chat/stream")
    ap.add_argument("--concurrency", type=int, default=10)
    ap.add_argument("--timeout-seconds", type=float, default=25.0)
    ap.add_argument("--global-limit", type=int, default=64,
                    help="dry-run 정책 모델의 글로벌 세마포어 (라이브 기본값 64)")
    ap.add_argument("--per-owner-limit", type=int, default=2)
    ap.add_argument("--same-owner-count", type=int, default=3,
                    help="dry-run에서 owner-0을 공유할 세션 수 (per-owner 429 시연)")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--live", action="store_true",
                    help="실제 POST 전송(로컬 전용). 실제 생성이 트리거됨")
    ap.add_argument("--allow-remote", action="store_true",
                    help="비-loopback 호스트 허용 — 지시서상 실서버 부하 금지, 사용 자제")
    ap.add_argument("--json-out", default=None, help="요약 JSON 저장 경로")
    args = ap.parse_args()

    if not (1 <= args.concurrency <= 64):
        print("[probe] --concurrency 범위는 1~64 (지시서 모의 범위 1~20 권장)", file=sys.stderr)
        return 2
    if args.dry_run and args.live:
        print("[probe] --dry-run과 --live는 동시에 지정 불가", file=sys.stderr)
        return 2

    if args.dry_run or not args.live:
        summary = dry_run(args)
        if not args.dry_run:
            print("[probe] --live 미지정 → dry-run으로 실행", file=sys.stderr)
    else:
        host = urlparse(args.base_url).hostname or ""
        if host not in LOOPBACK_HOSTS and not args.allow_remote:
            print(f"[probe] 비-loopback 대상({host})은 --allow-remote 없이 차단", file=sys.stderr)
            return 2
        summary = live_run(args)

    print_table(summary)
    blob = json.dumps(summary, ensure_ascii=False, indent=2)
    print(blob)
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as fh:
            fh.write(blob + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
