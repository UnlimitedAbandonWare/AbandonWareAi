#!/usr/bin/env python3
"""AWX Grok CLI Stop hook — UAW Citation Gate.

Contract: PASTE_DEVIN_GROK_CLI_UAW_WEBSEARCH_20261002 (DV4).
Spec: ~/.grok/docs/user-guide/10-hooks.md "Stop Decision Control".

stdin : Stop 이벤트 JSON (camelCase) — hookEventName="stop", reason,
        stopHookActive, lastAssistantMessage, sessionId, promptId, ...
stdout: 허용 → 무출력 exit 0
        차단 → {"decision":"block","reason":"[AWX-UAW] ..."}

게이트 규칙 (lastAssistantMessage 기준 — Grok Stop 페이로드에는
transcript/tool 목록이 없으므로 '이번 턴 web_search 호출'은 답 본문의
웹 근거 흔적으로 근사한다):

  차단 조건 = 웹 근거 흔적(URL·출처 줄·web_search/web_fetch 언급)이 있고
              '출처:' 목록 또는 '웹:' 브레드크럼이 빠졌을 때.

회로 차단기 (턴당 block 최대 1회 — Grok 자체 8회 상한 앞단):
  - stopHookActive == true        → 즉시 exit 0
  - reason != "end_turn"          → exit 0 (세션 종료 관찰성 발화 무시)
  - subagentType 있음             → exit 0 (서브에이전트 턴은 SubagentStop 소관)
Fail-soft: 모든 파싱/실행 오류 → 무출력 exit 0. 네트워크 0, 파일 쓰기 0.

등록: scripts/grok_websearch_install.py --apply
      → ~/.grok/hooks/uaw-citation-gate.json
"""
from __future__ import annotations

import json
import re
import sys

STDIN_CAP = 1048576  # 1 MiB

BLOCK_REASON = (
    "[AWX-UAW] 답변 끝에 '출처:'(번호·제목·날짜·T등급)와 "
    "'웹: <플레이트> · 검색 n회 · 출처 n' 브레드크럼을 "
    "붙여 마무리하십시오."
)

# 웹 근거 흔적 탐지 — 어느 하나라도 맞으면 게이트 적용 대상
URL_RE = re.compile(r"https?://[^\s)\]>\"']+")
SOURCES_RE = re.compile(
    r"(?im)^\s*(?:\[?\d+\]?\s*)?(?:출처|sources?|references?|참고\s*문헌)\s*[:：]")
WEB_TOOL_RE = re.compile(r"(?i)\bweb[_\s]?(?:search|fetch)\b")

# 게이트 통과 조건 — 둘 다 있어야 함
HAS_SOURCE_RE = re.compile(r"(?i)(출처\s*[:：]|^\s*sources?\s*[:：]|^\s*references?\s*[:：])", re.M)
HAS_CRUMB_RE = re.compile(r"(?i)(웹\s*[:：]|^\s*web\s*[:：])", re.M)

SKIP_MARKERS = ("[no-web]", "웹서치 없이", "웹 검색 없이", "웹검색 없이")


def _web_evidence(msg: str) -> bool:
    return bool(URL_RE.search(msg) or SOURCES_RE.search(msg) or WEB_TOOL_RE.search(msg))


def decide(payload: dict) -> str:
    """"block" | "ok" — 절대 예외를 던지지 않는다."""
    try:
        if payload.get("stopHookActive") is True:
            return "ok"  # 회로 차단기: 이번 턴 이미 block 1회 사용
        reason = payload.get("reason") or "end_turn"
        if reason != "end_turn":
            return "ok"  # 세션 종료(channel_closed/shutdown) 등 관찰성 발화
        if payload.get("subagentType"):
            return "ok"  # 서브에이전트 턴은 SubagentStop 소관
        msg = payload.get("lastAssistantMessage")
        if not isinstance(msg, str) or not msg.strip():
            return "ok"
        low = msg.lower()
        if any(marker in low for marker in SKIP_MARKERS):
            return "ok"
        if not _web_evidence(msg):
            return "ok"  # 비(非)웹 일반 답변
        if HAS_SOURCE_RE.search(msg) and HAS_CRUMB_RE.search(msg):
            return "ok"
        return "block"
    except Exception:
        return "ok"


def main() -> int:
    try:
        raw = sys.stdin.buffer.read(STDIN_CAP)
        if not raw.strip():
            return 0
        payload = json.loads(raw.decode("utf-8", errors="replace"))
        if not isinstance(payload, dict):
            return 0
        if decide(payload) == "block":
            sys.stdout.buffer.write(
                json.dumps({"decision": "block", "reason": BLOCK_REASON},
                           ensure_ascii=False).encode("utf-8"))
    except Exception:
        pass  # fail-open: 어떤 오류든 턴을 막지 않는다
    return 0


if __name__ == "__main__":
    sys.exit(main())
