# src111_merge15dw3 — P0/P1 Light Integration

본 패치는 P0(Complexity Classifier · Self‑Ask 3‑Way 라이트 · Fallback/Outbox)와
P1(Time‑Budget · Semaphore Gate · Single‑Flight) 3종을 **비침투형**으로 추가합니다.
주요 구현은 `service/*` 네임스페이스이며, `com.example.lms/*` 체인과의 연결은
선택적(옵셔널 빈) 방식으로 준비되어 있습니다.

## 추가된 주요 경로

- `service/rag/gate/*` — 질의 난이도 분류기
- `service/rag/planner/*` — Self‑Ask 3‑Way 라이트(규칙 기반)
- `service/tools/fallback/*` — 실패 허용 검색 / 아웃박스
- `service/rag/budget/*` — 요청 단위 시간 예산
- `service/rag/concurrency/*` — 세마포어 게이트
- `service/rag/rerank/*` — 재랭커 오케스트레이터(게이트+폴백)
- `service/rag/cache/*` — 싱글‑플라이트(캐시 스탬피드 방지)
- `config/RagP1Config.java` — 제네릭 싱글‑플라이트 빈

## 스프링 설정 (application.yml)

`src/main/resources/application.yml`에 다음 토글이 추가되었습니다.

```yaml
features:
  classifier: { enabled: true }
  selfask: { enabled: true, k-each: 6, k-final: 8 }
  fallback: { enabled: true }
  outbox: { enabled: true }
  timebudget: { enabled: true, default-ms: 6000 }
  reranker:
    semaphore:
      enabled: true
      max-concurrent: 3
      try-acquire-ms: 300
  singleflight: { enabled: true, wait-ms: 1200 }
```

## 선택적 연결(옵셔널 빈)

- `com.example.lms.strategy.RetrievalOrderService`에 경량 훅을 추가했습니다.
  - `maybePlanSelfAsk(String)` 메서드: Classifier가 SIMPLE이 아닌 경우, Self‑Ask 3‑Way를 수행해
    fused 컨텍스트를 리턴(없으면 `null`).
  - 기존 `decideOrder(...)` 동작은 변경하지 않았습니다.

- `AnalyzeWebSearchRetriever`에 제네릭 `SingleFlightExecutor` 필드가 주입될 수 있도록
  선택 필드를 추가했습니다(현재 기본 로직은 그대로).

## 표준 컨텍스트 계약

검색 결과 맵은 `{id,title,snippet,source,score,rank}` 키를 사용합니다.
이 규약은 기존 문서(“표준 컨텍스트 포맷”)와 일치합니다. fileciteturn0file1

## 프롬프트/트레잇 운영

프롬프트 스캐폴드는 **에이전트(system)와 특성(trait)**을 분리 저장 후
매니페스트로 머지하는 구조를 권장합니다(별도 ZIP 참조). 본 패치는 체인 레이어만 다룹니다. fileciteturn0file0

## 빠른 점검

- 단위 테스트(샘플 호출)
  - `new QueryComplexityClassifier().classify("최근 KPI 변경 내역은?", Set.of())`
    → `NEEDS_WEB`
  - `selfAsk.plan("시스템 성과 지표", 4, 6)` → 3분기 SubQuery 생성

- 엔드투엔드(Probe/Soak 재사용)
  - `POST /api/probe/search` 실행 시, `RetrievalOrderService.maybePlanSelfAsk(...)`를
    상위에서 호출하여 subQueries 메타를 SSE/로그로 확인 가능.

## 주의

- `SelfAskPlanner`의 리트리버/퓨저는 **옵셔널 빈** 인터페이스입니다.
  실제 연결은 기존 구현의 어댑터(웹/벡터/융합)로 주입하세요.
  주입이 없을 때도 **컴파일/런타임 에러 없이** 빈 결과로 안전 동작합니다.
