#!/usr/bin/env python3
"""AWX agy hook — PreInvocation "웹서치 먼저" + Stop 인용 게이트.

Contracts: DEMO1-DEVIN-AGY-WEBSEARCH-DEFAULT-20261002 (pre),
           DEMO1-DEVIN-AGY-UAW-WEB-OPTIMIZE-20261002 (stop, UAW CitationGate 투영).

stdin : hook payload JSON (conversationId, transcriptPath, invocationNum,
        또는 Stop: executionNum, terminationReason, ...).
stdout: pre → {} 또는 {"injectSteps":[{"ephemeralMessage": "..."}]}
        stop → {} 또는 {"decision":"continue","reason":"..."}
Fail-open: 어떤 오류든 exit 0 — 절대 agy를 막지 않는다. 네트워크 접근 0.

Deploy copy: %USERPROFILE%\\.gemini\\config\\hooks\\agy_websearch_hook.py
(agy_websearch_install.py --apply 가 복사한다; sha는 이 원본과 동일해야 한다).
Stop 상태: <deploy>/state/<conversationId>.json — 턴당 continue 최대 1회.
"""
from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

TAIL_BYTES = 262144       # transcript 뒤쪽만 읽는다 (256 KiB)
LOG_CAP_BYTES = 204800    # 선택 로그 상한 (200 KiB)

# transcript tool_calls[].name 에서 관측된 웹서치 도구 이름들.
# 확인된 것: search_web (agy 1.2.14 transcript). 나머지는 계열 이름 방어.
WEBSEARCH_NAMES = frozenset({
    "search_web", "web_search", "websearch",
    "google_search", "google_web_search", "web_search_query",
})

SKIP_MARKERS = ("[no-web]", "웹서치 없이", "웹 검색 없이", "웹검색 없이")

# --- Stop 인용 게이트 상수 ---
# step.type 이 이 값이면 그 자체로 웹 조회가 일어난 증거(실측 transcript: SEARCH_WEB).
WEBSEARCH_STEP_TYPES = frozenset({
    "search_web", "web_search", "websearch", "google_search",
    "google_web_search", "web_search_query", "read_url", "open_url",
})
# 어시스턴트의 최종 답으로 취급하는 step.type (실측: PLANNER_RESPONSE에 content).
ANSWER_TYPES = frozenset({
    "planner_response", "ai_response", "assistant_response",
    "assistant", "model_response", "response",
})
# 도구 결과형 step — 답 본문 후보에서 제외.
TOOLISH_TYPES = frozenset({
    "run_command", "search_web", "read_url", "tool_result",
    "function_response", "tool_call", "user_input", "system_message",
    "conversation_history",
})
STOP_REASON = (
    "[AWX] 답 끝에 출처:(제목·날짜·T등급) 목록과 "
    "'웹: 플레이트 · 검색 n회 · 본문확인 n · 출처 n(T1 n) · 모순 유/무' "
    "한 줄을 붙여 다시 마무리하세요."
)
STATE_MAX_AGE_S = 7 * 86400

REMINDER = (
    "[AWX] 이번 요청에 아직 웹서치를 안 했습니다. 답하기 전에 관련 공식 문서를 "
    "웹서치로 최소 1회 확인하고, 답 끝에 출처: 줄을 다세요. "
    "(사용자가 [no-web]이라고 하면 생략)"
)

OUT_INJECT = {"injectSteps": [{"ephemeralMessage": REMINDER}]}


def _read_tail(path) -> str | None:
    try:
        size = os.stat(path).st_size
        with open(path, "rb") as fh:
            if size > TAIL_BYTES:
                fh.seek(size - TAIL_BYTES)
            return fh.read().decode("utf-8", errors="replace")
    except (OSError, ValueError):
        return None


def _user_text(step) -> str:
    content = step.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, dict):
        parts = content.get("parts")
        if isinstance(parts, list):
            return " ".join(
                str(p.get("text") or "") for p in parts if isinstance(p, dict))
        return str(content.get("text") or "")
    if isinstance(content, list):
        return " ".join(
            str(p.get("text") or "") for p in content if isinstance(p, dict))
    for key in ("text", "message"):
        value = step.get(key)
        if isinstance(value, str):
            return value
    return ""


def decide(payload) -> str:
    """inject | skip_marker | skip_searched — 절대 예외를 던지지 않는다."""
    try:
        transcript = payload.get("transcriptPath")
        if not transcript:
            return "inject"
        text = _read_tail(transcript)
        if text is None:
            return "inject"
        steps = []
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                step = json.loads(line)
            except ValueError:
                continue  # 마지막 잘린 줄 등
            if isinstance(step, dict):
                steps.append(step)
        last_user = -1
        for index, step in enumerate(steps):
            if step.get("type") == "USER_INPUT":
                last_user = index
        if last_user < 0:
            return "inject"
        markers = _user_text(steps[last_user]).lower()
        if any(marker in markers for marker in SKIP_MARKERS):
            return "skip_marker"
        for step in steps[last_user + 1:]:
            calls = step.get("tool_calls")
            if not isinstance(calls, list):
                continue
            for call in calls:
                if isinstance(call, dict) and str(
                        call.get("name", "")).lower() in WEBSEARCH_NAMES:
                    return "skip_searched"
        return "inject"
    except Exception:
        return "inject"


def _emit(body) -> None:
    sys.stdout.buffer.write(
        json.dumps(body, ensure_ascii=False).encode("utf-8"))


def _parse_steps(text: str) -> list:
    steps = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            step = json.loads(line)
        except ValueError:
            continue  # 마지막 잘린 줄 등
        if isinstance(step, dict):
            steps.append(step)
    return steps


def _turn_state(text: str) -> tuple[list, int]:
    """(steps, 마지막 USER_INPUT 인덱스). 없으면 -1."""
    steps = _parse_steps(text)
    last_user = -1
    for index, step in enumerate(steps):
        if step.get("type") == "USER_INPUT":
            last_user = index
    return steps, last_user


def _web_done(steps, last_user: int) -> bool:
    for step in steps[last_user + 1:]:
        if str(step.get("type", "")).lower() in WEBSEARCH_STEP_TYPES:
            return True
        calls = step.get("tool_calls")
        if isinstance(calls, list):
            for call in calls:
                if isinstance(call, dict) and str(
                        call.get("name", "")).lower() in WEBSEARCH_NAMES:
                    return True
    return False


def _last_answer(steps, last_user: int) -> str:
    fallback = ""
    for step in reversed(steps[last_user + 1:]):
        stype = str(step.get("type", "")).lower()
        if stype in TOOLISH_TYPES:
            continue
        text = _user_text(step)
        if not text:
            continue
        if stype in ANSWER_TYPES:
            return text
        if not fallback:
            fallback = text
    return fallback


def _state_dir(override: str | None) -> Path | None:
    if override:
        return Path(override)
    env = os.environ.get("AWX_AGY_WEBSEARCH_STATE_DIR") or ""
    if env:
        return Path(env)
    try:
        here = Path(__file__).resolve()
        if here.parent.name.lower() == "hooks":
            return here.parent / "state"
    except OSError:
        pass
    return None


def _conv_id(payload) -> str:
    raw = str(payload.get("conversationId") or payload.get("transcriptPath") or "x")
    safe = "".join(c for c in raw if c.isalnum() or c in "-_")
    return safe[:80] or "x"


def _gc_state(state_dir: Path) -> None:
    try:
        now = time.time()
        for f in state_dir.glob("*.json"):
            try:
                if now - f.stat().st_mtime > STATE_MAX_AGE_S:
                    f.unlink()
            except OSError:
                pass
    except OSError:
        pass


def decide_stop(payload, state_dir=None) -> str:
    """continue_citation | ok — 절대 예외를 던지지 않는다."""
    try:
        reason = str(payload.get("terminationReason") or "")
        if reason and reason != "model_stop":
            return "ok"
        transcript = payload.get("transcriptPath")
        if not transcript:
            return "ok"
        text = _read_tail(transcript)
        if text is None:
            return "ok"
        steps, last_user = _turn_state(text)
        if last_user < 0:
            return "ok"
        markers = _user_text(steps[last_user]).lower()
        if any(marker in markers for marker in SKIP_MARKERS):
            return "ok"
        if not _web_done(steps, last_user):
            return "ok"  # 웹서치 0 → 선행 pre-hook 담당
        answer = _last_answer(steps, last_user)
        if "출처:" in answer and "웹:" in answer:
            return "ok"
        # 회로 차단기: 같은 턴(마지막 USER_INPUT 위치)에 continue는 1회뿐
        turn_key = str(last_user)
        if state_dir is not None:
            state_file = state_dir / (_conv_id(payload) + ".json")
            try:
                prev = json.loads(state_file.read_bytes() or b"{}") \
                    if state_file.exists() else {}
            except (OSError, ValueError):
                prev = {}
            if isinstance(prev, dict) and str(prev.get("turn_key")) == turn_key:
                return "ok"
            try:
                state_dir.mkdir(parents=True, exist_ok=True)
                state_file.write_text(
                    json.dumps({"turn_key": turn_key,
                                "at": time.strftime("%Y-%m-%dT%H:%M:%S")},
                               ensure_ascii=False),
                    encoding="utf-8")
                _gc_state(state_dir)
            except OSError:
                return "ok"  # 상태 기록 실패 시 continue 반복 위험 → 열어둠
        return "continue_citation"
    except Exception:
        return "ok"


def _log(decision: str, started: float) -> None:
    # 배치본(hooks/ 아래) 또는 환경변수 지정 시에만 로그 — 레포 scripts/ 실행은 무로그.
    log_path = os.environ.get("AWX_AGY_WEBSEARCH_HOOK_LOG") or ""
    if not log_path:
        try:
            here = Path(__file__).resolve()
            if here.parent.name.lower() == "hooks":
                log_path = str(here.with_suffix(".log"))
        except OSError:
            return
    if not log_path:
        return
    try:
        line = "%s decision=%s ms=%d\n" % (
            time.strftime("%Y-%m-%dT%H:%M:%S"), decision,
            int((time.monotonic() - started) * 1000))
        path = Path(log_path)
        if path.exists() and path.stat().st_size > LOG_CAP_BYTES:
            path.write_text(line, encoding="utf-8")
        else:
            with path.open("a", encoding="utf-8") as stream:
                stream.write(line)
    except OSError:
        pass


def main() -> int:
    started = time.monotonic()
    try:
        event = "pre"
        state_dir = None
        argv = sys.argv[1:]
        for i, arg in enumerate(argv):
            if arg == "--event" and i + 1 < len(argv):
                event = argv[i + 1].lower()
            elif arg.startswith("--event="):
                event = arg.split("=", 1)[1].lower()
            elif arg == "--state-dir" and i + 1 < len(argv):
                state_dir = Path(argv[i + 1])
            elif arg.startswith("--state-dir="):
                state_dir = Path(arg.split("=", 1)[1])
        raw = sys.stdin.buffer.read(1048576)
        if not raw.strip():
            decision = "bad_stdin"
            _emit({})
        else:
            try:
                payload = json.loads(raw.decode("utf-8", errors="replace"))
            except ValueError:
                payload = None
            if not isinstance(payload, dict):
                decision = "bad_stdin"
                _emit({})
            elif event == "stop":
                decision = decide_stop(payload, _state_dir(
                    str(state_dir) if state_dir else None))
                _emit({"decision": "continue", "reason": STOP_REASON}
                      if decision == "continue_citation" else {})
            else:
                decision = decide(payload)
                _emit(OUT_INJECT if decision == "inject" else {})
        _log(decision, started)
    except Exception:
        try:
            _emit({})
            _log("error", started)
        except Exception:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
