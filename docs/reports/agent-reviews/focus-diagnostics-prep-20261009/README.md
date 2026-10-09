# Goal

Fold6 마이크 → Nova focus → Meta Ray-Ban Display 장애를 발생 시각에서 좁혀 찾도록 기존 진단에 요청 경계 연결만 보완한다. 생산 변경은 `NovaFocusService.java`, `NovaFocusAnswerService.java` 두 파일이다. 전체 수음·STT·relay 계측이나 실기기 검증 완료를 주장하지 않는다.

# 현재 근거

- 루트 `C:\AbandonWare\demo-1\demo-1\src`, 브랜치 `codex/owned-runtime-browser-restart`, 조사 HEAD `d2d62e7ded7cf70a5d9c77b9482f537bf51e4fa5`. 활성 sourceSet은 `main/java`, `main/resources`, `src/test/java`이며 `:app`은 NO-SOURCE다. 다른 소유자의 `NovaFocusState.java`, Display index 및 진단 스크립트 변경은 보존했다.
- 기존 실행 조사: launcher `20261009-133925-82f9d4c0`, dev PID 50496, `local,meta-display`. `Read-RAG-Debug.bat -JsonStdout` ready; 14:10:35 KST까지 JSON 로그 증가 확인. 초기 served Display JS/HTML 네 파일은 현재 소스와 동일했지만 실행 Java와 현재 소스의 동일성은 당시 미검증이었다.
- 로그: `logs/debug-events.ndjson`, `logs/trace.ndjson`, `var/abnadon/debug/2026-10-09.ndjson`, `var/debug/chat-session-traces/YYYYMMDD/s-<hash>.json`, `var/rag-launcher/<runId>/chat-ui-vibe-listener-18180.out.log`. `var/agent-trace/latest.json`은 10월 4일 기록이므로 오늘 장애의 최신 근거로 쓰지 않는다.
- `ConversateSessionService.java:218` focus 처리가 일반 힌트보다 먼저 실행된다. 일반 힌트의 120자 기준(:65,:268)과 focus 질문 경로는 별개다. `NovaFocusState.java:184`의 8초는 질문 없는 wake 대기 제한이다. 짧은 질문과 3초 발화도 유효한 검증 시나리오다.
- `ConversateSessionService.java:628`의 owner-bound capture ring은 24개 한도이며, :639에서 누락 단계를 `not_observed`로 남긴다. 렌더 receipt는 DOM callback이며 전송 ACK나 물리 렌즈 관찰이 아니다.
- 기존 Nova JS 76 / Java 180 기록은 과거 증거다. 당시 wrapper의 evidence_incomplete 분류와 실기기 NOT_RUN을 그대로 유지한다. 이번 소스의 검증 결과와 혼합하지 않는다.

# Work

가장 큰 공백은 focus 질문 확정, API 시도·결과, 수락된 렌더 receipt가 하나의 안전한 요청 키와 시간으로 연결되지 않는 점이었다. UX 끝단만 기록하면 API 시도와 예산을 잃고, 모든 함수에 hook을 추가하면 범위와 비용이 커진다. 기존 공통 경계 두 곳을 사용했다.

`NovaFocusService`는 `focus_question_confirmed`, `focus_generation_started`, `focus_terminal`, `focus_first_visible`, `focus_presentation_done`, `focus_lifecycle_closed`를 기존 `DebugEventStore`로 보낸다. `where`는 관련 소스 함수다. owner/session/server instance/activation/request/turn은 opaque ID의 `hash:<12hex>`이며 원래 epoch와 현재 epoch를 구분한다. device/captureRun/connectionGeneration이 해당 경계에서 관측되지 않으면 null이다. 이 키의 일치는 검색 조건이며 권한 검증을 대체하지 않는다.

지연은 서버 monotonic clock의 질문 확정 이후 경과 및 답변 수락 이후 렌더 회신 경과다. 질문 확정 이전 수음/STT 지연과 관측되지 않은 렌더 지연은 null로 남긴다. generation 성공과 이후 `presentation_unconfirmed` 종료는 다른 사건이다. 중복 receipt는 기존 응답을 유지하면서 진단을 중복 생성하지 않는다.

`NovaFocusAnswerService`는 기존 안전한 attempt projection에 실제 `elapsedMs`와 설정 timeout, 적용 예산, 예산 출처, 경계 전체 경과를 추가한다. 상속 예산, 기존 timeout clamp, fallback·복구 정책은 변경하지 않는다. selection만 관측된 경우 attempt는 비고 evidenceBoundary는 `not_observed`다.

수집은 Service 내부 daemon 1개·대기 64개 한도로 기존 저장소에 전달한다. 64는 기존 설정이 아닌 새 고정 기본값이다. 요청당 최대 6개 정상 경계를 약 10개 요청의 짧은 burst 동안 버퍼링하면서 실패한 저장소가 메모리를 무한 사용하지 않도록 정했다. 실기기 처리량으로 보정한 값은 아니다. 큐 초과는 drop, 저장 예외는 fail 누계로 남기고 재시도하지 않는다. owner-bound 기존 focus diagnostics에도 누계를 노출한다. 로그 쓰기가 HTTP controller/수음 상태 잠금을 점유하지 않는다. 종료는 비차단이며 JVM 강제 종료 직전에는 대기 로그가 유실될 수 있다. 기존 DebugEventStore RAM 600·mirror 큐 256 한도와 회전 정책은 재사용한다. 기존 mirror NDJSON에는 byte/기간 정리 상한이 없고 logback은 일수 회전만 있어, 전체 디스크 저장이 유한하다고 판정할 수 없다. 새 플랫폼·회전 설정 변경은 이번 범위 밖이다.

## Devin의 기존 조회 명령

루트 PowerShell에서 사용한다. 아래 명령은 서비스 재시작·모델 호출·장애 주입을 하지 않는다.

```powershell
$env:AWX_RAG_NO_PAUSE='1'
$stack=(& .\Read-RAG-Debug.bat -JsonStdout | Out-String | ConvertFrom-Json)
$stack | Select-Object status,role,exitCode,reasonCode
$watch=(& .\Debug-Session.bat -Role dev -Action status -JsonStdout | Out-String | ConvertFrom-Json)
$watch | Select-Object status,watchStatus,watcherAlive,events,counters
python -B scripts/chat_session_debug_export.py list --since-hours 2 --summary --tail 10
python -B scripts/chat_session_debug_export.py show 'hash:<해당-session-hash>' --summary
python -B scripts/server_trace_digest.py --run '<사고 당시 launcher runId>' --since '2026-10-09T14:00:00+09:00' --no-write --max-seconds 5 --max-bytes 1048576
```

`Debug-Session`의 no-session(exit 3)은 수집 watcher 부재다. 장애 증거로 바꾸지 않는다. status JSON의 lastEvent/packet은 원문을 포함할 수 있고 Read-RAG-Debug의 error excerpt도 원문일 수 있으므로 위처럼 허용 필드만 출력한다. watcher 시작은 원문 로그 복사와 무제한 기본 duration 때문에 이 패키지에서 실행하지 않는다. export summary/compact-json만 사용하고 원문 export는 피한다. digest는 WARN/ERROR만 집계하므로 새 INFO focus 경계를 포함하지 않는다. digest의 자동 nextCommand나 전역 최신 session을 본인 것으로 선택하지 않는다.

focus INFO는 기존 paged 진단 API에서 아래와 같이 허용 필드만 출력한다. 시각 범위에서 후보를 찾은 뒤, 이미 owner-bound 화면/기록으로 확인한 ownerHash+sessionHash+epoch+serverInstanceHash를 맞춰 한 요청을 선택한다. 인증이 요구되면 기존 로그인 세션에서 조회하며 인증 파일을 열거나 검사를 제거하지 않는다.

```powershell
$sinceMs=[DateTimeOffset]::Parse('2026-10-09T14:00:00+09:00').ToUnixTimeMilliseconds()
$untilMs=[DateTimeOffset]::Parse('2026-10-09T14:10:00+09:00').ToUnixTimeMilliseconds()
$page=Invoke-RestMethod 'http://127.0.0.1:18180/api/diagnostics/debug/events/page?limit=500'
$page.items | Where-Object { $_.data.stage -like 'focus_*' -and
  $_.data.observedAtMs -ge $sinceMs -and $_.data.observedAtMs -le $untilMs } |
  ForEach-Object { [pscustomobject]@{
    atMs=$_.data.observedAtMs; stage=$_.data.stage; outcome=$_.data.outcome
    reason=$_.data.reasonCode; function=$_.where; owner=$_.data.ownerHash
    session=$_.data.sessionHash; server=$_.data.serverInstanceHash
    request=$_.data.requestHash; turn=$_.data.turnHash
    epoch=$_.data.epoch; currentEpoch=$_.data.currentEpoch
    elapsedMs=$_.data.latencyMs; renderMs=$_.data.renderLatencyMs
    boundary=$_.data.evidenceBoundary; hardwareObserved=$_.data.hardwareRenderedObserved
    dropped=$_.data.diagnosticDropped; failed=$_.data.diagnosticFailed
    model=$_.data.answerModel
  } } | ConvertTo-Json -Depth 8
[pscustomobject]@{hasMore=$page.hasMore; cursorStatus=$page.cursorStatus}
```

hasMore=true면 nextCursor를 사용해 같은 API의 다음 페이지를 조회한다. cursorStatus=evicted 또는 로그 회전/누락이면 coverage 부족으로 남긴다. RAM 600개를 지난 과거 사고는 디스크 NDJSON에서 기간·크기 상한을 둔 조회가 필요하다. 원문 파일 전체, raw stdout, 토큰·전사·답변 필드는 출력하지 않는다. owner/device/재연결 세대가 부족하면 귀속 미확정이며 1인 사용이라는 이유로 채우지 않는다.

# Acceptance

검증된 후보를 원본 해시·리스 및 다른 레인/대기 패치 충돌 없음 확인 후 적용했다. 생산 두 파일, 기존 테스트 두 파일, 이 조회 안내만 변경했다. 보호된 Gradle/settings/logback/API routing 네 파일의 원본 해시는 동일하다.

- 실제 RED: fixture 오류를 제외하고 32개 중 진단 assertion 8개 실패, 24개 통과(`red-corrected/run.json`). 초기 인자 오류와 fixture 오류는 RED로 세지 않았다.
- staged GREEN 32/32, 적용 소스의 fresh XML GREEN 32/32. Gradle UP-TO-DATE로 남은 이전 XML은 `evidence_incomplete`로 보존하고 PASS로 채택하지 않았다.
- 큐 overflow/정상 shutdown 비차단 검증을 더한 최종 GREEN **33/33**, 실패·오류·skip 0. 증거: `data/agent-handoff/codex-autonomy/focus-diagnostics-prep-1e19b105/green-final-33/run.json`과 해당 `command.log`. 정확한 두 suite와 네 소스 해시에 바인딩했다.
- 가짜 `backend_timeout`/`backend_unavailable`, 설정/상속 예산 보존, attempt 미관측, 원래 epoch 보존, 중복/stale receipt, sink 예외에도 후속 질문 유지, 느린 sink 중 HTTP controller monitor 반환, 생성 성공 뒤 `presentation_unconfirmed` 구분을 검증했다.
- 20개 고유 질문의 실제 Focus 상태 경계로 64개 큐 초과와 drop 증가를 검증했다. sink를 latch로 막아도 fake 입력·상태 조회와 shutdown은 1초 제한 안에 반환했다. shutdown 반환은 모든 로그 flush 완료나 실제 PCM/마이크 지속을 뜻하지 않는다.

실행 반영 완료: launcher `20261009-143843-af1f407b`, `status=ready`, `springReused=false`, dev PID **56264**, started 14:42:50 KST / ready 14:43:49 KST. 기존 DevWatch PID 63300을 다시 armed했다. 14:45:29 KST **Verify-RAG exit 0 / status=verified**로 compile·runtime·HTTP·freshness·DevWatch를 확인했다. `/chat` HTTP 200, source/served asset hash 동일, 기존 debug page/API failure 조회 정상이다. 상태·개수만 보존한 `live-verify-final.json`, `live-smoke.json`이 같은 task 디렉터리에 있다. JSON 로그도 새 부팅에서 14:43:27 KST까지 증가했다.

실기기 Fold6 → 안경: **NOT_RUN**. 실제 Focus/API/live render 이벤트 생성은 추가 모델 호출 금지에 따라 NOT_RUN이다. 재기동과 합성 테스트를 실제 안경 표시 결과로 승격하지 않았다.

# 금지

원음·전사·답변·ticket·키·token의 새 로그 수집, 인증 제거, 전역 최신 세션 자동 귀속, timeout 증가/삭제, 무한 재시도, API 클라이언트·테스트 삭제, 자동 소스 변경·임계치 조정, 새 관측 서비스, 배포·push를 하지 않았다. 외부 API: 없음.

# HOLD

실기기 표시와 owner/device/reconnect 전체 체인은 아직 미관측이다. 기존 mirror 저장 상한은 미해결로 명시한다. 이를 생성/API 실패 또는 0ms로 판정하지 않는다. 승인된 소스 보완의 테스트·실행 적용과 구분한다.

PLUGIN_USAGE: Codex executor/협업 서브에이전트와 기존 로컬 Python·PowerShell·BAT 사용. Display 조사 스킬과 work-ledger/완료 정리 스킬 적용. 추가 서비스·외부 모델 호출 없음.

한 줄: 승인된 두 파일 진단 보완·33개 테스트·새 실행 freshness 확인을 마쳤으며, 실기기 표시 증거는 NOT_RUN이다.
