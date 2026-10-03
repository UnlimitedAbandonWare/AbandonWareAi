# 03_GATE_TABLE — 게이트 체인 canonical FQCN + 프로파일 키 (WP1-H3)

- Contract: `DEMO1-DEVIN-UAW-HARMONY-FOUNDATION-20260928` · taskId `uaw-harmony-foundation-0928-c50981a8`
- Date: 2026-09-28 · 표만 작성 — 값/코드 변경 0 (OCR 이중선언은 관측 테이블로만).

## 1. Canonical 게이트 FQCN (사실 — probe S10 전부 존재)

| 게이트 | canonical FQCN | dormant 사본 수(계보) | 비고 |
| --- | --- | --- | --- |
| CitationGate | `com.example.lms.guard.CitationGate` (+`service.guard.CitationGate`) | ×7 계보 (abandonware.ai×2, patch, abandonwareai, zerobreak, nova.protocol, 루트 `guard.`) | zerobreak 계보는 활성화 금지 |
| FinalSigmoidGate | `com.example.lms.guard.FinalSigmoidGate` (+`resilience.FinalSigmoidGate`) | ×5 계보 | — |
| DomainWhitelist | `com.example.lms.service.rag.auth.DomainWhitelist` | ×3 계보 | — |
| PIISanitizer | `com.example.lms.service.guard.PIISanitizer` (+`guard.PiiSanitizer`) | ×7 계보 (철자 혼용 포함) | — |
| RuleBreak(보조 게이트) | `com.example.lms.guard.rulebreak.*` 5종 | ×7 생산자 계보 | canonical만 WebMvcConfig 등록(§3) |

## 2. 게이트 프로파일 키 (실측 — 파일·행)

| 키 | base `application.yml` | `application-dev.yml` | `application-prod.yml` | 비고 |
| --- | --- | --- | --- | --- |
| `gate.citation.enabled` | `true` (L218) | `true` (L92) | `true` (L88) | |
| `gate.citation.min` | `${GATE_CITATION_MIN:2}` (L219) | `3` (L93) | `3` (L89) | 프로파일 강화는 의도된 오버라이드 |
| `gate.finalSigmoid.enabled` | `true` (L221) | `true` (L95) | `true` (L91) | |
| `gate.finalSigmoid.k` / `x0` | `8.0` / `0.0` (L222-223) | `12.0` / `0.0` (L96-97) | `12.0` / `0.0` (L92-93) | |
| `gate.finalSigmoid.threshold` | `${GATE_FINAL_SIGMOID_THRESHOLD:0.70}` (L224) | 미오버라이드 → base 0.70 상속 | `${GATE_FINAL_SIGMOID_THRESHOLD:0.90}` (L94) | prod만 상향 |
| `gate.contextDiversity.min` | `${GATE_CONTEXT_DIVERSITY_MIN:0.20}` (L226) | — | — | |
| `probe.search.enabled` | `${PROBE_SEARCH_ENABLED:false}` (L213) | `true` (L42) | `false` (L82) | dev에서만 프로브 |
| `selfask.enabled` | — | `false` (L111) | `false` (L107) | 기본 OFF 유지 |
| `uaw.autolearn.enabled` | `${UAW_AUTOLEARN_ENABLED:false}` (L785) | `false` | `false` | 활성화=승인 후 |
| `uaw.thumbnail.enabled` | `${UAW_THUMBNAIL_ENABLED:false}` (L781) | — | — | |

- dev/prod 파일은 ignore 가드로 read 도구 차단 — 위 값은 grep 행 증거(`사실`).
- `application-patch.properties`는 `gate.citation.min=0`, `gate.finalSigmoid.threshold=0.50`,
  `gate.finalSigmoid.mode=log-only` 등 **완화값**을 담은 별도 patch 프로파일 — 활성 프로파일 아니면 영향 없음(프로파일 로딩 확인은 evidence_needed).

## 3. 설정 이중 선언 표 (관측만 — Abandon_X P0-B 추적, probe S8)

| 키 | application.properties | application.yml | 실효 주의 |
| --- | --- | --- | --- |
| `ocr.enabled` | `true` (L714) | `${OCR_ENABLED:false}` (L194) | **properties 우선 → 실질 true**. yml만 읽으면 반대로 오독 |
| `ocr.min-confidence` | `0.78` (L715) | `${OCR_MIN_CONFIDENCE:0.65}` (L196) | 실질 0.78 |
| `local-llm.base-url` | 선언 있음 | 선언 있음(다른 구조) | 동일 기본값이나 이원 선언 자체가 drift 위험 |
| `retrieval.vector.enabled` | `true` | `true` | 값 일치 — 단일화 후보만 |

- 추가 계층: `application.yml`의 `spring.config.import` optional 오버레이 5종(proj-override, openai-model-param-matrix, orchestration-stage-policy, application-llm.yaml, application-deepgram-local)이 뒤에 얹힘 — 실효값 추적 시 이 계층까지 포함.
- 단일화 패치는 **Codex/승인 소유**. property-origin 실확인(부팅 Environment)은 `evidence_needed` — 본 WP 범위 밖.

## 4. RuleBreak 배선 현황 (사실 — Abandon_X P0-A drift)

- `WebMvcConfig.addInterceptors` (L59-71): `ReqLogInterceptor` + `ruleBreakInterceptorProvider.getIfAvailable()` → 존재 시 `/**` 등록, 로그 `[AWX][rulebreak] mvc-interceptor=registered|skipped`.
- `RuleBreakInterceptor` = `@Component` + fail-soft, `RuleBreakContextHolder.set/clear`.
- `RuleBreakEvaluator` (L81-126): `nova.rulebreak.admin-token`(fallback `tools.rulebreak.admin-token`) 미설정 시 전 요청 inactive; 토큰 비교 `MessageDigest.isEqual`; 정책 기본 `SAFE_EXPLORE`, TTL 기본 60s; 결정은 `rulebreak.decision.*` TraceStore + 해시만 기록.
- 소비자: `RetrievalOrderService.planDslOrder()` SPEED_FIRST → VECTOR_KG_WEB + `retrievalOrder.authority.*`.
- 계약 테스트: `src/test/.../config/WebMvcRuleBreakRegistrationTest.java` 존재.
- 결론: **P0-A(미등록) 해소됨**. 남은 잔여: 발화 조건은 admin-token 보유 요청 + `retrieval.order.mode`≠fixed. 7개 dormant/write-only 계보는 그대로 격리.
