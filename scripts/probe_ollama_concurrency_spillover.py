#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""probe_ollama_concurrency_spillover.py — 로컬 슬롯 포화 → 클라우드 즉시 스필오버 검증 프로브.

PASTE_DEVIN_OLLAMA_CONCURRENCY_API_FALLBACK_20261005 WP2.
`LocalModelAdmission`(permits 세마포어, non-blocking tryAcquire)과
`LlmRouterAspect`의 cloudOnly 재선택 계약을 미러링한 정책 모델 + 스레드 시뮬레이터.

모드:
  --dry-run    : 스레드/네트워크 없이 정책 테이블과 10-user 시나리오를
                 결정론적으로 검증한다. exit=0. (기본 모드)
  --simulated  : threading.Semaphore(permits)를 공유하는 가상 사용자
                 1~64명을 실제 스레드로 경합시켜, 슬롯 초과분이 큐잉 없이
                 클라우드 mock으로 즉시 우회하는지 실측한다.
                 TTFT 지연 주입(기본 2500ms)은 가상시간으로 계산한다.

산출: 콘솔 표 + 요약 JSON(stdout, --json-out 선택).
종료 코드: 0=모든 단언 통과, 1=내부 오류, 2=인자/정책 거부, 3=단언 실패(FAIL).
"""
from __future__ import annotations

import argparse
import json
import sys
import threading
import time

SCHEMA = "awx.probe.ollama-concurrency-spillover.v1"

# 설계 기본값 — docs/design/OLLAMA_CONCURRENCY_API_FALLBACK_PLAN.md §4/§5.
DEFAULT_PERMITS = 1                 # llm.local-admission.permits-per-model (U-1)
FIRST_TOKEN_TIMEOUT_MS = 2500       # llm.local-admission.first-token-timeout-ms (U-2)
SPILLOVER_BUDGET_MS = 100.0         # 지시서: 슬롯 초과분은 100ms 이내 스필오버
TOTAL_BUDGET_MS = 4000.0            # 지시서: TTFT 스필오버 후 총 응답 상한
FALLBACK_LADDER = ["chatgpt_oauth", "groq", "gemini", "openai", "anthropic"]

# 가상 지연 모델(결정론) — 실제 네트워크 없이 경로별 예상 총 시간을 계산한다.
V_LOCAL_TTFT_MS = 800.0             # 정상 로컬 첫 토큰
V_LOCAL_GEN_MS = 400.0              # 정상 로컬 생성 완료
V_LOCAL_FAILFAST_MS = 50.0          # 503/conn-refused 즉시 실패
V_CLOUD_TTFT_MS = 250.0             # 클라우드 mock 첫 토큰
V_CLOUD_GEN_MS = 400.0              # 클라우드 mock 생성 완료

CLOUD = "cloud"
LOCAL = "local"


def decide(ctx: dict) -> dict:
    """Fast-Spillover 정책 모델 — Java seam의 기대 계약을 순수 함수로 표현한다.

    ctx: {slot_free, gpu_incident, local_status, ttft_ms}
    반환: {route, reason, local_attempts, local_retries, aborted_before_first_token,
           virtual_ms}
    """
    if ctx.get("gpu_incident"):
        # 트리거 D: 사고 플래그 → pre-dispatch 배제. 로컬 시도 자체가 0.
        return _result(CLOUD, "gpu_incident_predispatch", 0, 0, False,
                       V_CLOUD_TTFT_MS + V_CLOUD_GEN_MS)
    if not ctx.get("slot_free", True):
        # 트리거 A: 슬롯 포화 → 큐잉 없이 즉시 클라우드.
        return _result(CLOUD, "local_contended", 0, 0, False,
                       V_CLOUD_TTFT_MS + V_CLOUD_GEN_MS)
    status = ctx.get("local_status")
    if status in (500, 502, 503, 504) or status == "conn_refused":
        # 트리거 C: 로컬 재시도 0회 → 즉시 클라우드.
        return _result(CLOUD, "local_backend_unavailable", 1, 0, False,
                       V_LOCAL_FAILFAST_MS + V_CLOUD_TTFT_MS + V_CLOUD_GEN_MS)
    ttft = ctx.get("ttft_ms")
    if ttft is not None and ttft >= FIRST_TOKEN_TIMEOUT_MS:
        # 트리거 B: Zero-Token Abort & Switch — 토큰 방출 전이므로 투명 전환.
        return _result(CLOUD, "first_token_timeout", 1, 0, True,
                       FIRST_TOKEN_TIMEOUT_MS + V_CLOUD_TTFT_MS + V_CLOUD_GEN_MS)
    return _result(LOCAL, "local_ok", 1, 0, False,
                   V_LOCAL_TTFT_MS + V_LOCAL_GEN_MS)


def _result(route, reason, attempts, retries, aborted, virtual_ms):
    return {"route": route, "reason": reason, "local_attempts": attempts,
            "local_retries": retries,
            "aborted_before_first_token": aborted,
            "virtual_ms": round(virtual_ms, 1)}


# ---------------------------------------------------------------- dry-run

def dry_run(args) -> dict:
    checks = []

    def check(name, cond, detail=""):
        checks.append({"name": name, "pass": bool(cond), "detail": detail})

    # S1: 사용자 N명, 슬롯 permits → 정확히 permits명만 로컬, 나머지 즉시 스필오버.
    users = args.users
    slot_owner = users - 1  # 마지막 사용자가 슬롯 보유 가정(결정론)
    served_local, spilled = 0, 0
    for i in range(users):
        r = decide({"slot_free": i == slot_owner})
        if r["route"] == LOCAL:
            served_local += 1
        else:
            spilled += 1
            check(f"s1_spillover_reason_u{i}", r["reason"] == "local_contended",
                  r["reason"])
    check("s1_local_served", served_local == min(users, args.permits),
          f"local={served_local} expected={min(users, args.permits)}")
    check("s1_spilled", spilled == max(0, users - args.permits),
          f"spilled={spilled}")

    # S2: TTFT 2500ms 초과 → Zero-Token Abort 후 클라우드, 총 가상시간 <= 4000ms.
    r = decide({"slot_free": True, "ttft_ms": args.inject_ttft_ms})
    check("s2_reason", r["reason"] == "first_token_timeout", r["reason"])
    check("s2_aborted_zero_token", r["aborted_before_first_token"])
    check("s2_total_budget", r["virtual_ms"] <= TOTAL_BUDGET_MS,
          f"virtual_ms={r['virtual_ms']} <= {TOTAL_BUDGET_MS}")

    # S3: HTTP 503 → 로컬 재시도 0, 즉시 클라우드.
    r = decide({"slot_free": True, "local_status": 503})
    check("s3_reason", r["reason"] == "local_backend_unavailable", r["reason"])
    check("s3_zero_local_retry", r["local_retries"] == 0)

    # S3b: conn_refused 동일 처리.
    r = decide({"slot_free": True, "local_status": "conn_refused"})
    check("s3b_reason", r["reason"] == "local_backend_unavailable", r["reason"])
    check("s3b_zero_local_retry", r["local_retries"] == 0)

    # S4: gpu_incident → pre-dispatch 배제 (로컬 시도 0).
    r = decide({"slot_free": True, "gpu_incident": True})
    check("s4_reason", r["reason"] == "gpu_incident_predispatch", r["reason"])
    check("s4_no_local_attempt", r["local_attempts"] == 0)

    # S5: 폴백 사다리 순서 불변 (api-routing.yaml tier 순서).
    check("s5_ladder", FALLBACK_LADDER[:4] ==
          ["chatgpt_oauth", "groq", "gemini", "openai"],
          ",".join(FALLBACK_LADDER))

    return {"schema": SCHEMA, "mode": "dry-run", "users": users,
            "permits": args.permits,
            "policy": {"first_token_timeout_ms": FIRST_TOKEN_TIMEOUT_MS,
                       "spillover_budget_ms": SPILLOVER_BUDGET_MS,
                       "total_budget_ms": TOTAL_BUDGET_MS,
                       "fallback_ladder": FALLBACK_LADDER},
            "checks": checks,
            "counts": _check_counts(checks)}


# ------------------------------------------------------------- simulated

def simulated(args) -> dict:
    slots = threading.Semaphore(args.permits)
    results: list = [None] * args.users
    checks = []

    def worker(i):
        rec = {"id": i}
        if args.gpu_incident:
            rec.update(decide({"gpu_incident": True}))
            rec["decision_ms"] = 0.0
            results[i] = rec
            return
        t0 = time.monotonic()
        acquired = slots.acquire(blocking=False)
        decision_ms = (time.monotonic() - t0) * 1000.0
        rec["decision_ms"] = round(decision_ms, 3)
        rec["acquired"] = bool(acquired)
        try:
            if acquired:
                ctx = {"slot_free": True}
                if i == 0 and args.inject_ttft_ms:
                    ctx["ttft_ms"] = args.inject_ttft_ms
                if args.inject_http:
                    ctx["local_status"] = args.inject_http
                rec.update(decide(ctx))
                # 슬롯 점유 = in-flight 로컬 호출. 실측 경합을 만들기 위해
                # 모의 작업 시간(hold_ms)만큼 실제로 점유한다.
                time.sleep(max(0.0, args.hold_ms) / 1000.0)
            else:
                rec.update(decide({"slot_free": False}))
        finally:
            if acquired:
                slots.release()
        results[i] = rec

    threads = [threading.Thread(target=worker, args=(i,), daemon=True,
                                name=f"user-{i}") for i in range(args.users)]
    t0 = time.monotonic()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=10.0)
    wall_ms = (time.monotonic() - t0) * 1000.0

    done = [r for r in results if r is not None]
    acquired_n = [r for r in done if r.get("acquired")]
    contended = [r for r in done if r["reason"] == "local_contended"]

    def check(name, cond, detail=""):
        checks.append({"name": name, "pass": bool(cond), "detail": detail})

    check("all_completed", len(done) == args.users,
          f"done={len(done)}/{args.users}")
    if not args.gpu_incident:
        # 슬롯 획득자만 로컬 진입 가능; 나머지는 큐잉 없이 local_contended 스필오버.
        check("slots_acquired", len(acquired_n) == min(args.users, args.permits),
              f"acquired={len(acquired_n)} expected={min(args.users, args.permits)}")
        check("contended_spillovers",
              len(contended) == args.users - len(acquired_n),
              f"contended={len(contended)} expected={args.users - len(acquired_n)}")
        # 큐잉 없음의 실측 증거: 스필오버 결정(non-blocking acquire+분기) 시간.
        worst = max((r["decision_ms"] for r in contended), default=0.0)
        check("spillover_under_100ms", worst <= SPILLOVER_BUDGET_MS,
              f"max_decision_ms={worst}")
        if args.inject_ttft_ms and not args.inject_http:
            victim = results[0]
            check("ttft_abort", bool(victim) and
                  victim["reason"] == "first_token_timeout" and
                  victim["aborted_before_first_token"],
                  json.dumps(victim, ensure_ascii=False))
            check("ttft_total_budget", bool(victim) and
                  victim["virtual_ms"] <= TOTAL_BUDGET_MS,
                  f"virtual_ms={victim['virtual_ms'] if victim else None}")
        if args.inject_http:
            victims = [r for r in done if r["reason"] == "local_backend_unavailable"]
            check("http5xx_zero_retry", bool(victims) and
                  all(r["local_retries"] == 0 for r in victims),
                  f"victims={len(victims)}")
    else:
        check("incident_predispatch", all(
            r["reason"] == "gpu_incident_predispatch" and r["local_attempts"] == 0
            for r in done), f"done={len(done)}")

    return {"schema": SCHEMA, "mode": "simulated", "users": args.users,
            "permits": args.permits, "wall_ms": round(wall_ms, 1),
            "time_model": "virtual_ms(decision_ms는 실측)",
            "requests": done, "checks": checks,
            "counts": _check_counts(checks)}


def _check_counts(checks: list) -> dict:
    p = sum(1 for c in checks if c["pass"])
    return {"pass": p, "fail": len(checks) - p}


def print_report(summary: dict) -> None:
    print(f"[probe] mode={summary['mode']} users={summary['users']} "
          f"permits={summary['permits']}")
    for c in summary["checks"]:
        print(f"  [{'PASS' if c['pass'] else 'FAIL'}] {c['name']} {c['detail']}")
    counts = summary["counts"]
    print(f"[probe] checks pass={counts['pass']} fail={counts['fail']}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--users", type=int, default=10,
                    help="가상 동시 사용자 수 (지시서 모의 범위 1~10, 상한 64)")
    ap.add_argument("--permits", type=int, default=DEFAULT_PERMITS,
                    help="로컬 슬롯 수 (llm.local-admission.permits-per-model)")
    ap.add_argument("--inject-ttft-ms", type=float, default=2600.0,
                    help="user-0 로컬 호출의 첫 토큰 지연 주입(가상)")
    ap.add_argument("--inject-http", type=int, default=0,
                    help="로컬 호출에 주입할 HTTP 상태(예: 503)")
    ap.add_argument("--hold-ms", type=float, default=40.0,
                    help="슬롯 점유 중 모의 로컬 작업 시간(실측 경합 유도)")
    ap.add_argument("--gpu-incident", action="store_true",
                    help="var/incident/gpu.json 사고 상태 모의(pre-dispatch 배제)")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--simulated", action="store_true",
                    help="실제 스레드 경합 시뮬레이션(네트워크 없음)")
    ap.add_argument("--json-out", default=None, help="요약 JSON 저장 경로")
    args = ap.parse_args()

    if not (1 <= args.users <= 64):
        print("[probe] --users 범위는 1~64", file=sys.stderr)
        return 2
    if not (1 <= args.permits <= 16):
        print("[probe] --permits 범위는 1~16", file=sys.stderr)
        return 2
    if args.dry_run and args.simulated:
        print("[probe] --dry-run과 --simulated는 동시에 지정 불가", file=sys.stderr)
        return 2

    if args.simulated:
        summary = simulated(args)
    else:
        if not args.dry_run:
            print("[probe] 모드 미지정 → dry-run으로 실행", file=sys.stderr)
        summary = dry_run(args)

    print_report(summary)
    blob = json.dumps(summary, ensure_ascii=False, indent=2)
    print(blob)
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as fh:
            fh.write(blob + "\n")
    return 0 if summary["counts"]["fail"] == 0 else 3


if __name__ == "__main__":
    sys.exit(main())
