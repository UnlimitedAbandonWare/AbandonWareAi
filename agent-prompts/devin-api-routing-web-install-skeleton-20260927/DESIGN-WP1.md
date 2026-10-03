# WP1 Placement 설계 — 웹 설치 → route 등록 (설계+얇은 뼈대)

Task `api-routing-webinst-skeleton-0927-0c420932` · 2026-09-27 · WP0 inventory: `INVENTORY-WP0.md`

## 1. purpose → tier → route 표 (기존 표의 확장)

| purpose | tier | route / seam | 등록 경로 |
|---|---|---|---|
| local 모델 설치 (pull) | free_local | managed Ollama `/api/pull` — `LocalLlmProcessManager`가 소유한 endpoint만 | `POST /api/chat/models/install {target:"local", model}` |
| cloud route 등록 | low_cost 우선 → paid_quality 명시 승인 | `llmrouter.models.<key>`(바인드) + manifest `enabled` + `remote-model-selection-routes`(런타임 allowlist) | `POST /api/chat/models/install {target:"cloud", route}` |

미러: `configs/api-routing.yaml` `routes.model_install` + `docs/API_ROUTING_SPEC.md` §5.

## 2. allowlist 전략 (좁게)

- 전역 `app.ai.allow-remote-model-selection` **flip 금지**. 등록은 **routeKey 단위 add**만.
- 등록 가능 조건(전부 기존 시임 재사용 — `CloudModelRouteClassifier`+`HybridLlmGatewayProbeService`):
  1. manifest 행 ↔ `llmrouter.models.<key>` 매칭 존재
  2. manifest `enabled:true` AND route `cfg.enabled:true`
  3. credential env 존재(이름만 비교, 값 비출력 금지)
  4. provider=groq이면 `GroqFreeTierGuard` reason clean
  → 즉 `classifyDefaultCatalog("chat")` 행이 `eligible && disabledReason==null`일 때만
- 승인되지 않은 라우트(예: `openai-economy`)가 같이 열리지 않음을 `/api/chat/models` delta로 증명 — WP4.
- 지속성: 런타임 등록은 in-memory(재기동 시 env `CHAT_REMOTE_MODEL_SELECTION_ROUTES` 또는 properties 반영이 운영자 단계 — 문서화).

## 3. Meta Display cue lane 분리 (merge 금지)

- cue 생성은 `conversate.cue.routes.*` + `ConversateCueRoutingPolicy` (`application-meta-display.yml`) — 별도 정책면.
- 웹 설치/allowlist는 **메인 채팅 picker** 선택만 변경. cue 라우트 목록·가중치·예산 미터치.
- `openai-economy`처럼 cue 전용 route가 있으므로 전역 flip은 cue 선택도 오염시킴 → routeKey 단위만.

## 4. web-install 흐름

```
POST /api/chat/models/install
├─ target=local, model=<ollama id>
│   ├─ id 형식 검증 (catalog validId 규칙)
│   ├─ LocalLlmProcessManager.managesEndpoint + 이미 설치?(/api/tags)
│   ├─ managed → manager.requestModelPull(model) → 비동기 /api/pull
│   │   → catalog.expireCache() → {status:"pull_started"|"already_installed"|"not_managed"}
│   └─ unmanaged → {status:"not_managed", hint:"scripts/ollama-light-preload.ps1"} (경로명만)
└─ target=cloud, route=<llmrouter key>
    ├─ classifyDefaultCatalog 행 lookup (routeKey 일치)
    ├─ !row → "unknown_route"; !eligible → reason=row.disabledReason||"route_not_eligible"
    └─ eligible → catalog.registerApprovedRoute(route) → cache expire
        → {status:"registered", id:"llmrouter.<route>"}
```

## 5. catalog ↔ settings 정합 방향 (문서화; 최소 코드)

- picker 권위 = `ChatModelCatalogService`(서버 소유). settings(`ModelSettingsService`)는 `effectiveAllowRemoteSelection`(OpenAI 키 존재 시 true)으로 raw 모델 id 허용 → **불일치(B)**.
- 정합 방향: (a) 설정 저장도 카탈로그 `resolve(id)`로 검증하거나 (b) 설정 페이지에 카탈로그 selectable 표시를 미러. 스켈레톤에서는 변경하지 않고 방향만 기록 — settings는 admin 기본값 저장 seam이라 별 태스크.

## 6. 스켈레톤 diff 범위

| 파일 | 변경 |
|---|---|
| `ChatModelCatalogService` | `runtimeAllowedRoutes` Set + `registerApprovedRoute(route)` + `expireCache()` (~15줄) |
| `LocalLlmProcessManager` | `requestModelPull(model)` public — 기존 `postJson("/api/pull")`/executor 재사용 (~35줄) |
| `ModelInstallService` (new) | 분기/검증 orchestration |
| `ChatModelInstallController` (new) | `POST /api/chat/models/install` — no-store, reason-code 응답 |
| `ModelInstallServiceTest` (new) | 등록/거부 단위 증명 |

금지 준수: 새 provider client 없음, 두 번째 catalog/factory 없음, OpenRouter 런타임 추가 없음, YAML 파일 라이트 없음.
