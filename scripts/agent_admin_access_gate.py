#!/usr/bin/env python3
"""agent_admin_access_gate.py
==============================
에이전트가 관리자 권한이 필요한 검증 앞에서 사용자에게 로그인·계정을 묻지 않게
하는 라우팅 게이트. 출력은 JSON 한 줄, 종료코드는 항상 0이다.

  mode = auto_session            scripts/agent_admin_session.py + 서버 측 자동
                                 세션 단서가 있을 때 -> 그 세션으로 접속·검증
  mode = proto_open_observe      서버(127.0.0.1:18180)는 살아 있고 자동 세션
                                 단서가 없을 때 -> PROTO_OPEN 관찰로 진행,
                                 "관리자 로그인 검증 = 관찰(observe)"로 기록
  mode = hold_codex_patch_pending 둘 다 불가 -> HOLD (재개 조건을 next에 기록)

규칙 SSOT: docs/agents-rules/DEMO1-AGENT-ADMIN-AUTO.md
스킬: .agents/skills/demo1-agent-admin-auto-session/SKILL.md

표준 라이브러리만 사용. 네트워크 호출은 127.0.0.1:18180 TCP listen 확인뿐.
var/agent-admin/nonce 는 존재 여부만 본다 — 내용은 절대 읽지 않는다.

Usage:
  python -B scripts/agent_admin_access_gate.py [--root .]
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import sys
from pathlib import Path

SCHEMA = "awx.agent-admin-access-gate.v1"
HOST, PORT = "127.0.0.1", 18180

# 자동 세션 단서: Codex agent-admin-autologin 패치의 실제 신호만 본다.
# (느슨한 agent[-_]?admin 류는 @RestController("agentAdminController") 같은
# 기존 bean 이름을 오탐한다 — 2026-10-03 실측)
AUTO_SESSION_RE = re.compile(
    r"AgentAdminAuto|api/agent-auth/admin-session|agent-auth/admin-session"
    r"|agent-admin-auto|demo\.agent\.admin-auto",
    re.IGNORECASE)
LOG_MARKER_RE = re.compile(
    r"\[AWX\]\[agent-admin-auto\]|bootstrap-admin skipped", re.IGNORECASE)
LOG_TAIL_BYTES = 512 * 1024
MAX_JAVA_FILES = 4096
MAX_JAVA_BYTES = 1024 * 1024


def _script_present(root: Path) -> bool:
    return (root / "scripts" / "agent_admin_session.py").is_file()


def _auto_session_class_present(root: Path) -> bool:
    """main/java 아래 자동 관리자 세션 단서(클래스·엔드포인트 문자열) 존재 여부."""
    base = root / "main" / "java"
    if not base.is_dir():
        return False
    seen = 0
    for dirpath, _dirs, files in os.walk(base):
        for name in files:
            if not name.endswith(".java"):
                continue
            seen += 1
            if seen > MAX_JAVA_FILES:
                return False
            path = Path(dirpath) / name
            if AUTO_SESSION_RE.search(name):
                return True
            try:
                if path.stat().st_size > MAX_JAVA_BYTES:
                    continue
                if AUTO_SESSION_RE.search(
                        path.read_text(encoding="utf-8", errors="replace")):
                    return True
            except OSError:
                continue
    return False


def server_listening(host: str = HOST, port: int = PORT,
                     timeout: float = 0.8) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _nonce_present(root: Path) -> bool:
    # 존재 여부만 — nonce 내용은 절대 읽지 않는다.
    return (root / "var" / "agent-admin" / "nonce").exists()


def _recent_log_markers(root: Path) -> dict:
    """가장 최근 rag-launcher 리스너 로그 꼬리에서 마커 존재 여부."""
    found = {"agent_admin_auto": False, "bootstrap_admin_skipped": False,
             "log": None}
    base = root / "var" / "rag-launcher"
    if not base.is_dir():
        return found
    logs = sorted(
        base.glob("*/chat-ui-vibe-listener-18180.out.log"),
        key=lambda p: p.stat().st_mtime if p.exists() else 0,
        reverse=True)
    if not logs:
        return found
    log = logs[0]
    found["log"] = str(log.relative_to(root))
    try:
        with log.open("rb") as fh:
            fh.seek(0, os.SEEK_END)
            size = fh.tell()
            fh.seek(max(0, size - LOG_TAIL_BYTES))
            tail = fh.read().decode("utf-8", errors="replace")
    except OSError:
        return found
    found["agent_admin_auto"] = bool(
        re.search(r"\[AWX\]\[agent-admin-auto\]", tail))
    found["bootstrap_admin_skipped"] = bool(
        re.search(r"bootstrap-admin skipped", tail))
    return found


def _env_flag_on() -> bool:
    return os.environ.get("AWX_AGENT_ADMIN_AUTO", "").strip().lower() in {
        "1", "true", "on", "yes"}


def decide(*, script: bool, server_side_ready: bool, server_up: bool):
    if script and server_side_ready:
        return (
            "auto_session",
            "python -B scripts/agent_admin_session.py --base-url "
            "http://127.0.0.1:18180 (로컬 전용 — 공개 주소·Forwarded 헤더 금지)")
    if server_up:
        return (
            "proto_open_observe",
            "PROTO_OPEN 관찰로 진행 — http://127.0.0.1:18180 읽기 요청으로 확인하고 "
            "'관리자 로그인 검증 = 관찰(observe)'로 기록 (docs/agents-rules/"
            "DEMO1-AGENT-ADMIN-AUTO.md)")
    return (
        "hold_codex_patch_pending",
        "HOLD — 127.0.0.1:18180 미기동. 서버 기동 후 재실행하거나 "
        "agent-admin-autologin 패치 반영 + AWX_AGENT_ADMIN_AUTO 대기. "
        "사용자에게 로그인·계정 질문 금지")


def probe(root: Path) -> dict:
    script = _script_present(root)
    cls = _auto_session_class_present(root)
    nonce = _nonce_present(root)
    logs = _recent_log_markers(root)
    env_on = _env_flag_on()
    server_up = server_listening()
    server_side_ready = cls or nonce or logs["agent_admin_auto"] or env_on
    mode, nxt = decide(script=script, server_side_ready=server_side_ready,
                       server_up=server_up)
    return {
        "schema": SCHEMA,
        "mode": mode,
        "next": nxt,
        "ask_user": False,
        "evidence": [
            f"auto_session_class={'present' if cls else 'absent'}",
            f"agent_admin_session.py={'present' if script else 'absent'}",
            f"{HOST}:{PORT}={'listening' if server_up else 'closed'}",
            f"var/agent-admin/nonce={'present' if nonce else 'absent'} "
            "(existence only)",
            f"log_marker={'agent-admin-auto' if logs['agent_admin_auto'] else ('bootstrap-admin-skipped' if logs['bootstrap_admin_skipped'] else 'none')}"
            + (f" in {logs['log']}" if logs["log"] else " (no listener log)"),
            f"env AWX_AGENT_ADMIN_AUTO={'set' if env_on else 'unset'}",
        ],
    }


def _emit(result: dict) -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        print(json.dumps(result, ensure_ascii=False))
    except Exception:
        print(json.dumps(result, ensure_ascii=True))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="agent admin access gate")
    parser.add_argument("--root", default=".",
                        help="repo root for file probes (default: cwd)")
    args = parser.parse_args(argv)
    try:
        result = probe(Path(args.root).resolve())
    except Exception as exc:  # 게이트 자체 실패도 질문으로 새지 않게 HOLD로
        result = {
            "schema": SCHEMA,
            "mode": "hold_codex_patch_pending",
            "next": "HOLD — gate probe failed; docs/agents-rules/"
                    "DEMO1-AGENT-ADMIN-AUTO.md 참조. 사용자 질문 금지",
            "ask_user": False,
            "evidence": [f"probe_error={exc.__class__.__name__}"],
        }
    _emit(result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
