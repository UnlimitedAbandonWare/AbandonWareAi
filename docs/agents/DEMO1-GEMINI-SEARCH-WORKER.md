# DEMO1-GEMINI-SEARCH-WORKER — gemini_search_worker 표준 부품

Status: 도입 2026-10-06 (devin-gemini-search-worker-4fb02c8f). Live 확인:
GROUNDED_OK 1회 + 캐시 적중 호출 0회 (`data/agent-handoff/devin-gemini-search-worker-4fb02c8f/`).

## 구성 요소

| 부품 | 경로 | 역할 |
|---|---|---|
| 실행 SSOT | `scripts/gemini_search_worker.py` | native `generateContent` + `tools=[{"google_search":{}}]` 직접 호출, 카드 출력 |
| mock 테스트 | `scripts/test_gemini_search_worker.py` | 13건 — verdict 매핑·재시도 0·키 마스킹·캐시·쿼터 경고·preview 필터 |
| Codex 하위에이전트 | `C:\Users\nninn\.codex\agents\gemini_search_worker.toml` | read-only 조사 worker(명령 생성·카드 해석), add-only |
| 스킬 | `.agents/skills/demo1-gemini-search-worker/` | 언제/어떻게 + attach-block |
| 장착 블록 | `.agents/skills/demo1-gemini-search-worker/references/attach-block.md` | 지시서에 붙이는 ≤10줄 블록 |

## 장착 방식 판정 (W1 결론)

**방식 A 채택**: 스크립트가 단일 실행 SSOT.
근거: `wire_api="responses"`(config.toml:259) 경유 Vercel 게이트웨이에는
Gemini 고유 `groundingMetadata`(groundingChunks[].web.uri, webSearchQueries)
필드 채널이 없어 출처 증거를 돌려줄 수 없다(구조 판정). 라이브 확인:
`generateContent` 직접 호출은 groundingChunks 6개 + webSearchQueries 3개를
돌려줌(card-live-2.json). Codex read-only 서브에이전트의 네트워크 가능성이
불확실하므로 toml은 "부모에게 돌려줄 명령 생성 + 카드 해석" worker로 설계 —
실제 호출은 부모가 스크립트로 한다.

## 명령 계약

```
search "<질문>" [--domains a,b] [--depth L1|L2|L3] [--max-chars 3000]
              [--brief-id id] [--brief-cap 10] [--model id] [--allow-preview]
              [--state-dir dir] [--card-file path] [--dry-run] [--timeout 45]
models    # 해석된 모델 후보/픽 출력 (목록은 1h 캐시)
```

- 모델 해석: `--model` > `GEMINI_SEARCH_MODEL` > `/v1beta/models` 최신 안정
  `gemini-*-flash`(generateContent 지원, preview/exp 제외) > 제품 SSOT
  `GeminiGateway.DEFAULT_MODEL` 재독 > `MODEL_UNRESOLVED`. 하드코딩 id 없음.
- verdict: `GROUNDED_OK`(성공 유일) / `NO_GROUNDING` / `FINISH_TRUNCATED` /
  `BLOCKED_SAFETY` / `AUTH_FAIL`(401·403) / `RATE_LIMITED`(429) /
  `NET_FAIL` / `HTTP_ERROR` + 사전 차단(`KEY_MISSING`·`MODEL_UNRESOLVED`·
  `MODEL_PREVIEW_BLOCKED`·`BRIEF_CAP_EXCEEDED`·`DRY_RUN`·`EMPTY_RESPONSE`).
- 재시도: 401/403/429 = 0회, 그 외 네트워크·5xx = 최대 1회.
- 캐시: `var/gemini-search-worker/cache/` 24h, 성공 verdict만 저장·적중 시
  호출 0회(`cacheHit:true, callsUsed:0`).
- 사용량 장부: `var/gemini-search-worker/usage-YYYYMM.jsonl` 1줄/호출.
  월 4,000회 `WARN_NEAR_FREE_QUOTA`, 5,000회 `OVER_FREE_QUOTA_ALLOWED` —
  경고만, 비차단(사용자 결정 2026-10-06).
- 비용 근거(2026-10-06, https://ai.google.dev/gemini-api/docs/pricing):
  Gemini 3.x Grounding with Google Search = 월 5,000 검색 요청 무료, 초과
  $14/1,000건. gemini-3.8-flash 입력 $0.75·출력 $3.75/1M 토큰(2026-12-31까지,
  이후 $1.50/$7.50).
- stderr `[AWX][api-spend] {json}` 1줄 attribution.
- 키: `GEMINI_API_KEY` env만, `x-goog-api-key` 헤더 전용 — 값·key 파라미터는
  어떤 출력에도 남기지 않는다(scrub).

## 불변 (ratchet)

- INV-G1 키 문자열 출력 0 — test_06.
- INV-G2 `NO_GROUNDING`·`FINISH_TRUNCATED`는 성공 아님 — SUCCESS_VERDICTS={GROUNDED_OK}, test_02·03.
- INV-G3 401/403/429 재시도 0 — test_04·05.
- INV-G4 모델 id 고정 하드코딩 없음 — resolve_model 체인 + test_10.
