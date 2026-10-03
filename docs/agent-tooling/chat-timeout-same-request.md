# chat_timeout_same_request.py — 같은 요청 증거 추출기

한 /chat 요청의 서버 측 증거를 여러 로그에서 requestHash 기준으로 묶는다. 읽기 전용, 표준 라이브러리만.
관측되지 않은 필드는 `not_observed`로 남긴다(다른 요청 값으로 채우지 않음).

- 입력: `--log <파일>`(반복: Spring stdout, `var/abnadon/debug/*.ndjson`, Ollama 로그), `--trace-dir`(chat-session-traces 루트), `--since/--until`(ISO, naive=KST) 또는 `--request-id hash:xxxx`.
- 인식: `LLM_REQUEST_LIFECYCLE`/`LLM_REQUEST_PROOF`(requestHash·runHash·providerReceiptObserved), `stream-failed type=`, `SSE stream detached`(ctx UUID로 연결), `TRACE_SNAPSHOT`(status/reqHash), ndjson `requestId`, trace JSON(runId 조인), Ollama 로드 시작/abort/`Load failed`/GIN 요청(시간창 상관).
- 주의: `TRACE_SNAPSHOT`의 `reason=`은 스냅샷 트리거명(예: `http_request`)이지 실패 reasonCode가 아니다. 스트림의 `backend_timeout`은 SSE 본문에만 나가고 로그에는 `ModelSelectionException`+`failureClass`로만 남는다.

## 코덱스가 스모크 직후 바로 돌리는 명령 1줄

```powershell
python -B scripts\chat_timeout_same_request.py --since "<ISO>" --until "<ISO>" --log "var\rag-launcher\<LATEST>\chat-ui-vibe-listener-18180.out.log" --log "var\abnadon\debug\<yyyy-MM-dd>.ndjson" --log "var\rag-launcher\<ollama-lane>\ollama.err.log" --log "var\rag-launcher\<ollama-lane>\ollama.out.log" --trace-dir "var\debug\chat-session-traces" --out "data\agent-handoff\<your-run>\smoke-replay.json"
```

`<LATEST>`는 `var\rag-launcher\LATEST.json`이 가리키는 실행 디렉터리. 이번 스모크 재생 결과는
`data/agent-handoff/devin-chat-timeout-assist-44f7a8c5/smoke1-replay.json`.

## 2026-10-02 11:28 스모크 재생 요약 (hash-only)

| 필드 | 값 |
|---|---|
| requestId | `hash:8d4bc7e96cd9` |
| runId / sessionId | `hash:59aa8cb534f5` / `hash:2747b7c71856` |
| streamHttpStatus | 200 (TRACE_SNAPSHOT) |
| failureClass | `timeout_soft` |
| streamFailedType | `ModelSelectionException` @ 11:29:05.924 |
| timeoutAt | 11:29:05.918 (`http_client_failed`, http_client_started 11:28:54.212 → **11.706s**) |
| providerReceiptObserved | `false` 전 구간 → `unconfirmedGuard=provider_receipt_not_observed` |
| fallbackAttempted | `no` (fallbackCount=0) |
| providerModel | `requested:qwen3.5:9b` (effectiveModel=null) |
| Ollama 상관 | load start 11:28:56.6 → client disconnect → `Load failed` 11:29:06.15, GIN `POST /api/chat 499 12.132s` |
| not_observed | reasonCode(SSE 본문만), firstTokenAt(토큰 0), effectiveModel |
