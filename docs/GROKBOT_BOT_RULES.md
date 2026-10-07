# Grok Bot (desktop app) durable rules — SSOT copy for agy / agents on demo-1

- 출처: `grokbot-migrate-20261006 (sha12 28e0752cc37d)` (가져온 시각 2026-10-06 20:54 KST)
- 새 결정이 이김: 이 파일의 어떤 줄보다 최신 사용자 결정·AGENTS.md가 우선한다.
- 원본 export: var\grokbot-export-20261006
- R14의 cloudflared 터널 표현은 N7이 대체 - 실제는 로컬 Caddy(ZeroSSL), cloudflared 없음

# Grok Bot 상시 규칙 — demo-1 (1차 2026-10-04 12:4x KST, 이번 판 2026-10-06 20:3x KST에 D절 추가)
# 출처: Grok Bot 에이전트 메모리(profile). 최신 결정이 이김. 비밀값 없음.
# 새 봇 저장법: 아래 R1~R22, P1~P14를 각각 UpdateMemory(action=write, scope=agent, tier=profile)로 한 줄씩 저장.

## A. 번호 규칙 (원문 그대로)
R1 말투: 한국어 쉬운 존댓말로 결론을 첫 줄에 쓰고 어려운 말은 풀어 쓰며, 중요한 답 끝에 `한 줄:` 요약을, 지시서를 줄 때는 사용자가 에이전트에게 그대로 칠 `말로: 「…」` 한 문장을 붙인다.
R2 그룹방: 한 턴에 메시지 3개까지만 보내고, 작업은 직접 한 뒤 결과만 짧게 보고한다.
R3 PC: DESKTOP-M5NOV6K(machineId 7dd2c567-8c78-46c0-8732-b129fbc48cae — 새 봇은 ListMachines로 자기 쪽 id를 다시 확인), 루트 C:\AbandonWare\demo-1\demo-1\src, 규칙 파일 소문자 agents.md, 상태 문서 docs\PROJECT_STATUS.md, 스택 Java 17·Spring Boot 3.3.4·Gradle 8.7·LangChain4j 1.0.1 고정·H2(MariaDB 모드 파일 DB)·Next 16 BFF(src\frontend), 검증은 Verify-RAG.bat.
R4 셸: PC 셸은 PowerShell 5.1이라 `&&` 대신 `;`, git은 F:\git\cmd\git.exe, 읽기 명령은 GIT_OPTIONAL_LOCKS=0, 한글 콘솔 출력이 깨질 수 있고, 저장소 경로를 Read/CopyToBox가 거부하면 machine Shell로 읽는다.
R5 remote: 유효한 git remote는 origin = https://github.com/UnlimitedAbandonWare/AbandonWareAi 하나뿐. 지시서에는 옛 저장소 이름을 쓰지 않고 "remote 추가·변경 금지"라고만 쓴다.
R6 지시서 저장 5단계: PASTE_<AGENT>_<topic>_<yyyymmdd>.txt를 ① box /workspace/briefs/에 heredoc으로 쓰고 크기·sha12 계산 ② CopyFromBox(쓰기와 병렬 금지, C:\Users\nninn\에 떨어짐) ③ Move-Item으로 C:\Users\nninn\Downloads로 옮기고 크기·sha12가 box와 같은지 확인 ④ Downloads 경로·크기·sha12·말로·한 줄로 답 ⑤ 메모리에 log 기록. src\agent-prompts\…\BRIEF.txt 사본은 만들지 않는다(2026-10-03 08:42 사용자 요청).
R7 지시서 형식: [ANTI-STOP](맨 위·맨 아래) → 첫 명령 Set-Location + 환경 줄 → 0 한 줄 목표 → 1 확인된 사실(file:line, 확인 시각 KST) → 공통 규칙(ledger data\agent-handoff\<agent>-<topic>-<8hex>\, 수정 전 lease, 수정 허용·변경 금지 목록) → 작업 단계 → HOLD → ASK_ONCE → 절대 금지 → Acceptance(안 돌린 것 NOT_RUN) → 보고 형식(첫 줄 `외부 API:`). 완료 = Acceptance 전부 PASS일 때뿐.
R7b lease 명령: __patch_drop__\source_edit_session.ps1 -Action 값은 begin, end, status, verify, bind-scope, heartbeat, recover뿐(30행 ValidateSet). acquire/release는 없음. 겹침 확인은 scripts\agent_scope_lease.py, 기록은 scripts\work_journal.py.
R8 사실 확인: 지시서에 쓸 사실은 쓰기 전에 PC 소스에서 직접 file:line으로 확인하고, 확인 못 한 추측은 "확인 필요"로 표시.
R9 @skill 줄: Devin 지시서 맨 위에만 중복 없는 한 줄 `@demo1-project-root @agent-scope-lease @demo1-lease-conflict-autoflow @regression-check @positive-negative-neutral-judge @demo1-vibe-selfask-judge-auto @demo1-work-ledger @demo1-superpowers-repo-evidence-guard @demo1-agent-code-evidence-gate`(+ .agents\skills에 실제 있는 주제별 스킬). Codex·Grok·GPT Pro 지시서에는 @skill 줄을 넣지 않는다.
R10 Grok CLI: G0~G9 묶음, 산출물 var\codex-assist-<topic>\, 새 파일만(scripts\, .agents\skills\, .grok\rules 포인터 1개 = SSOT 경로 + 최대 5줄), lease는 source_edit_session.ps1, 기록은 work_journal.py, Gradle·라이브 API·제품 소스 수정 안 함.
R11 공통 금지: push/pull/commit·git add -A·reset/checkout/restore/stash/clean·remote 추가·변경, 전체 테스트 스위트, .env/.secrets/토큰 열람·출력, PROTO_OPEN·admin 강화, 다른 세션 lease·hunk 수정, DB 스키마 변경·데이터셋 삭제(백업만), chat.js 수정(작업별 명시 예외만), 다른 채팅에 영향 주는 전역 설정 수정, skip-permissions/full-access 모드 권장.
R12 판단: PROTO_OPEN 유지(관리자 로그인·로그아웃 차단 검사는 완료 조건 아님). 되돌릴 수 있는 로컬 단계는 Self-Ask(긍정/부정/반례 → 중립 판정)로 AUTO 진행, 되돌릴 수 없는 것만 ASK_ONCE. "밀어붙여" = hard stop을 지키며 전체 범위.
R12b AUTO 기본: AUTO가 Grok Bot·agy·다른 에이전트의 기본 설정(2026-10-03 사용자 확인). 지시서와 규칙은 AUTO 기준, ASK_ONCE는 되돌릴 수 없는 것에만.
R13 비용 순서(2026-10-03 07:54 확정, 화력 위주): ① Codex 크레딧(당시 62,500) → ② 외부 유료 API → ③ 무료 → ④ 로컬 Ollama(맨 마지막 예외). "무료·로컬 우선"은 폐기. 지시서마다 라이브 호출 상한(전체 브라우저 자가점검 약 25회), 401/403/429는 재시도 없이 근본 원인 맨 위 보고, mock 통과는 라이브 검증 아님.
R14 검증 대상: Devin 기본 검증은 https://abandonwareai.kro.kr/chat(PC 18180 cloudflared 터널). 상태 바꾸는 검사는 127.0.0.1:18180 먼저. 공개 채팅 전송은 작업당 3회까지 [devin-test] 접두어. 주 화면 /chat(chat-ui.html). 인터뷰 페이지(곁 RAG & Display Studio)는 디버그용, 지우지 않음.
R15 역할: Codex = 제품 소스 수정, Devin·Grok CLI·Clean·agy = 도구·스크립트·룰·검증, GPT Pro = 첨부 분석 초안(사용자가 바꿀 수 있음).
R16 Codex 버릇: 목표 파일만 읽고 멈추는 버릇 → [ANTI-STOP] 필수, 목표 파일 고친 뒤 Codex 창에 한 줄 nudge가 있어야 반영.
R17 Clean(Cline, 사용자 호칭 "킴미"): 2026-10-01부터 worktree 꺼짐 → 원본 트리에서 바로 돌므로 읽기 전용이나 lease 범위 안으로 제한.
R18 GLM: Codex가 ChatGPT 로그인이면 네이티브 glm_worker(zai/glm-5.2)는 HTTP 400. glm_agent MCP 준비됐을 때만 쓰고, 아니면 assist_glm_fallback → 기존 보조 에이전트 → Codex 직접(재시도 없음).
R19 안 하는 것: 승인 없이 방·DM 자동 게시 없음, 지시서에 실제 API 키·비밀번호 없음, skip-permissions류 추천 없음, 초안 써서 묻기.
R20 범위 예산(2026-10-03): 수정 허용 목록은 '예산'. 원인 체인상 필요한 작고(≤3파일·≤300줄) 되돌릴 수 있는 파일은 변경 금지·다른 lease가 아니면 AUTO 확장 + SCOPE_EXPAND 기록. "보수적 선택 = 적용 + checkpoint"("적용 안 함"이 아님).
R21 스캐너 오탐(2026-10-04): checkpoint/스캐너 오탐은 변수 이름 바꾸기로 피하거나 checkpoint를 건너뛰지 말고, 스캐너 쪽 일반 규칙 + 회귀 테스트로 자동 수정. 검사기(codex_work_checkpoint.py 등)를 변경 금지 목록에 넣지 않는다.
R22 보호 파일 조건(2026-10-04): Acceptance에 "보호 파일 해시가 시작·끝에 같음"을 쓰지 말고 "이 세션이 보호 파일에 쓴 횟수 = 0, 다른 변경은 EXTERNAL_DRIFT로 기록"을 쓴다(다른 세션 변경 때문에 거짓 FAIL이 남).

## B. 기타 profile 사실
P1 사용자는 한국어로 대화하는 걸 선호한다.
P2 Grok Bot 상시 모드: 낮은 가드레일(Prototype Light, proto-open·로컬 DX 기본 열림, admin fail-close·Meta Display 잠금 안 함)이지만 결과는 근거 있는 검증 패킷만. Self-Ask(무엇을 요청? 현재 동작을 무엇이 증명? 모호한 점? 바뀌면 안 되는 것? 가장 작은 seam?)를 먼저 하고, file/test/command를 인용하거나 evidence_needed / NOT_RUN 표시. 비밀값은 절대 출력하지 않음.
P3 Project Root 기본값은 C:\AbandonWare\demo-1\demo-1\src(DESKTOP-M5NOV6K). 사용자가 다른 경로를 말할 때만 바꾼다.
P4 PC는 GPU 2개: RTX 3090(로컬 Ollama·임베딩 주력, 2026-09-24 전원 문제 해결) + RTX 3060(보조). 전원 피크 이유로 API 임베딩 폴백을 기본으로 두지 않는다.
P5 API 키 진단은 자주 쓰는 API(Jev/Vercel, OpenAI, Gemini) 위주의 빠른 자동 스크립트로, 어느 API가 왜 실패했는지 분명히, 기본 비용 ≈0(유료 확인은 opt-in·최소).
P6 Codex 플러그인은 역할 제한: Superpowers = 가설 하나씩·실패 테스트 먼저, Browser = 새 세션 실제 재현(HTTP status/reasonCode/request id, 인증 상태 커밋 금지), GitHub = 로컬 HEAD vs 원격 SHA 비교 보조 증거만(commit/push/merge 없음), Exa = 공식 스펙 확인만, AWX Control Tower = 실제 빌드 분류만.
P7 에이전트 지시서는 실행만이 아니라 후처리 루프: 자기 문제는 스스로 고치고, 제품 결함은 Codex에 넘기고, 고친 뒤 다시 검증해서 닫는다.
P8 빠른 API 확인·테스트 디버깅은 Java/Gradle/bootRun 없이 도는 가벼운 스크립트 모듈(Python·PowerShell·순수 Node)을 선호하고, Codex·Devin·Grok·Clean이 똑같이 쓸 수 있어야 한다.
P9 Meta Ray-Ban Display 렌즈 출력은 대화 텍스트와 짧은 힌트만(SSOT 스킬 demo1-meta-display-simple-caption). 마이크·조작은 Fold6/web 몫. 힌트 FIELD_TESTED: 한글 약 480~540자 생성, 상한 612, 렌즈 4~8줄·26~30px, max-output-tokens는 글자수가 아님, force-hint 180s/Δ50자 임의 변경 금지, 영구 1등 모델 하드코딩 금지.
P10 Devin Desktop 설치 경로 C:\Users\nninn\AppData\Local\Programs\Devin(여기에 Skills 두지 않음). 전역 Skills는 %APPDATA%\devin\skills\, 프로젝트 Skills SSOT는 src\.agents\skills\. .windsurf\skills 래퍼 만들지 않고, .windsurf\rules와 .devin\rules에 중복 미러 금지.
P11 사용자는 Grok Bot과 Devin Desktop을 같이 쓴다. Grok 스킬·지시는 건드리지 말고 설정 충돌을 피한다.
P12 Jev(Vercel AI Gateway): ZDR(zeroDataRetention) 기본 OFF(Hobby 요금제에서 켜면 403). 2026-09-17 키 만료가 401 원인이었던 적 있음 → 만료 "Never" 새 키로 교체됨. 키 원문은 어디에도 쓰지 않음.
P13 Ollama 역할(2026-09-18 잠금): fast=qwen3.5:9b, chat=gemma4:26b, judge/coder=smtek/Qwen3.8-27B:Q3_K_XL, vision=qwen3-vl:8b, embed=qwen3-embedding:4b. 단 비용 순서상 Ollama는 맨 마지막(R13).
P14 demo-1 PROTO_OPEN(demo.auth.proto-open=true) 기본 유지. admin 잠금·DEMO_AUTH_PROTO_OPEN=false는 opt-in일 뿐 이번 단계 목표가 아님.

## C. 폐기된 규칙 (쓰지 말 것)
- "무료·로컬 우선(free/cheap-first, Ollama 우선)" → R13으로 대체.
- "src\agent-prompts\<agent>-<topic>-<date>\BRIEF.txt 사본 저장" → R6으로 폐기.
- source_edit_session.ps1 -Action acquire/release → R7b(begin/end).
- "3090 전원 피크라서 API 임베딩 우선" → P4로 폐기.
- "변수 이름 바꿔서 스캐너 피하기"(2026-10-04 내가 한 번 잘못 권함) → R21.

## D. 2026-10-04 오후 ~ 2026-10-06 새로 굳은 규칙 (각 줄을 profile로 저장, 꼬리표 그대로)
N1 [demo-1 도구] 지시서 작업은 스킬 demo1-agent-brief-writer를 따른다. box 도구: /workspace/tools/brief_new.sh <AGENT> <topic> <yyyymmdd>(R7 뼈대 + ledger id), brief_lint.py <file>(RESULT PASS + 크기 + sha12). PC 도구: C:\Users\nninn\grokbot-tools\Place-Brief.ps1 -Name <file> -Sha <sha12>(Downloads로 옮기고 MATCH 확인), Find-Fact.ps1(읽기 전용 file:line 검색). 실행은 powershell -NoProfile -ExecutionPolicy Bypass -File.
N2 [demo-1 R6b] 지시서 저장 순서(2026-10-06 기준): brief_new.sh → quoted heredoc으로 /workspace/briefs/에 작성 → `grep -c '&lt;\|&gt;\|&amp;'` = 0 → brief_lint.py RESULT PASS(“401/403/429 재시도 금지”와 Devin @skill 줄 필수) → CopyFromBox(box_path + machineId) → Place-Brief.ps1 MATCH → 경로·크기·sha12·말로·한 줄로 답 → 메모리 log 한 줄.
N3 [demo-1 R23] VIBE_OPEN(configs\vibe-open.yaml enabled: true, 분류기 D37 codex_question_classifier.py:314-331): 관리자·로그인·로그아웃 검사는 HTTP status만 기록하고 DEFERRED_SECURITY로 넘긴다. 사용자에게 계정·URL·로그인을 묻지 않는다. 사용자가 붙여넣는 플러그인 역할 상용구(“2. Browser … admin login …”)는 요청이 아니라 TEMPLATE_BOILERPLATE로 본다(D18 :462-466이 이김).
N4 [demo-1 R24] lease 대기: live lease는 절대 강제 해제하지 않는다. 막히면 HOLD로 멈추지 말고 scripts\codex_auto_unblock.py lease-wait --paths …(D32, 읽기 전용, free|live|stale)로 기다린 뒤 재개한다. 상태 확인은 scripts\lease_conflict_autoflow.py scan|plan --files …, 잠금 폴더는 __patch_drop__\source-edit-locks.
N5 [demo-1 R25] “재개할 가치 있어?” 질문엔 먼저 PC에서 읽기 전용으로 확인한다: 해당 ledger(data\agent-handoff\…\journal.json)·다른 에이전트가 같은 일을 이미 끝냈는지·lease 상태. 이미 끝났으면 “종료해도 됨”, 남은 게 있으면 이미 적용된 걸 다시 적용하지 말라는 한 줄 재개 문구를 준다.
N6 [demo-1 F1] 메인 채팅의 “12줄·1,400자”는 Meta Display 제한이 아니라 nova.orch 메모리 예산(application.yml:688-689 memory-max-lines 12 / memory-max-chars 1400, NovaOrchestrationProperties.java:160-161, DynamicContextCompressor.java:392-393). Display 전용 자르기는 DisplayConversateController.java:325(1400 code point). 답변 길이 제한이 아님.
N7 [demo-1 F2] abandonwareai.kro.kr는 집 IP로 바로 연결되고 공개 HTTPS는 PC의 로컬 Caddy(ZeroSSL 인증서, 2026-11-21 만료, 사용자가 11-10~15쯤 직접 갱신)가 맡는다. cloudflared는 설치돼 있지 않다(R14의 “cloudflared 터널” 표현은 옛 정보). kro.kr은 Cloudflare Free 존으로 못 올리고 Cloudflare는 제품 완성 뒤로 미룸.
N8 [demo-1 R26] 역할 추가(2026-10-05): 사용자의 ChatGPT “dot”(UnlimitedAbandon)이 관제탑. dot이 계획하고 PASTE_CODEX_<UPPER_SNAKE>_<date>.md 지시서를 써서 Codex에 보내며, Grok Bot은 주로 Devin 지시서·세션 판정·재개 문구를 맡는다. agy(Antigravity CLI)는 최신 Flash 자동 추적 + 난이도별 깊이(L1~L3).
N9 [demo-1 R27] Gemini Flash grounded 검색 보조(scripts\gemini_search_worker.py)는 월 5,000회 안에서 쓰고, 4,000/5,000에서 경고만 하고 막지 않는다(초과해도 싸면 괜찮다는 게 사용자 입장).
N10 [demo-1 R28] 그룹방 응답: 확인 메시지 없이 작업을 먼저 하고, 결과만 최대 3개의 짧은 메시지로. box에서 로그인하지 않는다(로그인이 필요하면 사용자 1:1로).

## 공유 사용자 메모리 (scope=user)
U1 [profile] Decided 2026-10-02: the user mainly uses cloud API models. demo-1's mainstream chat path is API, and local Ollama is only a last fallback. Briefs should verify against API models and not spend effort on local-only problems; the old local-first rule was a cost guard, not product direction.
U2 [profile] demo-1's primary product surface is the AbandonWare AI main chat at https://abandonwareai.kro.kr/chat (local http://127.0.0.1:18180/chat, chat-ui.html); the interview page (곁 RAG & Display Studio, static/assets/interview/*) is only an occasional local debugging surface and must not be deleted (decided 2026-10-01).
U3 [profile] Default target for Devin (2026-10-01): unless a brief names another surface, Devin checks and verifies against https://abandonwareai.kro.kr/chat (PC 18180 server, same backend and data). State-changing checks run on local 127.0.0.1:18180 first. Public chat sends are capped at 3 per task with a [devin-test] prefix. Meta Ray-Ban Display, the debug studio and the interview page are used only when a brief names them explicitly.
U4 [profile] Correction checked 2026-10-04: abandonwareai.kro.kr resolves straight to the home IP (kro.kr DNS is dnsze.com). Public HTTPS is served by local Caddy with a ZeroSSL cert that expires 2026-11-21, not by a cloudflared tunnel, and cloudflared is not installed on the PC. kro.kr is not on the Public Suffix List, so Cloudflare Free cannot take it as a zone. The user is putting off Cloudflare until the product is finished and will renew ZeroSSL by hand around Nov 10–15.
U5 [log] As of 2026-10-01 11:06 KST the user turned off Clean's (Cline's) worktree mode, so Clean now runs directly in the original tree C:\AbandonWare\demo-1\demo-1\src and sees uncommitted changes; briefs should keep Clean read-only or lease-scoped since Codex/Devin/Grok share that tree.
U6 [log] 2026-10-04 20:0x KST: PASTE_CODEX_stack-fit-pushback_20261004.txt (20,052 B, sha12 96737cceaf09) makes Codex's decline-if-it-doesn't-fit behavior permanent: SSOT configs/stack-fit.yaml (tech radar adopt/trial/hold), scripts/stack_fit_guard.py (FIT/ADAPT/DECLINE/OVERRIDE) + 14 tests, skill demo1-stack-fit-pushback, ADR-0001-nestjs-declined.
