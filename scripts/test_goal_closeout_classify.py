#!/usr/bin/env python3
"""Tests for scripts/goal_closeout_classify.py.

Fixtures are trimmed excerpts of real 2026-10-08/09 Codex goal-session closeout
messages (no secret values - verified when extracted)."""
import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "goal_closeout_classify", ROOT / "scripts" / "goal_closeout_classify.py")
GCC = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GCC)


# 10-08 16:19 nova-focus-text-retention: source applied, tests passed, goal
# ended "blocked" on server-down + real-glasses items.
S1_NOVA_TEXT_RETENTION = """
| 항목 | 상태 |
|---|---|
| 집중 표시 회귀 테스트 | **27/27 통과** |
| 관련 Java 상태·표시 계약 테스트 | **32/32 통과**, Gradle 빌드 성공 |
| 별도 서버의 새 브라우저 세션 인증 검사 | 로그인·차단·로그아웃 후 차단 통과 |
| 전체 Display 테스트 | **208/209 통과** — 수정 범위 밖의 스크립트 개수 검사 1개 실패 |
| 실제 `/chat` | 로컬·공개 URL 모두 HTTP 응답을 받지 못함. 로컬 18180 리스너 **0개** |
| 착용 렌즈의 문자 유지 | **미검증** |
"""

# 10-08 18:07 fold6_lens_recovery: Java 229 / JS 137 PASS; blocked on
# user-only glasses check + launcher env failure + stale watch.state.
S2_FOLD6_LENS_RECOVERY = """
| 이전 JVM·DevWatch | 19:46 KST 기준 이전 JVM·DevWatch 프로세스 없음, 18180·18181·18182 수신 프로세스 없음 |
| 최신 런처 시도 | 다른 실행자의 기동 기록이 project-settings-load-failed로 종료 |
| Fold6→안경 착용 확인 | **NOT_RUN** |
| Java 회귀 | 229 통과 |
| JS 회귀 | 137 통과 |
"""

# 10-08 18:08 lens_links_model_speed: 21 files edited; GREEN withheld on
# pre-existing router failures + another chat editing the same file.
S3_LENS_LINKS = """
- **실제 안경 표시**, Gemini 계정의 생성 속도는 관찰하지 못했습니다.
- 공유 서버 `18180`이 이번 수정 빌드로 교체됐다는 증거도 확보하지 않았습니다.
- 라우터 테스트의 기존 실패 12개는 이번 변경과 무관합니다.
- 제 lease 반환 후 다른 Fold6 채팅이 `NovaFocusService.java`를 수정했습니다.
- GLM 독립 검토는 타임아웃으로 결과를 받지 못했습니다.
"""

# 10-08 19:04 naver_apihub_config_align: blocked by foreign live lease AND
# still-unresolved in-scope probe failures -> partial, not blocked.
S4_NAVER_ALIGN = """
- **resolver의 미해결 실패:** 잘못된 provider mode에서도 legacy pair로 NAVER가 활성화됩니다.
- **두 Python probe의 미해결 실패:** `openapi` 선택을 무시하고 완전한 legacy pair를 선택하지 않습니다.
- **차단:** 필수 파일의 소유권은 다른 세션의 live lease가 점유 중 — 강제 해제 금지 규칙상 대기만 가능합니다.
- **적용·검증한 변경:** 직렬화 노출 방지, 검사기 오탐 수정 — 80개 통과.
"""

# 10-09 09:25 fold6-reload-resume_R2: closed complete; NOT_RUN items remain
# user-only; launcher env failure recorded separately.
S5_FOLD6_RELOAD = """
- 실제 Fold6의 마이크 권한 수명주기, 페어링된 기기의 새로고침, 렌즈 자막 연속성은 **NOT_RUN**입니다.
- 기존 JS 회귀의 실패는 이번 변경과 무관한 기존 실패로 분류했습니다.
- 런처 기동은 project-settings-load-failed로 실패가 반복됐습니다.
- PROTO_OPEN 인증 정책 항목은 실패 결과로만 기록했습니다.
- 소스 구현 목표는 완료로 닫았습니다.
"""

# 10-09 11:14 nova-focus-keepalive: JS 76/76 + Java 180 PASS; ended PARTIAL on
# admin-login proof, diag-URL 403, Fold6 mic/ASR/lens NOT_RUN.
S6_NOVA_KEEPALIVE = """
| JS 회귀 | **76/76 통과** |
| Java 회귀 | **180 통과** |
| Fold6 마이크·ASR·자동 힌트 연속성 및 안경 | **NOT_RUN** |
| 기존 진단 URL | 403 반환, 재시도 0 |
| 관리자 로그인 입증 | 미확인 |
| 보고서 lease | git-operation-active exit 6 거절 |
"""

# Synthetic: an in-scope test failure must still block completion.
S7_AGENT_FAIL = """
| 회귀 테스트 | 24/27 통과 — 이번 변경 범위 내 3개 실패 |
| 린트 | 통과 |
"""


class CloseoutClassifyTests(unittest.TestCase):
    def classify(self, text):
        return GCC.classify_text(text)

    def test_s1_complete_user_only_glasses(self):
        res = self.classify(S1_NOVA_TEXT_RETENTION)
        self.assertEqual(res["verdict"], "complete")
        self.assertTrue(any("착용" in t or "렌즈" in t for t in res["userOnly"]))
        self.assertEqual(res["counts"]["AGENT_BLOCKING"], 0)

    def test_s2_complete_launcher_external(self):
        res = self.classify(S2_FOLD6_LENS_RECOVERY)
        self.assertEqual(res["verdict"], "complete")
        self.assertTrue(any("착용" in t or "Fold6" in t for t in res["userOnly"]))
        self.assertTrue(any("project-settings-load-failed" in t or "런처" in t
                            for t in res["external"]))

    def test_s3_complete_existing_failures_external(self):
        res = self.classify(S3_LENS_LINKS)
        self.assertEqual(res["verdict"], "complete")
        self.assertEqual(res["counts"]["AGENT_BLOCKING"], 0)
        self.assertGreaterEqual(res["counts"]["EXTERNAL"], 2)

    def test_s4_lease_external_but_unresolved_partial(self):
        res = self.classify(S4_NAVER_ALIGN)
        self.assertEqual(res["verdict"], "partial")
        self.assertEqual(res["counts"]["AGENT_BLOCKING"], 2)
        self.assertTrue(any("lease" in t for t in res["external"]))

    def test_s5_complete_device_notrun_user_only(self):
        res = self.classify(S5_FOLD6_RELOAD)
        self.assertEqual(res["verdict"], "complete")
        self.assertTrue(any("Fold6" in t or "마이크" in t or "렌즈" in t
                            for t in res["userOnly"]))

    def test_s6_complete_blocked_items_reclassified(self):
        res = self.classify(S6_NOVA_KEEPALIVE)
        self.assertEqual(res["verdict"], "complete")
        self.assertTrue(res["headline"].startswith("DONE"))
        self.assertEqual(res["counts"]["AGENT_BLOCKING"], 0)
        self.assertGreaterEqual(res["counts"]["USER_ONLY"], 1)
        self.assertTrue(any("403" in t or "진단" in t for t in res["external"]))

    def test_s7_in_scope_failure_partial(self):
        res = self.classify(S7_AGENT_FAIL)
        self.assertEqual(res["verdict"], "partial")
        self.assertEqual(res["counts"]["AGENT_BLOCKING"], 1)

    def test_not_run_never_promoted_to_pass(self):
        res = self.classify(S6_NOVA_KEEPALIVE)
        for it in res["items"]:
            if it["class"] in ("USER_ONLY", "EXTERNAL"):
                self.assertNotEqual(it["status"], "PASS")

    def test_empty_report_blocked_is_conservative(self):
        res = self.classify("- 전체 회귀 테스트 실패")
        self.assertEqual(res["verdict"], "blocked")
        self.assertEqual(res["counts"]["AGENT_BLOCKING"], 1)


if __name__ == "__main__":
    unittest.main()
