# 필요한 도구와 역할만 선택

## 기본 실행자

부모 Codex가 브라우저 탭 하나, 시나리오 ledger, 소스 변경·집중 테스트·최종 판정을 소유한다. 독립 에이전트는 별도 읽기 질문으로만 병렬화한다. 같은 탭·세션·서버를 여러 에이전트가 동시에 조작하지 않는다. 소규모 UI 결함은 단독 처리한다.

현재 세션의 도구 목록/스킬 문서를 확인한 뒤 `catalogued`, `connected`, `invocation-ready`, `executed`를 구별한다. tag와 도구 이름 존재는 실행 준비나 모델 응답 증명이 아니다. 연결된 선택 수단이 충분하면 새 플러그인을 설치하지 않는다.

| 필요한 증거 | 선택할 기존 기능 | 판단 한계 |
|---|---|---|
| 실제 클릭·입력·화면 | 현재 Browser/Computer의 `cua_repl`/`cua` API; 해당 세션의 문서부터 읽기 | 첫 진입/선택과 이후 상태 조회 규칙 준수. 새 AX/DOM 요소로 조작. 현재 native 앱 기능이 없으면 브라우저로 가능한 범위만 수행 |
| 요청·응답·console | 그 브라우저 도구가 실제 제공하는 network/console 또는 허용된 UI 관측 | 제공되지 않은 Playwright/evaluate/CDP API를 가정하지 않음. 현재 UI 제어 도구 경계를 다른 기술로 우회하지 않음 |
| 실행/테스트·로그 | 기존 PowerShell/Node/Gradle·Display launcher, AWX Control Tower `run_pipeline`, `build_error_mine`, `trace_snapshot_probe` | launcher/check/probe 성공은 브라우저나 추론 성공이 아님. snapshot 메타데이터는 해당 request의 의미/attempt를 증명하지 않음 |
| 실패 원인 탐색 | [invisible-eye](../../demo1-invisible-eye/SKILL.md) / 기존 debugging 경로 | 같은 증거·source gate 재사용. 중복 진단기나 두 번째 adjudicator를 추가하지 않음 |
| 복구 | 기존 source checkpoint / task-owned runtime 실행기 | AWX 복구 alias를 별도 복구 소유자로 취급하지 않음. 다른 작업 PID/lock/lease는 보존 |

`cua_repl`을 사용 중이면 그 세션의 UI API 제한이 우선한다. CLI Playwright는 별도로 허용된 환경/요청이고 기존 설치가 확인된 경우에만 해당 스킬을 따른다. UI를 누른 것과 내부 함수를 실행한 것을 구분한다.

## 독립 모델 선택

먼저 `$token-efficient-agents`를 따른다. 아래는 이 저장소에서 **맡길 수 있는 역할**이며 모델 우열에 대한 성능 측정이 아니다. 질문이 결정에 영향을 줄 때만 한 경로를 선택한다. 결함 하나의 동일 리뷰를 여러 서비스에 보내지 않는다.

| 경로 | 적합한 독립 질문 | 호출 조건 |
|---|---|---|
| Devin local SWE-2 | 공개/합성 최소 재현의 반례·retry/idempotency 분석 | `$devin-research-delegation`의 기존 helper, live catalog의 정확한 Free `swe-2`, 준비 증거. 해당 경로 우선; 1회/90초, 유료·Cloud fallback 없음 |
| Gemini | 공개/합성 UI 상태 전이 또는 구현 선택의 대안 | `$gemini-subscription-review`, 실제 `gemini_status` ready, 기존 reviewer 미선택. 1회/90초; auth/quota/cost 실패 후 반복 없음 |
| Grok | 기존 증거의 반례를 찾는 제한된 질문 | `$demo1-grok-subscription-review`, AWX `grok_review_change(mode=status)`와 fresh account/billing acceptance. 미확인 시 generation 없음; 다른 API/credit 우회 없음 |
| GPT/ChatGPT 구독 리뷰 | 현재 증거로 판단 가능한 좁은 계약/설계 검토 | AWX `codex_review_change`의 실제 status와 지원 profile 사용. “GPT Pro”는 고정 모델 ID가 아님. 구독명만으로 모델/무료/잔여량을 추정하지 않음 |
| GLM / 기본 explorer | 여러 파일의 경로 추적·로그 요약 | `$glm-offload`의 process-only presence 및 delivery-marker/HOLD 규칙. CLI 0.144.1 + sol/v2 transport HOLD는 지침대로 유지. 불가 시 기본 explorer 경로; 설정 복구나 재탐색 호출 반복 없음 |

기존 source preflight가 필요한 패치는 그 단일 gate가 심사를 소유한다. 허용된 진단 입력은 snapshot을 고정하기 전에 Codex가 검증·요약하며, tool-free POSITIVE/NEGATIVE/NEUTRAL 내부에 provider를 호출하지 않는다. 외부 의견은 실제 브라우저 결과를 덮을 수 없다.

질문 packet은 한 가설/반례, 공개·합성 근거, 실패 조건, 출력 제한만 포함한다. 독립 읽기 역할은 브라우저 재현과 병렬 진행할 수 있지만 먼저 받은 답을 다른 모델에게 보여주며 동의를 유도하지 않는다. 의견이 다르면 서로 다른 예측을 실제 재현/집중 테스트로 판별한다. 투표나 응답의 문체로 결정하지 않는다.

선택 ledger: `role, reason, modelObserved|null, status, attempts, durationMs, usageObserved|null, decisionChangingFinding, parentVerification, adopted|rejected|unresolved`. 원문 입력/답변은 기록하지 않는다. 준비 확인만 했으면 `attempts=0`; 응답/usage 미관측은 null이며 0이나 무료로 바꾸지 않는다.

## 다른 태그의 사용 경계

Superpowers는 재현·가설·검증 절차에 사용하고 저장소의 기존 승인/owner 규칙을 따른다. Plugin Management는 필요한 기능이 없을 때 검색/연결 상태 확인에 사용한다. Deep Research/SciSpace는 현재 결정을 바꿀 공식 규격·연구 문제가 있을 때만, Wolfram은 별도 계산 문제가 있을 때만 선택한다. 평범한 버튼/timeout 결함에는 호출하지 않는다.

Data/Visualize는 충분한 실측 사례의 실패 구간·횟수·지연을 비교할 필요가 있을 때 사용한다. 몇 사례면 표로 충분하며 미관측을 0으로 채운 그래프를 만들지 않는다. Sites는 사용자가 명시한 공개 산출물 작업에만 사용한다. 이 로컬 웹앱 QA를 위해 Spring/추론 endpoint·로그·대화를 자동 공개하지 않는다.
