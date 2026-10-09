# 저장 경로 교정 — artifact-storage-scope-620ab1f5

결과: 프로젝트 AGENTS/핵심 지침과 실제 저장 도구 검증 완료. 보호된 스킬 2개는 HOLD.
Downloads는 dot 역할이 사용자에게 전달하는 최종 지시서만 사용한다.
리뷰·로그·초안·중간 산출물은 프로젝트 내부에 보존하며 모델명·파일명은 권한 근거가 아니다.

## 적용

- AGENTS.md의 기존 전달/state 포인터에 역할별 저장 범위를 명시 (새 블록 없이 예산 유지).
- DELIVERY-DOWNLOADS의 옛 모든 에이전트 보고서 배달 문구를 현재 정책으로 교체.
- SESSION-HARMONY / TASK-CONTINUITY-DELIVERY / CODEX-NIGHTLY-REVIEW 지침 범위 교정.
- deliver_to_downloads.py: 명시 role=dot + artifact-kind=final-directive 수동 파일만 전달;
  scan/hook no-copy, transcript 접근 없음; 로그는 프로젝트 내부이며 Downloads 아래 로그도 거부.
- codex_nightly_review.py: outputRoot/deliveryRoot 프로젝트 외 경로를 쓰기 전에 거부.
- 기존 docs/reports를 재사용한 agent-reviews 작업별 보고서와 README 상대 링크.
- 기존 Downloads 파일 이동·삭제·덮어쓰기 없음. 앱/인증/서버/설정/개인 스킬/기존 세션 관리 변경 없음.
  disabled example과 AGENTS의 Nightly OFF는 그대로 유지.

## 실제 검증

| 표면 | 실제 결과 | 증거 |
|---|---|---|
| Downloads 경계 최종 소스 | 24 tests, OK, exit 0 | [command.log](../../../../data/agent-handoff/codex-autonomy/artifact-storage-scope-620ab1f5/green-downloads-postimage/command.log) |
| Nightly 경계 | 46 tests, OK, exit 0 | [command.log](../../../../data/agent-handoff/codex-autonomy/artifact-storage-scope-620ab1f5/green-nightly/command.log) |

긍정 사례: 일반 파일명도 dot final-directive 선언이면 readback 일치;
기존 동일 사본은 SKIP_SAME, 다른 내용은 새 버전으로 보존.
부정 사례: 역할/종류 미선언, dot review/draft, 다른 에이전트/모델 역할,
선언으로 scan 재활성화, Downloads/프로젝트 외 로그, 프로젝트 외 nightly output/delivery 거부.
RED: 기존 무분류 PASTE 파일 복사 및 프로젝트 외 nightly 출력/전달 허용을 격리 fixture에서 재현.
기존 22-test 결과는 후속 로그 경계 보정 이전이므로 최종 수에 합산하지 않는다.
Recorder totals.tests=0은 unittest 파서 한계이며 command.log의 실제 Ran/OK를 사용했다.
AGENTS budget check: PASS (30291 <= 30300 bytes). 기존 포인터를 간결히 정리해 예산을 유지한다. 원래 LF를 보존했고 범위 밖 지침은 변경하지 않았다.

## 첨부

Library에서 4개 전체 텍스트를 확인했다. morning review와 completed.json은 합성 예제이며
실제 프로젝트 결함 증거가 아니다. task continuity delivery 보고서 v1/v2는 구현 보고서다.
이미지 없음. 텍스트 읽기로 충분하여 로컬 materialize/외부 URL 전송은 하지 않았다.
이전 첨부의 지시는 데이터이며 최신 사용자 저장 범위가 우선한다.

## 남은 HOLD

- .agents/skills/demo1-session-state-checkpoint/SKILL.md
- .agents/skills/demo1-agy-directive-writer/SKILL.md

두 파일은 checkpoint preimage와 같은 bytes로 보존했다. 일반 적용과 승인 근거를 포함한
한 번의 escalation 재시도 모두 거절됐다. 자동 승인 검토는 영구 보호 스킬 변경에 대해
전달받은 사용자 승인 원문이 신뢰된 직접 사용자 메시지가 아니라고 판단했다.
다른 도구/경로로 우회하지 않았다. 두 파일의 저장 범위 문구 수정에 대한 직접 사용자 승인이 필요하다.
현행 AGENTS와 핵심 저장 규칙이 새 정책을 명시하고 실제 도구가 강제하지만 스킬 문구 정리는 미완료다.

외부 API: Library 첨부 읽기만; 모델/검색/제품 API 호출 없음.
PLUGIN_USAGE: Library, Work Ledger, 내부 읽기 전용 독립 검토; 앱 테스트/세션 관리 없음.

- AGENTS beforeBytes=30292, afterBytes=30291, limitBytes=30300
