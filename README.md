# Dynamic RAG Orchestration Platform

This checkout is the Desktop canonical root for the Dynamic RAG Orchestration Platform.
The older pack notes below are retained as historical design context.

## Run

- `Start-RAG.bat` — starts the Spring Boot stack (profiles `local,meta-display`, ports `18180`/`18181`/`18182`).
- `Close-RAG.bat` — stops the stack. `Debug-RAG.bat` / `Debug-Meta-Display.bat` — diagnostics.
- `verify_boot.ps1` / `verify_boot.sh` — boot verification.

## Configuration (env names only — real values are never committed)

- Copy `.env.example` to `.env` (gitignored) or export the variables yourself:
  `UPSTASH_REDIS_URL`, `UPSTASH_REDIS_TOKEN`, `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`,
  `BRAVE_API_KEY_FREE`, `BRAVE_API_KEY`, `NAVER_KEYS`, `PINECONE_API_KEY`, `PINECONE_ENV`,
  `PROBE_ADMIN_TOKEN`, `DEEPGRAM_API_KEY`, `DEEPGRAM_API_KEY_SECONDARY`.
- Provider/model routing SSOT: `configs/api-routing.yaml` + `docs/API_ROUTING_SPEC.md`.
- Device-local secret material stays under `.secrets/` (gitignored). A local `apikey.txt`
  may be created when needed — it is gitignored; never commit a real key file.

# Nova Protocol — Zero Break (v0.1) Drop‑in Pack
이 패키지는 **Zero Break / Brave / Safe Autorun** 3종 Plan DSL과,
Spring 기반 RAG 오케스트레이션에 연결되는 핵심 게이트/인터셉터/관리 API 스켈레톤을 제공합니다.
기존 코드 변경 최소화(Decorator & Plan‑DSL) 원칙으로 설계되었습니다.

## 포함물(요약)
- `ops/zerobreak/plans/*.yaml` : 작전 계획(Plan DSL) 3종 — `safe_autorun.v1`, `brave.v1`, `zero_break.v1`
- `ops/zerobreak/policies/rulebreak.policies.yaml` : 룰 완화 정책 매핑
- `config/application-zerobreak.yml` : Spring 프로필용 설정 키
- `prompts/*` : 트레이트/매니페스트 (프롬프트 분리‑머지 운영)
- `src/main/java/com/abandonwareai/zerobreak/*` : 컨텍스트/게이트/인터셉터/어드민 API
- `scripts/integrate.sh` : 통합 체크리스트 (수동 머지 스크립트)

## 빠른 통합(10분 가이드)
1) 프로젝트 루트에 압축을 해제합니다.
2) Spring 프로필에 `zerobreak` 추가 후 실행:
   ```bash
   java -Dspring.profiles.active=zerobreak -jar app.jar
   ```
3) (선택) Plan 강제 지정 헤더
   - `X-Plan-Id: safe_autorun.v1 | brave.v1 | zero_break.v1`
   - 또는 `X-ZeroBreak-Token: <signed>` + `X-ZeroBreak-Policy: recency|max_recall|speed_first|wide_web`
4) 내부 점검 엔드포인트
   - `GET  /internal/zerobreak/plan`    : 로드 가능한 플랜 ID 나열
   - `POST /internal/zerobreak/dry-run` : 게이트·시그모이드 드라이런(실행 없음)

## 설계 원칙
- **Explode → Fuse → Rerank → Gate** 순서 확정.
- 규칙 완화는 *전면 우회 금지*, 반드시 감사 로그/배너 고지·CitationGate≥3·PII Strict·FinalSigmoid≥0.90 동시 적용.
- 기존 체인/핸들러는 유지하고, 본 패키지는 **컨텍스트/플랜/게이트**를 주입하는 *데코레이터*로 동작.

## 참고
- 본 스켈레톤은 외부 의존성 없이 컴파일 가능한 최소 형태로 제공됩니다.
  (실사용 시 기존 모듈: `DynamicRetrievalHandlerChain`, `WeightedRRF`, `OnnxCrossEncoderReranker` 등과 와이어링 필요)
