---
name: demo1-reachability-first-debug
description: "Use when '안 나오', '안 됨', '여러 번 고쳤는데', '같은 증상' 버그(focus, display, model selection, provider_not_configured, ModelSelectionException, 저장 설정)에서 파일을 고르기 전에 런타임 증거로 실패 단계부터 찾고(reachability first), 지시서 가설이 증거와 어긋나면 CHALLENGE를 내며 assist_runtime_skeptic을 병렬로 띄운다. 서버 준비/테스트해도 돼?/launcher-already-running/재시작 알림/테스트 누락 때도 사용(R7-R12, model_default_probe.py --ready)."
---

# demo1-reachability-first-debug

도달하지 않는 코드를 고치는 헛수정을 막는다. 수정 단계는 기존 규칙(R20-R22, lease begin/end,
API 비용 순서, `$demo1-dev-reload`)을 그대로 따른다. 이 스킬은 "어디를 고칠지"만 정한다.

## 언제
- 화면/디스플레이/Nova Focus에 답이 안 나옴, "응답을 확인하지 못했습니다" 류.
- 같은 증상에 수정이 이미 1회 이상 들어갔는데 그대로일 때 → R6 먼저.

## 체크리스트 (순서대로)

R1. 실패 단계부터 (reachability first)
- 같은 requestId 1건의 런타임 흔적: `var\rag-launcher\<최신 폴더>\*.out.log`,
  `logs\debug-events.ndjson`, `var\abnadon\debug\<yyyy-MM-dd>.ndjson`.
- 기록: reasonCode/close reason, 모델 시도 수(attempt n/m), 요청→실패 ms, 마지막 클래스.
- 예: <200ms + 모델 시도 0 → 모델 선택/승인(admission) 실패. ChatWorkflow·프롬프트·검색은 후보 제외.
- 실패 단계가 정해지기 전에는 수정 파일을 정하지 않는다.

R2. 저장된 사용자 설정이 yml 기본값을 이긴다
- `application-*.yml` 기본값(AUTO 등)을 바꾸기 전에 그 요청의 유효 프로필을 읽는다
  (mode FIXED/AUTO, 모델, executionTarget API_ONLY 등, fallback).
- 저장소: 테이블 `nova_focus_profile.settingsJson` (`main\java\com\example\lms\assist\NovaFocusProfile.java:11,19`),
  읽기/쓰기 `NovaFocusHistoryService.java:147`(read) / `:151`(write, settingsVersion 검사),
  API `POST /api/assist/display/focus/settings/read`·`/settings` (`DisplayConversateController.java:117,122`).
- 요청별 실제 값: TraceStore `focus.selection.*` (`NovaFocusAnswerService.java:221-230`), 또는
  `python C:\Users\nninn\grokbot-tools\served_asset_check.py --resolve` (읽기 전용).
- 저장 프로필이 FIXED면 yml 기본값 변경은 효과 없음 → 그 수정 금지.

R3. 나쁜 저장값은 "쓴 쪽(writer)"을 고친다
- 값만 한 번 고치지 말고 그 값을 저장한 코드(UI 프리셋, 저장 API, 마이그레이션)를 찾는다:
  `rg -n "<잘못된 값 또는 패턴>" main\resources\static main\java`
- 값만 고치면 다음 버튼 탭/저장에서 다시 깨진다.

R4. 단위 테스트 green ≠ 경로 실행
- 완료 조건: 수정 뒤 같은 시나리오 요청 1건의 런타임 로그에 수정한 경로가 찍힌 file:line.
- 없으면 보고에 `UNVERIFIED_RUNTIME` (완료 처리 금지).

R5. 서버가 바뀐 것을 실제로 서빙하는지
- `var\dev-reload\watch.state.json`(updatedAt/lastTier/status), `var\dev-reload\last-restart.out.log` 시각 > 변경 시각.
- `curl.exe -s http://127.0.0.1:18180/api/chat/models` → 끈 모델이 `selectable:true`로 남아 있지 않은지.
- `python C:\Users\nninn\grokbot-tools\served_asset_check.py <바꾼 static 파일> [--model <id>]` → 서빙본 sha = 디스크 sha.
- 불일치면 `$demo1-dev-reload`로 재기동 뒤 다시 확인.

R6. 같은 증상에 2번 헛수정이면 멈춘다
- 패치 중단 → CHALLENGE 출력 → R1부터 실패 경계를 새 런타임 증거로 다시 도출.
- 이전 수정마다 "왜 그 경계에 닿지 않았나" 한 줄. 그 전까지 세 번째 패치 금지.

## CHALLENGE (지시서 가설 반박)
- dot/PASTE 지시서 가설을 실행하기 전에 R1~R5 증거(실패 단계, attempt 수, 유효 저장 프로필,
  나쁜 값의 writer, 런타임 재기동)와 대조한다.
- 어긋나거나 경로가 도달 불가면 패치하지 말고 먼저 출력:
  ```
  CHALLENGE
  claim: <지시서가 고치라는 것 한 줄>
  contrary: <반대 증거 file:line 또는 로그 시각 + reasonCode/attempt/ms>
  boundary: <실제 실패 경계로 보이는 단계·file:line>
  ```
- R6 시점에도 반드시 낸다. 말투·반박 형식은 `$demo1-stack-fit-pushback`을 따른다(여기서 재정의하지 않음).
- 사용자는 "이거 문제 있는데요?"를 원한다. 지시서라서 그대로 따르지 않는다.

## 병렬 회의론 리뷰어 (assist_runtime_skeptic)
- display / focus / model selection / "여러 번 고쳤는데 그대로" 작업: **첫 패치 전**, 그리고 **수정이 실패할 때마다**
  `.codex/agents/assist_runtime_skeptic.toml` 커스텀 에이전트를 병렬로 띄운다(읽기 전용, 동시 조수 ≤2 규칙 유지).
- 호출(본체가 8-hex 마커를 만들어 넣는다):
  ```text
  Use the assist_runtime_skeptic custom agent.
  objective: <지시서 가설 1줄> — 런타임 증거로 CHALLENGE 또는 AGREE
  deliveryMarker: <8 hex>
  brief: <지시서 경로 또는 붙여넣은 핵심 3줄>
  symptom: <증상 + 재현 시각 KST>
  ```
- 결과 `verdict=CHALLENGE`면 본체는 그 반박을 증거로 반증하거나 경계를 다시 도출하기 전에는 패치 금지.
  `AGREE`면 진행. 리뷰어 결과는 증거일 뿐, 최종 판단은 본체.
- 프로젝트 범위 spawn이 실패하면(openai/codex#26408) `docs\codex\CODEX_ASSIST_SUBAGENTS_KO.md`의 전역 복사 안내를 사용자에게 알린다(본체가 전역을 건드리지 않음).

## 진단 스크립트
<!-- 다른 작업자가 Python 진단 스크립트 참조를 여기에 추가한다. 이 제목을 유지할 것. -->
- `C:\Users\nninn\grokbot-tools\served_asset_check.py` — 서빙본 vs 디스크 sha, 모델 selectable, `--resolve` 유효 모델(읽기 전용).
- `scripts\model_default_probe.py --focus` — 저장 프로필·모델 selectable·재기동 신선도·루나 프리셋을 한 번에 보는 유효 모델 판정(읽기 전용). 모델/설정/무응답 증상이면 파일을 고르기 전에 먼저 실행하고 `verdicts[0]`부터 처리(아래 'Effective model resolver').
- `scripts\model_default_probe.py --ready` — 노바 준비 게이트(아래 R7). `--restarts-since HH:MM` — 재시작 감지(R8).

## 보고에 꼭
실패 단계+근거(reasonCode, attempt, ms, 로그 file:line) / 유효 프로필(R2) / writer file:line(R3) /
런타임 증거(R4) / 재기동 시각·served sha(R5) / 리뷰어 verdict.

## 사례: Nova Focus 무응답 (2026-10-09)
- 18:41~18:46 실패 5건, 25~111ms, 모델 시도 0, provider_not_configured/ModelSelectionException → ChatWorkflow 이전(admission).
- writer: `display-focus-controls.js` 당시 `:146` 루나 프리셋 순서 `[/openai-economy/i,/luna/i]` + `:137` presetModel 두 번째 find가
  `selectable` 무시 → 루나 탭이 꺼진 `llmrouter.openai-economy`를 저장 프로필(FIXED, API_ONLY, fallback OFF)에 저장.
  (줄 번호는 수정 전 기준. 이후 codex-focus-luna-admission 세션이 고침.)
- 헛수정: `ChatWorkflow.java:3183`(테스트 114/115 green, 경로 미도달: R1·R4) → `application-meta-display.yml:155` AUTO 기본값
  (저장 프로필 FIXED라 무시: R2) → route allowlist 토글 후 19:09 되돌림(서버는 19:03:58 이후 재기동 없음: R5)
  → 프로필만 수정 제안(다음 루나 탭에 재발: R3). 2회째에 멈추고 CHALLENGE 냈어야 함(R6).

## Effective model resolver (add-only, Grok Bot 2026-10-09)

When the symptom is model / settings / "no answer" / "답을 만들지 못했어요" / provider_not_configured / ModelSelectionException, run this BEFORE picking any file to edit:

```powershell
Set-Location C:\AbandonWare\demo-1\demo-1\src ; $env:PYTHONIOENCODING='utf-8'
python -B scripts\model_default_probe.py --focus --json-out var\effective-model.json
# same logic outside the repo: python -B C:\Users\nninn\grokbot-tools\served_asset_check.py --resolve
```

It joins saved profile (scripts\db_agent.py SELECT on nova_focus_profile) + /api/chat/models selectable + recent focus_terminal events + dev-reload last Spring restart vs source mtimes + luna preset order. Read-only, owner shown only as 12-hex hash.

Act on `verdicts[0]`:
- MODEL_NOT_SELECTABLE: saved FIXED model cannot run. Fix the WRITER (preset picks selectable only, settings save rejects non-selectable). Do not edit AUTO defaults, do not add paid routes to the allowlist to hide it.
- STALE_RUNTIME: a watched file changed after the last Spring restart, or the served asset differs. Reload (demo1-dev-reload) and re-run before any live test or conclusion.
- PRESET_WRITER_RISK: display-focus-controls.js preset list hits a non-selectable id before luna. The button will re-write the bad value.
- PROFILE_UNREAD: read the profile first; never assume defaults reach the user.
- DEFAULT_SHADOWED_BY_PROFILE: owner is FIXED; default/yml changes cannot reach this owner.
- OK: selection is reachable; look downstream (ChatWorkflow, provider).

Re-run after every fix and paste `verdicts` into EVIDENCE.md. Unit tests GREEN is not proof until --focus no longer shows the verdict you were fixing.

## Nova ready gate · 재시작 알림 · 완료 판정 (add-only, Grok Bot 2026-10-09 grokbot3)

오늘 반복된 실패: health UP·일반 채팅 성공만 보고 "테스트해도 됩니다"라고 말했는데 노바 경로는
provider_not_configured로 막혀 있었다(저장 FIXED 프로필이 비활성 모델, AUTO 기본 `llmrouter.gemini-pro` 거부).
17:21:35 패치는 사용자의 "재시작 전에 알려줘"보다 먼저 DevWatch 재시작을 일으켰다. 107 통과 / 7 미실행을 거의 완료로 봤다.
18:38·18:40 사용자의 이중 실행(`launcher-already-running`)은 Codex의 18:36:57 dev 재기동이 아직 기동 중(18:41:06 ready)이라
생긴 것인데, 아무도 "지금 서버가 어떤 상태인지"를 몰랐다(창 문구 "wear runtime stays up"은 실제로 dev였다).

```powershell
Set-Location C:\AbandonWare\demo-1\demo-1\src ; $env:PYTHONIOENCODING='utf-8'
python -B scripts\model_default_probe.py --ready              # 한 줄 판정 + 사용자용 한국어 한 줄
python -B scripts\model_default_probe.py --ready --json --json-out var\nova-ready.json
python -B scripts\model_default_probe.py --restarts-since 17:00   # 또는 30m / "2026-10-09 17:00"
```
읽기 전용(모델 호출·쓰기·재시작 없음). 판정 우선순위:
`RUNTIME_DOWN > LAUNCHER_BUSY > RUNTIME_RECORD_STALE > STALE_RUNTIME > FOCUS_MODEL_BLOCKED > FOCUS_LAST_FAILED > FOCUS_MODEL_UNVERIFIED > NOT_PROVEN_FOCUS > READY`.
- 노바 증거의 owner = 저장 프로필 중 settingsVersion이 가장 큰 owner(사용자 기기). Codex/프로브 owner의 성공은
  `otherOwnersSuccessSinceRestart`로만 따로 센다(사용자 증거 아님). 다른 owner를 보려면 `--owner-hash <12hex>`.
- 런처 락은 이름 있는 커널 뮤텍스 `Local\AWX-RAG-<sha256(소문자 루트)[:20]>` (`scripts\start_rag_stack.ps1:733,746-747`).
  뮤텍스는 프로세스가 끝나면 풀리므로 "stale lock"은 없다. 오래될 수 있는 것은 기록(`var\rag-launcher\LATEST.json`,
  `spring-owned.json`) → `RUNTIME_RECORD_STALE`. 뮤텍스가 잡혀 있으면 = 다른 실행기가 지금 (재)기동 중 → `LAUNCHER_BUSY`.
- 마지막 재시작 = DevWatch `] Spring restart` 줄(정적 파일의 `no Spring restart` 줄은 제외)과 런처 run(`result.json` ready, springReused=false) 중 최신.
  (런처 재기동은 dev-reload.log에 안 남는다. 이전 `--focus`가 이 때문에 STALE_RUNTIME을 잘못 낼 수 있었고 함께 고쳤다.)
- static(`main/resources/static/**`) 변경은 재시작 시각이 아니라 서빙본 sha = 디스크 sha로 판정.

R7. "테스트해도 됩니다 / 서버 준비됐어요"는 `--ready` = `READY`일 때만
- `NOT_PROVEN_FOCUS`면 "서버·모델 경로는 OK, 노바 실제 답변은 아직 미확인 — 첫 질문 결과로 확인"이라고 말한다.
- 그 외 판정이면 출력된 `한 줄:` 문구를 그대로 쓰고 테스트를 권하지 않는다.
- 일반 채팅 성공 ≠ 노바 성공(R11). health UP, `Status-RAG.bat`, 포트 열림, BAT exit 0도 증거가 아니다
  (`docs\agents-rules\DEMO1-SERVER-LIFECYCLE-VERIFY.md`).

R8. 사용자가 "재시작 전에 알려줘"라고 했으면
- `main/java`, `main/resources`, `app/src/main/**` 아래 저장은 곧 DevWatch 재시작이다(static은 재시작 없음).
  저장 **전에** "지금 적용합니다, 서버가 약 1~5분 재시작됩니다"를 먼저 알린다. 별도 작업 공간(worktree/patch 파일)에서 준비하고,
  알림 후에 watched 경로로 옮긴다.
- 적용 후 `--restarts-since <알림 시각>`으로 "재시작 감지 HH:MM"을 보고하고, `--ready`가 READY(또는 NOT_PROVEN_FOCUS 문구)일 때 다시 알린다.
- 예상 못 한 재시작 감지: 사용자 테스트 중에는 매 보고 전에 `--restarts-since <테스트 시작 시각>`를 돌려 새 줄이 있으면 즉시 알린다.

R9. 테스트 실행 목록에서 빠진 테스트 = NOT_DONE
- "107 passed"여도 대상 목록 대비 미실행·skip·필터 누락이 1개라도 있으면 상태는 `NOT_DONE (n missing: <이름>)`.
  누락 이유를 찾아 실행하거나, 실행 불가 사유를 적기 전에는 완료·준비 문구 금지.

R10. `[PREFLIGHT] FAILED reason=launcher-already-running`을 보면
- 실행 거부일 뿐 서버를 건드리지 않았다(JVM 그대로). 다시 실행하거나 ForceRestart 하지 않는다.
- 바로 `--ready`를 돌려 사용자에게: "새로 뜬 창은 닫으세요. 서버는 <판정 한 줄>"로 답한다.
  `LAUNCHER_BUSY`면 기다리라고, `RUNTIME_UP` 계열이면 이미 켜져 있다고, role이 dev인지 wear인지도 말한다
  (Start-Meta-Display 창의 "wear runtime stays up"은 실패 때도 고정 출력되는 문구다).
- `--ready`의 `leftoverLauncherWindows`(pause에 멈춘 Start-*.bat 창)는 서버와 무관하니 닫아도 된다고 안내만 한다(직접 종료하지 않음).

R11. 일반 채팅 OK ≠ 노바 OK
- 노바(Focus)는 저장 프로필 → admission → 모델 선택 경로가 따로 있다. `/chat` 성공, `/api/chat/models` 200,
  특정 모델 지정 검사 성공은 노바 증거가 아니다. 증거는 재시작 이후 `logs\debug-events.ndjson`의
  `focus_terminal outcome=success`(가능하면 같은 activation의 `focus_first_visible`/`focus_presentation_done`).

R12. 노바 기본 모델: 빠른 것 먼저(AUTO), 저장 FIXED가 가린다
- 사용자 선호: AUTO 기본 = Gemini Flash-Lite 먼저, 그다음 Codex 5.6 Luna(`chatgpt-oauth:gpt-5.6-luna`). 비싼/비활성 API 경로를 기본으로 두지 않는다.
- 저장 프로필이 FIXED면 AUTO·yml 기본값 변경은 그 사용자에게 닿지 않는다(R2, `DEFAULT_SHADOWED_BY_PROFILE`).
  기본값을 바꿨다면 보고에 "FIXED 사용자는 영향 없음"을 쓰고, `--focus`로 실제 유효 모델을 확인한다.

남은 미검증(2026-10-09 20:3x 기준): 실제 안경 화면 표시·새 Focus 이벤트(하드웨어 렌더 관측 없음),
디스크 로그 상한(아래 "로그 저장량") — 해결로 보고하지 말 것.

### 로그 저장량 (읽기 전용 확인 결과, 설정 변경 안 함)
- `logs\debug-events.ndjson`: `main\resources\logback-spring.xml:20-29` RollingFileAppender, 일별, `maxHistory=7`, `totalSizeCap` 없음.
  `logs\trace.ndjson`도 같음(`:32-41`). `logging.file.total-size-cap=100MB`(`application.properties:244`)는 Spring 기본 파일 로그용이라 이 둘에 적용 안 됨.
- `var\abnadon\debug\<yyyy-MM-dd>.ndjson`: `DebugEventStore.mirrorNdjson`(`DebugEventStore.java:541-560`), 키 `abandonware.debug.ndjson-dir`
  (`application.yml:475`, env `ABNADON_DEBUG_DIR`). 보존/상한 설정 없음(09-21부터 누적).
- 가장 큰 것: `var\rag-launcher\` 실행마다 폴더(listener out.log 최대 50MB), 상한 없음. 정리는 기존
  `scripts\agent_archive.py scan → prune`(계획만) → `copy_residue_cleanup` apply 경로를 쓰고, 새 정리 도구를 만들지 않는다.
