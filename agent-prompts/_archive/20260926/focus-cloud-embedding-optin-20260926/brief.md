# Devin 지시서 — Focus cloud embedding opt-in (`matchIfMissing=false`) (2026-09-26)

## Goal
Focus 메모리 임베딩의 **클라우드 경로를 기본 OFF(opt-in)** 로 바꾼다.
키가 있어도 `focus.memory.embedding.cloud-enabled=true` 없으면 `FocusCloudEmbedding` 빈이 등록되지 않게.
완료 = Acceptance PASS. push 금지. “읽기만” ≠ 완료.

## Project Root
`C:\AbandonWare\demo-1\demo-1\src`

## Evidence
- `main/java/com/example/lms/config/FocusMemoryEmbeddingConfig.java` L26:
  `@ConditionalOnProperty(... cloud-enabled ... matchIfMissing = true)` ← **문제**
- `local-enabled` 기본 false 쪽과 맞물리면 키 있을 때 유료 클라우드로 조용히 감
- Git origin은 이미 AbandonWareAi (이번 스코프 아님)
- 진행 중 lease와 충돌 피함: query-flow / vibe-longrun / mgain-assist 건드리지 말 것

## Work (최소 seam)
1. `FocusMemoryEmbeddingConfig.java`: `matchIfMissing = false` (cloud-enabled)
2. 필요 시 yml에 문서용 주석만 (기본값 false 명시). 일반 RAG embedding 빈은 건드리지 말 것
3. `FocusMemoryApiFallbackTest` (및 관련 fixture): 클라우드 케이스에 **명시적으로** `focus.memory.embedding.cloud-enabled=true` 설정
4. compile + 해당 테스트 클래스 green

## Acceptance
- [ ] property 없음 → FocusCloudEmbedding 빈 없음
- [ ] `cloud-enabled=true` (+ 키 조건 기존과 동일) → 빈 등록
- [ ] `FocusMemoryApiFallbackTest` green
- [ ] 일반 RAG/다른 embedding 설정 변경 없음
- [ ] push/secrets/다른 THE ONE 파일 없음

## Must NOT
- query-flow / vibe-longrun / mgain product(chat.js TraceHtml) 병행 수정
- proto-open / CSRF 강화
- secrets 출력·커밋 · push · add -A · lease steal
- AbandonWare3 재등록
- Focus 전체를 끄는 대형 리팩터 / 무관 파일

## Soft-auto git
conditional_local_git, foreign staging 보존, selective path only.

## Shortlist 참고 (이번 THE ONE 아님)
2) /chat control prefs → localStorage (`chat.js` lease free)
3) peer-signal bridge (`agent_preflight.signals` + Devin bus hook)

## SSOT
`agent-prompts/focus-cloud-embedding-optin-20260926/brief.md`
