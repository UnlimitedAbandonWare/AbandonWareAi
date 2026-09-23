# 디버깅 공백 수정 지시서

- 작성일: 2026-09-19 · 작성자: Grok · taskId: `debug-gap-report-0a0b3c57`
- 루트: `C:\AbandonWare\demo-1\demo-1\src`
- 선행: `debug-bat-diagnostics-e40b42e3` (Debug BAT 진입점, live e2e verified)
- 방법: 현재 `scripts/debug_rag_stack.ps1` + BAT + 스킬 + 라이브 `Debug-Meta-Display.bat -Json` (18:27 KST, wear pid 33336, exit 0)
- 이번 단계: **소스 변경 0**. 적용 가치가 있는 수정만 GO로 남긴다.
- 상태 표기: `[확인]` 소스/실행 근거 · `[미검증]` 증거 부족 · `[기각]` 하지 말 것

---

## 1. 한 줄 결론

Debug BAT 자체는 기동·소유권·verbose 적용/복원·wear 보호까지 동작한다. 부족한 것은 **증상 분류**다. 힌트 지연/누락을 진단하려고 `status`/`tail`을 쓰면, 고빈도 `conversate.cue` 로그가 다른 단계를 밀어내고, `-Json`에는 그 요약이 없으며, `-Pattern`은 과거 줄을 보지 못한다. 다음 패치는 Java가 아니라 `debug_rag_stack.ps1` + 계약 테스트 + 스킬 한 줄 연결이면 충분하다.

---

## 2. 이미 된 것 (다시 만들지 말 것)

| 항목 | 근거 |
|---|---|
| Debug-RAG / Debug-Meta-Display BAT, 공통 엔진 | `Debug-*.bat` → `scripts/debug_rag_stack.ps1` |
| status 읽기전용, start/restart는 런처 재사용, wear 보호 | live 2026-09-19 ~15:4x, journal `debug-bat-diagnostics-e40b42e3` |
| verbose = process-env `AWX_RAG_DEBUG_LOGGERS` → `--logging.level.*` JVM 인자, 재사용 JVM은 exit 5 | `chat_ui_vibe_listener.ps1` L109-116, live cmdline |
| tail 닫아도 서버 유지, threads/jfr bounded | live tail/threads/jfr |
| AGENTS `DEMO1-DEBUG-ENTRYPOINTS` | `AGENTS.md` L160-169 |
| 스트리밍 파이프 hang 수정 | `Invoke-DebugChildScript`가 파일 리다이렉트 + `-Wait` |

`docs/DEBUG_BAT_USAGE.md`는 당시 plannedScope에 있었으나 **만들지 않는다.** AGENTS 절이 SSOT다.

---

## 3. 라이브에서 재현된 공백 (2026-09-19 18:27 KST)

`cmd /c Debug-Meta-Display.bat -Json` → exit 0, wear pid **33336**, `verboseLogging=absent`, ownership `identity-match`, webReady True, health UP.

| 관찰 | 의미 |
|---|---|
| `cue-decision-search-generate count(last 60m)=2649`, 마지막 `cueDecision=NO_CUE,decisionReason=SMALL_TALK,hintGenerated=false` | 게이트 거부까지 “검색·생성”으로 집계. 힌트 경로가 실제로 돌았는지 구분 불가 |
| `transcript-input=0`, `search-routing=0`, `display-delivery=0`, `errors=0` | 같은 60분에 cue 2649건이 있는데 입력/전달/오류가 0. Tail 4000줄 창이 cue에 점유됨 |
| out-log **27,345,674 bytes** | `Get-Content -Tail 4000` 후 `LogMinutes=60` 필터 → 시간 창이 아니라 줄 수 창 |
| `-Json` 파일에 pipeline/freshness/ports/readiness **없음** | `var/debug/wear-20260919-182758-status.json`은 runtimes+exitCode뿐 |
| `FRESHNESS STALE-CANDIDATE` newest=`OpenAiTokenParamCompat.java` | Java mtime만 비교. 서빙 중인 `receiver.js` 해시와 무관. 다른 작업(E1-E6) 파일 변경이 wear stale로 표시 |
| port **18182 ownerPid=0** | 매니페스트는 18182를 기록하나 LISTEN 소유자 없음 |
| DevWatch 로그 `??rebuild` | UTF-8 화살표가 debug log 복사에서 깨짐 |
| 15:46 `tail -Pattern "힌트"` **captured=0** | EOF부터만 따라감. 직전 이력은 12줄 컨텍스트뿐이고 필터도 안 함 |

같은 날 15:45(이전 wear pid 22756)에는 `transcript-input count=1`가 `display.conversate state=RUNNING reason=started` 한 줄이었다. 세션 시작을 전사 입력으로 오인한다.

16:00:42 같은 런타임 로그: `display.runtime stage=http_error ... path=/api/assist/display/relay/diagnostics code=http_403`. `display-delivery` 패턴 `display.runtime stage=`는 이 오류를 전달 성공으로 잡을 수 있고, `errors` 패턴은 `display.request_failed`만 본다.

---

## 4. GO — 적용할 수정 (우선순위)

한 번에 새 대시보드/액션을 만들지 말 것. 1→2→3 순서로, 각 단계 후 계약 테스트 + `Debug-Meta-Display.bat -Json`으로 같은 증상을 재확인.

### G1. 파이프라인 집계가 고빈도 cue에 먹힘 + 마커가 단계를 섞음 — **P1**

**파일:** `scripts/debug_rag_stack.ps1` (`Get-DebugStageSummary`, `$script:DebugStageMarkers` L231-246, L250-293)

**원인 [확인]**
- 로그를 시간으로 자르지 않고 `-Tail 4000`만 읽는다.
- wear `transcript-input` 패턴에 `display.conversate state=`가 있어 `reason=started`가 입력으로 잡힌다.
- `cue-decision-search-generate`가 모든 `conversate.cue`를 한 통으로 센다 (`NO_CUE` 포함).
- `display-delivery`가 모든 `display.runtime stage=`를 잡고, `http_error`를 errors로 안 보낸다.
- 렌즈 전달 마커(`lens/text`, `hintId`, `hintGenerated=true`)가 없다.

**수정**
1. 파일을 끝에서 읽어 **cutoff(`-LogMinutes`)에 닿을 때까지**. 상한은 유지(예: 2만 줄 / 8 MiB). 27MB 전체를 로드하지 말 것.
2. 단계를 분리:
   - `transcript-input`: `stt.usage`, `display.conversate inputPath=`, `interview.display.accepted`만. `state=` 제거.
   - `cue-gate`: `conversate.cue` + `cueDecision=` 추출. 카운트를 `NO_CUE` / `CUE` / `RAG_CUE`로 나누거나 `hintGenerated=true`만 별도 `hint-generate`.
   - `search-routing`: 기존 Hybrid/EVIDENCE_GATE/llm-failover 유지.
   - `display-delivery`: `display.runtime stage=` 중 `http_error`/`request_failed` 제외 + `hintGenerated=true` 또는 기존 lens 로그가 있으면 그것.
   - `errors`: `display.request_failed`, `display.runtime stage=http_error`, `[API_FAILURE]`, `chat-failed`, `stream-failed`.
3. 콘솔 한 줄에 `last fields=`뿐 아니라 **구분 카운트**(예: `noCue=2640 hint=9`)를 넣는다.

**검증:** 지금과 같은 wear 로그에서 `cue-gate`는 커도 `display-delivery`/`errors`가 0으로 고정되지 않을 것. `reason=started`는 transcript-input에 안 잡힐 것. `scripts/debug_rag_stack_tests.ps1`에 픽스처 로그 4줄.

**하지 말 것:** 새 로그 포맷을 Java에 추가하지 말 것. 있는 INFO 마커만 쓴다.

### G2. `tail -Pattern`이 과거를 안 봄 — **P1**

**파일:** `Invoke-DebugTail` L563-601. `-StageContextLines`는 L32/L54에만 있고 **사용처 0**.

**원인 [확인]** 스트림을 EOF로 Seek한 뒤 신규 줄만 본다. AGENTS는 “힌트 지연 → tail -Pattern”인데, 15:46 `-Pattern "힌트"` captured=0.

**수정**
- follow 전에 기존 로그에서 패턴 매칭 **최근 N줄**(기본 30, `-StageContextLines`가 0보다 크면 그 값, 상한 20은 파라미터 범위를 50 정도로 올리거나 별도 `-HistoryLines`).
- 그 다음 현재처럼 bounded follow.
- 이력이 0이고 follow도 0이면 종료 메시지에 “과거 매칭 0 / 감시 중 신규 0”을 구분해 적는다.

**검증:** 픽스처 파일에 `힌트` 한 줄 + 신규 없음 → captured history ≥ 1, 서버 무접촉.

### G3. `-Json`이 콘솔 요약과 다른 객체 — **P1**

**파일:** `Invoke-DebugStatus`, `Invoke-DebugEntry` L724-728, `$script:Report` L69-72.

**원인 [확인]** 18:27 JSON에 pipeline/freshness/ports/readiness/logPath 없음. 에이전트가 `-Json`만 파싱하면 “ready”만 본다.

**수정:** `$script:Report`에 이미 계산한 객체를 넣는다: `readiness`, `freshness`, `pipeline`, `ports`, `log`, `devWatch.status`. 로그 원문·쿼리·cmdline 넣지 말 것. `lastSnippet`는 160자 allowlist 필드만(이미 cue key 추출이 있음).

**검증:** `-Json` 파일에 `pipeline` 배열이 있고 콘솔 stage 수와 같다.

### G4. 계약 테스트 부재 — **P1**

**파일 (신규):** `scripts/debug_rag_stack_tests.ps1`  
**스타일:** `scripts/dev_reload_watch_tests.ps1` (dot-source + stub, InvocationName 가드 이미 L741).

최소 단언:
- 로거 파싱: 유효 `pkg=DEBUG` / 거부 `rm -rf` / 최대 8개
- 단계 마커: `reason=started` ≠ transcript-input; `NO_CUE` ≠ hint-generate; `http_error` → errors
- retention glob이 `wear-tail-*.log`도 포함(아래 G7)
- JSON schema에 pipeline 키
- DryRun start가 프로세스를 안 만듦(이미 동작, 회귀)

**검증:** `powershell -File scripts/debug_rag_stack_tests.ps1` exit 0. live BAT 재시작은 이 테스트가 통과한 뒤에만.

### G5. 스킬이 Debug BAT를 관찰 슬롯으로 안 가리킴 — **P1**

**파일 (한 줄~표 한 칸, 새 문서 금지)**
- `.agents/skills/demo1-evidence-debugging/SKILL.md` — 관찰 도구 기본값을 `Debug-RAG.bat` / `Debug-Meta-Display.bat -Action status` 후 필요 시 `tail`/`restart -Loggers`.
- `.agents/skills/demo1-debugging-with-two-tools/SKILL.md` — observation slot 기본에 동일.
- `.agents/skills/demo1-toolchain-auto-select/SKILL.md` — “Start/close 실패, 미반영, 힌트 지연” 행 → Debug BAT.

현재 `.agents/skills`에서 `Debug-RAG`/`debug_rag_stack` 매칭 **0건** [확인].

**하지 말 것:** INDEX에 새 canonical 스킬을 추가하지 말 것. BAT는 도구이지 스킬이 아니다.

### G6. wear 기본 로거가 안내한 워크플로보다 좁음 — **P2** (G1과 같이 가능)

**파일:** `Resolve-DebugLoggers` L313-316.

wear 기본은 `com.example.lms.assist=DEBUG`뿐. `conversate.cue`는 이미 INFO라 힌트 게이트는 보인다. 검색 공백(`outCount=0`)은 `[Hybrid]` WARN은 보이지만 search DEBUG는 안 켠다.

**수정:** wear 기본에 `com.example.lms.search=DEBUG` 한 줄만 추가. llm 패키지 전체 DEBUG는 로그 폭증 위험이 있어 **넣지 말 것**. help 텍스트와 AGENTS 한 줄을 맞춘다.

**검증:** `-Action start -DryRun`에 두 로거가 보이고, 실제 restart는 사용자가 힌트 지연을 볼 때만.

### G7. 작은 스크립트 결함 (G1과 같은 패치에 포함) — **P2**

| 결함 | 위치 | 수정 |
|---|---|---|
| tail 캡처 파일명 `wear-tail-<ts>.log`가 retention 정규식에 안 맞음 | L298 vs L578 | glob에 `^(dev|wear)-tail-` 추가하거나 파일명을 `{role}-{stamp}-tail.log`로 통일 |
| `Get-DebugLogPath` fallback `latest-file-unproven` | L222-226 | 다른 role 로그를 붙잡지 말 것. 매니페스트 없으면 tail 실패(이미 status는 WARN) |
| JFR이 파일 생성 전에 OK | `Invoke-DebugJfr` L656 | “scheduled”를 정직히 유지하되, 파일이 없으면 `jfr-scheduled-file-pending`으로 status 구분. duration만큼 블로킹하지 말 것 |
| threads 덤프에 BLOCKED/deadlock 요약 없음 | `Invoke-DebugThreads` | `jcmd Thread.print` 후 `BLOCKED`/`deadlock` 카운트 한 줄. 새 액션 추가 금지 |
| 서빙 자산 해시 없음 | `Invoke-DebugStatus` freshness | wear일 때 `GET /assets/display/meta/receiver.js` sha256 vs `assets/display/meta/receiver.js` (런처가 이미 하는 비교). Java 트리 전체 mtime 스캔은 보조로 남기되 “unrelated-source-mtime”로 표시 |

---

## 5. 선택 (이번 패치 밖, 근거는 있음)

| ID | 내용 | 왜 지금 아닌가 |
|---|---|---|
| S1 | `logs/debug-events.ndjson` / `DebugEventStore`를 status에 최근 N건 요약 | 이미 적색 필드(hash/count). 읽으면 좋지만 G1이 먼저. 27MB out-log와 별개 파일 |
| S2 | `threads`에 `jcmd GC.heap_info` 한 줄 | AGENTS는 hang/CPU → threads/jfr로 충분. 새 `-Action heap` 만들지 말 것 |
| S3 | 18182 LISTEN 없음 분류 | 매니페스트 ports에 있는데 ownerPid=0. netty가 같은 JVM에서 bind 실패인지 미기동인지 **미검증**. status에 `netty=not_listening`만 명시하고 실패로 올리지 말 것 |
| S4 | `relay/diagnostics` http_403 | `boot.js`는 `X-Display-Runtime`을 넣는다. 403은 Origin/헤더 계약(`DisplayConversateHttpTest` 403 단언). Debug BAT 범위 아님. G1 errors 분리가 되면 관찰 가능 |

---

## 6. 하지 말 것 (기각)

- 새 Debug 대시보드, admin 페이지, `/actuator/loggers` 라이브 레벨 변경 — 엔드포인트 현재 0건, 재시작 없는 레벨 변경은 새 표면.
- `docs/DEBUG_BAT_USAGE.md` 장문, README Nova Protocol 블록 개조.
- User/Machine env에 로거 persist, openssl/키/로그 원문 덤프.
- wear 점유 중 `Debug-RAG.bat -Action restart`로 dev verbose를 우회 — 이미 exit 2가 맞음. **dev live verbose는 ports occupied라 미검증**으로 남을 것.
- Java에 새 로그 라인/프레임워크를 넣어 파이프라인을 “고치기”.
- 실안경 경로, 유료 provider 호출, 실행 중 wear 재시작을 이 보고서 검증으로 쓰기.

---

## 7. 권장 적용 묶음

| cycle | 대상 | 완료 조건 |
|---|---|---|
| A | `debug_rag_stack.ps1` G1+G2+G3+G7 | 픽스처 테스트 + 실행 중 wear에 `status -Json`만 (재시작 없음). pipeline 구분 카운트와 JSON 키 존재 |
| B | `debug_rag_stack_tests.ps1` | 테스트 exit 0, `dev_reload_watch_tests.ps1` 회귀 불필요(파일 독립) |
| C | 스킬 3파일 한 줄 + help/AGENTS 로거 문구 (G5, G6 문구) | `validate_demo1_skill_family` 해당 스킬, `verify_codex_instructions` 기존 실패 증가 없음 |

Cycle A는 소스 lease가 `scripts/debug_rag_stack.ps1`에 필요할 수 있다. 현재 활성 lease는 Java E1-E6 파일들이며 **이 스크립트와 겹치지 않음**. 그래도 begin 전에 `source_edit_session.ps1 -Action status`로 확인.

---

## 8. 검증에 쓴 명령 (재실행용)

```bat
set AWX_RAG_NO_PAUSE=1
Debug-Meta-Display.bat -Json
Debug-Meta-Display.bat -Action tail -TailSeconds 5 -Pattern "conversate.cue"
```

재시작·verbose start는 cycle A 이후에만. 현재 wear(pid 33336, run `20260919-164451-b931ffc9`)는 이 보고서가 소유하지 않음 — Close하지 말 것.
