# 02_SETUP_ORDER — UAW 조화 셋업 레시피 (WP2, 아직 기능 구현 아님)

- Contract: `DEMO1-DEVIN-UAW-HARMONY-FOUNDATION-20260928` · taskId `uaw-harmony-foundation-0928-c50981a8`
- Date: 2026-09-28 · Abandon_X §5(안전 해소 순서)를 **셋업 관점**으로 재해석.
  각 단계는 Observation → Setup Commands → Verification. `Patch Blocks`는 비워 두거나 `OWNER=Codex`.
- 원칙: 기능 로직 추가 금지. 순서는 "행동해도 기존 기능을 깨지 않는" 기준. 1 단계 = 1 근본 원인 = 1 커밋(커밋 자체는 Codex/승인).

## Step 1 — 런처/스캔 문서화 (canonical 고정)

- **Observation**: `build.gradle.kts` L931 `mainClass=LmsApplication`; `LmsApplication` L20 `scanBasePackages={com.example.lms, com.nova.protocol}`; `AgentApplication.java` 존재-only (삭제·스캔확장 금지).
- **Setup Commands**: `python -B scripts/uaw_spine_probe.py --root .` → S1/S2 OK, S4 WARN(존재 경고)가 정상.
- **Verification**: probe exit 0 + S1~S3 OK. 부팅 스크립트(`Start-RAG.bat`→`start_rag_stack.ps1`)가 mainClass를 거치는지는 Start 경로 문서로 확인.
- **Patch Blocks**: `OWNER=Codex` — 런처 주석/문서 명시 필요 시 별도 승인.

## Step 2 — 설정 이중 선언 관측 (수정 아님)

- **Observation**: `03_GATE_TABLE.md` §3 표 — properties가 yml을 무력화(OCR 실질 true/0.78). `spring.config.import` 오버레이 5종까지가 실효 계층.
- **Setup Commands**: `python -B scripts/uaw_spine_probe.py` → S8 WARN 목록. 실효값은 부팅 후 `management:18181` env/property-origin 또는 부팅 로그 확인(별도 세션, evidence_needed).
- **Verification**: S8의 dual 목록이 문서 표와 일치.
- **Patch Blocks**: `OWNER=Codex` — 어느 포맷이 정본인지 결정 후 단일화. 이 파일에서는 키 삭제 0.

## Step 3 — planDsl 계약 문서화

- **Observation**: `04_PLANDSL_CONSUMERS.md` — broad 키 `not_used`, hint/projection 실소비자 존재, `planDslOrder()`는 DSL이 아니라 RuleBreak 분기.
- **Setup Commands**: `python -B scripts/uaw_spine_probe.py` → S5 OK = 마커 존재. 계약 테스트 목록은 04 §4.
- **Verification**: S5 OK 유지 + focused 테스트(필요 시) `gradlew.bat test --tests UnifiedRagOrchestratorPlanHintsTest --tests ServicePlanDslLoaderTraceContractTest`.
- **Patch Blocks**: `OWNER=Codex` — `planDslOrder()` 이름 오인 주석, DSL 실행기.

## Step 4 — RuleBreak canonical 등록 설계 카드 (이미 배선됨 — 검증 카드로 전환)

- **Observation**: `03_GATE_TABLE.md` §4 — WebMvcConfig 조건부 등록 + admin-token evaluator. **이미 제품에 있으므로 재패치 금지**. 발화 조건: admin-token 일치 요청 + `retrieval.order.mode`≠fixed(05 §4 게이팅 뉘앙스).
- **Setup Commands**: probe S6 OK 확인. 발화 계약 테스트는 `WebMvcRuleBreakRegistrationTest` + `RetrievalOrderServiceTest`.
- **Verification**: (Codex 측) SPEED_FIRST 발화 시 `retrievalOrder.authority.owner=PLAN_DSL` 트레이스 관측.
- **Patch Blocks**: 비움 — 제품은 이미 완료. 남은 선택지: dormant 7계보 격리(Step 6)와 발화 E2E 증명.
- 절대 금지: zerobreak 계보/nova-protocol WebFilter로 대체.

## Step 5 — dormant 격리 정책 (삭제보다 exclude)

- **Observation**: probe S9 — `com/abandonware/ai`, `com/abandonwareai`, `com/abandonware/patch`, 루트 패키지 6종. Abandon_X P2-G 계보 목록.
- **Setup Commands**: `python -B scripts/uaw_spine_probe.py` → S9 존재 목록 == 기대치.
- **Verification**: 각 후보 "스캔 밖 + 미import + 미호출" 3중 증명 후에만 제거 후보. `allow-bean-definition-overriding` 도입 금지.
- **Patch Blocks**: `OWNER=Codex` — compile exclude 또는 아카이브 이동은 별도 승인 작업.

## Step 6 — UAW product "켜는 체크리스트" (플래그 OFF 유지)

- **Observation**: `uaw.autolearn.*` ~20 클래스, `uaw.thumbnail/presence/selfclean` — 모두 canonical이나 기본 disabled (`application.yml` L781-805).
- **Setup Commands** (켜기는 승인 후, 순서 고정):
  1. 대상 기능의 기본 플래그 확인: `uaw.autolearn.enabled`, `uaw.thumbnail.enabled`, `uaw.presence.*`, `uaw.selfclean.*`
  2. 의존 선행 확인(스케줄러/quota/예산 가드 존재 여부) — `uaw/autolearn/*Scheduler*`, `*Quota*` 클래스 존재 확인
  3. 프로파일 한정(예: dev만) 플래그 ON → 부팅 로그로 빈 생성 확인
  4. 기능별 Done 증거 정의(로그 키/엔드포인트/테스트) — "포트폴리오에 적혀 있으니"는 금지
- **Verification**: 각 단계의 명령+exit+관측 키. `SKIP_INACTIVE` 증거 허용(비활성 기본값도 정상 verdict).
- **Patch Blocks**: `OWNER=Codex`.

## Step 7 — plans/ 별칭 단일화 (P1-E)

- **Observation**: probe S7 — `brave/safe_autorun/zero_break` 비-v1 별칭이 v1과 공존. 로더 선택 규칙 미확인(`evidence_needed`).
- **Setup Commands**: 로더의 `*.yaml` vs `*.v1.yaml` 우선순위를 `PlanHintApplier.load` 계열 코드에서 읽어 확정(읽기).
- **Verification**: 선택 규칙 문장이 코드 라인으로 증명된 후에만 삭제/통합 제안.
- **Patch Blocks**: `OWNER=Codex`.

---

### 운영 규칙 (모든 Step 공통)

- 파일 겹침: Codex MAX-PUSH lease와 같은 경로면 **HOLD** — Devin diff=0 유지.
- 검증 표기: tool-ran / target-verified / build-ran / full-verification 분리 (`awx.debug.verify.v2`).
- Git: commit/push 금지 unless 사용자 명시. 이 문서의 명령은 읽기 전용만 포함.
