# Devin 구현 지시서 — Codex 주간 세션 분석 기반 에이전트 스킬·기능·도구 (2026-09-17~24)

- 작성일: 2026-09-24
- 분석 대상: `%USERPROFILE%\.codex\sessions\2026\09\17` ~ `2026\09\24`, 55개 세션 JSONL, 약 358MB (전수 파싱)
- 분석 산출물: `%TEMP%\codex-week\` (`extract.py`, `stage2.py`, `sessions_summary.json`, `report.txt`, `report2.txt`)
- 목표: 최근 1주일의 실사용 패턴에서 반복 확인된 수요를 바탕으로 아래 스킬/기능/도구를 구현·보강한다.
- 작업 루트: `<repo>` — 기존 AGENTS.md 규칙, 스킬 SSOT, 소유권·lease·preimage·검증 게이트 전부 준수.

## 1. 근거 데이터 (집계 완료, 검증됨)

### 1-1. 도구 사용 빈도 (55 세션 합계)
| 도구 | 호출 수 | 해석 |
|---|---|---|
| `wait` | 181 | 서브에이전트 완료 대기 |
| `js` | 157 | 오케스트레이션 상태 점검·집계 |
| `followup_task` | 134 | 워커 후속 지시 |
| `send_message` | 130 | 워커↔오케스트레이터 메시지 |
| `request_user_input_async` | 35 | 사용자 질문 카드 |
| `spawn_agent` | 31 | 서브에이전트 생성 |
| `sleep` 13, `list_agents` 6, `interrupt_agent` 2, `wait_agent` 1 | | |

→ 호출 대부분이 **멀티에이전트 오케스트레이션 프리미티브**이며 09-23~09-24에 집중됨.

### 1-2. 스킬 언급 빈도 (상위)
`scoped-blocker-recovery` 11, `demo1-work-ledger` 7, `demo1-devin-source-orchestrator` 6, `demo1-completed-directive-cleanup` 6, `demo1-desktop-canonical-goal-intake` 5, `demo1-invisible-eye` 4, `demo1-goal-asset-preservation` 4, `demo1-meta-display-simple-caption` 4, `demo1-hard-constraints` 4, `demo1-conditional-local-git` 4, `demo1-meta-display-db-export` 3.

### 1-3. 일별 마찰 (blocked / errors)
| 날짜 | 세션 | blocked | errors | 특징 |
|---|---|---|---|---|
| 09-17 | 12 | 0 | 3 | Meta Display 초기화면·자막, goal 파일 기반 자율 연속 작업 |
| 09-18 | 11 | 0 | 0 | mp4 영상 증거 기반 구현·검증, "수정 내용 10초 안에 확인" 요구, 지시파일 우선순위 점검 |
| 09-19 | 2 | 0 | 0 | Exa 리서치 + 서브에이전트 워커(glm_worker) 도입 |
| 09-22 | 1 | 0 | 0 | — |
| 09-23 | 17 | 3 | 18 | 4-agent 병렬 운영 + 대규모 오케스트레이션 |
| 09-24 | 12 | 3 | 18 | 스킬 정리·압축·파이프라인 자동화 목표 집중 |

→ blocked·error 36건이 **오케스트레이션 집중일(23~24일)에 전량** 몰림 = 오케스트레이션 신뢰성이 최대 마찰 지점.

### 1-4. 최근 goal-objective.md 16건 트렌드 (09-13~09-24 누적, 전수 확인)
- 완료·성공 사례: `0918a0` vibe 스킬 라우터 인덱스 확장(11→27 스킬, 한국어 라우팅), `0916a0` agentic-chat-postprocess.
- 진행·미완(재개 대상): `0915a0` awx_skill_router.py CLI(중단), `0913g0` devin_task_orchestrate 자동화, `0913h0` codex_context_status.py 예산 추적, `0913k0`/`0916c0` patchdrop 강건화·복구 절차.
- 최신(09-23~24): `0923a0` 4-agent 병렬 라이브 운영 + AGENTS.md 개조, `0924a0` 스킬 파일 정리, `0924b0` agent-work-pipeline 계획표, `0924c0` mcp-control-tower, `0924d0` subsystem-patch-directive(시스템 메시지 30~40% 압축).

## 2. 구현 지시 항목 (우선순위 순)

### P1. 멀티에이전트 오케스트레이션 안정화 팩
- 근거: wait/js/followup_task/send_message 합계 582회, blocked 6건·errors 36건이 09-23~24에 집중.
- 구현:
  1. 워커 세션 레지스트리(세션 id·역할·상태·마지막 하트비트) 단일 JSON + 조회 CLI.
  2. blocked/error 자동 분류기: 원인 코드(lease 충돌 / 무응답 / 컨텍스트 초과 / 도구 실패) 필수 기록.
  3. `glm_worker`(0919b0) 류 워커의 표준 템플릿화: 등록·지시·보고·종료 공통 계약.
  4. 실패 워커 재시도 정책: 최대 1회, 동일 원인 반복 시 중단·보고.
- 수용 기준: 09-23~24 유사 부하 재현 시 blocked 원인 코드 100% 기록, 무응답 워커 자동 감지·보고, 기존 spawn/wait 흐름과 무충돌.

### P2. scoped-blocker-recovery + work-ledger 자동 복구 UX
- 근거: 최다 언급 스킬(11회) + work-ledger 7회 — 매 세션 동일 수동 절차 반복.
- 구현: lease 상태 요약 CLI(begin 가능 여부·충돌 소유자·quarantine 후보 한 번에 표시), journal `in_progress` 자동 목록화·정리 제안, checkpoint begin 실패 시 다음 행동 자동 안내.
- 수용 기준: 복구 절차 명령 수 감소(기존 5+ → 2 이하), 외국 lease 강제 해제 금지 규칙 유지.

### P3. PatchDrop 오케스트레이터 강건화 (0913k0·0916c0 계승)
- 근거: 잘못된 폴더 재귀·패키지 무결성 문제가 반복 목표로 재지정됨.
- 구현: 대상 경로 화이트리스트 외 재귀 차단, 번들 무결성(파트 완전성·slug당 v3 단일성) 사전 검증, 임계 초과 시 자동 중단, `patch-drop-pending` 모호 큐 처리 절차.
- 수용 기준: `janitor_inventory.ps1` 선행 검증 없이 apply 불가, 손상 번들 자동 reject 이동, Gradle 검증 후 applied/rejected 이동 계약 유지.

### P4. 스킬 라우터 인덱스 lint + 한국어 라우팅 회귀 테스트 (0918a0 성공 계승, 0915a0 재개)
- 근거: `skills-intent-index.yaml`이 SSOT로 자리잡음; 라우팅 실패(@skill 5개 이상 나열) 방지 필요.
- 구현: 인덱스 스키마·중복·깨진 경로 lint CLI, 한국어 지시문 라우팅 회귀 테스트 세트, 중단된 `awx_skill_router.py`(0915a0)는 키워드 라우팅부터 재개.
- 수용 기준: lint 오류 0, 회귀 테스트 통과, 신규 스킬 추가 시 인덱스 누락 자동 감지.

### P5. 컨텍스트 예산·시스템 프롬프트 압축 (0913h0·0924d0·0924c0 계승)
- 구현: `codex_context_status.py --json --detailed` + 예산 임계 자동 중단, 시스템 프롬프트 → 스킬 이동으로 시스템 메시지 30~40% 압축, MCP 도구 최소 활성화 + 사용 로그(mcp-control-tower).
- 수용 기준: 압축 전후 토큰 수 측정 리포트 첨부, 기존 라우팅/가드 동작 회귀 없음.

### P6. agentic-chat-postprocess (0916a0 계승)
- 구현: 커밋 메시지·보고서에서 지시문/원문 대화 잔류 자동 제거, 한국어 텍스트 정규화, 보고서 템플릿 적용.
- 수용 기준: 더티 커밋 메시지 재현 케이스 정제 통과, 비밀값 패턴 탐지 시 차단.

### P7. 빠른 라이브 검증 루프 (09-18 "수정 내용 10초 안에 확인" 수요 대응)
- 구현: 저장→리로드→확인 단축 검증 스크립트(데모 페이지/디스플레이 경로), mp4 영상 증거 → 요구사항 체크리스트 추출 템플릿.
- 수용 기준: 편집 후 사용자 확인까지 10초 내 피드백 루프 1회 시연.

### P8. devin_task_orchestrate / agent-work-pipeline 자동 계획표 (0913g0·0924b0 계승)
- 구현: 세션 분류 자동화, 턴 예산 계산, 계획표 자동 생성·갱신.
- 수용 기준: 브리프 파일 입력 시 단계별 계획표 JSON + 사람 검토용 요약 출력.

### P9. 스킬 탑재 다이어트 + 발동 측정 (신규, 2026-09-24 실측 근거)
- 근거(55세션 전수 재집계, 지시문 상시탑재 라인과 실사용 라인 분리):
  - `.agents/skills` 총 **119개**. 언급의 68~80%가 **지시문 상시 탑재에서만 발생**하는 스킬 15종: gpu-power-fallback 80%, grok-subscription-review 78%, rtx3090-health-watch 77%, triad-deliberation 76%, patchdrop-safe-patch-orchestrator 76%, devin-directive-loop 75%, goal-asset-preservation 74%, macsrc-smb-direct-patch 73%, vibe-skill-router 72% 등 — 매 세션 컨텍스트를 소비하지만 실제 발동은 드묾.
  - 실사용 상위는 `completed-directive-cleanup`(실사용 2272), `meta-display-webapp`(905), `source-edit-three-way-preflight`(781), `meta-display-verification`(772), `evidence-debugging`(764), `mcp-control-tower`(719), `invisible-eye`(636), `goal-complete-stop`(610), `work-ledger`(593) — **절차·가드 스킬이 실사용의 중심**이고, 제품 도메인은 meta-display-* 계열이 유일하게 큼.
- 구현:
  1. 스킬 **발동 로깅**(세션 id·스킬명·trigger intent·소모 턴) — 언급 집계와 실제 호출을 구분하는 것이 첫 단계.
  2. 상시 탑재 목록과 라우터 온디맨드 목록 분리: boilerplate-dominant 15종부터 온디맨드 전환 검토. 단, AGENTS.md 규칙상 "저사용만으로 병합/삭제 금지" 원칙 준수 — **삭제가 아니라 로딩 경로 전환**만.
  3. 절차 스킬(cleanup/ledger/intake/stop)의 자동 실행 비율 상향 — 세션당 수동 발동 횟수 감소가 목표.
- 수용 기준: 세션 시스템 프롬프트 내 스킬 관련 토큰 30% 이상 감소(0924d0 압축 목표와 합산 측정), 발동 로그 2주 수집 후 상시탑재 재조정 근거 확보, 라우팅 회귀 테스트(P4) 통과.

## 3. 공통 제약 (전 항목 하드 규칙)
- 비밀값·토큰·쿠키 출력/커밋 금지, `.secrets/` 접근 금지.
- 기존 가드 우회 금지: lease·preimage·checkpoint·AGENTS.md BEGIN/END 규칙 준수.
- 무료 모델 유지, 유료 API는 `AWX_AGENT_ALLOW_PAID_MODELS=1` 명시 시에만.
- 외국 lease/저널/스테이징 변경 금지 — 충돌 시 해당 파일만 스킵·보고.
- 각 항목은 독립 커밋 가능 단위로 분리하고 검증 증거(명령 출력/테스트 결과)를 첨부.

## 4. 산출물
1. P항목별 구현 코드 + 테스트
2. 검증 증거 리포트
3. `docs/PROJECT_STATUS.md` 갱신 행
4. 변경된 스킬은 `.agents/skills-intent-index.yaml` 인덱스 갱신

