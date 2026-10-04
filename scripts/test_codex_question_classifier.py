import unittest

from codex_question_classifier import classify

import io
from contextlib import redirect_stdout
from codex_question_classifier import (
    classify_with_options, main, pick_option)


class CodexQuestionClassifierTest(unittest.TestCase):

    def assertVerdict(self, text, verdict, rule=None):
        result = classify(text)
        self.assertEqual(verdict, result["verdict"], f"{text!r} -> {result}")
        if rule is not None:
            self.assertEqual(rule, result["rule"], f"{text!r} -> {result}")
        return result

    # --- 실제 질문 재현 (2026-10-02 settings-routing 세션) ---

    def test_real_scope_question_is_auto_d1(self):
        self.assertVerdict(
            "이번 /goal의 범위는 목표 파일 읽기·확인까지인가, "
            "Phase A/B 구현·A1~A8 검증까지인가?",
            "AUTO", "D1")

    def test_real_baseline_criterion_conflict_is_auto_d2(self):
        self.assertVerdict(
            "chat.js에 이미 다른 세션 diff(+104/-12)가 있어 'diff 출력 없음' "
            "기준과 양립 불가. '이번 세션 수정 0, 시작·종료 SHA-256 동일'로 "
            "판정해도 되나?",
            "AUTO", "D2")

    def test_glm_400_failure_is_auto_d6(self):
        self.assertVerdict(
            "GLM 보조가 HTTP 400(모델 미지원)으로 실패했는데 재시도할까요?",
            "AUTO", "D6")

    # --- ASK_ONCE 범주 ---

    def test_dataset_delete_is_ask_once(self):
        self.assertVerdict("데이터셋 지워도 될까요?", "ASK_ONCE",
                           "ask-delete-data")

    def test_flag_default_true_is_ask_once(self):
        self.assertVerdict(
            "routing.enabled 기본값 true로 바꿀까요?", "ASK_ONCE",
            "ask-flag-default-on")

    def test_git_push_is_ask_once(self):
        self.assertVerdict("git push 할까요?", "ASK_ONCE", "ask-git-remote")

    def test_public_deploy_is_ask_once(self):
        self.assertVerdict("cloudflared로 공개 배포할까요?", "ASK_ONCE",
                           "ask-public-deploy")

    def test_schema_change_is_ask_once(self):
        self.assertVerdict("users 테이블 스키마를 변경해도 될까요?",
                           "ASK_ONCE", "ask-schema-change")

    # --- HOLD: ASK_ONCE 범주 2개 이상 혼합 ---

    def test_compound_ask_is_hold(self):
        self.assertVerdict("데이터셋을 삭제하고 결과를 git push 할까요?",
                           "HOLD", "ask-compound")

    # --- AUTO D 표 나머지 ---

    def test_doc_line_mismatch_is_auto_d3(self):
        self.assertVerdict(
            "문서의 라인 번호가 실제 트리와 다른데 어느 쪽을 따를까요?",
            "AUTO", "D3")

    def test_missing_test_dir_is_auto_d4(self):
        self.assertVerdict(
            "테스트 폴더가 없는데 새로 만들까요?", "AUTO", "D4")

    def test_naming_question_is_auto_d5(self):
        self.assertVerdict("클래스 이름을 뭘로 할까요?", "AUTO", "D5")

    def test_phase_partial_fail_is_auto_d7(self):
        self.assertVerdict(
            "Phase A 게이트가 일부 실패했는데 계속할까요?", "AUTO", "D7")

    def test_unrelated_test_fail_is_auto_d8(self):
        self.assertVerdict(
            "관련 없는 기존 테스트가 실패했는데 고칠까요?", "AUTO", "D8")

    def test_server_restart_is_auto_d9(self):
        self.assertVerdict("검증용 서버를 재기동할까요?", "AUTO", "D9")

    def test_two_impl_ways_is_auto_d10(self):
        self.assertVerdict(
            "구현 방식이 두 가지인데 어느 쪽으로 할까요?", "AUTO", "D10")

    def test_flag_default_off_is_auto_d11(self):
        self.assertVerdict("새 기능 기본값을 false로 둘까요?", "AUTO", "D11")

    def test_conflicting_directives_is_auto_d12(self):
        self.assertVerdict(
            "이전 지시서와 최신 지시서가 충돌하는데 어느 쪽을 따를까요?",
            "AUTO", "D12")

    # --- 영어 문장 ---

    def test_english_scope_question_is_auto_d1(self):
        self.assertVerdict(
            "Should I just read the goal file, or implement and verify?",
            "AUTO", "D1")

    def test_english_delete_is_ask_once(self):
        self.assertVerdict("Should I delete the test dataset?",
                           "ASK_ONCE", "ask-delete-data")

    # --- 애매한 문장 → AUTO/SELFASK (기본이 질문이 되지 않게) ---

    def test_ambiguous_is_auto_selfask(self):
        result = self.assertVerdict("이 순서대로 진행할까요?", "AUTO", "SELFASK")
        self.assertIn("selfask", result["default_answer"])

    # --- 실제 질문 재현 (2026-10-03 auto-answer 세션: D13/D14 좁은 예외) ---

    def test_q1_narrow_auth_exception_is_auto_d13(self):
        result = self.assertVerdict(
            "개인 설정 서버 저장을 완료하려면 AppSecurityConfig의 인증 필수 규칙에서 "
            "/api/settings/preferences의 owner별 PATCH만 허용하는 변경이 필요합니다. "
            "첨부의 '인증 정책 변경 금지'와 충돌하므로 이 좁은 예외를 허용할까요?",
            "AUTO", "D13")
        self.assertIn("CONFLICT", result["log_line"] + result["default_answer"])

    def test_q2_script_env_priority_is_auto_d14(self):
        self.assertVerdict(
            "시작 스크립트가 AWX_OPTIONAL_HTTPS_ENABLED의 사용자 환경값을 "
            "자식 프로세스 값보다 먼저 읽습니다. 프로세스 우선순위만 수정하고 "
            "집중 테스트를 추가하는 범위 확대를 허용할까요? 1 최소 수정 허용 / 2 보류",
            "AUTO", "D14")

    # --- ASK_ONCE 가드 유지 (D13/D14 조건 밖) ---

    def test_admin_auth_disable_is_ask_once(self):
        self.assertVerdict("관리자 URL 인증을 해제할까요?", "ASK_ONCE",
                           "ask-admin-scope")

    def test_user_env_var_change_is_ask_once(self):
        self.assertVerdict("사용자 환경변수 AWX_*를 false로 바꿀까요?",
                           "ASK_ONCE", "ask-env-global")

    def test_db_schema_change_question_is_ask_once(self):
        self.assertVerdict("DB 스키마를 변경할까요?", "ASK_ONCE",
                           "ask-schema-change")

    # --- D16/D17/D19 ---

    def test_full_suite_request_is_auto_d16(self):
        result = self.assertVerdict("전체 테스트를 돌릴까요?", "AUTO", "D16")
        self.assertIn("NOT_RUN", result["default_answer"])

    def test_failing_test_delete_is_auto_d17(self):
        result = self.assertVerdict("실패 테스트를 삭제하고 다시 만들까요?",
                                    "AUTO", "D17")
        self.assertIn("금지", result["default_answer"])

    def test_remote_diff_unknown_sha_is_auto_d19(self):
        result = self.assertVerdict(
            "원격 diff를 근거로 써도 될까요? (SHA 관계 미확인)",
            "AUTO", "D19")
        self.assertIn("로컬", result["default_answer"])

    # --- 출력 계약 ---

    def test_result_fields_and_log_line(self):
        result = classify("git push 할까요?")
        self.assertEqual({"verdict", "rule", "default_answer", "log_line"},
                         set(result))
        self.assertTrue(result["log_line"].startswith("ASK_ONCE:"))
        auto = classify("클래스 이름을 뭘로 할까요?")
        self.assertTrue(auto["log_line"].startswith("AUTO_DECISION:"))

    # --- add-only: F2 실측 카드 규칙 D21~D23 (2026-10-03) ---

    def test_cross_chat_message_is_auto_d21(self):
        self.assertVerdict(
            "동일 목표를 실행 중인 다른 활성 Codex 채팅이 같은 파일을 수정 "
            "중입니다. 그 채팅에 작업 분담 메시지를 보내도 될까요? 채팅 간 "
            "메시지는 도구 규칙상 사용자 명시 승인이 필요합니다.",
            "AUTO", "D21")

    def test_plain_send_message_is_not_d21(self):
        result = classify("이 결과 메시지를 그대로 보낼까요?")
        self.assertNotEqual("D21", result["rule"])

    def test_local_synthetic_call_is_auto_d22(self):
        self.assertVerdict(
            "로컬 Ollama(127.0.0.1:11434)의 gemma4:26b에 합성 프롬프트로 "
            "생성 요청을 1회만 보내도 될까요?",
            "AUTO", "D22")

    def test_paid_limit_stays_ask_once_not_d22(self):
        self.assertVerdict("유료 API 상한 초과인데 계속할까요?",
                           "ASK_ONCE", "ask-paid-limit")

    def test_shared_guard_false_positive_is_auto_d23(self):
        self.assertVerdict(
            "공유 쓰기 가드가 종료된 기록도 용량으로 계산해 새 소스 수정을 "
            "막습니다. 작은 가드 수정과 회귀 테스트를 범위에 추가해도 될까요?",
            "AUTO", "D23")

    def test_secret_misread_guard_is_auto_d23(self):
        self.assertVerdict(
            "보관 검사기가 테스트 문자열을 비밀값으로 오인해 회귀 테스트 "
            "수정을 막습니다. 검사기 수정을 허용할까요?",
            "AUTO", "D23")

    def test_auth_policy_exception_stays_ask_once(self):
        self.assertVerdict(
            "AppSecurityConfig의 인증 필수 규칙에서 owner별 PATCH만 허용하는 "
            "변경이 필요합니다. '인증 정책 변경 금지'와 충돌하므로 이 좁은 "
            "예외를 허용할까요? 관리자 URL·전역 설정·역할 라우팅의 보호는 "
            "그대로 유지합니다.",
            "ASK_ONCE", "ask-auth-policy")

    # --- add-only: --options 선택지 자동 선택 ---

    def test_pick_option_auto_restart_proceed(self):
        picked, _ = pick_option(
            ["로컬 dev 서버 1회 추가 재기동 허용",
             "추가 재기동 없이 결과만 보고"], "AUTO", "D9")
        self.assertEqual("로컬 dev 서버 1회 추가 재기동 허용", picked)

    def test_pick_option_cross_chat_hint_beats_recommended_mark(self):
        picked, reason = pick_option(
            ["메시지 허용: 다른 채팅은 소스·스모크 (권장)",
             "메시지 없이 이 채팅만 계속; 겹치는 파일은 HOLD"], "AUTO", "D21")
        self.assertIn("메시지 없이", picked)
        self.assertTrue(reason.startswith("rule-hint:"))

    def test_pick_option_ask_once_picks_safe_default(self):
        picked, reason = pick_option(
            ["좁은 예외 허용", "인증 정책 유지하고 해당 항목 보류"],
            "ASK_ONCE", "ask-auth-policy")
        self.assertIn("유지", picked)
        self.assertIn("safe", reason)

    def test_pick_option_never_picks_irreversible(self):
        picked, _ = pick_option(
            ["데이터셋 삭제 후 진행", "이번에는 보류"], "AUTO", "SELFASK")
        self.assertIn("보류", picked)

    def test_pick_option_recommended_mark_auto(self):
        picked, reason = pick_option(
            ["계속 진행", "이번에는 보류 (권장)"], "AUTO", "SELFASK")
        self.assertIn("보류", picked)
        self.assertEqual("recommended-reversible", reason)

    def test_pick_option_no_safe_for_ask_once_returns_none(self):
        picked, reason = pick_option(
            ["데이터 삭제", "전체 삭제"], "ASK_ONCE", "ask-delete-data")
        self.assertIsNone(picked)
        self.assertIn("irreversible", reason + "no-safe-option")

    def test_classify_with_options_fields(self):
        result = classify_with_options(
            "다른 활성 Codex 채팅에 분담 메시지를 보낼까요? 채팅 간 메시지는 "
            "사용자 명시 승인이 필요합니다.",
            ["메시지 허용 (권장)", "메시지 없이 이 채팅만 계속"])
        self.assertEqual("AUTO", result["verdict"])
        self.assertEqual("D21", result["rule"])
        self.assertIn("메시지 없이", result["picked_option"])
        self.assertIn("picked_reason", result)

    def test_main_options_flag_exit_code(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = main(["--text", "검증용 로컬 서버를 재기동할까요?",
                         "--options", "재기동 허용|보류"])
        self.assertEqual(0, code)
        out = buf.getvalue()
        self.assertIn("picked_option", out)
        with redirect_stdout(io.StringIO()):
            code2 = main(["--text", "git push 할까요?",
                          "--options", "push|보류"])
        self.assertEqual(3, code2)

    # --- add-only: D29 지시서 허용 목록 밖 원인 체인 범위 확장 (2026-10-03) ---

    def test_f2_english_outside_allowlist_is_auto_d29(self):
        self.assertVerdict(
            "The live root cause requires LlmRouterAspect.java, outside the "
            "user attachment explicit allowed edit paths. A concrete "
            "unapplied 70-line patch is saved. Should this additional "
            "source file be authorized under a fresh target lease, or kept "
            "unapplied pending user approval?",
            "AUTO", "D29")

    def test_korean_scope_expand_is_auto(self):
        result = self.assertVerdict(
            "원인 파일이 지시서 수정 허용 목록 밖입니다. 되돌릴 수 있고 "
            "다른 세션 lease도 없는데 범위 확장 승인할까요?",
            "AUTO")
        self.assertIn(result["rule"], ("D29", "D1"))

    def test_scope_expand_with_schema_stays_ask_once(self):
        self.assertVerdict(
            "The fix file is outside the allowlist, and it also needs a "
            "DB schema change — proceed?",
            "ASK_ONCE", "ask-schema-change")

    def test_scope_expand_chatjs_is_hold(self):
        result = self.assertVerdict(
            "chat.js is outside the allowed edit list but the root cause "
            "needs it — apply the saved patch?",
            "HOLD")
        self.assertEqual("D29-forbidden", result["rule"])

    def test_scope_expand_with_push_stays_ask_once(self):
        self.assertVerdict(
            "Root cause file is outside the allowed edit paths; to share "
            "the fix I must push to origin — proceed?",
            "ASK_ONCE", "ask-git-remote")

    # --- add-only: D30~D34 실측 멈춤 사례 U1~U5 (2026-10-03) ---

    def test_u1_live_budget_extend_is_auto_d30(self):
        self.assertVerdict(
            "라이브 생성 최대 10회 중 10/10에 도달했습니다. 남은 검증 항목 "
            "2개를 위해 추가 4회 승인할까요?",
            "AUTO", "D30")

    def test_u1_live_budget_english_is_auto_d30(self):
        self.assertVerdict(
            "Live generation cap 10/10 reached — may I extend by 4 more "
            "calls for the remaining verification items?",
            "AUTO", "D30")

    def test_hardcap_means_partial_not_extend(self):
        result = self.assertVerdict(
            "지시서에 HARD_CAP 추가 생성 절대 금지라고 있는데 라이브 상한 "
            "10/10에 도달했습니다. 어떻게 할까요?",
            "AUTO", "D30-cap")
        self.assertIn("partial", result["default_answer"].lower())

    def test_paid_limit_stays_ask_once_not_d30(self):
        self.assertVerdict("유료 API 호출 상한 초과인데 증액할까요?",
                           "ASK_ONCE", "ask-paid-limit")

    def test_u2_user_evidence_wait_is_auto_d31(self):
        self.assertVerdict(
            "사용자가 직접 보낸 KST 시각과 답이 화면에 표시됐는지 "
            "알려 주세요.",
            "AUTO", "D31")

    def test_u2_did_answer_appear_is_auto_d31(self):
        self.assertVerdict(
            "Tell me the exact time you sent it and whether the answer "
            "appeared on screen.",
            "AUTO", "D31")

    def test_u3_live_lease_blocked_is_auto_d32(self):
        self.assertVerdict(
            "대상 파일이 다른 세션의 live lease와 겹칩니다. BLOCKED로 "
            "종료할까요?",
            "AUTO", "D32")

    def test_u3_lease_wait_english_is_auto_d32(self):
        self.assertVerdict(
            "A live lease held by another session overlaps the target — "
            "should I end the task BLOCKED or wait for release?",
            "AUTO", "D32")

    def test_stale_lease_reclaim_is_not_d32(self):
        result = classify("만료된 stale lease를 reclaim하고 진행할까요?")
        self.assertNotEqual("D32", result["rule"])

    def test_u5_superseded_session_is_auto_d33(self):
        self.assertVerdict(
            "같은 목표를 다루던 다른 세션이 이미 verified로 완료했습니다. "
            "이 세션을 재개할까요?",
            "AUTO", "D33")

    def test_u5_newer_session_solved_is_auto_d33(self):
        self.assertVerdict(
            "A newer session already solved this goal — resume anyway?",
            "AUTO", "D33")

    def test_foreign_active_session_is_not_d33(self):
        result = classify(
            "다른 세션이 아직 같은 파일을 수정 중인데 계속할까요?")
        self.assertNotEqual("D33", result["rule"])

    def test_u1_u2_env_transient_is_auto_d34(self):
        self.assertVerdict(
            "브라우저 도구가 'URL protocol is not allowed'로 거부됐습니다. "
            "라이브 카운트에 넣을까요?",
            "AUTO", "D34")

    def test_launcher_already_running_is_auto_d34(self):
        self.assertVerdict(
            "launcher already running — 새 런타임을 띄울까요, 기존 것에 "
            "붙을까요?",
            "AUTO", "D34")

    def test_server_restart_stays_d9_not_d34(self):
        self.assertVerdict("검증용 서버를 재기동할까요?", "AUTO", "D9")


if __name__ == "__main__":
    unittest.main()
