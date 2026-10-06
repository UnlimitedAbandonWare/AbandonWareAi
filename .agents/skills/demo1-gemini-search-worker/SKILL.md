---
name: demo1-gemini-search-worker
description: "소스만으로 확정 못 하는 외부 사양(공식 API 형식·모델 id·가격·라이브러리 버전 동작)을 Gemini Flash + Google Search grounding으로 확인하고 출처 달린 ≤3KB 카드를 받을 때 사용"
---

# demo1 Gemini Search Worker

한 줄 장착형 하위 에이전트. 실행 SSOT는 `scripts/gemini_search_worker.py` 하나 —
Codex 하위에이전트·Devin·agy·Grok CLI 모두 같은 명령을 부른다.

## 언제 쓰나

- 로컬 소스만으로 확정할 수 없는 **외부 사양** 확인: 공식 API 요청/응답 형식,
  모델 id·상태, 가격·한도, 라이브러리 버전별 동작, 릴리스 노트.
- 소스 탐색·코드 리뷰·로컬 로그 분석의 대용으로 쓰지 않는다 — 그건 glm_worker
  (로컬 반박 검토)와 일반 탐색의 영역. 분담: `glm_worker` = 로컬 코드·로그,
  `gemini_search_worker` = 외부 공식 사양. 둘 다 read-only.

## 호출

```powershell
python -B scripts/gemini_search_worker.py search "<질문>" `
  --domains ai.google.dev,docs.spring.io --depth L2 --brief-id <taskId>
```

- `--depth` L1/L2/L3는 `configs/agy-depth.json`(demo1-agy-depth-router)의
  판정을 재사용 — L1 단순 확인 / L2 버전·날짜 포함 / L3 교차검증.
- `--domains` = 공식 문서 도메인 힌트(프롬프트에 주입, 서버측 강제 아님).
- 출력 = stdout 단일 JSON 카드(≤3KB): `verdict, answer, sources[{title,uri}],
  webSearchQueries, finishReason, usage, model, requestId, checkedAtKst`.
- 성공은 `verdict=GROUNDED_OK`뿐. `NO_GROUNDING`·`FINISH_TRUNCATED`는
  근거 없는 답/잘린 답이므로 재질의하거나 미확정으로 남긴다.
- 진단: `python -B scripts/gemini_search_worker.py models` → 현재 픽된
  최신 안정 Flash id.

## 계약 (지시서 작성자용)

- 지시서에는 [references/attach-block.md](references/attach-block.md)를
  그대로 붙인다(≤10줄, 도메인·상한만 채움).
- 키: `GEMINI_API_KEY` env만 읽음. 값·URL·헤더 출력 금지(키는 헤더 전용).
- 상한: 지시서별 `--brief-cap`(기본 10). 401/403/429 재시도 금지 — 카드
  verdict(`AUTH_FAIL`/`RATE_LIMITED`)만 보고하고 원인을 상단에.
- 결과는 **참고 증거**: 로컬 HEAD 소스·테스트가 우선, worker 동의 ≠ 검증 성공.
- 검색 결과를 제품 프롬프트나 다른 회사 메인 모델 입력으로 넘기지 않는다.
- 캐시 24h(같은 질문 재호출 0), 사용량 장부 `var/gemini-search-worker/usage-*.jsonl`,
  월 4,000회 WARN·5,000회 OVER 경고(차단 아님 — 공식 초과 단가 $14/1,000건,
  ai.google.dev 가격표 2026-10-06 확인).

## Codex 하위에이전트 배선

`C:\Users\nninn\.codex\agents\gemini_search_worker.toml`(add-only) —
read-only 샌드박스 worker로, 실제 API 호출은 하지 않고 부모에게 돌려줄
정확한 스크립트 명령과 카드 해석을 산출한다. 부모 세션 인증 레인에서
서브에이전트 spawn이 실패하면 부모가 스크립트를 직접 실행하면 된다(동일 결과).

상세 SSOT: `docs/agents/DEMO1-GEMINI-SEARCH-WORKER.md`
