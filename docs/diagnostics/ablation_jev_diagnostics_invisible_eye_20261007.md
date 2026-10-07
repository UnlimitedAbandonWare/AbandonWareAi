# Ablation·Z 진단 Invisible Eye — 6대 숨은 함정 가이드 (2026-10-07)

작성: `devin-ablation-diagnostics-assist-458cfecf` (Devin, STRICT_ZERO 제품 소스)
근거 지시서: `PASTE_CODEX_ABLATION_DIAGNOSTICS_JEV_EVALUATION_20261007` (Codex 소유)
점검기: `python -B scripts/verify_ablation_diagnostics_assist.py --report`
픽스처: `python -B scripts/mock_jev_evaluation_gateway.py --smoke`

이 문서는 Codex 제품 패치와 겹치지 않는 **조수 가이드**다. 아래 함정은 코드를
읽어도 눈에 잘 띄지 않는 구조적 단절이며, 각 항목은 `왜 안 보이는지 → 최소
수정 방향 → 절대 건드리지 말 것` 순서로 정리했다. 줄 번호는 2026-10-07
12:1x KST 관측 기준이며, Codex 진행 중 drift로 바뀔 수 있다 — 판단 전 반드시
점검기를 다시 돌린다.

## 0. 라이브 관측 요약 (점검기 첫 실행 결과)

| ID | 함정 | 라이브 상태 (12:1x KST) |
|---|---|---|
| E2 | ThreadLocal 타이밍 단절 | **PARTIAL** — `attributionMeta` 병합으로 typed TAA 7키는 도달, render 중 신규 ThreadLocal의 비타입 키(`taa.error.*`, `trace.attribution.suppressed.*`, `traceHtml.ablation.suppressed.render`)는 metadata 미포함 |
| E3 | 60k cap 절단 초과 | **RESOLVED** — Codex `ablation-r2-cap`이 `Math.max(1024, htmlMaxLen) - marker.length()`로 이미 수정 (`TraceSnapshotStore.java:328-329`) |
| E2-2 | durable projection 예산/allowlist | **RESOLVED** — typed TAA 9키가 `ChatTraceMetaMessageRestorer` allowlist에 이미 존재; 봉투는 두 겹(v1/v2: 2,048B/16필드, v3 detail: 8,192B/96필드/10,924B64) |
| E6 | `extremeZ.` vs `extremez.` 대소문자 | **PITFALL_PRESENT** — `ExtremeZBurstAspect`가 `extremez.*`(lower) 54건 기록, `TraceSnapshotExporter.ALLOWED_PREFIXES`는 `extremeZ.`(camel)만 case-sensitive 허용 |
| E6-2 | `extremez.activated` 의미 | **NOTE** — `merged.size() > baseSize`(`:335`), 문서 증가 관측이지 발동/승인 동의어 아님 |
| E4 | Jev 비용 경로 | **PITFALL_PRESENT** — `JevGatewayClient.cost()`(:190-197)는 legacy `gateway.cost`만 읽음, 공식 `providerMetadata.gateway.cost` 미지원 |

baseline drift: 10개 추적 파일 중 4파일(ChatApiController/TraceHtmlBuilder/
Restorer/Store)이 브리프 02:50 UTC SHA와 다름 — Codex 진행 중이므로 정상 drift.
최신 스냅샷은 `--write-baseline`으로 갱신한다.

## 1. E2 — ThreadLocal 타이밍 단절 (계산→저장)

**구조:** stream `ChatApiController`에서 `extraMeta=TraceStore.getAll()`
(L2601) → `TraceStore.clear()`(L2675) → `buildSplitPanelWithMetadata`
(L2679) 순이다. TAA는 render 안쪽에서 새 ThreadLocal에 `taa.*`를 기록한다.
snapshot 입력은 `renderedTrace.metadata()`(L2694)다.

**왜 안 보이는가:** HTML에는 callout이 보이는데 저장/복원에서만 빠지므로
"렌더는 됐는데 재방문하면 사라진다"로만 보인다. getAll과 render가 같은 try
블록이라 순서를 놓치기 쉽다.

**현재 완화 상태:** `TraceHtmlBuilder.buildSplitPanelWithMetadata`가
`snapshotMeta.putAll(attributionMeta)`(:121)로 typed 7키(`taa.version`,
`taa.outcome`, `taa.outcome.risk`, `taa.topContributor.id/group`,
`taa.candidate.count`, `taa.beam.count`)를 반환 metadata에 싣는다.

**잔여 함정:** render 중 새 ThreadLocal에만 쓰이는 비타입 키 — TAA 서비스의
`taa.error.*`, `taa.bestPath.score`, `taa.contribution.*`,
`taa.web.urlRecovered.*`, `trace.attribution.suppressed.*`, builder의
`traceHtml.ablation.suppressed.render` — 는 snapshot metadata에 들어가지
않는다. suppress/오류 신호를 durable에 남기려면 builder의 attributionMeta
계열로 명시 전달하거나 persister 입력에 합류하는 최소 변경이 필요하다.

**최소 수정 방향 (Codex 소유):** 같은 요청의 TAA 결과를 한 번 계산해 HTML과
저장이 공유. render 후 전체 TraceStore 무차별 merge 금지, 타 요청 recent
error로 빈 결과 채우기 금지. ring miss는 `상세 trace 만료/요약만 복원`을
표시하고, 부족한 trace로 재추론한 risk를 원래 값처럼 보이게 하지 않는다.

## 2. E3 — 60k cap: cap 절단 "뒤" marker 부착 (현재 RESOLVED)

**함정:** 구 코드는 `substring(0, Math.max(1024, htmlMaxLen))` 후
`"\n<!-- truncated -->"`를 붙여 최종 길이 = cap+19. UI는
`chat-trace-ui.js` `MAX_HTML = 60000` (:5) 초과 시 `source.length >
MAX_HTML`에서 null 반환(:114) — 서버가 만든 "안전한" HTML을 UI가 통째로
거절할 수 있었다.

**왜 안 보이는가:** 서버 로그에는 cap 적용처럼 보이고, UI 쪽은 그냥 패널이
비어 보인다. 60,019 > 60,000이라는 19자 차이는 길이 출력을 직접 재지 않으면
눈에 띄지 않는다.

**현재 상태:** `TraceSnapshotStore.java:328-329`가
`Math.max(1024, htmlMaxLen) - marker.length()`로 수정되어 **marker 포함
최종 길이 ≤ cap**이다 (Codex `ablation-r2-cap` lease 작업). 지시서 권고
U3-(a)와 일치한다.

**절대 건드리지 말 것:** UI `MAX_HTML` 상향, validator 우회, sanitizer 무력화.
cap override(`trace.snapshot.html.max-len`) 경계 59,999/60,000/60,001 회귀는
Codex WP-R2 계약으로 남는다.

## 3. E2-2 — durable projection 예산은 두 겹이다

**함정:** "2048B/16필드"는 절반의 사실이다. `ChatTraceMetaMessageRestorer`의
봉투는 두 가지:

| 봉투 | 버전 | 한도 |
|---|---|---|
| durable fallback | v1/v2 | **2,048B** 디코딩 / **2,732B64** / **16필드** |
| detail (diag.*) | v3 | **8,192B** / **10,924B64** / **96필드** |

`ChatTraceSnapshotPointerPersister`는 diagnostics가 있으면 v3(8,192B), 없으면
v1(2,048B)로 기록하고, 초과 시 `storageMode=durable_fallback`의 최소 필드로
폴백한다 — **조용히 비우지 않고** 구조적 폴백이다.

**왜 안 보이는가:** 필드 하나만 추가해도 "들어가겠지"라고 생각하기 쉽다.
실제로는 `isValidDurableField`의 per-key 인코딩 round-trip 검증,
`SAFE_LABEL`/`SAFE_HASH` 패턴, 80필드 diag 상한, b64 길이 제한까지 통과해야
한다. prefix를 allowlist에 넣는 것만으로는 보존이 보장되지 않는다.

**현재 상태:** typed TAA 9키(`ablation.finalized`, `taa.candidate.count`,
`taa.beam.count`, `taa.outcome.risk`, `ablation.score.final`, `taa.version`,
`taa.outcome`, `taa.topContributor.id`, `taa.topContributor.group`)가 이미
allowlist에 존재한다 — 브리프의 "taa.*/ablation.*를 받지 않는다"는 typed
키 기준으로는 이미 해소됐다. wildcard 허용은 여전히 없어야 한다.

**최소 수정 방향:** 새 diag 키는 typed scalar만, dense 입력 시 96필드/8,192B
우선순위·기존 diagnostics 보존을 함께 검증. raw evidence/beam/question/
log/snippet 저장·공개 금지.

## 4. E6 — `extremeZ.`(camel) vs `extremez.`(lower) 대소문자

**함정:** 세 namespace가 공존한다.

| namespace | 역할 | 예시 |
|---|---|---|
| `EXTREMEZ` | `routing.executionPlan.primaryMode` **계획값** | 실행 아님 |
| `extremeZ.` | plan **설정** 읽기 | `planInt("extremeZ.maxSubQueries")` |
| `extremez.` | 실행 **관측** 쓰기 | `trace("extremez.activated")` |

`TraceSnapshotExporter.ALLOWED_PREFIXES`는 `extremeZ.`(camel)만
case-sensitive `startsWith`로 허용(`:107-120`) — Aspect가 기록하는
`extremez.*`(lower) 54건은 exporter에서 통째로 누락된다.

**왜 안 보이는가:** `SafeRedactor`는 camel 허용 목록을 갖고, grouped
panel/exporter/existing diagnostics가 서로 다른 namespace를 쓴다.
"소스에 key가 있다"와 "exporter가 그 key를 내보낸다"를 혼동하면 된다.

**최소 수정 방향:** 표시·복원에서 계획값·trigger 관측·실행·증가를 분리
표기. 대문자 EXTREME/Z_TRIGGER/Z_MODE를 추측해 하드코딩하지 않는다. alias
충돌이 현재 producer/시점과 연결되지 않으면 UNKNOWN. exporter는 웹과 별도
lane — 기본 복구 범위를 넓히지 않고 근거 차이로 기록한다.

## 5. E6-2 — `extremez.activated`는 "문서 증가"다

**함정:** `ExtremeZBurstAspect:335` —
`trace("extremez.activated", merged.size() > baseSize)`. 실행했어도
dedup/상한으로 증가 0이면 `activated=false`, trigger 자체와는 별개 관측이다.

**왜 안 보이는가:** 이름이 "activated"라 발동·승인·실행과 동의어로 읽기
쉽다. `ExtremeZTrigger.trigger.activated`(OVERDRIVE/HYPERNOVA에서도 true)와는
다른 필드다.

**표시 규칙:** `계획 EXTREMEZ` / `trigger 관측` / `실행 관측` /
`activated(증가)`를 분리 표기. 실행관측+추가0 = `activated=false`,
declared/enabled만으로 ON 승격 금지, 명시적 false/skipped와 absent
NOT_OBSERVED 구분.

## 6. E4 — Jev 비용: 공식 `providerMetadata.gateway.cost`

**함정:** 공식 Vercel 계약(2026-10-07 확인)의 비용은
`providerMetadata.gateway.cost`다. `JevGatewayClient.cost()`(:190-197)는
legacy `gateway.cost`만 읽는다 — 공식 응답에서 비용은 읽히지 않는다.

**계약:** 비용 누락은 **UNKNOWN**이며 0원 보정 금지. `probability`(선택
확률)와 공식 `confidence`(분포 집중도 통계)는 별개 필드 — 내부
`confidenceAccepted`를 갑자기 재정의하지 않는다.

**최소 수정 방향 (Codex WP-J1 옵션):** 두 경로를 안전하게 지원하는 최소
파서 수정만. 오프라인 대조는 `mock_jev_evaluation_gateway.py --smoke`
픽스처(공식/legacy/누락/invalid label/분포 불일치/unknown model/CJK
fail-soft 9종)를 쓴다. 실제 dispatch·billing·CJK 품질은 NOT_RUN.

## 7. P6 복원력 원칙 연계

- **원칙 2 (서브에이전트 삼중 경계):** 본 도구·문서는 조수 lane이다 — 제품
  소스(main/, frontend/, static/js/chat.js, src/test)는 Codex lease 소유.
  조수는 scripts/, docs/, data/agent-handoff/ 신규 파일만 쓰고, Codex가
  완료를 주장할 때 점검기+baseline drift로 독립 교차검증한다.
- **원칙 5 (Jev 판정 선별 한정):** Jev output은 advisory label이지 truth/
  인과 증명/verification verdict가 아니다. 진단 probe는 유한 후보 안에서의
  우선순위 보조일 뿐, 기존 selector/검증 게이트의 최종 권한을 대체하지
  않는다. tool PASS ≠ 제품 검증 PASS.

## 8. 불변 제약 리마인드

- `dev.langchain4j` 1.0.1 고정, Spring Boot 기존 버전 유지 — mixed/beta
  관측 시 해당 lane 중단·보고.
- `openssl`/`opnessl` 이름·값·형식·구조 불변.
- PROTO_OPEN 유지 — 새 로그인/role gate 금지.
- 실제 Vercel 유료/외부 호출 NOT_RUN — 오프라인 mock만.
- 서버 재기동(ForceRestart/DevWatch)은 Codex 패치 완료 후 그 세션 소유.
- heuristic risk/softmax/expectedDelta/confidence를 진실 확률로 선언 금지.
