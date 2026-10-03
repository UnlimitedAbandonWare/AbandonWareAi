#!/usr/bin/env python3
"""test_agent_admin_access_gate.py — agent_admin_access_gate 회귀 테스트.

세 mode(auto_session / proto_open_observe / hold_codex_patch_pending),
nonce 내용 미출력, ask_user=false 고정, exit code 0을 검증한다.
표준 라이브러리만 사용. 네트워크는 server_listening을 mock으로 대체.
"""
from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import agent_admin_access_gate as gate  # noqa: E402


def _make_root(with_script=False, with_class=False, with_nonce=False,
               with_log_marker=False) -> Path:
    root = Path(tempfile.mkdtemp(prefix="aag-test-"))
    if with_script:
        s = root / "scripts"
        s.mkdir(parents=True, exist_ok=True)
        (s / "agent_admin_session.py").write_text("# stub\n",
                                                  encoding="utf-8")
    if with_class:
        j = root / "main" / "java" / "com" / "example" / "lms" / "api"
        j.mkdir(parents=True, exist_ok=True)
        (j / "AgentAdminAutoSessionController.java").write_text(
            "class AgentAdminAutoSessionController {}\n", encoding="utf-8")
    if with_nonce:
        n = root / "var" / "agent-admin"
        n.mkdir(parents=True, exist_ok=True)
        (n / "nonce").write_text("FAKE-SECRET-NONCE-12345\n",
                                 encoding="utf-8")
    if with_log_marker:
        d = root / "var" / "rag-launcher" / "20990101-000000-deadbeef"
        d.mkdir(parents=True, exist_ok=True)
        (d / "chat-ui-vibe-listener-18180.out.log").write_text(
            "2026-01-01T00:00:00 INFO [AWX][agent-admin-auto] issued\n",
            encoding="utf-8")
    return root


class AgentAdminAccessGateTest(unittest.TestCase):

    def setUp(self):
        # 환경 플래그가 로컬 환경에 따라 결과를 바꾸지 않게 격리한다.
        self._env = mock.patch.dict(
            os.environ, {"AWX_AGENT_ADMIN_AUTO": ""})
        self._env.start()

    def tearDown(self):
        self._env.stop()

    def _probe(self, root: Path, server_up: bool) -> dict:
        with mock.patch.object(gate, "server_listening",
                               return_value=server_up):
            return gate.probe(root)

    # --- 세 mode ---

    def test_mode_auto_session(self):
        root = _make_root(with_script=True, with_class=True)
        out = self._probe(root, server_up=True)
        self.assertEqual("auto_session", out["mode"])
        self.assertIn("agent_admin_session.py", out["next"])

    def test_mode_proto_open_observe(self):
        root = _make_root()
        out = self._probe(root, server_up=True)
        self.assertEqual("proto_open_observe", out["mode"])
        self.assertIn("127.0.0.1:18180", out["next"])

    def test_mode_hold_codex_patch_pending(self):
        root = _make_root()
        out = self._probe(root, server_up=False)
        self.assertEqual("hold_codex_patch_pending", out["mode"])

    def test_script_without_server_side_still_observes(self):
        # 스크립트만 있고 서버 측 단서가 없으면 자동 세션이 아니라 관찰 모드.
        root = _make_root(with_script=True)
        out = self._probe(root, server_up=True)
        self.assertEqual("proto_open_observe", out["mode"])

    def test_script_plus_env_flag_is_auto_session(self):
        root = _make_root(with_script=True)
        with mock.patch.dict(os.environ, {"AWX_AGENT_ADMIN_AUTO": "true"}):
            out = self._probe(root, server_up=True)
        self.assertEqual("auto_session", out["mode"])

    def test_unrelated_agent_admin_bean_name_is_not_evidence(self):
        # @RestController("agentAdminController") 같은 기존 bean 이름은
        # 자동 세션 단서가 아니다 (2026-10-03 오탐 회귀).
        root = _make_root(with_script=True)
        j = root / "main" / "java" / "com" / "abandonware" / "ai" / "agent" / "web"
        j.mkdir(parents=True, exist_ok=True)
        (j / "AdminController.java").write_text(
            '@RestController("agentAdminController")\n'
            "class AdminController {}\n", encoding="utf-8")
        out = self._probe(root, server_up=True)
        self.assertEqual("proto_open_observe", out["mode"])
        self.assertTrue(any("auto_session_class=absent" in e
                            for e in out["evidence"]))

    # --- ask_user 고정 / nonce 비밀 ---

    def test_ask_user_always_false(self):
        for kwargs, up in [
            (dict(with_script=True, with_class=True), True),
            (dict(), True),
            (dict(), False),
        ]:
            out = self._probe(_make_root(**kwargs), server_up=up)
            self.assertIs(False, out["ask_user"], out)

    def test_nonce_content_never_leaks(self):
        root = _make_root(with_script=True, with_nonce=True)
        out = self._probe(root, server_up=True)
        blob = json.dumps(out, ensure_ascii=False)
        self.assertNotIn("FAKE-SECRET-NONCE-12345", blob)
        # 존재 여부만 evidence에 남고 값은 나오지 않는다.
        self.assertTrue(any("nonce=present" in e for e in out["evidence"]))
        # nonce 파일 바이트가 그대로인지(읽기/변경 없음).
        nonce = root / "var" / "agent-admin" / "nonce"
        self.assertEqual("FAKE-SECRET-NONCE-12345\n",
                         nonce.read_text(encoding="utf-8"))

    # --- CLI 계약 ---

    def test_main_exit_zero_and_json_line(self):
        root = _make_root()
        buf = io.StringIO()
        with mock.patch.object(gate, "server_listening",
                               return_value=True):
            with redirect_stdout(buf):
                code = gate.main(["--root", str(root)])
        self.assertEqual(0, code)
        line = buf.getvalue().strip().splitlines()
        self.assertEqual(1, len(line))
        out = json.loads(line[0])
        self.assertEqual("proto_open_observe", out["mode"])
        self.assertIs(False, out["ask_user"])

    def test_main_never_raises_on_bad_root(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = gate.main(["--root", "Z:\\definitely\\missing\\path"])
        self.assertEqual(0, code)
        out = json.loads(buf.getvalue().strip())
        self.assertIs(False, out["ask_user"])
        self.assertIn(out["mode"], ("proto_open_observe",
                                    "hold_codex_patch_pending"))


if __name__ == "__main__":
    unittest.main()
