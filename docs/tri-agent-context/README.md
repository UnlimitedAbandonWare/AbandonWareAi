---
doc_id: TRI-CTX-README
title: Tri-Agent Shared Context Catalog — Index
created_at: "2026-10-05T09:00:00+09:00"
expires_at: "PERPETUAL"
ttl_days: null
lifecycle: INVARIANT
validity_basis: "카탈로그 구조 계약 — 지시서 devin-agent-context-docs-90d-20261005 (2026-10-05 KST); 개별 문서 내용의 신선도는 각 문서 frontmatter 참조"
---

# Tri-Agent Context Catalog

> Codex·Devin·Grok CLI·agy가 같은 체크아웃에서 공유하는 컨텍스트 문서군.
> **불변(INVARIANT)** 운영 상수와 **90일 가변(VOLATILE)** 스냅샷을 분리해,
> 만료 관리는 각 문서의 YAML frontmatter가 담당한다.
> 검증: `python -B scripts/validate_agent_context_docs.py` (exit 0 = 구조+수명주기 유효).

## 문서 내비게이션 매트릭스

| 문서 | doc_id | lifecycle | ttl_days | expires_at | 내용 |
|---|---|---|---|---|---|
| [01_ARCHITECTURAL_INVARIANTS.md](01_ARCHITECTURAL_INVARIANTS.md) | TRI-CTX-01 | INVARIANT | — | PERPETUAL | Project Root SSOT, PROTO_OPEN, Git Local-First, lmsdb H2, 셸 계약, 검증-판정 분리 |
| [02_TRI_AGENT_ROLES_AND_HANDOFF.md](02_TRI_AGENT_ROLES_AND_HANDOFF.md) | TRI-CTX-02 | INVARIANT | — | PERPETUAL | 역할 분담, single-seam 리스+저널, FOR_<owner>/handoff 패킷, 병렬 세션 |
| [03_LIVE_LLM_RAG_REGISTRY_90D.md](03_LIVE_LLM_RAG_REGISTRY_90D.md) | TRI-CTX-03 | VOLATILE | 90 | 2027-01-03 | llm.yaml 기본값, 활성 Codex 심, SelfAskPlanner/RRF 계약, auth-first 모델, Ollama 잠금, Vercel 크레딧 |
| [04_AGENT_TOOLING_AND_PROTOCOL_SPECS_90D.md](04_AGENT_TOOLING_AND_PROTOCOL_SPECS_90D.md) | TRI-CTX-04 | VOLATILE | 90 | 2027-01-03 | Devin 지시서 규격, MCP stdio/HTTP 프레이밍, CP949 방어, Grok 메모리 브리지, agy 진입 |
| [05_RECENT_DISCOVERED_EDGE_CASES_90D.md](05_RECENT_DISCOVERED_EDGE_CASES_90D.md) | TRI-CTX-05 | VOLATILE | 90 | 2027-01-03 | DPoP/Realtime/SSE 엣지, Self-Ask bypass, receipt freshness |
| [06_SPRING_BOOT_3_3_4_LANGCHAIN4J_1_0_1_SSOT.md](06_SPRING_BOOT_3_3_4_LANGCHAIN4J_1_0_1_SSOT.md) | TRI-CTX-06 | INVARIANT | — | PERPETUAL | Spring Boot 3.3.4/Java 17 베이스라인, LangChain4j 1.0.1 순수성 게이트, ChatModel/RAG API 치트시트, LlmConfig 빈 배선, 안티패턴 방어 |

## 에이전트별 추천 Pinning 세트

| 에이전트 | pin | 이유 |
|---|---|---|
| Codex | 01 + 02 + 03 (+Spring/LangChain4j 코드 시 06) | 코어 소스 작업 전 불변 상수·리스 절차·라이브 레지스트리·플랫폼 고정 규격 |
| Devin | 01 + 02 + 04 + 06 (+필요시 05) | 런타임 검증·문서/스크립트·도구 규격·빌드 순수성 게이트 |
| Grok CLI | 01 + 04 + 06 | 도구/스크립트 작성 시 셸 계약·메모리 브리지 규격·플랫폼 SSOT |
| agy | 02 + 04 | 지시서 증폭 시 역할 경계·Devin 규격 인용 |

Pinning은 선택적이다 — 지시서에 필요한 문서만 인용하고 나머지는 배제한다 (04 §Devin 규격).

## TTL 정책

- `lifecycle: VOLATILE` 문서는 `ttl_days: 90`, `expires_at: created_at + 90일`
  (이번 생성분 = 2026-10-05T09:00+09:00 → 2027-01-03T09:00+09:00).
- 만료 경과 문서는 폐기가 아니라 **재대조 후 재작성** 대상: `validity_basis`의 SSOT를 다시 읽고
  `created_at`/`expires_at`을 갱신하거나 STALE 표기 후 아카이브.
- `lifecycle: INVARIANT` 문서는 `expires_at: PERPETUAL`, `ttl_days: null` — 사용자 명시 변경
  시에만 갱신.
- frontmatter는 flat key-value만 사용한다 (검증기가 중첩 YAML을 지원하지 않음).

## 검증

```powershell
python -B scripts/validate_agent_context_docs.py
```

검사 항목: 필수 7 파일 실존 → UTF-8 no-BOM → frontmatter 필수 키 7종 →
lifecycle 규칙(INVARIANT=PERPETUAL/null, VOLATILE=ttl 90+만료일 일치) →
doc_id 유일성 → 상대 링크(파일+앵커) 무결 → 종합 exit (0=PASS, 1=FAIL, 2=실행 오류).

## 관련 카탈로그

- [docs/api-spec-catalog](../api-spec-catalog/README.md) — 4대 API 규격 엣지케이스 본편
  (`validate_api_catalog.py` 검증, 동일 90일 living 문서 정책)
