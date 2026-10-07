# Codex PC lag follow-up: failed cleanup retry prevention

작업 `codex-port-close-retry-2c47755c`, 2026-10-07 09:18–09:26 KST. 부모의 후속 승인에 따라 이전 보고서 `CODEX_PC_LAG_20261007.md`에 NOT_FIXED로 남긴 **정상 verify 실패 후 close 실패를 무시하고 새 자식을 만드는 경로**를 실제 수정했습니다. 앱 렉의 인과관계는 여전히 NOT_PROVEN입니다.

## 적용된 변경

`scripts/agent_port_lease.py`의 verify-false 분기에서 close 결과를 검사하는 `_close_for_retry`를 사용합니다. **close.ok가 정확히 True**, 반환 lease와 현재 저장된 동일 lease가 **released**, 양쪽에 **pid 키가 명시적으로 있고 값이 None**, lease ID/owner/session/service/port/생성시각이 close 전 snapshot과 일치할 때만 기존 재시도를 허용합니다. 실패·누락·truthy 문자열·불명확한 성공은 해당 run에서 즉시 실패 반환하여 추가 acquire/start/verify를 막습니다.

close 예외는 같은 원래 예외를 다시 전달합니다. pending 기록·trace·stderr가 실패하거나 두 번째 중단을 내도 원래 close 예외가 유지됩니다. close가 실패를 반환한 뒤 pending 저장이 실패해도 같은 호출은 재시도하지 않습니다. reason은 알려진 코드만 보존하고 잘못된 형식은 고정 cleanup-failed로 기록하므로 예외 원문·명령줄이 진단에 들어가지 않습니다.

`_record_cleanup_pending`은 close 전 snapshot이 running/unhealthy인지와 현재 immutable identity를 allocation lock 안에서 확인합니다. 아직 active면 원래 PID 일치가 필요합니다. close가 stopped/released까지 진행한 뒤 실패했다면 동일 identity와 pid=None을 확인하고 포트 예약을 pending으로 보존합니다. **이전 PID·killAllowed를 복원하지 않습니다.** 원래 PID/생성시각은 `cleanupSnapshot`이라는 별도 근거 메타데이터이며 종료 대상으로 사용하지 않습니다. 기존 verify-interruption의 terminal snapshot 거부는 유지됩니다. 외부 owner/PID/생성시각 변경은 덮지 않습니다.

이전 pending의 TTL 독립 hold, 동일 owner/session/service 재실행 차단, stop/close/release/reap 선행 거부도 유지됩니다. 이는 자원 정리 완료가 아니라 **미확정 정리의 보존과 중복 생성 방지**입니다. 저장 자체가 실패하면 현재 호출의 추가 spawn은 막지만 이후 별도 호출까지 durable pending이 유지된다고 보장할 수 없습니다. 원래 파일/자식은 남기고 reason을 보고합니다.

수정은 위 두 내부 경계와 `scripts/test_agent_port_lease_run_cleanup.py`뿐입니다. AST 원문 비교로 `kill_process`, `_stop_locked`, `close`, `start`, `holds_port`, `acquire`, `_release_owned` 본문이 후속 작업 전과 같음을 확인했습니다. **기존 /T /F 로직을 확대하거나 실제 자원을 종료하지 않았습니다.** 정상 성공/keep과 일관된 close 성공 후 정상 재시도는 유지했습니다. start 실패/중단 및 정상 verify 성공 후 별도 close의 정리 정책은 이번 범위에 넣지 않았습니다.

## RED → GREEN과 독립 검토

원본 hash가 앞선 15PASS 버전과 일치함을 확인하고 후보 테스트를 작업 폴더에 staging하여 소스 변경 전 RED를 실행했습니다. 기존 신규13 + 새8 = **21 tests / failures13 / errors1 / exit1**, 실제 서비스 자식 생성0·실제 종료0이었습니다. 그 후 코드와 테스트를 같은 target-scoped lease/checkpoint cycle에서 적용했습니다.

첫 GREEN은 기존 15개를 포함한 23개였습니다. 독립 리뷰와 root의 확인에서 반환/현재 pid 키 누락과 list/dict reason 두 경계를 발견하여 추가 RED2(failures2/errors1)를 고정했습니다. pid 키 존재와 reason 문자열 검사를 보완한 최종 **25 tests / failures0 / skipped0 / exit0**이며, 실제 unittest TestResult 계수와 current source/test pre/post hash를 common_verifier에 묶었습니다. 독립 재리뷰에서도 PID 누락2·잘못된 reason2의 재시도 차단과 원래 예외 보존을 메모리 내에서 확인했으며 추가 차단 사항은 없습니다.

검사 범위: close 성공 후 정상 재시도, false/예외/불명확한 결과, 부분 stopped/released 후 false 또는 예외, pending 저장 실패 후 같은 호출 추가 spawn0, trace/stderr/두 번째 중단 시 원래 close 예외 유지, foreign owner/PID/생성시각 미덮어쓰기, 기존 verify 중단·success·keep·동일 identity 차단·다른 identity 할당·TTL hold·reap/stop/release 안전성입니다. 종료 함수와 실제 Popen은 금지 mock으로 막았습니다. 넓은 서버 종료/서비스 재시작 시험은 실행하지 않았습니다.

- source SHA256: `2ef9bb0a576beb78a5123588d9d7603d1fbb051d7057c1d3bfbe383bd5196df8`
- focus test SHA256: `6a026cedc3a6d76f22dd129c65bd184e4f2e1a2f61c7dc14fc4d6e5c27e59a06`
- 기존 test SHA256(변경 없음): `d8776f9a7f573cdc238c6cb352044d4ecc085cdfc861a1dc1939e1527c681268`
- 증거: `data/agent-handoff/codex-autonomy/codex-port-close-retry-2c47755c/`의 `red-result.json`, `red.log`, `review-red-result.json`, `review-red.log`, `final-green.json`, `final-green.log`, `implementation/`, `review-repair/`, `final-evidence.json`, `retained-resources.json`.

파일 소유권 preflight는 OWNER/CLEAR이며 두 대상의 활성 lease/claim 중첩은 없었습니다. preflight가 옛 광범위 plannedScope로 unclaimed edit를 추정한 두 파일은 앞선 본 작업의 검증 hash와 정확히 일치하여 자체 변경임을 기존 receipt로 확인했습니다. 매 cycle 전 현재 hash와 checkpoint backup을 보존하고 편집 직전에 target lease를 verify했습니다. GREEN의 실제 exit0만 finish에 전달했고 자기 source-edit lease는 모두 end했습니다. 다른 작업의 `session413-final-report` 리스와 파일은 종료·해제하지 않았습니다.

## 유지한 실제 자원과 한계

00:24:40 UTC에 JVM13184는 시작23:43:14.593 UTC 그대로이며 18180/18181 listening을 유지했습니다. Codex16656도 시작23:10:37.632 UTC 그대로, ABCore51960도 유지됐습니다. 이번 작업에서 실제 프로세스 종료0, 실제 서비스 자식 생성0, 현재 runtime port lease 변경0, 정리된 자원0입니다. 열린 포트나 오래된 PID를 유휴로 판정하지 않았습니다.

이전 유효 자원 표본의 CPU20.58→16.19%, available32.32→34.11 GiB, ABCore I/O 약120 MiB/s는 앞선 보고서의 관측이며 이번 패치 효과 수치가 아닙니다. 이번 후속 단계는 코드·mock 행동 전후를 비교했습니다: 미확정 close 후 같은 run의 start3회 → start1회, coherent close 성공 시 start2회 정상 retry, 저장 실패 시에도 추가 start0회. 실제 UI 지연의 전후 값은 없습니다.

**최종:** 이전 NOT_FIXED의 verify-false/close-failure 재시도 경로는 예방 수정 적용/포커스 검증됨. 실제 기존 orphan 청소와 Codex 앱 렉 해결은 주장하지 않습니다. 재부팅·Codex/Devin/서버/브라우저 재시작·실제 서비스 자식 중단 시험·Java build/blanket test·캐시/세션 삭제·보안 변경·외부 모델 호출·설치·commit/push는 NOT_RUN입니다. UI 반응시간은 허용된 native 측정 경로가 없어 NOT_RUN, 앱 렉 인과관계는 NOT_PROVEN입니다. 비밀/인증/접근 거부 session 저장소를 읽거나 우회하지 않았습니다.

외부 API: 이번 후속 단계는 로컬 검사만 사용; 외부 모델 호출 없음.
PLUGIN_USAGE:
- superpowers: USED(systematic-debugging and focused verification of the local runner)
- codex-app-tools: NOT_USED — 이번 후속은 로컬 실행기 경계 확인으로 충분.
- web: NOT_USED — 기존 공식 Windows/Python 종료 자료를 이전 보고서에 유지; 이번은 로컬 계약 수정.
- devin: NOT_RUN(user prohibited external model calls)
- browser: NOT_USED — 활성 브라우저/세션 조작 없음.
- computer-use: NOT_USED — 현재 native 측정 경로 unavailable.
- glm: NOT_USED — 외부 모델 호출 없음.
