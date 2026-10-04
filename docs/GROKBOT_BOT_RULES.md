# Grok Bot (desktop app) durable rules — SSOT copy for agy / agents on demo-1

- 출처: Grok Bot export `grokbot-export-20261003` (가져온 시각 2026-10-03 20:08 KST)
- 새 결정이 이김: 이 파일의 어떤 줄보다 최신 사용자 결정·AGENTS.md가 우선한다.
- 원본 export: var/grokbot-export-20261003/memory/RULES.md

# Grok Bot durable rules for demo-1 (exported 2026-10-03 19:55 KST)
# Source: Grok Bot (desktop app) memory. Newest decision wins. Secrets: none.

R1 말투: 한국어 쉬운 존댓말, 결론 첫 줄, 어려운 말은 풀어 쓰기, 중요한 답 끝에 `한 줄:`, 지시서에는 사용자가 그대로 칠 `말로: 「…」` 한 문장.
R2 그룹방: 한 턴에 메시지 3개까지, 작업은 직접 하고 결과만 짧게.
R3 PC: DESKTOP-M5NOV6K, 루트 C:\AbandonWare\demo-1\demo-1\src, 규칙 파일 소문자 agents.md, 상태 문서 docs\PROJECT_STATUS.md, 스택 Java 17·Spring Boot 3.3.4·Gradle 8.7·LangChain4j 1.0.1 고정·H2·Next 16 BFF(src\frontend), 검증 Verify-RAG.bat.
R4 셸: PowerShell 5.1이라 `&&` 대신 `;`, git은 F:\git\cmd\git.exe, 읽기 명령은 GIT_OPTIONAL_LOCKS=0, 한글 콘솔 깨질 수 있음.
R5 remote: origin = https://github.com/UnlimitedAbandonWare/AbandonWareAi 하나뿐. 지시서에는 옛 저장소 이름을 쓰지 않고 "remote 추가·변경 금지"만 쓴다.
R6 지시서 저장: PASTE_<AGENT>_<topic>_<yyyymmdd>.txt를 Downloads에 두고 크기·sha12를 답에 적는다. src\agent-prompts\…\BRIEF.txt 사본은 만들지 않는다(2026-10-03 08:42 사용자 요청, 예전 규칙 폐기).
R7 지시서 형식: [ANTI-STOP](맨 위·맨 아래) → 첫 명령 Set-Location + 환경 줄 → 0 한 줄 목표 → 1 확인된 사실(file:line, 확인 시각 KST) → 공통 규칙(ledger data\agent-handoff\<agent>-<topic>-<8hex>\, 수정 전 lease, 수정 허용·변경 금지 목록) → 작업 단계 → HOLD → ASK_ONCE → 절대 금지 → Acceptance(안 돌린 것 NOT_RUN) → 보고 형식(첫 줄 `외부 API:`). 완료 = Acceptance 전부 PASS.
R7b lease 명령: __patch_drop__\source_edit_session.ps1 -Action 값은 begin, end, status, verify, bind-scope, heartbeat, recover뿐(30행 ValidateSet). acquire/release는 없음. 겹침 확인은 scripts\agent_scope_lease.py, 기록은 scripts\work_journal.py.
R8 사실 확인: 지시서 사실은 PC 소스에서 file:line으로 직접 확인. 못 한 것은 "확인 필요".
R10 Grok CLI: G0~G9 묶음, 산출물 var\codex-assist-<topic>\, 새 파일만(scripts\, .agents\skills\, .grok\rules 포인터 1개 = SSOT 경로 + 최대 5줄), Gradle·라이브 API·제품 소스 수정 안 함.
R11 공통 금지: push/pull/commit·git add -A·reset/checkout/restore/stash/clean·remote 추가·변경, 전체 테스트 스위트, .env/.secrets/토큰 열람·출력, PROTO_OPEN·admin 강화, 다른 세션 lease·hunk 수정, DB 스키마 변경·데이터셋 삭제(백업만), chat.js 수정(작업별 명시 예외만), 다른 채팅에 영향 주는 전역 설정 수정, skip-permissions/full-access 모드 권장.
R12 판단: PROTO_OPEN 유지(관리자 로그인 차단 검사는 완료 조건 아님). 되돌릴 수 있는 로컬 단계는 Self-Ask(긍정/부정/반례 → 중립 판정)로 AUTO, 되돌릴 수 없는 것만 ASK_ONCE. "밀어붙여" = hard stop 지키며 전체 범위.
R12b AUTO 기본: AUTO가 Grok Bot·agy·다른 에이전트의 기본(2026-10-03 사용자 확인).
R13 비용 순서(2026-10-03 07:54 확정, 화력 위주): ① Codex 크레딧(당시 62,500) → ② 외부 유료 API → ③ 무료 → ④ 로컬 Ollama(맨 마지막 예외). "무료·로컬 우선"은 폐기. 지시서마다 라이브 호출 상한(브라우저 자가점검 전체 약 25회), 401/403/429는 재시도 없이 근본 원인 맨 위 보고, mock 통과는 라이브 검증 아님.
R14 검증 대상: https://abandonwareai.kro.kr/chat (PC 18180 cloudflared 터널). 상태 바꾸는 검사는 127.0.0.1:18180 먼저. 공개 채팅 전송은 작업당 3회, [devin-test] 접두어. 주 화면 /chat(chat-ui.html). 인터뷰 페이지는 디버그용, 지우지 않음.
R15 역할: Codex = 제품 소스 수정, Devin·Grok CLI·Clean·agy = 도구·스크립트·룰·검증, GPT Pro = 첨부 분석 초안(사용자가 바꿀 수 있음).
R16 Codex 버릇: 목표 파일만 읽고 멈춤 → [ANTI-STOP] 필수, 목표 파일 고친 뒤 Codex 창에 한 줄 nudge.
R17 Clean(Cline, 킴미): 2026-10-01부터 worktree 꺼짐 → 읽기 전용이나 lease 범위 안.
R18 GLM: Codex가 ChatGPT 로그인이면 네이티브 glm_worker는 HTTP 400. glm_agent MCP 준비됐을 때만, 아니면 assist_glm_fallback → 보조 에이전트 → Codex 직접(재시도 없음).
R19 안 하는 것: 승인 없이 방·DM 자동 게시 없음, 지시서에 실제 키·비밀번호 없음, skip-permissions류 추천 없음, 초안 써서 묻기.
R20 범위 예산(2026-10-03): 수정 허용 목록은 '예산'. 원인 체인상 필요한 작고(≤3파일·≤300줄) 되돌릴 수 있는 파일은 변경 금지·다른 lease가 아니면 AUTO 확장 + SCOPE_EXPAND 기록. "보수적 선택 = 적용 + checkpoint", "적용 안 함"이 아님.
X1 Jev(Vercel AI Gateway): ZDR 기본 OFF(Hobby 요금제에서 켜면 403). 키 만료가 401 원인이었던 적 있음(2026-09-17 만료 → 새 키).
X2 Meta Ray-Ban Display 렌즈 출력은 대화 텍스트와 짧은 힌트만(SSOT 스킬 demo1-meta-display-simple-caption). 마이크·조작은 Fold6/web.
X3 RTX 3090 전원 문제 해결됨(2026-09-24): 로컬 GPU 사용 가능, 전원 피크 이유로 API 임베딩 폴백을 기본으로 두지 않음.

## Superseded (쓰지 말 것)
- "무료·로컬 우선(free/cheap-first, Ollama 우선)" → R13으로 대체.
- "src\agent-prompts\<agent>-<topic>-<date>\BRIEF.txt 사본 저장" → R6으로 폐기.
- source_edit_session.ps1 -Action acquire/release → R7b(begin/end).
