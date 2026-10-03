# WP0 Inventory — API 라우팅 / 모델 선택 현황

Task `api-routing-webinst-skeleton-0927-0c420932` · Checked 2026-09-27 ~12:35 KST (03:35 UTC) on live checkout + live server `127.0.0.1:18180` (profile `local,meta-display`).

표기: **사실** = 이 체크아웃 소스/라이브 응답으로 확인. **추정** = 경로상 유력하나 미관측.

## 1. 선택 경로 SSOT (사실)

```
picker/설정 ──GET /api/chat/models──▶ ChatModelCatalogService.choices()
   ├─ local: loopback(llm.base-url) → Ollama /api/tags (+/api/show 1회/세대)
   └─ cloud: CloudModelRouteClassifier.classifyDefaultCatalog("chat")
        ├─ manifest 행 ↔ llmrouter.models.<key> 매칭 (name + provider|endpointHost)
        ├─ manifest enabled:false → cloud_manifest_disabled
        ├─ route 없음 && catalogTrust=attachment_unverified → 행 자체 drop
        └─ HybridLlmGatewayProbeService.evaluate → credential env + GroqFreeTierGuard
   Choice.selectable = permitted && eligible && route != null
   permitted = allowRemote(전역) || route ∈ remote-model-selection-routes
```

- `application.properties:665` `app.ai.allow-remote-model-selection=false`
- `application.properties:667` `app.ai.remote-model-selection-routes=${CHAT_REMOTE_MODEL_SELECTION_ROUTES:api3}` → **기본 allowlist = api3 1개**
- strict 요청: `ChatWorkflow` → `DynamicChatModelFactory` (`exactSelection && llmrouter.*` → aspect 선처리) → `LlmRouterAspect`가 `llmrouter.<key>` → `llmrouter.models.<key>` 해석
- 설정/Admin 경로(다른 SSOT): `ModelSettingsService`·`PageController` → `OpenAiModelSelectionPolicy.effectiveAllowRemoteSelection(provider, flag, OPENAI_API_KEY)`

## 2. 라이브 selectable set (사실 — GET /api/chat/models, 2026-09-27 03:35 UTC)

| id | provider | status | selectable | reason |
|---|---|---|---|---|
| gemma4:12b / 26b / 31b / latest | Ollama | installed | true | — |
| qwen3-vl:8b, qwen3.5:9b, qwen3.6:27b, smtek/Qwen3.8-27B:Q3_K_XL | Ollama | installed | true | — |
| `llmrouter.api3` | groq | configured | **true** | — |
| `llmrouter.mistral-medium` | mistral | unavailable | false | `remote_selection_disabled` |
| `llmrouter.openai-economy` | openai | unavailable | false | `remote_selection_disabled` |

- manifest 행 중 route 미매칭+unverified로 **행 자체가 안 뜸**: gpt-5.5, gpt-5.4-mini, gemini-2.5-pro, claude-current-family, openrouter-discovery(`model_discovery` 필터).
- meta-display 프로필이 프리미엄 route name을 재지정(`openai-premium→gpt-5.6-sol`, `openai-balanced→gpt-5.6-terra`, `gemini-pro→gemini-3.8-flash`) → manifest의 gpt-5.5/5.4-mini/2.5-pro 행과 불일치 → 미표시 (사실: name 매칭 코드 + yml diff 대조).

## 3. 원인 A–E 판정

| ID | 판정 | 근거 |
|---|---|---|
| **A** allowlist 좁음 | **사실·확정** | 라이브 응답 2행이 `remote_selection_disabled`; allowlist 기본값 `api3`만 |
| **B** 설정↔카탈로그 이중 권위 | **사실·확정** | `effectiveAllowRemoteSelection`은 `OPENAI_API_KEY` 존재만으로 true → model-settings는 `app.ai.openai-chat-models`(기본 gpt-5.5 등)를 저장 허용하지만 picker 카탈로그는 `llmrouter.*` id만 인정 + raw flag가 false면 전부 disabled. 둘이 다름 |
| **C** manifest/route 정합 | **사실·부분 수정** | API01 문서의 "Groq 행 disabled"는 **stale** — 현재 manifest `openai/gpt-oss-120b` enabled:true(sha 5a93de31…). premium 행들은 enabled:false 유지 + meta-display 프로필 name 재지정으로 route 미매칭(이중 차단) |
| **D** 맹목 flip 위험 | **사실·확정** | `openai-economy`는 meta-display에서 enabled+OPENAI_API_KEY 존재 시 eligible 후보. 전역 `allow-remote=true`면 allowlist 없이 즉시 selectable 추가 → 승인 외 라우트 동반 개방 증명 필요 |
| **E** 웹 설치 공백 | **사실·확정** | `/api/chat/models*`는 읽기 전용(`recheck`는 expiresAt 리셋 + bounded detail probe, pull 없음). 로컬 pull은 `LocalLlmProcessManager.warmupOnce()` 내부(`/api/pull`, `local-llm.warmup.pull`) + `scripts/ollama-light-preload.ps1` 뿐 |

## 4. Groq 가드 관측 (사실 — 존재/스키마만, 수치 미인용)

`data/usage/groq-free-plan.json` 존재(1307B, mtime 2026-09-27 10:39 KST):
`plan=free`, `verifiedAtMs=2026-09-24T05:11:04Z`, `expiresAtMs=2026-12-23T05:11:04Z`, `coordination=shared_ledger`, `keySha256`/`organizationHash` 필드 존재, `limits`에 3개 published 모델 키.
→ 90일 maxAge 내 → **api3 eligible = 라이브 selectable=true와 일치**. API01 문서의 "expired 2026-09-25"는 갱신 전 관측으로 stale.
가드 bypass 없음; 결제 수치·키 값 미출력.

## 5. manifest ↔ diagnostics reconcile

- `docs/diagnostics/API01-main-selection-20260927.md` (task `api01-main-selection-0927-c9be6137`, HOLD): "Groq manifest row disabled / evidence expired / runtime refused" — **셋 다 stale**: manifest enabled(후속 api01-ttl 태스크), evidence 갱신됨, 현재 18180 LISTEN·api3 selectable.
- `docs/diagnostics/directive-v2-sequential-20260927.md` API-01 행도 동일 사유로 stale — "shared flag admit 추가 라우트" 우려는 유효(D).
- 활성 외계 journal `mxasain-full-force-0927` (codex-full-force-0927, scope `main/java|main/resources|src/test|docs/diagnostics`): API01-03 진행 중, `api01-r2` cycle verified — ChatModelCatalogService/properties/manifest/카탈로그 테스트가 그 postimage와 현재 bytes 일치(본인 sha 대조 확인). 이 태스크는 그 파일들을 재수정 중일 수 있으므로 본 WP는 **새 파일 중심 + catalog 최소 확장만** 수행.

## 6. 현재 gap 요약 (설계 입력)

1. allowlist 확장 경로가 env/재기동뿐 → 런타임 등록 seam 없음
2. catalog↔settings 이중 권위 → "원격 허용" 표시 불일치
3. 웹 설치 endpoint 없음; local pull 공개 메서드 없음(private warmup 전용)
4. catalog 캐시 30s TTL만 존재, install 후 즉시 expire 훅 없음
5. premium manifest 행의 route name ↔ meta-display override 불일치는 별건(설계 문서에 후속 기록)
