# RUNME_agent.md — src111_merge15 (에이전트 모드용 최소 실행 지시)

목표: GPT pro(에이전트 모드)가 자동으로 **빌드→실행→헬스체크**까지 수행하도록 표준 절차를 제공합니다.

## 0) 환경
- JDK: 17 이상
- 빌드: Gradle Wrapper 동봉(`./gradlew`)
- 모듈: `app`, `lms-core`, `cfvm-raw` (settings.gradle 참조)

## 1) 의존 확인
```bash
./gradlew --version
./gradlew projects
```

## 2) 빌드 & 실행(기본)
```bash
./gradlew :app:assemble
./gradlew :app:bootRun --args='--spring.profiles.active=ultra'
```

> `application-patch.properties`는 **app/src/main/resources/** 에 위치합니다.
> 필요 토글 예: `rerank.dpp.enabled=true`, `onnx.enabled=true`, `fusion.wpm.enabled=true`.

## 3) 기본 헬스/진단 엔드포인트
- `GET /bootstrap` — 세션/상태 진단
- `GET /internal/soak/run?k=3&topic=default` — 소규모 Soak 샘플
- `POST /api/probe/search` — 하이브리드 리트리벌 디버그

## 4) 흔한 실패 원인
- **레거시 경로 사용**: `src/main/resources/...` 대신 `app/src/main/resources/...` 를 사용하세요.
- **중복 리소스 수정**: 동일 키의 또 다른 복사본이 있을 수 있으니 *app 경로*만 수정.

## 5) 안전 실행
- 외부 API 키/시크릿은 환경변수로 주입(예: `UPSTASH_REDIS_URL`, `NAVER_SEARCH_KEY`).
- 웹검색/캐시 토글: `naver.hedge.enabled`, `naver.search.timeout-ms`, `upstash.cache.ttl-seconds`.

(자동화 파이프라인이 이 파일을 읽어 절차를 그대로 수행하면 됩니다.)
