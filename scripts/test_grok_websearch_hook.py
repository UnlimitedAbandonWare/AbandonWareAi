#!/usr/bin/env python3
"""test_grok_websearch_hook.py — Grok Stop 인용 게이트 단위 테스트.

Contract: PASTE_DEVIN_GROK_CLI_UAW_WEBSEARCH_20261002 (DV5).
  1) 인용+브레드크럼 완비 답변      → allow (exit 0, 무출력)
  2) 인용 누락 웹 답변             → {"decision":"block"} 반환
  3) stopHookActive==true          → 강제 allow (회로 차단기)
  4) 비(非)웹 일반 답변            → allow
  5) JSON 오류/예외 입력           → fail-soft allow (exit 0)
실행: python -B scripts/test_grok_websearch_hook.py  → exit 0 = ALL PASS
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

HOOK = Path(__file__).resolve().parent / "grok_websearch_hook.py"

GOOD_ANSWER = (
    "결론: dot 한도는 프로모션성으로 보면 된다 [1].\n"
    "근거 문단 [1][2].\n"
    "출처: [1] Dots usage help (2026-09, T1, 본문확인) https://example.com/a\n"
    "웹: W1_AUTH · 검색 4회 · 본문확인 2 · 출처 3(T1 2) · 모순 없음\n"
)
MISSING_ANSWER = (
    "공식 문서를 보면 된다. 자세한 내용은 https://example.com/docs 참고."
)
PLAIN_ANSWER = (
    "로컬 파일 목록을 확인했습니다. scripts/ 아래 fuse 스크립트가 있습니다."
)
EN_SOURCES_ONLY = (
    "Per the docs [1].\nSources: [1] Example (https://example.com)\n"
)


def run_hook(payload) -> tuple[int, str]:
    raw = payload if isinstance(payload, str) else json.dumps(
        payload, ensure_ascii=False)
    proc = subprocess.run([sys.executable, "-B", str(HOOK)],
                          input=raw.encode("utf-8"),
                          capture_output=True, timeout=15)
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def check(name: str, ok: bool, detail: str = "") -> bool:
    print("%s %s %s" % ("PASS" if ok else "FAIL", name, detail))
    return ok


def main() -> int:
    results = []

    code, out = run_hook({"hookEventName": "stop", "reason": "end_turn",
                          "stopHookActive": False,
                          "lastAssistantMessage": GOOD_ANSWER})
    results.append(check("cited+crumb -> allow",
                         code == 0 and not out.strip(), "exit=%d" % code))

    code, out = run_hook({"hookEventName": "stop", "reason": "end_turn",
                          "stopHookActive": False,
                          "lastAssistantMessage": MISSING_ANSWER})
    try:
        blocked = json.loads(out).get("decision") == "block"
    except ValueError:
        blocked = False
    results.append(check("web-answer missing citation -> block",
                         code == 0 and blocked, "out=%s" % out.strip()[:80]))

    code, out = run_hook({"hookEventName": "stop", "reason": "end_turn",
                          "stopHookActive": True,
                          "lastAssistantMessage": MISSING_ANSWER})
    results.append(check("stopHookActive -> allow (breaker)",
                         code == 0 and not out.strip(), "exit=%d" % code))

    code, out = run_hook({"hookEventName": "stop", "reason": "end_turn",
                          "stopHookActive": False,
                          "lastAssistantMessage": PLAIN_ANSWER})
    results.append(check("non-web answer -> allow",
                         code == 0 and not out.strip(), "exit=%d" % code))

    for label, bad in (("invalid-json", "{not json"),
                       ("non-object", "[1,2,3]"),
                       ("empty", "")):
        code, out = run_hook(bad)
        results.append(check("fail-soft %s -> allow" % label,
                             code == 0 and not out.strip(), "exit=%d" % code))

    code, out = run_hook({"hookEventName": "stop", "reason": "shutdown",
                          "stopHookActive": False,
                          "lastAssistantMessage": MISSING_ANSWER})
    results.append(check("session-end fire -> allow",
                         code == 0 and not out.strip(), "exit=%d" % code))

    code, out = run_hook({"hookEventName": "stop", "reason": "end_turn",
                          "stopHookActive": False,
                          "lastAssistantMessage": EN_SOURCES_ONLY})
    try:
        blocked = json.loads(out).get("decision") == "block"
    except ValueError:
        blocked = False
    results.append(check("sources only, no crumb -> block",
                         code == 0 and blocked, "out=%s" % out.strip()[:80]))

    print("---")
    if all(results):
        print("ALL PASS (%d/%d)" % (len(results), len(results)))
        return 0
    print("FAILED %d/%d" % (len(results) - sum(results), len(results)))
    return 1


if __name__ == "__main__":
    sys.exit(main())
