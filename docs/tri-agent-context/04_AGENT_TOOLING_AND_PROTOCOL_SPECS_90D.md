---
doc_id: TRI-CTX-04
title: Agent Tooling and Protocol Specs — 90-Day Volatile
created_at: "2026-10-05T09:00:00+09:00"
expires_at: "2027-01-03T09:00:00+09:00"
ttl_days: 90
lifecycle: VOLATILE
validity_basis: "live tree 스크립트/스킬 인벤토리 + api-spec-catalog 실증 문서 + 지시서 부록 웹서치 주장(출처 미검증 표기) 2026-10-05 KST"
---

# 04. 에이전트 도구/프로토콜 규격 (90일 VOLATILE)

> 도구 이름·플래그·엔트리는 가변이다 — 새 세션은 인용 전 파일 실존을 확인한다.
> 벤더 규격 상세는 [docs/api-spec-catalog](../api-spec-catalog/README.md)가 본편, 여기는 에이전트 운용 요지만.

## Devin 최적 프롬프트 규격 (지시서 설계)

웹서치 출처 주장(공식 URL 미첨부 → `추정` 등급, 도입 전 재확인):

- **Spec 중심 구조화**: 목표·범위·Acceptance를 명세처럼 적고 자유서술 설계를 줄인다.
- **선택적 Context Pinning**: 필요한 파일/규칙만 고정하고 나머지 컨텍스트를 배제해 토큰을 아낀다.
- **Slice 분해**: WP 단위로 쪼개 각 단위가 독립 검증 가능하게 한다.

이 체크아웃에서 실측되는 Devin 지시서 규격 (사실 — BRIEF.txt 관례):

- 첫 줄 `[ANTI-STOP]` + "실행 지시서" 선언, `Project Root` 절대경로, `한 줄 목표`.
- `사실`(기준 시각 명시) / `공통 규칙` / `항목 WPn` / `HOLD 조건` / `ASK_ONCE(자동 결정 기본값)` /
  `절대 금지` / `Acceptance` / `보고 형식(외부 API: 첫 줄)`.
- 추천 pinning 세트: 대상 파일 경로, 관련 SSOT 문서, 검증 커맨드, 금지 목록.

## MCP 전송 프레이밍 (stdio vs Streamable HTTP)

| 전송 | 프레이밍 | 세션 | 함정 |
|---|---|---|---|
| stdio | 개행 구분 NDJSON — 단일 라인만 | 프로세스 수명 = 세션 | `Content-Length` 헤더 금지, stdout 로그 오염 즉사 |
| Streamable HTTP | POST 요청/응답 + 선택적 SSE 업그레이드 | `Mcp-Session-Id` 헤더 | 404 = 세션 만료 → `initialize` 재협상 필수 |

- **취소 정정**: `$cancellation` 메서드는 존재하지 않는다. 취소는 알림
  `notifications/cancelled` (`requestId`,`reason`)로 가며 best-effort — 지연 도착 응답은
  requestId로 폐기.
- heartbeat 프레임 없음: 프로토콜 `ping`(30–60s 권장) 또는 SSE 유휴 감지로 좀비 탐지.
- 근거: [docs/api-spec-catalog/01_AI_AGENT_PROTOCOLS.md](../api-spec-catalog/01_AI_AGENT_PROTOCOLS.md) T1-E1~E5.

## Windows CP949 방어

- 콘솔 기본 인코딩 CP949: UTF-8 텍스트가 `????`/``로 깨지는 것은 표시 계층 문제지 데이터 손상이 아니다.
- 파일 쓰기는 `[IO.File]::WriteAllText(..., UTF8Encoding($false))` (no-BOM). 스크립트 출력은
  JSON `ensure_ascii` 선택 확인, 판독은 파일 리다이렉트 후 읽기.
- 기존 방어 예: `scripts/out_peek.py`, `server_trace_digest.py`의 cp949 폴백 디코딩.

## Grok CLI 메모리 브리지

- 진입: `Start-GrokBot-Memory.bat`; 스크립트 `scripts/grok_to_agy_memory_bridge.py`
  (`--incremental` 동기), `grokbot_memory_primer.py`(≤2500자 프라이머 md/text/json),
  `grok_memory_recall.py`(가중 top-3 리콜), `grokbot_skill_catalog.py`(레시피 카탈로그).
- 인덱스 SSOT: `docs/GROKBOT_MEMORY_INDEX.md`. 역할 스킬: `.agents/skills/demo1-grokbot-role`,
  `demo1-agy-grokbot-mode` (같은 핸드오버 팩 공유 — 내용 이중화 금지).

## agy CLI 엔트리 규격

- 진입: `Start-Agy-CLI.bat` → AGENTS.md Core Request Entry → `demo1_vibe_skill_router.py resolve`.
- 기본 `--mode plan` 읽기 전용; 사용자 요청 편집만 기존 lease/checkpoint 게이트 뒤에서.
- 보고 규격: `file:line` + 커맨드 + 관측 출력 근거.
- 스킬: `.agents/skills/demo1-agy-cli-entry/SKILL.md` (tools/agents headless 킷, $0 lint/doctor 프로브).

## 관련 문서

- [05_RECENT_DISCOVERED_EDGE_CASES_90D.md](05_RECENT_DISCOVERED_EDGE_CASES_90D.md) — 프로토콜 엣지 상세
- [02_TRI_AGENT_ROLES_AND_HANDOFF.md](02_TRI_AGENT_ROLES_AND_HANDOFF.md) — 역할·핸드오프
- [README.md](README.md)
