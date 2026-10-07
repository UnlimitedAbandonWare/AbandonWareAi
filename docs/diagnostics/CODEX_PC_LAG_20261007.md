# Codex 지연 안전 진단 — 2026-10-07 KST

작업: `codex-pc-lag-c5bc846b`. 관측 구간: 2026-10-06 23:31–23:38 UTC, 10-07 08:31–08:38 KST. 루트: `C:\AbandonWare\demo-1\demo-1\src`.

## 결과와 실제 조치

**현재 Codex 렉의 확정 원인은 미확인입니다. 재부팅 없는 실제 개선은 NOT_PROVEN입니다.** 원인 조사와 보고서·집계 증거 저장을 완료했습니다. 소스 수정 0, 프로세스 종료 0, 포트 종료 0, 리스 해제/삭제 0, 앱/서버/브라우저 재시작 0, 캐시/세션 삭제 0, 설치·설정 변경·commit·push 0입니다. 종료된 작업의 소유 자원으로 확정할 수 있는 정리 대상은 없었습니다.

현재 가장 강한 부하 신호는 백업 서비스 계열의 지속 I/O입니다. 이것이 Codex UI 지연을 유발했다는 인과관계는 검증하지 못했습니다. 앱 자체 문제일 가능성도 열려 있습니다. 현재 로그가 비어 있어 과거 MCP 오류만으로 플러그인을 끄거나 repo를 바꾸지 않았습니다.

## 관측 사실

- 재부팅: Windows System / Microsoft-Windows-Kernel-General 이벤트 ID 12의 `TimeCreated` = **2026-10-06 23:08:32.117 UTC / 10-07 08:08:32.117 KST**. TickCount64 추정 23:08:31 UTC와 일치합니다. 초기 관측은 부팅 후 약 25분입니다. 기록상의 오래된 작업이 재부팅 전 프로세스까지 살아 있다는 뜻은 아닙니다.
- 공식 Codex 앱 `list_threads`: 최근 25개 항목의 로컬 Codex 작업 중 active 3, idle 2, notLoaded 7. 후속 최근 8개 조회에서도 로컬 active 3입니다. 전체 작업 수는 아닙니다. idle/notLoaded는 작업 소유 자식 프로세스가 유휴라는 증거가 아닙니다.
- 초기 프로세스 그룹: Java 19 / working 6,742 MiB / private 9,616 MiB; Devin 계열 46 / working 10,525 MiB / private 8,523 MiB; Node 40 / working 2,476 MiB / private 2,341 MiB. PowerShell 7 + pwsh 2. 별도 시점 ChatGPT 호스트 계열 15개 / working 2,568.5 MiB / private 2,612.2 MiB / handles 7,921. 작업 집합 합계는 공유 페이지를 중복할 수 있습니다.
- PID 16656 `codex`는 ChatGPT PID 15712의 자식입니다. Codex 백엔드 PID의 수치가 앱 UI 반응성을 직접 측정하지는 않습니다. Codex PID 49928/31256의 부모는 각각 node_repl 30664/20848입니다. Java PID 43800/28448/36176의 부모는 각각 살아 있는 Devin 10136/10656/13648입니다. 포트 열림·경과시간·부모 종료 여부만으로 고아/누수를 판정하지 않았습니다.
- 현재 소스 리스 1건은 다른 작업 `conditional-jev-selection-a109054d`의 Java/test 대상 예약으로 관측됐습니다. ownerState unknown / heartbeat absent지만 TTL이 유효한 외국 리스이므로 유지했습니다. 별도 작업 원장의 `in_progress`도 완료 증거로 해석하지 않았습니다.
- 동적 포트 실행기 공식 `status`의 저장 기록 **38건 모두 released**, nonterminal 0. 실제 listening 소켓과 동일하지 않은 과거 기록입니다. 현재 자식 잔류와 이 실행기 사이의 연결 증거는 없습니다.
- PID 51960 `ABCore` / parent 5768 `ABService`가 표본의 I/O를 지배했습니다. `AOMEI Backupper Scheduler Service`가 Running으로 확인됐습니다. 마지막 유효 표본의 ABCore I/O 120.85 MiB/s, 전체 디스크 121.18 MiB/s, 평균 queue 0.983, 평균 write latency 28.28 ms. 프로세스 I/O 카운터는 파일 외 I/O도 포함하므로 디스크 기여율로 확정하지 않았습니다. ABCore/ABService 생성시각은 권한상 확인되지 않아 조작 대상에서 제외했습니다.

## 전후 수치 — 개입 없는 관측 변화

baseline 23:35:09 UTC와 최종 유효 카운터 23:37:14 UTC, 프로세스 재관측 23:36:40 UTC를 비교했습니다. **개선 조치를 하지 않았으므로 변화는 자연 변동이며 개선 효과가 아닙니다.**

| 항목 | baseline | 후속 관측 |
|---|---:|---:|
| 메모리 available | 32,299 MiB | 33,097 MiB |
| committed / limit | 49.79 / 117.92 GiB, 42.22% | 50.03 / 117.92 GiB, 42.43% |
| 전체 CPU | 17.04% | 20.58% |
| 전체 disk throughput | 120.39 MiB/s | 121.18 MiB/s |
| disk queue | 0.998 | 0.983 |
| Codex PID 16656 private | 859.1 MiB | 860.2 MiB |
| Codex PID 16656 working | 877.0 MiB | 819.3 MiB |
| Codex PID 16656 handles | 1,371 | 1,389 |
| 전체 프로세스 / handles | 650 / 218,536 | 655 / 218,901 |
| 고유 listening PID/port 쌍 | 62 | 62 |

8초 CPU delta 표본의 조회 가능한 프로세스 합계는 전체 CPU 5.45% 상당, Codex 계열 0.95%였습니다. protected/system 프로세스가 빠질 수 있는 **하한 관측**입니다. 이어 실제 시스템 성능 카운터에서 일시 CPU 59.05–67.51%, 이후 14–24% 수준이 관측됐습니다. 메모리 여유/커밋은 현재 부족 상태를 지지하지 않습니다. 짧은 handles 증가 18과 private 증가 1.1 MiB는 누수 증거가 아닙니다.

탐색용 전체 Process(*) 5표본은 종료/생성 경합으로 invalid counter 경고를 냈습니다. 해당 파일은 탐색 증거로만 보존했습니다. 후속 3표본은 system + 고정 instance를 조회하고 **모든 36개 CounterSample Status=0**을 확인했습니다. PID 51960/parent5768 및 codex PID16656도 카운터로 다시 확인했습니다. 최종 수치는 이 유효 표본과 Get-Process 정적 수치를 사용합니다.

## 앱 진단과 추정 구분

허용된 `%LOCALAPPDATA%\Codex\logs\2026\10\06`의 현 실행 23:10:36–23:11:33 UTC 생성 파일 3개는 모두 0 bytes입니다. 이전 실행의 제한된 6,000줄(21:16:23–22:54:57 UTC)을 내부에서 집계한 결과 INFO 4,565 / WARNING 922 / ERROR 513. WARN/ERROR 중 MCP 초기화/연결 관련 키워드 726, MCP+timeout 102, transport 68회로 범주가 중복됩니다. 동일 오류 지문 2개가 각각 109회 반복됐습니다. 원문 메시지/오류 지문/대화/쿼리는 저장하지 않았습니다.

이것은 **과거 MCP 초기화/통신 문제 신호**이며 현 실행 렉의 원인으로 확정되지 않습니다. 제한된 로그에 OOM/renderer/GPU crash/event-loop 패턴이 없었지만 문제 부재를 증명하지 않습니다. 실행 파일 PE 버전은 미제공이며 업데이트 확인·설치는 NOT_RUN입니다.

| 가설 | 현재 판정 | 해결되지 않은 구분 검사 |
|---|---|---|
| 열린 포트/오래된 PowerShell 누수 | NOT_PROVEN; 동적 리스 38/38 released | 완료 작업 ↔ 현재 PID+생성시각 ↔ 소비자/lease/heartbeat 부재 연결이 없음 |
| 메모리 부족이 현재 주원인 | 표본에서 지지되지 않음 | 장기 부하 시 변화와 UI 반응시간은 미측정 |
| 백업/동시 개발 I/O가 UI를 지연 | 현재 부하 신호가 가장 강함; 인과관계 미확인 | 백업의 정상 완료 전후 동일 UI 조작 지연 비교 NOT_RUN |
| Codex/MCP 앱 문제 | 과거 오류 신호만 존재 | 현 실행 진단 로그 또는 공식 앱 반응성 측정 필요 |

## 실행기 포커스 RED 및 보류

`scripts/agent_port_lease.py:960`의 `run()`은 성공한 start 이후 verify의 KeyboardInterrupt에서 finally 정리를 호출하지 않습니다. 메모리 내 SyntheticRunner로 acquire/start/verify/close/release를 전부 가짜로 대체했고 실제 자식/포트/리스 생성은 0입니다. 종료 시 close 1회 기대에 대해 실제 0 / release 0으로 **RED 1개, exit 1**을 재현했습니다.

현재 포트 원장은 전부 released이며 이 결함과 현 렉의 연결이 없습니다. 사용자가 앱 문제에 추측 repo 변경을 금지했으므로 **패치와 GREEN은 NOT_RUN**입니다. 이 RED는 앱 렉 재현/해결 증거가 아닙니다.

DevWatch는 `scripts/dev_reload_watch.ps1:219` 단일 인스턴스 mutex와 `:258` finally 해제를 사용하며 누적 증거가 없습니다. `agent_session_watch.py:251`의 mtime-only Devin lock 정리, 전역 Safe-Cleanup, port runner의 강제 taskkill 경로는 실행하지 않았습니다. 특정 debug watcher `_stop.request` 정상 정리 경로도 종료 작업 소유가 확인되지 않아 NOT_RUN입니다.

## 유지한 자원, 접근 제한, NOT_RUN

관측 중 메인 JVM PID **47960**, 시작 **23:32:58.892 UTC**, listening **18180/18181**을 확인했고 본 작업에서는 건드리지 않았습니다. **23:41:47 UTC 최종 재조회에서는 기존 PID와 두 포트 listening이 미관측**입니다. 상태 변화의 원인/행위자는 미확인입니다. 다른 활성 작업과 충돌할 수 있어 복구 재시작도 하지 않았습니다. 다른 모든 listening 포트·Codex·Devin·Java·Node·PowerShell·브라우저·백업 작업에도 종료 명령을 내리지 않았습니다. 관측 간 프로세스 생성/종료는 본 작업의 개입 없이 발생했습니다. 포트 18182도 이 표본에서 미관측입니다. 따라서 활성 자원에 개입하지 않았다는 결과와 메인 서버가 최종에도 살아 있다는 주장은 구분하며, 후자는 NOT_PROVEN입니다.

Win32_OperatingSystem/Processor/Memory/Win32_Process CIM 조회는 ACCESS DENIED여서 그 조회를 중단했습니다. 허용된 Get-Process, Get-Counter, netstat, System boot event와 공식 앱 상태 요약을 사용했습니다. device bus preflight는 `resource-operation-rejected`로 미관측입니다. 권한 승격이나 우회 시도는 없습니다. 접근 거부된 세션 저장소는 접근하지 않았습니다. 세션·인증·비밀 파일·원문 대화·환경 덤프·원문 command line은 수집하지 않았습니다. 민감 명령줄은 전체 미수집 방식으로 제외했습니다.

NOT_RUN: 실제 Codex UI 렉 RED/GREEN, 장기 누수 시험, 유휴 자원 종료/정리, 백업 pause/priority/schedule 변경, 서버 health/재기동, 앱/Devin/브라우저 재시작, reboot, cache/session 삭제, 외부 모델, 설치·새 daemon·schedule, 보안 변경, commit/push. 해당 조치는 현재 원인 연결·작업 소유권·생성시각/소비자 증거가 부족하거나 사용자 금지 범위입니다. 사용자 승인 질문은 보내지 않았습니다.

## 증거와 기록

증거 폴더: `data/agent-handoff/codex-autonomy/codex-pc-lag-c5bc846b/`.

- `baseline.json`: system/부모 PID/시작시각/메모리/handles/listening PID-port 집계; command line 없음.
- `final-snapshot.json`: 탐색 5표본, counter-invalid 경고가 있어 counter 상태를 단독 신뢰하지 않음; 최종 Get-Process 정적 수치 포함.
- `validated-counters.json`: 3표본, 모든 CounterSample Status=0.
- `app-state-summary.json`: 공식 앱의 후속 active 상태만 저장; 원문/요약 문구 제외.
- `port-lease-summary.json`: 38 released, nonterminal 0.
- `lifecycle-red.json`: 가짜 실행기 포커스 RED, 실제 자식 생성 0.
- `cycle-report/`: 보고서만 checkpoint 추적. 초기 JSON 포함 bundle은 source-owner-lease-required로 시작되지 않아 보고서 전용 cycle로 범위를 좁혔습니다. application source는 수정하지 않았습니다.

외부 API: Codex 앱 제공 상태 조회와 Python/Microsoft 공식 문서 조회; 외부 모델 호출 없음.
PLUGIN_USAGE:
- Superpowers: USED(systematic-debugging 원인 조사 절차)
- Codex app tools: USED(list_threads 상태 요약)
- Web: USED(Windows 프로세스 종료 API 공식 문서)
- Devin: NOT_RUN(사용자 외부 모델 호출 금지)
- Browser: NOT_USED
- Computer: NOT_USED
- GLM: NOT_USED

결론: 조사·증거 저장 완료, 앱 지연 해결은 PARTIAL / NOT_PROVEN. 실제 개선을 주장하거나 활성 자원을 정리할 근거가 아직 없습니다.

## 읽기 전용 후속 확인 — 08:44–08:47 KST

후속 작업 `codex-pc-lag-followup-90c0f4b8`. 부모 요청에 따라 기존 서버 상태 변화와 백업 완료 여부만 약 3분 동안 제한적으로 관측했습니다. 세션 전환·중단·복구·제품 수정은 0입니다.

**서버는 새 실행기로 정상 기동한 기록과 연결됐습니다.** 23:44:29 UTC와 23:47:25 UTC에 18180/18181 모두 **JVM 13184**, 시작 **23:43:14.593 UTC**가 소유했습니다. `var/rag-launcher/20261007-084001-2eb187e6/result.json`의 `status=ready`, `listenerStatus=listener-ready`, `springPid=13184`, `springReused=false`, `runtimeRole=dev`, 완료 **23:44:00.248 UTC**와 일치합니다.

같은 실행의 `spring-owned.json`은 listener 13184 / parent 43808, launcher 50032 / parent 49696, `readyAt=23:43:51.502 UTC`를 기록합니다. 23:46:18 UTC에 50032 cmd·43808 java·13184 java의 시작시각과 생존이 재확인됐습니다. 인접 실행 `084045-c6f11a26`, `084103-c50594ab`는 `PREFLIGHT / launcher-already-running`으로 차단됐습니다. **기존 JVM47960의 종료 주체와 새 실행의 작업 소유자/taskId는 UNKNOWN**입니다. owner/taskId가 없는 실행기 기록에 소유권을 추정해서 부여하지 않았습니다. DevWatch `watch.state.json`의 PID31336 / SPRING-running / 갱신23:38:53 UTC는 새 실행과 직접 연결되지 않습니다.

현재 source lease는 `conditional-jev-selection-a109054d`와 `codex-context-final-816496ee` 2건이며 각각 유효 TTL이 있습니다. 둘 다 ownerProcessId=0이라 새 launcher의 소유자로 판정하지 않았습니다. lease를 해제하지 않았습니다. 실행 정책으로 source status CLI가 거부된 레인은 재시도하지 않았고, 허용된 lease JSON의 식별자·TTL·owner PID 필드만 투영했습니다. `debug_rag_stack -Action status`는 진단 파일 작성/retention 삭제 및 logTail 출력이 있어 순수 읽기 전용 조건에 맞지 않아 호출하지 않았습니다.

**백업 완료는 관측되지 않았습니다.** `ABCore` PID51960 / parent5768과 AOMEI Scheduler Service Running이 유지됐고, 23:45:05–09 UTC 및 23:47:21–25 UTC의 짧은 동일 카운터 표본에서도 지속 I/O가 있었습니다. Running인 지속 서비스는 백업 작업의 완료 상태가 아닙니다. 설치/ProgramData 폴더의 후보 로그 메타데이터만 확인했으며 `C:\ProgramData\AomeiBR\brlog.xml`은 마지막 수정15:15:54 UTC로 현 백업 관측 이전입니다. 로그 원문·백업 파일 목록·설정·인증 파일은 읽지 않았습니다. 허용된 조회에 현 작업 완료 상태/이벤트가 없어 완료 후 조건을 만족했다고 판단하지 않았고 무기한 기다리지 않았습니다.

| 동일 카운터 | 이전 유효 표본 23:37:14 UTC | 후속 유효 표본 23:47:25 UTC |
|---|---:|---:|
| 메모리 available | 32.32 GiB | 33.80 GiB |
| commit | 42.43% | 42.91% |
| 전체 CPU | 20.58% | 35.30% |
| 전체 disk | 121.18 MiB/s | 124.34 MiB/s |
| disk queue | 0.983 | 1.054 |
| 평균 write latency | 28.28 ms | 17.91 ms |
| ABCore I/O | 120.85 MiB/s | 119.54 MiB/s |
| pages input/sec | 이전 유효 후속 표본에서 미수집 | 46.73 |

후속 3표본 **39/39 CounterSample Status=0**, ABCore PID51960/parent5768과 Codex PID16656이 일치합니다. Codex 백엔드 자체의 마지막 CPU는 24 논리 CPU 기준 약 **0.162%**이며 이것이 UI 반응성은 아닙니다. CPU는 앞선 후속 3표본에서 39–65%로 변동했습니다. 백업이 끝났다는 전제가 성립하지 않고 아무 개입도 하지 않았으므로 위 변화는 **개선 효과가 아닙니다**. 증거: `data/agent-handoff/codex-autonomy/codex-pc-lag-followup-90c0f4b8/counters.json`.

**사용자 PC Codex UI 반응시간 비교는 NOT_RUN**입니다. 현재 제공된 computer-use 도구 문서에서 native API가 disabled이며, 앱 capture_screen_context는 active voice chat에서만 사용할 수 있습니다. 허용된 Get-Process 조회에서도 ChatGPT/Codex의 MainWindowHandle이 모두 0이므로 Responding=true를 UI 응답 증거로 쓰지 않았습니다. 브라우저나 다른 코딩 세션을 열어 대체 측정하지 않았습니다. 사용자 UI 조작을 수행하지 않았고 전후 UI 지연 수치는 없습니다.

실행기 RED는 **`scripts/agent_port_lease.py::AgentPortLease.run()`의 start 성공 → verify 중 KeyboardInterrupt → 정리 없이 unwind** 경로입니다. 가짜 메서드만 사용한 1개 재현에서 기대 close1/실제0, release0, exit1입니다. 실제 자식을 시작한 뒤 같은 중단이 발생하면 소유 자식/포트 lease가 남을 수 있는 위험이지만, 이번 관측의 실제 orphan은 확인되지 않았고 dynamic port 기록38건은 전부 released였습니다. Codex 렉과의 연관은 여전히 **NOT_PROVEN**, 수정·GREEN은 **NOT_RUN**입니다. 수정 후보 범위는 해당 run 메서드의 성공 보존/소유권 검사/종료 경로를 지키는 예외 정리뿐이며, 현 목표의 원인 연결과 활성 소유권 확인 전에는 패치하지 않습니다.

다음 최소 확인은 사용자가 백업의 정상 완료를 확인할 수 있는 때에 동일한 사용자 UI 조작과 짧은 자원 표본을 비교하는 것입니다. 완료 상태와 native UI 측정 경로가 없는 현재 환경에서는 인과관계 판별이 막혀 있습니다. 신규 정리 스크립트·상주 워치·일정은 만들지 않았습니다.

검증 기록 정정: 후속 `cycle-status` 보존/봉인 사이에 다른 작업의 `codex-context-reuse-93f36624` 상태 행이 변경돼 diff가 own append1 + foreign modify1입니다. 전체 diff가 자신의 1행뿐이라는 검사는 exit1로 실패했으나 호출자가 finish에 exit0을 전달하는 오류가 있었습니다. 기존 receipt는 보존하되 해당 주장에 대한 PASS로 사용할 수 없음을 `codex-pc-lag-followup-90c0f4b8/verification-correction.json`에 명시했습니다. 외부 변경의 작성자는 미확인이고 상태 문서 rollback/덮어쓰기는 하지 않았습니다. 보고서 lint와 자원 증거7검사는 별도로 통과했으며, 앱 개선 증거가 아니라는 구분은 유지합니다.

## 별도 최소 수정 후보의 안전성 판정 — port run cleanup

작업 `codex-port-run-cleanup-safety-8b110fe0`. 사용자 직접 수정·테스트 승인 후 `scripts/agent_port_lease.py::run()`의 중단 정리 누락을 별도 후보로 재검토했습니다. 목표는 현재 렉 해결이 아닌 재현된 수명 정리 결함 방지입니다.

대상 preflight는 **OWNER/CLEAR**, duplicate/liveWriter/overlappingClaim/unclaimedEdit가 모두 비어 있고 현재 source lease도 두 대상과 비중첩입니다. 따라서 파일 충돌이 차단 원인은 아닙니다. 확인 해시:

- `scripts/agent_port_lease.py`: `3c83258c00288e40c65a69aed45612a1845f6d6ccee8a67404f9ffeeaee9b245`
- `scripts/test_agent_port_lease.py`: `d8776f9a7f573cdc238c6cb352044d4ecc085cdfc861a1dc1939e1527c681268`

**정확한 차단점은 Windows에서 직접 생성한 PID 하나만 정상 종료하는 기존 수단이 없다는 것입니다.** 현재 `start()`는 `stdin=DEVNULL`이며 shutdown 명령·HTTP shutdown·협동 종료 callback을 제공하지 않습니다. `close()`의 native 호출만 mock으로 바꾼 임시 fixture에서 강제 종료 `/F`와 트리 종료 `/T`가 실제 선택됨을 재현했습니다. fixture는 생성 PID98765라는 가짜 identity만 사용했고, 실제 자식 생성0/native 명령 실행0입니다. 그러므로 단순 `finally: close()`는 사용자가 보호한 후손/다른 리스에 영향을 줄 수 있는 종료를 추가합니다.

Windows에서 Popen.terminate()/SIGTERM은 TerminateProcess이며 정상 종료 대안이 아닙니다. [Python 공식 문서](https://docs.python.org/3/library/subprocess.html#subprocess.Popen.terminate). CTRL_BREAK_EVENT는 지정된 새 프로세스 그룹 중 같은 콘솔을 공유하는 프로세스들에 전파되므로 직접 만든 한 PID만 대상으로 하는 계약과 다릅니다. [Microsoft 공식 문서](https://learn.microsoft.com/en-us/windows/console/generateconsolectrlevent).

추가로 현재 `start()`는 살아 있는 Popen에도 `returncode=0`을 대입해 handle을 분리합니다. 이를 그대로 보관해 poll() 기반 정리를 추가하면 살아 있는 자식을 이미 종료된 것으로 오판합니다. 따라서 호출 단위의 원본 Popen/PID/최초 생성시각 유지와 child별 협동 종료 계약을 함께 다뤄야 합니다. 지금의 generic runner에 종료 프로토콜을 새로 만들면 승인된 단순 예외 정리를 넘어 start/keep/자식 서비스 계약을 변경합니다.

사용자는 광범위한 lifecycle 변경이 필요하면 패치하지 말라고 명시했습니다. 이 조건에 따라 **production/test 소스 변경0, 새 정리 도구0, source lease 취득0, 실제 프로세스 종료0**으로 이 후보를 BLOCKED 처리합니다. RED는 기존 1개 재현을 유지하며 **GREEN/정리 누락 방지 효과는 NOT_RUN/NOT_PROVEN**입니다. live 자식을 그냥 released로 표시하거나 강제 종료를 정상 종료로 표현하는 패치는 하지 않았습니다. 현재 동적 포트 기록38 released와 이 RED의 현재 렉 인과관계 미입증은 그대로입니다.

범위를 충족하는 다음 전제는 해당 자식이 이미 제공하는 PID/owner 범위의 협동 종료 수단입니다. 현재 코드에는 이것이 없어 동일 PID+생성시각의 소유 증거를 더 수집해도 정상 종료 수단 자체가 생기지 않습니다. 이 차단을 사용자에게 불필요한 승인 질문으로 넘기지 않고 정확히 보고합니다. 원문 세션·인증 저장소 접근이나 활성 자원 변경은 없습니다.

## 사용자 후속 승인에 따른 비파괴 개선 — 09:02–09:12 KST

작업 `codex-port-run-pending-668566e0`. 위 BLOCKED는 정상 종료 수단을 추가하는 이전 후보의 역사적 판정입니다. 사용자가 이어서 **소유권 보존 + cleanup_pending + 중복 실행 차단**을 허용하여, 종료 수단이 필요 없는 최소 수정 두 파일을 실제 적용했습니다. 현재 렉의 원인으로 확정한 것은 아니며, 재현한 실행기 결함의 예방입니다.

**실제 변경:** `scripts/agent_port_lease.py`의 `run()`에서 성공한 start 이후 verify가 예외/중단되면, snapshot과 현재 lease의 owner/session/service/port/PID/생성시각이 일치하고 둘 다 running 또는 unhealthy인 해당 lease만 `cleanup_pending / verify-interrupted`로 기록합니다. 종료·해제 없이 최초 예외를 다시 전달합니다. 예외 원문은 기록하지 않으며 기록/진단 출력의 일반 오류가 최초 예외를 덮지 않습니다. 이미 stopped/released이거나 identity가 변한 lease는 덮지 않습니다.

`cleanup_pending`은 TTL이 지나도 포트를 보유합니다. 같은 owner/session/service의 새 acquire는 기존 allocation lock 안에서 `cleanup-pending`으로 거절되어 run이 재시도/새 start로 이어지지 않습니다. 다른 owner/session/service는 해당 예약 포트를 피해 기존처럼 할당합니다. pending의 stop/close/release는 PID 조회나 종료 호출 **이전**에 거절하고, reap도 건드리지 않습니다. 현재 PID 조회 실패를 죽은 프로세스의 증거로 사용하지 않습니다. 원본 Popen을 보관·adopt하거나 Windows 강제 종료를 새 경로에서 호출하지 않았습니다.

이는 **정리 완료가 아니라 미해결 정리의 보존·반복 생성 억제**입니다. pending에는 자동 해제나 새 종료 프로토콜을 추가하지 않았으므로 실제 발생 시 소유 작업의 명시적 해결이 필요합니다. 살아 있는 자식을 released로 표시하거나 정리됐다고 보고하지 않습니다. 기존 정상 verify-false 뒤 close 실패 결과를 무시하고 retry하는 별도 경로, start 중단, 별도 CLI close의 기존 강제 종료 구현은 이번 범위에서 NOT_FIXED입니다.

**실제 자식/호출 계약 확인:** 실행기는 임의 argv를 받는 generic Popen이며 정상 종료 협동 계약이 없습니다. `agent_isolated_auth_verify.py`는 acquire/close로 예약만 관리하고 자체 Gradle focused test를 실행합니다. `verify_zero_cost_safety.py`는 acquire/release를 이용하고 자체 수명 제한 Python mock-server를 실행합니다. 둘 다 `run()`을 호출하지 않고 외부 생성 PID를 lease에 adopt하지 않습니다. 기존 contract의 acquire → start → health → trace → verify → stop → release 순서는 정상 경로에 유지했습니다.

**RED → GREEN:** 새 `scripts/test_agent_port_lease_run_cleanup.py`는 임시 디렉터리 lease와 가짜 PID를 사용하고 Popen·process_identity·kill_process를 금지 mock으로 막았습니다. 원본 source 해시가 그대로인 RED에서 10 tests / failures14(하위 사례 포함), exit1이었습니다. 첫 GREEN10 이후 독립 리뷰가 발견한 unhealthy 전환·이미 terminal인 snapshot·닫힌 stderr 세 사례도 RED3 / failures2 / errors1을 재현한 뒤 보완했습니다. 최종 신규13 + 기존 owner mismatch/parallel acquire2 = **15 tests, failures0, skipped0, exit0**입니다. 기존 정상 success/keep/verify-false, foreign/변경된 PID와 생성시각, TTL 이후 hold, 중복 거절, pending close/release/reap 거절, 저장·trace 실패 시 최초 예외 보존을 검사했습니다. 서비스용 실제 자식 생성0, 실제 프로세스 종료0, 현재 runtime lease 수정0입니다.

최종 확인은 `verified-final.json`의 실제 unittest TestResult 계수와 source/test pre/post SHA 일치에 묶였습니다. 첫 common_verifier 호출은 unittest의 `Ran N tests` 텍스트를 인식하지 못해 **INCOMPLETE/testCount0**이며 PASS로 쓰지 않았습니다. 다음 호출은 실행된 TestResult의 testsRun/failures/errors/skipped를 JSON으로 출력하여 15개를 검증했습니다. 기존 검증기의 판정명 `VERIFIED_PENDING_APPROVAL`은 저장된 enum이며 추가 사용자 승인 요청의 근거로 쓰지 않았습니다.

- 최종 source SHA256: `69aebf299270fb98bbb5a821665e4b75adb108e60cb9869b0248485317f941db`
- 신규 test SHA256: `3bfbc7ab8f595910909888b25c0ae2d19d00fc7d4d0ea5648c916016e82637d7`
- 기존 test는 그대로: `d8776f9a7f573cdc238c6cb352044d4ecc085cdfc861a1dc1939e1527c681268`
- 증거 폴더: `data/agent-handoff/codex-autonomy/codex-port-run-pending-668566e0/` (`red.log`, `review-red.log`, `review-green.log`, `verified-final.json`, `current-verified/`, `post-counters.json`, `retained-resources.json`).

파일 소유권 preflight OWNER/CLEAR, 대상 중첩 없음. 편집 전 target lease와 checkpoint backup을 취득하고 매 편집 직전 검증했습니다. 중간 `review-repair` 봉인은 자체 리스 turnover 이후 source-lease-drift로 거절돼 PASS가 아닙니다. 원본 backup을 보존하고 새 `current-verified` cycle에서 현재 두 파일을 별도로 봉인/검증했습니다. 모든 자기 source-edit lease를 정상 end 했고 다른 작업 리스는 해제하지 않았습니다.

| 관측값 | 이전 유효 표본 23:37:14 UTC | 수정 후 유효 표본 00:06:20 UTC |
|---|---:|---:|
| 메모리 available | 32.32 GiB | 34.11 GiB |
| commit | 42.43% | 43.11% |
| 전체 CPU | 20.58% | 16.19% |
| 전체 disk | 121.18 MiB/s | 120.11 MiB/s |
| disk queue | 0.983 | 0.980 |
| 평균 write latency | 28.28 ms | 29.99 ms |
| ABCore I/O | 120.85 MiB/s | 119.83 MiB/s |

후속 3표본 **36/36 Status=0**. ABCore PID51960 / parent5768의 지속 I/O는 남아 있고 백업 작업 완료는 UNKNOWN입니다. 메모리 고갈·commit 포화는 관측되지 않았습니다. 위 CPU 변동을 패치의 효과로 귀속하지 않습니다. 수정한 Python CLI는 다음 실행부터 사용되지만 현 백업·Codex·서버 자원에 개입하지 않았고 live interruption을 유도하지 않았습니다.

00:11:35 UTC에 **JVM13184 시작23:43:14.593 UTC, 18180/18181 listening**과 **Codex16656 시작23:10:37.632 UTC**가 유지됐습니다. 당시 handles는 JVM2126/Codex1579, private bytes는 약 JVM1.31 GiB/Codex0.87 GiB입니다. ABCore51960도 유지됐으며 시작시각 접근 불가는 UNKNOWN으로 남겼습니다. 동적 포트 lease는 최신 허용된 요약에서 43건 모두 released / nonterminal0이며, 이전38건 이후 다른 작업이 남긴 증가를 이 패치나 orphan으로 해석하지 않았습니다. 열린 포트·오래된 시작시각만으로 유휴 판정하지 않았습니다.

**최종 판정:** 재부팅·재시작 없는 실행기 예방 수정은 적용/포커스 검증됨. 실제 청소된 프로세스/포트는 **없음**. Codex 앱 렉의 해결 및 이번 실행기 결함과의 인과관계는 **NOT_PROVEN**. 사용자 UI 전후 반응시간, 실제 서비스 자식 중단/종료 시험, Java build/blanket test, 브라우저 조작, cache/session 삭제, 외부 모델 호출/설치/commit/push는 **NOT_RUN**입니다. ABCore I/O는 관측된 자원 경쟁 후보이며 누수·원인으로 확정하지 않았습니다. 접근 거부된 session/인증 저장소 우회는 하지 않았습니다.
