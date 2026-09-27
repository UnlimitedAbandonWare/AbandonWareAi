# P2 Jev 패치 진행 도구집 — Devin 지시서 동반 (2026-09-26)

용도: `agent-prompts/jev-p2-induction-20260926/brief.md`와 2026-09-26 사용자 지시서를 받은 세션이
단계마다 정확한 **스크립트/스킬/룰/명령**을 적재적소에 쓰도록 매핑한다. 새 설계·새 지시가 아니다.
우선순위: **C-root 현재 코드 > 지시서·핸드오프 > ZIP/옛 스냅샷**.

## 0. 착수 3단계 (AGENTS 필수 순서)

1. `python -B scripts/agent_preflight.py --root .` — journals/lease/tool 상태 JSON 확인.
2. `python -B scripts/demo1_vibe_skill_router.py resolve "<한 줄 요약>"` — primary 스킬 1개 결정.
   `source-write` intent → `demo1-work-ledger`. **@태그 5개 이상 나열은 라우팅 실패**(AGENTS).
3. 자기 journal만 열기:
   `python -B scripts/work_journal.py open --root . --task <slug> --agent devin --purpose "<1줄>" --scope <repo-relative-path>` (복수 `--scope`)

**lease (2026-09-26 08:0x UTC 실측):** active 1건 `codex-assist-runway` → `scripts/codex_work_checkpoint.py`,
`scripts/test_codex_work_checkpoint_source_expressions.py` — Jev 접점과 무관. 겹침 여부는 항상 manifest로 확인:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__/source_edit_session.ps1 `
  -Action status -Root . -Json -TargetManifest <json-file>
# json 파일 형식 {"targets":[{"path":"...","sha256":"<64hex|null>"}]} — inline JSON 불가, 새 파일은 sha256:null
```
`targetConflict.allowed=true`이면 진행. 외세션 live lease는 절대 삭제/강제 해제 금지(겹치면 다른 파일부터, `lease_conflict_autoflow.py reclaim`은 stale 전용).

## A. Phase A — 재인증·smoke (제품 배선 전)

| 항목 | 값 |
|---|---|
| 명령 | `node scripts/jev_gateway_smoke.mjs` (exit `0`=PASS, `3`=FAIL) |
| env **이름만** | `AI_GATEWAY_API_KEY`(호스트에 present), `AWX_JEV_ENDPOINT/MODEL/TIMEOUT_MS/ALLOW_HOST` |
| **현재 시점 재확인 2026-09-26T08:05:37.713Z** | **FAIL `auth-blocked` — HTTP 401 `authentication_error`, attempts=1, latencyMs=384, exit=3** |
| 보고 문구 | Phase A = `FAIL(auth-blocked)` (2026-09-24 2회 401과 동일 분류, 현재 시점으로 갱신됨) |

- FAIL 처리는 지시서 A 그대로: (1) 사용자에게 Vercel/Gateway 재로그인·키 갱신만 요청 **또는** (2) mock+SHADOW 제품 경로 진행 + live ON 차단.
- mock 재현: `AWX_JEV_ENDPOINT=http://127.0.0.1:<port>/v1/evaluate AWX_JEV_ALLOW_HOST=127.0.0.1 node scripts/jev_gateway_smoke.mjs`
  (loopback만 https 예외, 그 외 호스트는 allowlist 필수 — 스크립트 자체가 강제).
- **"실API 미확인을 mock PASS로 위장해 live ON" 금지.** 키/토큰/Authorization 출력 금지(스미키 JSON 출력 유지).
- 프로모 Free 종료(≈2026-09-25): `demo.jev.free-only`/`allow-paid`는 AGENTS.md `DEMO1-VERCEL-AI-GATEWAY-CREDIT`(≈343행) 그대로.
  예산·가격 불명 → **호출 스킵 + 기존 경로 유지**, 자동 유료 전환 금지.

## B. Phase B — 제품 배선 (min-diff) 접점·구현체

### B1. 접점 (2026-09-26 C-root 실측)

| 접점 | 위치 | 무엇만 |
|---|---|---|
| Cue local_rules 게이트 | `main/java/com/example/lms/assist/ConversateApiCueService.java:85`(`gateProvider=local_rules`), `:91`(`decisionSource=local_rules`) | 게이트 직후 debug 맵에 `jevDecision` + `jevReasonCode` 추가. SHADOW까지는 기존 `cueDecision`/`ragNeeded`에 **적용하지 않음** |
| Focus 검색 판정 | `main/java/com/example/lms/assist/NovaFocusAnswerService.java:53` `decisions.decide(...).shouldSearch()` 계산 **이후** | 같은 값 `TraceStore.put` 패턴(`:99`~`:115`)으로 `focus.jevDecision`/`focus.jevReasonCode` |
| Jev 클라이언트 | 신규 얇은 `JevDecisionClient` — **main/java에 기존 Jev 참조 0건(실측)** | Gateway native `POST /v1/evaluate`만. `/chat/completions`로 가짜 Jev 금지 |
| 설정 | `main/resources/application-meta-display.yml` `demo:` 블록(`:132`) 아래 `jev:` 신규 — **모든 application*.yml에 `jev` 0건(실측)** | `enabled:false`, `mode: off\|shadow\|on`, `model: typesafe-ai/jev`, 짧은 `timeout-ms`, `free-only:true`, `allow-paid:false` |
| 폰 설정 UI | `main/resources/static/assets/display/index.html:75`(`nf-jev` 셀렉트 전부 disabled), `:89`(`Jev: JEV_NOT_CONFIGURED` 고정 문구) | 실제 모드(OFF/SHADOW/ON/미설정)와 문구·enabled만 일치. **`display-focus-controls.js`에 `nf-jev` 바인딩 0건(실측)** → 기존 settings paint 경로에 얹기, 새 컨트롤 금지 |

접점 외 파일(P0 클라, 음성 resume/quiet-gate, audioEpoch, STT, PCM)은 손대지 않는다.

### B2. 모드 사다리·fail-open·로그

- `OFF → mock → SHADOW(로그만, 라우팅 미적용) → 제한 ON(키·예산·A PASS일 때만)`.
- fail-open: 타임아웃/401/파싱 실패 → 기존 결정적 로직(`ConversateQuestionPolicy` local_rules / `SearchDecisionService`)으로 즉시 복귀. **근거0 HOLD 재도입 금지** (`DEMO1-EVIDENCE-ZERO-RELEASE`).
- 판정 라벨 계약: `RECENT_ONLY | SCOPED_RAG | WEB | HYBRID | CLARIFY` (+ `defer`/`budget_skip` 등 fail-open 사유 코드만).
- 로그: `[AWX][jev] decision=... reasonCode=...`만. PCM·대화원문·키 금지.
- 채팅/힌트 **생성** 모델을 Jev로 바꾸지 않는다. 킴미 `jev-plan-assist`의 계획 제안은 클라이언트/티켓 패턴 재사용만 — 오케스트레이터 전면 개조 금지(필요 시 SHADOW 로그 + 후속 이슈).

## C. Phase C — 검증 (지정된 것만 실행)

1. **hot path span 0** (JS):
   `node --test src/test/js/conversate-pcm.test.cjs src/test/js/display-voice.test.cjs src/test/js/display-stop-lifecycle.test.cjs src/test/js/display-capture-recovery.test.cjs`
   + PCM chunk / `beginVoice` / reconnect 경로에 Jev 클라이언트 호출 0건 단위 테스트(mock 카운터).
2. **단위(Java, mock 필수 — 라이브 키 불필요)**:
   `.\gradlew.bat :test --tests com.example.lms.assist.ConversateApiCueServiceTest --tests com.example.lms.assist.ConversateCueRoutingPolicyTest --tests com.example.lms.assist.NovaFocusAnswerServiceTest --console=plain`
   + `JevDecisionClient` mock success / timeout fallthrough 신규 케이스.
3. **P0/P1 회귀** (깨면 롤백):
   - JS128 세트 = `data/agent-handoff/codex-autonomy/display-phone-policy-0926-f9fee58c/js-final-01/run.json`의 `commandArgv` 12파일 그대로 `node --test ...`.
   - HTTP/STT = `.\gradlew.bat :test --tests com.example.lms.assist.DisplayPhonePolicyTest --tests com.example.lms.assist.DisplayAudioBatchTest --tests com.example.lms.assist.ConversateCloudSttTest --tests com.example.lms.assist.ConversateAsrBridgeTest --console=plain`
4. **trace 1건**: 확정 Focus 질문 1건 → `python -B scripts/chat_session_debug_export.py`(`var/debug/chat-session-traces`)로 `jevDecision`+`reasonCode` 확인. SHADOW에서 음성/Focus 성공이 깨지지 않음(fail-open) 증거.
5. **요청→실제 모델과 Jev 판정 분리**: `focus.selection.requestedModelHash`/`resultModelHash`(`NovaFocusAnswerService.java:113-114`)와 Jev 필드가 섞이지 않음.
6. **라이브 반영** (Java/자원 변경 시에만): 셸 에이전트는 먼저 `set AWX_RAG_NO_PAUSE=1`.
   `Close-RAG.bat` → `Start-RAG.bat` (또는 DevWatch `[DEV-RELOAD] socket ready`) → `Verify-RAG.bat` (exit `0` verified / `3` not-running / `6` failed).
   **DDL partial은 이번 완료 게이트 아님** (지시서 명시): 최신 `var/debug/dev-20260926-155241-verify.log` = exit 0 / `target=partial` / DDL 128 = 정상 관측.
   `Status-RAG.bat`/`-CheckOnly`는 증거가 아님.

## D. 스킬 라우팅 — 단계당 primary 1개 (지정 @태그 정리)

| 단계 | primary | 보조/조건부 | 이번 패치에서 제외 |
|---|---|---|---|
| 착수·ledger·checkpoint | `demo1-work-ledger` (router 결과; guard `demo1-project-root`) | — | — |
| A (비용·호출 판단) | `demo1-agent-api-spend-guard` | — | — |
| B (소스 수정) | `safe-source-edit` | lease/checkpoint는 work-ledger 절차 | — |
| C (검증 실행) | `compile-verify-smoke` | 라이브 반영 `start-rag-reload` | — |
| 증거 수집·반박 | `demo1-evidence-debugging` | trace 내보내기 `demo1-chat-session-debug` | — |
| 검색 0건/라우팅 증상이 **실제로** 붙을 때만 | — | `rag-search-diagnosis`, `search-zero-result-recovery` | 평소 로드 금지 |
| 교차 판정 | — | — | `positive-negative-neutral-judge`(triad는 명시 요청 시에만), `objective-executor`(중복), `demo1-core-request-router`(위임용) |
| Display UI/lens | — | `frontend-display-debug`는 `nf-jev` 표시가 실제로 깨질 때만 | `demo1-meta-display-simple-caption`, `demo1-meta-display-resume`, `demo1-repairing-from-live-evidence`(증거 결여 시에만) |

- 음성 resume/quiet-gate 스킬은 **같은 패치에 묶지 않는다**(지시서 금지).
- 킴미 `jev-plan-assist` 규칙(계획 제안만, 생성/HOLD/권한 대체 금지, fail-open)은 B2와 충돌 없이 그대로 상위 규칙.

## E. 워크플로/guard 명령 치트시트

```powershell
# checkpoint 1사이클 (begin 실패 = 변경 시작 금지)
python -B scripts/codex_work_checkpoint.py begin --root . --run <cycle> --decision <task>/decision.json --target <path>
python -B scripts/codex_work_checkpoint.py apply  --root . --run <cycle> --target <path> --content-file <file>
python -B scripts/codex_work_checkpoint.py seal   --root . --run <cycle>
python -B scripts/run_verified_command.py --output <fresh-dir> -- <검증 명령...>
python -B scripts/codex_work_checkpoint.py finish --root . --run <cycle> --exit-code <n> --command-id <id>
python -B scripts/work_journal.py note --root . --task <taskId> --kind verify --text "..." --ref <cycle>/checkpoint.json
python -B scripts/work_journal.py close --root . --task <taskId> --result verified --summary "..."
```

- 상태 행은 `python -B scripts/status_doc.py --file docs/PROJECT_STATUS.md read --key <id>` → `append-row/update-row --expect-sha256 <hash>` 로만(충돌 시 덮어쓰기 금지).
- 웨어측 관측이 필요하면 `python -B scripts/devin_task_orchestrate.py capture --role wear --invoke` (redacted status → `data/agent-handoff/display-debug/latest.json`의 `patchHints`를 쓰기 전에 읽기).
- phase 골격이 필요하면 `python -B scripts/devin_task_orchestrate.py plan --brief-file <지시서>` — 2026-09-26 실행 결과 `nova-focus` playbook 매칭(프리플라이트→캡처→검증 골격만 참조, **write 목록은 지시서 THE ONE으로 제한**).
- **Git**: 커밋은 사용자 요청 시에만 `python -B scripts/agent_git_vibe_commit.py --repo . --path <owned>... --message-file <file>` (JSON `committed=`/`deferred=`). push·`add -A`·`commit -a`·rewrite·타인 스테이징 변경 금지, foreign staging 보존.

## F. 완료 보고 (한 장, 지시서 표 그대로)

| 항목 | 허용 결과 |
|---|---|
| Phase A | `PASS` 또는 `FAIL(auth-blocked)` — **현재 기록: FAIL(auth-blocked) 2026-09-26T08:05:37Z** |
| P2 제품 | `done(mock\|shadow\|on)` — 모호한 `JEV_NOT_CONFIGURED`만으로 종료 금지. 키 없으면 mock+SHADOW 배선 완료 + live=`blocked(auth\|budget)` |
| hot path | Jev span 0 |
| fail-open | 장애 시 기존 경로 (테스트명) |
| diff | Cue/Focus(+yml/client/test) 중심 최소 |

## G. 금지 (요약 — 지시서 그대로)

새 오케스트레이터 · Jev를 채팅/힌트 생성 모델로 교체 · GraphRAG 자동색인 · timeout-only · secrets 출력 ·
push/`add -A`/rewrite · 타인 스테이징 · P0 클라 파일 불필요 재수정 · 관리자/PROTO_OPEN 인증 이슈 혼입 ·
근거0 HOLD 재도입 · mock PASS를 live PASS로 위장.

## H. 이 문서가 인용한 실측 팩트 (2026-09-26, C-root)

- Phase A: smoke 원본 JSON `{"jevResult":"FAIL","reason":"auth-blocked",...,"httpStatus":401,"latencyMs":384}` exit 3.
- `main/resources/application*.yml`에 `jev` 0건 · `main/java`에 Jev 참조 0건 (grep 실측).
- `ConversateApiCueService.java:85/91` local_rules 게이트·decision · `NovaFocusAnswerService.java:53` SearchDecisionService 계산, `:99+` TraceStore.put 패턴, `:113-114` requested/result model hash.
- `display/index.html:75/89` nf-jev 비활성 + `JEV_NOT_CONFIGURED` · JS 바인딩 0건.
- JS128/HTTP STT34 근거: `display-phone-policy-0926-f9fee58c` (`js-final-01/run.json`, `java-green-02/run.json`).
- 관련 기존 지시서: `agent-prompts/jev-gateway-20260924/brief.md`, `jev-plan-assist-20260924/brief.md`,
  `display-reconnect-transcript-tts-jev-20260926/brief.md`, handoff `.../jev-gateway-decision-layer-b9795be3/status-row.txt`.
