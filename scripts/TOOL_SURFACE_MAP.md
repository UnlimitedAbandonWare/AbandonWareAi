# Tool → surface map (2026-10-01)

주력 화면 SSOT: `docs/PRIMARY_SURFACE.md`. 파일 이동·이름 변경·삭제 없음 — 분류만 한다
(다른 스크립트·테스트가 경로를 참조하기 때문).

- **main-chat**: 주력 메인 `/chat`(chat-ui) 검증용 — "채팅이 된다"의 근거.
- **debug-surface**: 면접·display 디버깅 화면용 — 가끔 사용, 결과는 보조 증거.
- **shared**: 화면 무관 공통(기동·진단·보안·provenance·계약).

| 도구 | 분류 | 용도 / 언제 쓰는가 |
|---|---|---|
| `scripts/chat_ui_stream_contract_tests.js` | main-chat | chat-ui 스트림 계약 — 메인 채팅 회귀 시 |
| `scripts/chat_ui_browser_fault_fixture_tests.js` (+`chat_ui_browser_fault_fixture.js`) | main-chat | 브라우저 fault 픽스처 — 메인 오류 경로 |
| `scripts/chat_ui_view_layer_contract_tests.js` | main-chat | chat-ui 뷰 레이어 계약 |
| `scripts/chat_ui_admission_recovery_contract_tests.js`, `chat_ui_autoconfig_contract_tests.js`, `chat_ui_geometry_contract_tests.js`, `chat_ui_reasoning_restore_contract_tests.js`, `chat_ui_recovery_operator_contract_tests.cjs`, `chat_ui_external_evidence_status_contract_tests.js`, `chat_ui_image_artifact_contract_tests.cjs`, `chat_ui_image_job_lifecycle_contract_tests.js`, `chat_ui_heartbeat_harmony_contract_tests.ps1`, `chat_ui_heartbeat_trace_memory_contract_tests.ps1` | main-chat | chat-ui 세부 계약 묶음 — 해당 증상 회귀 시 |
| `scripts/chat_ui_vibe_lifecycle.ps1`, `chat_ui_vibe_listener.ps1`(+'_tests'), `chat_ui_vibe_soak.ps1`(+'_tests') | main-chat | 메인 chat-ui 라이프사이클·소크 — 장시간 사용성 점검 |
| `scripts/chat_model_picker_behavior_tests.cjs`, `chat_wait_routing_tests.cjs`, `chat_stream_query_rewrite_contract_tests.ps1`, `chat_stream_signal_debug_action_contract_tests.ps1`, `chat_trace_*`, `smoke_chat_debug_*`, `chat_session_debug_export.py`, `agentic_chat_postprocess.py` | main-chat | 메인 채팅 동작·트레이스·후처리 계약 |
| `scripts/interview_demo_contract_tests.cjs` | debug-surface | 면접 페이지 계약 — 디버깅 화면 회귀 시 |
| `scripts/interview_demo_dom_tests.cjs` | debug-surface | 면접 DOM 계약 |
| `scripts/interview_e2e_contract_tests.cjs` | debug-surface | 면접 e2e 계약 |
| `scripts/rag_studio_contract_tests.cjs`, `rag_studio_dom_tests.cjs` | debug-surface | RAG Studio(면접 스튜디오) 계약 |
| `scripts/meta_display_*`, `display_receiver_rag_contract_tests.cjs`, `display_diagnostics_separation_tests.cjs`, `fold6_display_capture_tests.cjs`, `probe_display_input.ps1` | debug-surface | display/렌즈 디버깅 표면 |
| `scripts/p6dbg_demo_route_matrix.py` | debug-surface | `InterviewDemoFilter`(demo=on) 경로 시뮬레이션 — 면접 활성 상태 라우트 감사 |
| `scripts/chat_ui_vibe_display_tests.ps1` | shared | provenance 판정 장치(display/studio/legacy/interview/mismatch 케이스) — "어느 화면이 서빙됐는가"를 검증하는 공통 기구 |
| `scripts/p6r2dbg_owner_route_matrix.py` | shared | 소유자 경로 보안 행렬(demo on/off 모두 커버) — R2 감사 도구 |
| `scripts/start_rag_stack.ps1`, `scripts/debug_rag_stack.ps1`, `Start-RAG.bat`, `Start-Meta-Display.bat`, `Debug-RAG.bat`, `Debug-Meta-Display.bat`, `Verify-RAG.bat` | shared | 기동·진단·verify — 현재 서빙된 화면을 관측(WP4 후 로그에 `surface=main|interview` 표기) |
| `scripts/chatgpt_oauth_flow.py`, `lint_chatgpt_oauth_contract.py`, `scan_chatgpt_oauth_secrets.py`, `smoke_agent_chatgpt_oauth.ps1` | shared | OAuth 계약·비밀 스캔 — 화면 무관 |

원칙: "메인이 살아있다"는 main-chat 도구 또는 런처의 chat-ui(`/js/chat.js` 로드)
증거로만 말한다. debug-surface PASS는 보조 증거이고 주력 판정을 대신하지 않는다.
