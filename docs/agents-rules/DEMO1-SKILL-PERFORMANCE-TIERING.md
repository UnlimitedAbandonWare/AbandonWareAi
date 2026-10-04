<!-- BEGIN DEMO1-SKILL-PERFORMANCE-TIERING -->
## Skill performance tiering (adaptive 3-tier allocation)

질의 난이도·위험도에 따라 스킬을 3티어로 자동 배분한다. 고비용·고성능 스킬은
시그널이 켜질 때만 승격 발동하고, 경량 작업은 기본 경로를 유지한다.
구현: `scripts/demo1_vibe_skill_router.py` + `.agents/skills-intent-index.yaml`
`auto_promote` 블록. 분류기 연동: `scripts/codex_question_classifier.py`.

### Tier 0 — Light / Fast Lane (예산 ~15k 토큰)
- 대상: 단일 파일, 오타·주석·가역적 로컬 작업, 단순 라우팅.
- 스킬: `demo1-codex-auto-decide` + `safe-source-edit` + `demo1-output-budget`.
- 출력 계약: tier 필드 없음(기존 resolve 결과와 동일). Triad 스폰 금지.
- 문답: 1/2/3 선택 퀴즈·서브에이전트 스폰 여부는 인라인 AUTO(D36).

### Tier 1 — Performance / Tactical Lane (예산 30k~60k 토큰)
- 대상: 복합 로직, 서브시스템 패치, 첫 테스트 실패.
- 승격: primary 유지 + `demo1-evidence-debugging` 자동 페어링.
  시그널: 복합 로직/디버깅, 첫(번째) 시도·테스트·빌드 실패, first test fail.
- 자동 활성화 후보: `demo1-subsystem-patch-directive`, `awx-uaw-web-research`.

### Tier 2 — Deep Reasoning / Strategic Lane (예산 80k~120k 토큰)
- 대상: 아키텍처 변경, 복합 서브시스템 충돌, 동일 실패 반복(회수 명시),
  보안·시크릿 경계, 정교/완벽/근본적 작업 요청.
- 승격: primary `demo1-triad-deliberation`(3축 심의) +
  optional `demo1-triangulating-counter-evidence`, `triad`·`counter-evidence`
  패밀리 자동 해금. 시그널: multi_subsystem(서로 다른 서브시스템 이름 2개+
  — extremez/overdrive/cfvm/moe), 복합 서브시스템, 서브시스템 충돌,
  N회 이상 실패·재시도(회수 토큰 필수 — bare '반복'은 승격하지 않음),
  실패/재시도 루프, failure loop, 아키텍처·구조 재설계, 근본적으로,
  정교히/완벽히.

### 불변 가드 (모든 tier에 적용)
- `demo1-output-budget`: 도구/스크립트 콘솔 출력 ≤30줄/1KB 요약, 상세는
  `var/diagnostics/` 파일로 저장 후 경로만 참조. 고성능 승격 중에도 불변 —
  승격 결과의 `guards` 필드로 에코된다.
- `demo1-session-state-checkpoint`: 누적 세션 토큰 ≥140,000이면 컴팩션 전
  ≤20줄 state.md 권고. 분류기 `--session-tokens N` 플래그 또는 D35
  (세션 토큰/컴팩션 문맥 + 체크포인트 행위)가 같은 임계를 쓴다 —
  상수 `SESSION_TOKEN_CHECKPOINT_THRESHOLD = 140_000`.

### 자동 승격 vs 사용자 명시
- 자동 승격은 시그널 기반이다: 위 시그널이 없으면 `default_forbid_families`
  (counter-evidence, macsrc-patchdrop, triad)는 그대로 잠긴다.
- 사용자가 "삼자 심의/교차 검증/반증"처럼 패밀리를 명시하면 기존
  `unlock` 패턴이 그대로 우선한다 — 두 경로 모두 `unlocked_families`에 합산.
- 승격 결과는 `tier`, `tierSignals`, `autoPromotedFrom`(원래 라우팅),
  `guards`를 포함해 감사 가능하다.

### 개량화(Tuning) 포인트
- 시그널·스킬 매핑·해금: `.agents/skills-intent-index.yaml` `auto_promote`.
- 체크포인트 임계: `codex_question_classifier.py`
  `SESSION_TOKEN_CHECKPOINT_THRESHOLD`.
- 계측: `scripts/codex_skill_performance_monitor.py` — 세션 티어, 토큰
  총량, 출력 잘림, 고성능 호출 수, 패치 마찰 지표 (`--brief` 한 줄 / JSON).
<!-- END DEMO1-SKILL-PERFORMANCE-TIERING -->
