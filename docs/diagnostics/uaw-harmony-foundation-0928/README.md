# uaw-harmony-foundation-0928 — 밑밥 패키지 색인

- Contract: `DEMO1-DEVIN-UAW-HARMONY-FOUNDATION-20260928`
- Date: 2026-09-28 · taskId `uaw-harmony-foundation-0928-c50981a8` · agent `devin-uaw-harmony`
- 역할: UAW 큰 그림(Dynamic RAG orch + gates + Nova LIVE 축)에 맞는 도구·탐침·조화맵 밑밥.
  제품 Java/JS 패치는 **Codex MAX-PUSH 소유** — 본 패키지는 읽기 전용 산출물 + 신규 스크립트/스킬만.

## 파일

| 파일 | 내용 | WP |
| --- | --- | --- |
| `01_SPINE_MATRIX.md` | UAW 주장 ↔ live 판정 매트릭스 (스파인 표 + 얹지 말 것 표 + 소유 경계) | WP0 |
| `02_SETUP_ORDER.md` | 셋업 순서 레시피 7단계 (Observation→Setup→Verification, Patch=Codex) | WP2 |
| `03_GATE_TABLE.md` | 게이트 canonical FQCN + 프로파일 키 표 + 설정 이중선언 표 | WP1-H3 |
| `04_PLANDSL_CONSUMERS.md` | planDsl not_used 계약 + 실소비자/비소비자 정적 스캔 | WP1-H2 |
| `05_FAILURE_ORDER_CALLS.md` | Failure Pattern ↔ RetrievalOrderService 호출 그래프 + mode 게이팅 | WP1-H4 |
| `probe_summary.md` | `scripts/uaw_spine_probe.py` 실행 결과 (OK=6 WARN=4) | WP1-H1 |

## 연결 산출물

- 탐침: `scripts/uaw_spine_probe.py` (S1~S10, exit 0 = 스캔 완료)
- 스킬: `.agents/skills/demo1-uaw-harmony-foundation/SKILL.md` + intent `uaw-harmony-foundation`
- 지도 갱신: `docs/diagnostics/uaw-vibe-longrun-20260928/00_HARMONY_MAP.md` §4 델타

## 판정 요약 (사실 = 이번 세션 live 확인)

- 해소됨: RuleBreak 생산자 미등록(Abandon_X P0-A → 현재 WebMvcConfig 조건부 등록 + admin-token evaluator)
- 지속: OCR 등 4키 properties↔yml 이중선언, plans/ 비-v1 별칭 3쌍, dormant 루트 9종, AgentApplication 존재
- 조건부: Failure→Order 고리는 `retrieval.order.mode`≠fixed(기본 fixed, adjustFromCfvm이 플립)일 때만 도달
- 미검증(evidence_needed): property-origin 실효값, plans 로더 선택 규칙, 런타임 발화 트레이스
