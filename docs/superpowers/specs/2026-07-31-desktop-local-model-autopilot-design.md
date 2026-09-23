# Desktop Local Model Autopilot 균형형 설계

> **MODEL LOCK (2026-09-17, noted 2026-09-19):** This dated document may reference retired model tags (`qwen3:8b`, `qwen3:30b`, `qwen3-coder:*`, `gemma3:*`, `qwen2.5:7b-instruct`). Do not execute or wire those tags — live SoT: repo `AGENTS.md` model lock + `configs/api-routing.yaml` + `ollama ls`.

**상태:** 균형형 설계와 모델 후보·다운로드 변경안 승인 완료, 구현 미승인  
**승인일:** 2026-07-31  
**변경안 승인일:** 2026-07-31  
**후보 조사일:** 2026-07-31  
**설계 모드:** Balanced / recommend-first  
**실행 소유자:** Desktop  
**Notebook 역할:** 증거 수집 지원과 설계 문서 작성  

## 1. 목적

RTX 3090과 RTX 3060을 사용하는 Desktop이 Ollama 런타임, GPU 메모리, 설치 모델, 실제 응답 품질을 직접 측정하여 역할별 모델을 추천하도록 한다. 다운로드와 기본 모델 승격은 서로 분리한다. 첫 다운로드 묶음은 3060용 `qwen3.5:9b`·`gemma4:12b`, 3090용 `qwen3.6:27b`·`gemma4:31b`로 고정하고, 실측 승자만 역할 binding에 승격한다. 검증을 통과하지 않은 최신 모델이나 브라우저에서 임의로 선택된 `qwen3:8b`가 전역 주 모델로 자동 승격되지 않게 한다.

이 설계에서 “최적”은 최신 모델명이나 파라미터 수가 아니라 다음을 모두 만족하는 상태를 의미한다.

1. 지정된 GPU에서 CPU offload 없이 실행된다.
2. 해당 역할의 품질 기준을 현재 기준선보다 개선하거나 유지한다.
3. 응답 지연과 VRAM 여유가 역할별 한계를 통과한다.
4. 요청별 실제 모델 계보를 검증할 수 있다.
5. 실패 시 기존 모델 binding으로 즉시 복귀할 수 있다.

## 2. 현재 증거

### 2.1 활성 소스 경계

- root main Java: `main/java`
- root main resources: `main/resources`
- `:app` main Java: `app/src/main/java_clean`
- `:app` main resources: `app/src/main/resources`
- `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, 백업과 build 출력은 수정 대상에서 제외한다.

### 2.2 현재 모델 역할

`main/resources/configs/models.manifest.yaml`과 `main/resources/application-llm.yaml`은 이미 역할별 모델을 분리한다.

| 역할 | 현재 기준선 | 기본 endpoint |
| --- | --- | --- |
| 주 채팅 | `gemma4:26b` | RTX 3090 / `127.0.0.1:11434` |
| high | `gemma4:26b` | RTX 3090 / `127.0.0.1:11434` |
| judge | `qwen3:30b` | RTX 3090 / `127.0.0.1:11434` |
| coder | `qwen3-coder:30b` | RTX 3090 / `127.0.0.1:11434` |
| fast/light/rewrite/explore | `qwen3:8b` | RTX 3060 / `127.0.0.1:11435` |
| vision | `qwen3-vl:8b` | RTX 3060 / `127.0.0.1:11435` |
| embedding | `qwen3-embedding:4b` | RTX 3060 / `127.0.0.1:11435` |

따라서 `qwen3:8b`는 현재 주 모델이 아니라 빠른 보조 모델이다. 브라우저 모델 picker는 등록된 모델을 전역 `CurrentModel`로 저장할 수 있지만 GPU 적합성이나 역할별 벤치마크 점수는 판단하지 않는다.

### 2.3 재사용할 자산

- `main/java/com/example/lms/health/GpuHardwareDiagnostics.java`
  - `nvidia-smi` 기반 GPU 이름, 메모리, 사용률, 온도, 전력 진단
- `main/java/com/example/lms/health/GpuGatewayDiagnostics.java`
  - primary/fast/embedding endpoint guard와 preflight
- `main/java/com/example/lms/config/LocalLlmProcessManager.java`
  - Ollama health, autostart, `/api/pull`, `/api/show`, embedding warmup
- `main/java/com/example/lms/manifest/ModelManifestConfig.java`
  - 모델 manifest와 registry 로딩
- `main/java/com/example/lms/service/ModelSettingsService.java`
  - 브라우저 모델 저장 검증 경계
- `scripts/smoke_gpu_gateway_preflight.ps1`
  - dual-GPU gateway와 Desktop profile smoke

새 gateway 프레임워크를 만들지 않고 이 경계들을 확장한다.

### 2.4 Evidence ID

- `E-LOCAL-ROLE-BINDINGS`: 현재 active manifest와 `application-llm.yaml`의 role/endpoint binding.
- `E-LOCAL-GATEWAY-ASSETS`: GPU/Ollama 진단, process manager, model settings 경계와 기존 smoke script.
- `E-OFFICIAL-QWEN`: Ollama 공식 Qwen 3/3.5/3.6 모델 크기, context, digest.
- `E-OFFICIAL-GEMMA`: Ollama 공식 Gemma 4 12B/26B/31B 크기, context, 동일 계열 benchmark.
- `E-OFFICIAL-RUNTIME`: Ollama 공식 GPU, Windows model store, pull/tags/ps/context 문서.
- `E-DESKTOP-RUNTIME`: `evidence_needed`; Desktop GPU UUID/VRAM, Ollama version, 설치 모델, `E:\models` free disk, 실제 benchmark.

## 3. 범위

### 3.1 포함

- Desktop의 GPU 두 개와 드라이버 버전 탐지
- Ollama 11434/11435 endpoint와 설치·실행 모델 탐지
- `E:\models` 모델 저장소 상태 확인
- 역할별 후보 allowlist와 균형형 평가
- 승인 기반 모델 다운로드
- 기준선 대비 A/B 검증
- 검증된 추천 결과의 UI 표시
- opt-in 자동 승격과 자동 rollback
- redacted TraceStore와 진단 증거

### 3.2 제외

- Notebook에서 Desktop 설정 변경
- GPU 드라이버 자동 설치 또는 업데이트
- 모델 자동 삭제
- 임베딩 모델과 벡터 차원 변경
- 공개 API, DB schema, credential 이름 변경
- Ollama Cloud 자동 활성화
- `latest` 태그를 최신이라는 이유만으로 자동 승격
- 두 GPU를 하나의 VRAM 풀로 간주하는 용량 계산

## 4. 선택한 접근법

역할 기반 Model Autopilot을 사용한다.

### 4.1 대안과 선택 이유

1. 정적 일괄 교체
   - 구현은 단순하지만 3060 fast 역할과 3090 quality 역할을 혼합한다.
   - 동일 모델이 주 채팅, 재작성, 코딩, 비전, 임베딩을 모두 담당하게 될 위험이 있다.

2. 역할 기반 recommend-first Autopilot
   - 현재 dual endpoint와 manifest를 유지한다.
   - 후보를 설치·검증한 뒤 역할별로 승격한다.
   - 이 설계에서 선택한 방식이다.

3. 지속적 자동 최신화
   - 주기적인 registry 탐색과 자동 pull을 수행할 수 있지만 moving tag, 디스크 증가, 회귀 위험이 크다.
   - 초기 범위에서 제외하며 recommend-first가 안정화된 뒤 별도 설계로 다룬다.

## 5. 논리 구조

```text
Desktop Evidence Collector
  -> GPU Inventory
  -> Ollama Endpoint Inventory
  -> Installed/Running Model Inventory
  -> Candidate Admission
  -> Role Benchmark
  -> Recommendation Snapshot
  -> Approved Pull
  -> Trial Binding
  -> Promotion or Rollback
  -> Browser Status
```

### 5.1 Desktop Evidence Collector

읽기 전용으로 다음을 수집한다.

- GPU name, UUID, total/free VRAM, driver version
- Ollama version
- 11434와 11435의 `/api/version`, `/api/tags`, `/api/ps`
- 설치 모델 name, digest, size, parameter size, quantization
- 실행 모델 `size_vram`, context length, GPU/CPU processor 비율
- `E:\models` 존재, 쓰기 가능, 여유 공간
- 사용자 범위 `OLLAMA_MODELS`가 선언 경로와 일치하는지 여부

원시 backing path나 전체 환경 변수는 출력하지 않는다. 모델 저장소는 `modelStoreConfigured=true|false`, `modelStoreExists=true|false`, `freeSpaceGiB`만 공개한다.

### 5.2 GPU Lane Resolver

- RTX 3090 lane: primary, high, judge, coder
- RTX 3060 lane: fast, rewrite, explore, vision, embedding
- GPU 선택에는 숫자 index보다 `nvidia-smi -L`에서 얻은 UUID를 사용한다.
- 이미 건강한 endpoint가 있으면 재사용한다.
- 누락된 endpoint만 시작한다.
- 두 endpoint의 포트나 프로세스 소유자를 구분하지 못하면 `endpoint-collision`으로 중지한다.
- 각 Ollama server는 자신의 `CUDA_VISIBLE_DEVICES`에 하나의 GPU UUID만 가진다.

단일 Ollama server가 모델을 어느 GPU에 놓을지 추정하여 역할을 라우팅하지 않는다. dual endpoint가 확인되지 않으면 자동 승격을 수행하지 않는다.

### 5.3 Candidate Catalog

초기 균형형 후보는 공식 Ollama registry에서 조사한 명시 태그만 허용한다. 공식 페이지의 파라미터 수나 최대 context는 입장 조건일 뿐, 실제 품질·VRAM 적합성의 통과 증거가 아니다.

| GPU/역할 | 기준선 | 1순위 후보 | 품질 비교 후보 | 공식 패키지/최대 context | 초기 trial context |
| --- | --- | --- | --- | --- | --- |
| RTX 3060 fast/rewrite/explore | `qwen3:8b` | `qwen3.5:9b` | `gemma4:12b` | 6.6GB/256K, 7.6GB/256K | 각 8K, 여유 통과 시 16K |
| RTX 3090 main/high | `gemma4:26b` | `qwen3.6:27b` | `gemma4:31b` | 17GB/256K, 20GB/256K | 27B는 16K→32K, 31B는 8K |
| RTX 3090 coder | `qwen3-coder:30b` | `qwen3.6:27b` | 현재 기준선 유지 | 17GB/256K | 16K, 코드 task 기준 비교 |
| RTX 3060 vision | `qwen3-vl:8b` | 기존 기준선 유지 | 위 fast 후보를 임의 재사용하지 않음 | 별도 평가 | 현재값 유지 |
| RTX 3060 embedding | `qwen3-embedding:4b` | 후보 없음 | 모든 자동 교체 제외 | 차원 불변 | 현재값 유지 |

3060의 우선 후보는 `qwen3.5:9b`다. 현재 `qwen3:8b`의 5.2GB/40K보다 공식 패키지는 6.6GB로 소폭 크지만 256K 계열이며, direct replacement로 시험하기 적당하다. `gemma4:12b`는 7.6GB라서 더 무겁지만 같은 GPU에서 품질 우위를 확인하는 challenger로 둔다. 어느 모델도 이름만으로 우승시키지 않는다.

3090의 우선 후보는 `qwen3.6:27b`다. `gemma4:31b`는 20GB이고 Gemma 4 공식 동일 계열 비교에서 기존 26B보다 여러 지표가 높아 품질 challenger 가치가 있다. 다만 24GB VRAM에서 KV cache와 Windows 여유가 작으므로 8K context에서 시작하고, free VRAM 2GiB와 100% GPU 적재를 동시에 통과할 때만 main 후보로 인정한다.

다음 모델은 첫 다운로드 묶음에서 제외한다.

- `qwen3.6:35b`: 공식 패키지 24GB라서 3090의 context/KV cache 여유를 남기기 어렵다.
- `qwen3-coder-next`: Q4 패키지가 약 52GB라서 단일 3090 lane 대상이 아니다.
- `ministral-3:14b`: 9.1GB/256K의 유망한 3060 후보지만 Ollama runtime 버전 확인이 필요한 2차 후보로 둔다.
- `gpt-oss:20b`: 이번 사용자의 Qwen/Gemma 세대교체 목표에서 비교 축을 좁히기 위해 첫 wave에서 제외한다.

첫 구현에서 내려받을 허용 목록은 정확히 다음 네 태그다.

1. `qwen3.5:9b` — 3060 우선 후보
2. `gemma4:12b` — 3060 품질 challenger
3. `qwen3.6:27b` — 3090 우선 후보
4. `gemma4:31b` — 3090 품질 challenger

2026-07-31 공식 registry 조사 시 digest prefix는 각각 `6488c96fa5fa`, `4eb23ef187e2`, `a50eda8ed977`, `6316f0629137`이다. 구현 시 `scripts/config/desktop-ollama-model-candidates.json`에 source URL과 함께 pin한다. registry의 동일 태그 digest가 달라지면 자동 수용하지 않고 `candidate-digest-changed`로 중지하여 catalog 검토를 요구한다.

후보 catalog는 다음 메타데이터를 가진다.

- exact model tag
- official source URL
- allowed roles
- expected download size range
- minimum free disk
- minimum free VRAM headroom
- trial context length
- benchmark profile
- current state: absent, installed, admitted, rejected, recommended, promoted
- rejection reason

모델 registry 웹 검색을 런타임 필수 경로로 만들지 않는다. 공식 후보 목록 업데이트는 별도의 관리 작업이다.

## 6. 다운로드와 모델 저장소

### 6.1 기본 정책

- 기본값은 `recommend-only`다.
- `approved-pull`은 사용자가 Desktop 명령에서 후보 하나 또는 명시된 first-wave 묶음을 승인한 경우에만 동작한다.
- `auto-promote`는 별도 feature flag가 켜지고 benchmark가 통과한 경우에만 동작한다.
- pull은 전역 단일 lease로 직렬화한다.
- pull 전에 예상 크기보다 충분한 여유 공간이 있는지 확인한다.
- pull 후 `/api/tags` 또는 `/api/show`에서 tag, digest, size를 다시 읽는다.
- 검증 실패나 취소 시 현재 binding을 변경하지 않는다.
- 기존 모델을 자동 삭제하지 않는다.

### 6.2 `E:\models` 계약

`E:\models`는 사용자가 지정한 Desktop 모델 저장소 후보다.

1. Desktop에서 경로 존재, 쓰기 가능, free space를 확인한다.
2. 사용자 범위 `OLLAMA_MODELS`를 내부적으로 비교한다.
3. 값이 다르면 환경 변수를 변경하지 않고 `model-store-mismatch`로 보고한다.
4. 사용자가 지속 설정을 승인하면 사용자 범위 `OLLAMA_MODELS=E:\models`를 설정한다.
5. 실행 중인 Ollama를 종료하고 재시작한 뒤 `/api/version`과 `/api/tags`를 확인한다.
6. 두 server가 동일 저장소를 사용하는 경우 pull lease와 post-pull digest 검증이 모두 있어야 한다.

공유 저장소의 동시 읽기·pull 안전성을 증명할 수 없으면 자동 pull을 중지한다. 이 경우 별도 저장소를 임의 생성하지 않고 사용자의 선택을 요청한다.

### 6.3 첫 다운로드 묶음

Desktop preflight와 구현 승인이 모두 통과하면 네 후보를 전역 lease 아래 한 번에 하나씩 내려받는다. 논리적 패키지 합계는 약 51.2GB이며 shared blob 재사용 여부를 보수적으로 가정하지 않는다. 첫 pull 전에 `E:\models`에 최소 65GB의 여유 공간이 없으면 전체 묶음을 시작하지 않는다.

다운로드 순서는 작은 모델부터 `qwen3.5:9b` → `gemma4:12b` → `qwen3.6:27b` → `gemma4:31b`로 한다. 각 pull 직후 tag·digest·size를 검증하고, 하나가 실패하면 다음 모델을 받지 않는다. 성공한 모델은 보존하지만 아직 기본값으로 바꾸지 않는다.

### 6.4 PowerShell 도구 계약

새 기능은 하나의 거대 스크립트가 아니라 네 개의 경계가 분리된 Desktop 도구와 한 개의 secret-free catalog로 구현한다.

| 파일 | mutation | 핵심 입력 | 출력/timeout |
| --- | --- | --- | --- |
| `scripts/config/desktop-ollama-model-candidates.json` | 없음 | exact tag, source URL, digest prefix, package GB, GPU lane, trial context | 정적 allowlist; credential 금지 |
| `scripts/desktop_ollama_model_preflight.ps1` | 없음 | `-PrimaryEndpoint`, `-FastEndpoint`, `-ModelsRoot`, `-OutputPath` | redacted inventory JSON; 60초 |
| `scripts/desktop_ollama_model_download.ps1` | 모델 저장소에 approved pull | `-ModelTag`, `-Endpoint`, `-ModelsRoot`, `-ApproveDownload`, `-OutputPath` | pull report와 검증 hash; 모델당 7,200초 |
| `scripts/desktop_ollama_model_benchmark.ps1` | 일시적 model load만 | `-CatalogPath`, `-InventoryPath`, `-OutputPath` | 전체 role recommendation report; 후보당 1,800초 |
| `scripts/desktop_ollama_model_promote.ps1` | 사용자 범위 role binding | `-RecommendationReport`, `-ApprovePromotion`, `-OutputPath` | before/after hash와 rollback report; 300초 |

공통 계약은 다음과 같다.

- `-WhatIf`와 비대화형 실행을 지원한다.
- model tag는 catalog exact match만 허용하고 `latest`, 자유 문자열, shell interpolation을 거부한다.
- endpoint는 기본 loopback allowlist `127.0.0.1:11434|11435`만 허용한다.
- download는 `/api/pull`의 stream status를 bounded progress로 집계하며 raw response 전체를 저장하지 않는다.
- lease, timeout, 취소, 부분 성공 상태를 명시적으로 기록한다.
- output은 count, boolean, digest, size, timing, reason code만 포함하며 전체 환경 변수와 원시 prompt/response를 금지한다.
- promotion은 `LLM_FAST_MODEL`, `LLM_CHAT_MODEL`, `LLM_HIGH_MODEL`, 선택적으로 `LLM_CODER_MODEL`만 사용자 범위에서 변경한다. machine 범위, DB, credential, embedding 설정은 건드리지 않는다.
- 이전 사용자 범위 binding을 rollback snapshot으로 남기고, 실패 시 즉시 복원한다.
- 다운로드 모델 삭제는 어떤 스크립트에도 넣지 않는다.

preflight는 Ollama version, GPU UUID/VRAM/driver, 두 endpoint의 version/tags/ps, model store boolean, free disk를 한 번 수집한다. download는 catalog와 digest를 책임진다. benchmark는 catalog의 후보와 기준선을 작은 모델부터 직렬로 불러 동일 prompt/options에서 실제 role 품질과 100% GPU 적재를 비교하고 하나의 recommendation report를 만든다. promote는 그 검증된 report만 소비한다. 이 경계를 합쳐 우회하는 `-Force` 옵션은 제공하지 않는다.

## 7. 역할별 평가

### 7.1 공통 hard gate

다음 중 하나라도 실패하면 후보 점수를 계산하지 않고 탈락시킨다.

- 명시 태그가 설치되지 않았고 pull 승인이 없음
- `/api/show` 또는 `/api/tags` digest 확인 실패
- 지정 lane endpoint가 건강하지 않음
- target context로 preload 실패
- `ollama ps`에서 CPU offload 발견
- 지정 GPU의 최소 VRAM headroom 미달
- 빈 응답, 비정상 JSON, tool schema 위반
- timeout 또는 server restart
- request model과 response model 계보 불일치

### 7.2 균형형 점수

주 채팅 후보 점수는 다음 가중치를 사용한다.

```text
balancedMainScore = 100 * (
  0.35 * koreanRagQuality
  + 0.20 * instructionFollowing
  + 0.15 * codingAndToolUse
  + 0.10 * factualityAndCitationDiscipline
  + 0.10 * latencyEfficiency
  + 0.05 * vramHeadroom
  + 0.05 * runtimeStability
)
```

fast lane은 다음 가중치를 사용한다.

```text
balancedFastScore = 100 * (
  0.30 * rewriteAndExtractionQuality
  + 0.30 * latencyEfficiency
  + 0.15 * instructionFollowing
  + 0.10 * toolAndStructuredOutput
  + 0.10 * vramHeadroom
  + 0.05 * runtimeStability
)
```

coder lane은 repository-scale code task pass rate를 50%, tool/structured output을 20%, latency를 15%, VRAM headroom을 10%, stability를 5%로 계산한다.

모든 입력은 0.0부터 1.0이고 동일 prompt/options, 동일 context, 동일 timeout에서 측정한다.

### 7.3 승격 조건

주 채팅 후보는 다음을 모두 만족해야 한다.

- 기준선 대비 balancedMainScore가 5점 이상 높음
- 한국어/RAG 품질이 기준선보다 낮지 않음
- 요청 성공률 100%
- p95 latency가 기준선의 1.5배 이하
- trial 종료 시 RTX 3090 free VRAM 2GiB 이상
- 100% GPU 적재

fast 후보는 다음을 모두 만족해야 한다.

- balancedFastScore가 기준선 이상
- rewrite/extraction 품질이 기준선보다 낮지 않음
- p95 latency가 기준선 이하
- trial 종료 시 RTX 3060 free VRAM 1.5GiB 이상
- 100% GPU 적재

coder 후보는 코드 검증 task pass rate가 기준선보다 높아야 한다. 승자가 없으면 현재 binding을 유지하며, 이는 성공적인 `keep-baseline` 판정이다.

### 7.4 계획된 role binding

다운로드 완료는 승격 조건이 아니다. Desktop A/B 검증 후 다음 우선순위로 binding을 결정한다.

| 환경 변수/역할 | 1차 목표 | 대안 | rollback |
| --- | --- | --- | --- |
| `LLM_FAST_MODEL` / fast·rewrite·explore | `qwen3.5:9b` | `gemma4:12b`가 fast score에서 이길 때만 대체 | `qwen3:8b` |
| `LLM_CHAT_MODEL` / main | `qwen3.6:27b` | `gemma4:31b`가 품질 + VRAM gate를 모두 이길 때만 대체 | `gemma4:26b` |
| `LLM_HIGH_MODEL` / high | main 승자 | 역할별 품질 차이가 있으면 기존 `gemma4:26b` 유지 | `gemma4:26b` |
| `LLM_CODER_MODEL` / coder | `qwen3.6:27b`와 현 기준선 비교 승자 | 동점 또는 회귀 시 변경 없음 | `qwen3-coder:30b` |

`qwen3:8b`는 새 3060 후보가 통과한 뒤 삭제하지 않고 rollback/legacy fallback으로 강등한다. 설치되었다는 이유만으로 Browser의 일반 선택이나 stale `CurrentModel` 값이 main/high 역할을 덮어쓰지 못하게 한다.

모델 페이지의 256K는 아키텍처 최대치이지 이 하드웨어에서의 운영 기본값이 아니다. 각 후보는 표의 초기 context로 시작하고 `/api/ps`의 100% GPU 적재와 free VRAM gate가 유지될 때만 한 단계 높인다. 자동으로 256K까지 늘리지 않는다.

## 8. Browser 동작

Browser는 모델 최적화 결정을 직접 만들지 않는다. 서버가 생성한 Recommendation Snapshot만 표시한다.

모델 picker 각 행에는 다음을 표시한다.

- model tag
- installed 여부
- assigned role
- target GPU
- current/recommended/trial 상태
- benchmark score와 기준선 대비 delta
- rejection 또는 disabled reason
- download 크기

초기 구현에서 Browser는 읽기 전용 추천 상태만 표시한다. 공개 mutation API를 새로 만들지 않는다. 운영 동작은 인증된 Desktop 명령으로 다음과 같이 분리한다.

- `Approved pull`: 후보를 다운로드하지만 기본값을 바꾸지 않는다.
- `Trial`: bounded benchmark에만 후보 binding을 적용한다.
- `Promote`: benchmark 통과 후보만 role binding으로 승격한다.
- `Force select`: 초기 범위에서 제공하지 않는다.

향후 Browser 버튼이 필요하면 인증·CSRF·권한·감사 계약을 포함하는 별도 공개 API 변경 승인을 받아야 한다.

`qwen3:8b`가 fast-only catalog 항목인 상태에서 일반 모델 선택 요청만으로 main binding이 바뀌어서는 안 된다. 기존 `/api/settings/model` 공개 signature는 변경하지 않으며, role mismatch guard는 opt-in observe 모드에서 시작한다.

## 9. 런타임 계보와 관측성

UI에 모델명이 보이거나 prompt build가 성공한 것만으로 실제 모델 사용을 증명하지 않는다.

각 benchmark 및 실제 요청은 다음 redacted 필드를 남긴다.

- requestId 또는 traceId
- role
- requestedModelHash와 길이
- selectedModelHash와 길이
- endpoint host/port allowlisted label
- GPU lane
- promptHash
- optionsHash
- attempt sequence
- response model hash
- status와 failureClass
- latencyMs
- input/output token count
- processor class: gpu, cpu_gpu_split, cpu, unknown
- context length
- promotion decision과 reason

원시 prompt, 응답 본문, 모델 저장 경로, 환경 변수 값, credential은 TraceStore와 UI에 기록하지 않는다.

`promptHash + optionsHash + attempt/response row` 중 하나라도 없으면 `runtimeLineageVerdict=HOLD`다.

## 10. 실패 처리

| failureClass | 결과 |
| --- | --- |
| `desktop-gpu-inventory-missing` | 추천과 pull 중지 |
| `unsupported-driver` | 기존 모델 유지, driver 수동 조치 안내 |
| `ollama-runtime-unavailable` | endpoint 시작을 시도하지 않고 autostart 정책 확인 |
| `endpoint-collision` | 모든 mutation 중지 |
| `model-store-mismatch` | 환경 변수와 pull 중지 |
| `insufficient-disk` | pull 거부 |
| `pull-lease-conflict` | 중복 pull 거부 |
| `pull-failed` | 후보 absent/rejected, binding 유지 |
| `digest-mismatch` | 후보 quarantine, 승격 금지 |
| `candidate-digest-changed` | catalog 업데이트 전 pull 금지 |
| `insufficient-vram` | 후보 탈락 |
| `cpu-offload-detected` | 후보 탈락 |
| `context-headroom-failed` | 더 낮은 context로 한 번만 재시험 후 탈락 |
| `benchmark-regression` | 기준선 유지 |
| `role-mismatch` | save 거부 또는 명시적 force 확인 |
| `lineage-missing` | 자동 승격 금지 |

fail-soft는 기존 모델로 응답 가능한 경우에만 적용한다. 잘못된 모델을 정상 성공으로 표시하지 않는다.

## 11. 보안과 권한

- endpoint는 기본적으로 loopback만 사용한다.
- remote endpoint는 기존 `LocalLlmGatewaySecurity`와 owner-token 정책을 유지한다.
- 모델 다운로드에는 API key를 로그로 남기지 않는다.
- 전체 환경 변수 dump를 금지한다.
- persistent 사용자 환경 변수 변경은 별도 사용자 승인 후 Desktop만 수행한다.
- machine 범위 환경 변수는 변경하지 않는다.
- DB schema와 credential property는 변경하지 않는다.
- LangChain4j dependency는 `1.0.1`을 유지한다.
- embedding model과 index dimension은 변경하지 않는다.

## 12. 검증 전략

### 12.1 Desktop read-only preflight

```powershell
nvidia-smi --query-gpu=name,uuid,memory.total,memory.free,driver_version --format=csv,noheader,nounits
ollama --version
ollama list
Invoke-RestMethod http://127.0.0.1:11434/api/version
Invoke-RestMethod http://127.0.0.1:11434/api/tags
Invoke-RestMethod http://127.0.0.1:11434/api/ps
Invoke-RestMethod http://127.0.0.1:11435/api/version
Invoke-RestMethod http://127.0.0.1:11435/api/tags
Invoke-RestMethod http://127.0.0.1:11435/api/ps
```

성공 조건:

- RTX 3090과 RTX 3060이 각각 한 번 탐지된다.
- driver가 현재 Ollama NVIDIA 요구 조건을 만족한다.
- 두 endpoint가 건강하거나 누락 사유가 분명하다.
- 설치 모델은 tag, digest, size를 가진다.
- 실행 모델은 processor와 context 정보를 가진다.

### 12.2 계획된 Desktop 스크립트 검증

다음 명령은 스크립트 구현과 사용자의 다운로드 실행 승인이 끝난 뒤 Desktop canonical root에서 실행한다. 먼저 `-WhatIf`와 preflight를 통과하고, 그 다음 네 pull을 순차 실행한다.

```powershell
$evidenceRoot = Join-Path (Get-Location) 'data\agent-handoff\model-autopilot'
New-Item -ItemType Directory -Force -Path $evidenceRoot | Out-Null

powershell -NoProfile -File .\scripts\desktop_ollama_model_preflight.ps1 `
  -PrimaryEndpoint 'http://127.0.0.1:11434' `
  -FastEndpoint 'http://127.0.0.1:11435' `
  -ModelsRoot 'E:\models' `
  -OutputPath (Join-Path $evidenceRoot 'preflight.json')

$pulls = @(
  @{ Tag = 'qwen3.5:9b'; Endpoint = 'http://127.0.0.1:11435' },
  @{ Tag = 'gemma4:12b'; Endpoint = 'http://127.0.0.1:11435' },
  @{ Tag = 'qwen3.6:27b'; Endpoint = 'http://127.0.0.1:11434' },
  @{ Tag = 'gemma4:31b'; Endpoint = 'http://127.0.0.1:11434' }
)

foreach ($pull in $pulls) {
  powershell -NoProfile -File .\scripts\desktop_ollama_model_download.ps1 `
    -ModelTag $pull.Tag `
    -Endpoint $pull.Endpoint `
    -ModelsRoot 'E:\models' `
    -ApproveDownload `
    -OutputPath (Join-Path $evidenceRoot ("download-{0}.json" -f ($pull.Tag -replace '[:.]', '-')))
  if ($LASTEXITCODE -ne 0) { throw "model-download-failed" }
}

powershell -NoProfile -File .\scripts\desktop_ollama_model_benchmark.ps1 `
  -CatalogPath .\scripts\config\desktop-ollama-model-candidates.json `
  -InventoryPath (Join-Path $evidenceRoot 'preflight.json') `
  -OutputPath (Join-Path $evidenceRoot 'recommendation.json')

powershell -NoProfile -File .\scripts\desktop_ollama_model_promote.ps1 `
  -RecommendationReport (Join-Path $evidenceRoot 'recommendation.json') `
  -WhatIf `
  -OutputPath (Join-Path $evidenceRoot 'promote-whatif.json')
```

추천 report 검토와 별도 승격 승인이 끝난 경우에만 마지막 명령의 `-WhatIf`를 `-ApprovePromotion`으로 바꾸어 실행한다.

### 12.3 단위·계약 테스트

기존 focused tests를 확장한다.

PowerShell 도구에는 Pester 기반 계약 테스트를 추가한다.

- catalog에 네 exact tag와 공식 source URL이 있는지 확인
- unlisted tag, `latest`, digest 변경, 65GB 미만 free disk가 fail-closed인지 확인
- `-WhatIf`에서 모델 저장소·환경 변수를 변경하지 않는지 확인
- download lease 충돌과 timeout이 bounded reason code를 내는지 확인
- benchmark report에 원시 prompt/response가 없고 lineage hash가 있는지 확인
- promote 실패가 이전 사용자 범위 binding을 복원하는지 확인

- `GpuHardwareDiagnosticsTest`
  - UUID, driver, free VRAM parsing과 redaction
- `GpuGatewayDiagnosticsTest`
  - tags/ps 상태, endpoint collision, partial availability
- `LocalLlmProcessManagerTest`
  - approved pull, serialized lease, no auto-delete
- `LocalModelConfigYamlTest`
  - exact tag, role, endpoint, embedding 불변성
- `ModelSettingsServiceRedactionContractTest`
  - role mismatch와 model identifier redaction
- `ModelSettingsControllerTraceTest`
  - 공개 error payload에 raw model/path가 없는지 확인

### 12.4 Gradle 검증

Desktop host-local cache와 split output을 사용한다.

```powershell
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop'
$projectCache = Join-Path $env:LOCALAPPDATA 'AbandonWareX\gradle-project-cache\model-autopilot'
.\gradlew.bat --project-cache-dir $projectCache test --tests "com.example.lms.health.GpuHardwareDiagnosticsTest" --tests "com.example.lms.health.GpuGatewayDiagnosticsTest" --tests "com.example.lms.config.LocalLlmProcessManagerTest" --tests "com.example.lms.manifest.LocalModelConfigYamlTest" --tests "com.example.lms.service.ModelSettingsServiceRedactionContractTest" --tests "com.example.lms.api.ModelSettingsControllerTraceTest"
.\gradlew.bat --project-cache-dir $projectCache compileJava
.\gradlew.bat --project-cache-dir $projectCache :app:classes
.\gradlew.bat --project-cache-dir $projectCache bootJar
```

### 12.5 Runtime smoke

1. 현재 기준선으로 benchmark를 실행한다.
2. 후보를 pull하되 binding은 유지한다.
3. 후보를 target context로 preload한다.
4. `/api/ps`에서 100% GPU와 VRAM headroom을 확인한다.
5. 동일 benchmark를 실행한다.
6. promotion decision과 reason을 기록한다.
7. 추천 후보에 bounded trial을 적용한다.
8. request/response lineage를 확인한다.
9. 조건을 통과하면 승격하고, 실패하면 기존 binding을 복원한다.

## 13. 단계적 출시

### Phase 0: Evidence only

- GPU, endpoint, model store, 설치 모델 inventory만 생성한다.
- source, 환경 변수, 모델 저장소를 변경하지 않는다.

### Phase 1: Recommend only

- 후보 admission과 benchmark를 수행한다.
- UI에 추천과 거부 사유를 표시한다.
- pull과 binding 변경은 하지 않는다.

### Phase 2: Approved pull

- 구현 승인과 Desktop preflight를 통과한 첫 wave 네 태그만 받는다.
- 전역 lease 아래 작은 모델부터 한 번에 하나씩 pull한다.
- 매 모델마다 digest와 저장소 상태를 검증하며 첫 실패에서 묶음을 중지한다.
- 기본 binding은 유지한다.

### Phase 3: Trial and manual promote

- bounded trial과 A/B 평가를 실행한다.
- 통과 후보에만 수동 Promote를 허용한다.

### Phase 4: Opt-in auto promote

- 안정화된 benchmark와 lineage가 모두 존재할 때만 활성화한다.
- 첫 회귀 신호에서 자동으로 기존 binding으로 복귀한다.

각 Phase는 독립적으로 끌 수 있어야 하며, 다음 Phase가 실패해도 이전 Phase의 read-only 진단은 유지한다.

## 14. Rollback

승격 전 다음 snapshot을 저장한다.

- role별 기존 model tag
- endpoint binding
- feature flag
- benchmark baseline hash
- 현재 모델 row의 redacted identifier hash

rollback은 다음 순서로 수행한다.

1. auto-promote를 끈다.
2. 이전 role binding을 복원한다.
3. 현재 모델 선택을 이전 검증 모델로 복원한다.
4. endpoint health와 request lineage를 재검증한다.
5. 새로 다운로드한 모델은 보존하며 자동 삭제하지 않는다.

환경 변수 rollback은 변경 전 사용자 범위 값으로 복원한다. 기존 값이 없었다면 해당 사용자 범위 변수만 제거한다.

## 15. 완료 조건

다음이 모두 충족되면 구현이 완료된 것으로 판단한다.

- Desktop이 RTX 3090/3060을 UUID와 VRAM으로 구분한다.
- 11434/11435의 실행 소유와 모델 inventory가 확인된다.
- `E:\models` 상태가 boolean과 free-space 증거로 기록된다.
- 3060에서는 `qwen3.5:9b`와 `gemma4:12b`, 3090에서는 `qwen3.6:27b`와 `gemma4:31b`가 catalog digest로 다운로드·검증된다.
- `qwen3:8b`는 새 3060 후보가 통과하면 rollback/legacy fallback으로 강등되고 일반 Browser 동작만으로 main에 승격되지 않는다.
- 3090 main과 coder, 3060 fast 모델이 독립적으로 추천된다.
- pull은 승인·lease·disk·digest gate를 통과한다.
- promotion은 benchmark·VRAM·lineage gate를 통과한다.
- embedding 모델과 차원이 변하지 않는다.
- focused tests, compileJava, `:app:classes`, bootJar가 통과한다.
- Desktop runtime smoke가 실제 provider/model 계보를 입증한다.

## 16. GoalContract

```text
goalId: desktop-local-model-autopilot-v2
rewrittenUserIntent: RTX 3060/3090 Desktop이 공식 allowlist의 새 모델 네 개를 E:\models에 승인 기반으로 내려받고 실측 승자를 역할별 기본 모델로 승격한다.
desiredOutcome: 3060의 qwen3:8b를 qwen3.5:9b 또는 gemma4:12b 승자로 교체하고, 3090의 main/coder 기준선을 qwen3.6:27b 또는 gemma4:31b와 검증 비교한다.
measurableSuccess: dual GPU와 endpoint 탐지, model store 확인, 네 후보 tag/digest 검증, 역할별 A/B 판정, promotion 또는 keep-baseline reason 기록.
nonGoals: GPU driver 자동 업데이트, 모델 자동 삭제, embedding 교체, cloud 자동 활성화.
authorizedMutationSurface: 구현 승인 후 Desktop canonical source와 사용자 승인된 모델 저장소 및 사용자 범위 Ollama 환경 변수.
prohibitedSurface: Notebook application-source write, DB schema, credential, public API, embedding index.
evidenceBaseline: active sourceSets와 현재 role binding은 Y:\ 파일 증거로 확인; 새 후보 크기·digest는 2026-07-31 공식 Ollama registry로 확인; Desktop runtime inventory는 미확인.
assumptions: Desktop에 RTX 3060 12GB와 RTX 3090 24GB가 있고 11435/11434 endpoint가 각각 해당 GPU UUID로 고정될 수 있음.
constraints: exact tag allowlist, no CPU offload, 3060 free VRAM 1.5GiB, 3090 free VRAM 2GiB, no auto-delete, no public API/DB/credential/embedding mutation.
verificationOwner: Desktop
verificationCommands: section 12.2 exact Desktop script matrix -> section 12.4 focused Gradle tests/compileJava/:app:classes/bootJar -> section 12.5 runtime smoke.
rollback: 이전 role binding과 환경 변수 snapshot 복원.
stopConditions: endpoint collision, store mismatch, 65GB 미만 free disk, disk/VRAM 부족, CPU offload, digest 변경/불일치, benchmark regression, lineage missing.
timeBudgetMinutes: 240 hard cap
goalScore: 72.5
verdict: APPLY for design; implementation and Desktop final proof remain gated.
evidence_needed: Desktop GPU/Ollama/model inventory and role benchmark.
```

## 17. SourceDirective

```text
directiveId: desktop-local-model-autopilot-v2-directive
sourceOwner: desktop
provenRoot: Desktop must re-prove C:\AbandonWare\demo-1\demo-1\src before mutation.
provenBranch: Desktop evidence required; Notebook supporting HEAD is main.
activeSourceSets: main/java, main/resources, app/src/main/java_clean, app/src/main/resources.
targetFiles: scripts/config/desktop-ollama-model-candidates.json; scripts/desktop_ollama_model_preflight.ps1; scripts/desktop_ollama_model_download.ps1; scripts/desktop_ollama_model_benchmark.ps1; scripts/desktop_ollama_model_promote.ps1; main/java/com/example/lms/health/GpuHardwareDiagnostics.java; main/java/com/example/lms/health/GpuGatewayDiagnostics.java; main/java/com/example/lms/config/LocalLlmProcessManager.java; main/resources/configs/models.manifest.yaml; main/resources/application-desktop-gpu-node.yml; main/java/com/example/lms/service/ModelSettingsService.java; main/java/com/example/lms/web/PageController.java; scripts/smoke_gpu_gateway_preflight.ps1; corresponding existing focused tests. A new recommendation-policy owner file remains evidence_needed until the implementation plan revalidates the live call path.
callPathOrBoundary: Desktop evidence -> admission -> benchmark -> recommendation -> approved pull -> trial -> promotion/rollback -> Browser status.
beforeBehavior: Browser may persist a registered model without GPU-fit or role-score comparison.
afterBehavior: server-side role policy and Desktop evidence gate recommendation, pull, and promotion.
excludedFilesAndMirrors: inactive source trees, archives, generated outputs, unrelated RAG and embedding implementations.
publicApiChange: forbidden
secretMutation: forbidden
redTest: unlisted/latest tag, changed digest, missing GPU/endpoint/store/free-disk/VRAM/lineage evidence produces no pull or promotion.
greenTest: four approved exact tags download serially with matching digest; each admitted winner runs 100% on the assigned GPU, beats its role baseline, preserves lineage, and promotes reversibly.
exactVerificationCommands: execute the no-placeholder command matrix in section 12.2, then the exact Gradle commands in section 12.4; replace -WhatIf with -ApprovePromotion only after explicit promotion approval.
expectedEvidence: counts, booleans, hashes, timing, VRAM headroom, role score delta, reason code.
failureClassifications: inventory missing, runtime unavailable, endpoint collision, store mismatch, disk/VRAM shortage, pull/digest failure, CPU offload, regression, role mismatch, lineage missing.
rollback: restore prior bindings and user-scope environment snapshot; retain downloaded models.
patchdropContract: no Notebook PatchDrop; Desktop final owner.
desktopFinalProof=evidence_needed
```

## 18. 공식 근거

- Ollama GPU 지원 및 UUID 기반 GPU 선택: <https://docs.ollama.com/gpu>
- Windows 모델 저장소와 `OLLAMA_MODELS`: <https://docs.ollama.com/windows>
- 컨텍스트 길이와 GPU offload 확인: <https://docs.ollama.com/context-length>
- 설치 모델 목록 API: <https://docs.ollama.com/api/tags>
- 실행 모델과 VRAM API: <https://docs.ollama.com/api/ps>
- 모델 pull API: <https://docs.ollama.com/api/pull>
- Qwen 3 현재 세대와 `qwen3:8b` 크기: <https://ollama.com/library/qwen3>
- Qwen 3.5와 `qwen3.5:9b`: <https://ollama.com/library/qwen3.5>
- Gemma 4 모델: <https://ollama.com/library/gemma4>
- Qwen 3.6 모델: <https://ollama.com/library/qwen3.6>
- Qwen3-Coder 모델: <https://ollama.com/library/qwen3-coder>
- Qwen3-Coder-Next 크기: <https://ollama.com/library/qwen3-coder-next>
- Ministral 3 모델: <https://ollama.com/library/ministral-3>

## 19. 세 방향 재평가

### POSITIVE_QUERY / PositivePacket

- `candidateGoal`: 공식 allowlist의 네 모델을 안전하게 내려받고 3060/3090 역할별 A/B 승자를 승격한다.
- `validatedAssumptions`: dual endpoint와 역할별 manifest가 이미 있으며, pull·진단·설정 경계를 재사용할 수 있다.
- `reusableAssets`: `GpuHardwareDiagnostics`, `GpuGatewayDiagnostics`, `LocalLlmProcessManager`, `ModelSettingsService`, 기존 GPU smoke.
- `expectedUserValue`: 3060의 오래된 fast 기준선 교체와 3090의 품질 상향을 한 번의 검증 절차로 반복 가능하게 한다.
- `minimalVerification`: Desktop preflight → exact-tag pull/digest → 동일 A/B → `/api/ps` GPU/VRAM gate → reversible promote.
- `evidenceIds`: `E-LOCAL-ROLE-BINDINGS`, `E-LOCAL-GATEWAY-ASSETS`, `E-OFFICIAL-QWEN`, `E-OFFICIAL-GEMMA`, `E-OFFICIAL-RUNTIME`.
- `unknowns`: Desktop의 실제 3060 VRAM 변형, 현재 Ollama version, free disk, 실측 한국어/RAG/코딩 성능.

### NEGATIVE_QUERY / NegativePacket

- `challengedGoal`: 더 새롭거나 더 큰 모델을 자동으로 최적이라고 간주하는 일괄 교체.
- `falsifiers`: CPU offload, VRAM headroom 미달, digest 변경, 기준선 대비 품질·지연 회귀, request/response lineage 불일치.
- `counterExamples`: 31B 20GB 모델은 24GB VRAM에 들어가도 긴 context에서 KV cache 여유를 잃을 수 있고, 12B challenger는 3060 fast latency를 악화할 수 있다.
- `authorityRisks`: Notebook이 Desktop 모델 저장소나 사용자 환경 변수를 변경하는 것, 구현 승인 전 pull하는 것.
- `safetyRisks`: moving tag, 동시 pull, 부족한 disk, stale Browser `CurrentModel`, 모델 자동 삭제.
- `missingEvidence`: `E-DESKTOP-RUNTIME` 전체.
- `smallestDisconfirmingProbe`: Desktop에서 read-only preflight 한 번을 실행해 GPU VRAM, endpoint, version, store, free disk를 확인한다.
- `evidenceIds`: `E-DESKTOP-RUNTIME`, `E-OFFICIAL-RUNTIME`.

### NEUTRAL_QUERY / NeutralVerdict

- `verdict`: APPLY for revised design; HOLD for download or source implementation until explicit implementation approval and Desktop preflight.
- `selectedOrRewrittenGoal`: 네 exact tag를 계획된 first wave로 정의하되 다운로드, benchmark, promotion을 서로 다른 승인·검증 단계로 분리한다.
- `goalScore`: 72.5.
- `scoreInputs`: evidenceStrength 0.85, causalStrength 0.80, verificationFeasibility 0.75, userValue 0.90, reversibility 0.95, costEfficiency 0.80, timeFit 0.80, blastRadius 0.10, ambiguity 0.40, authorityOrSafetyExpansion 0.15. 출처는 위 Evidence ID와 `E-DESKTOP-RUNTIME` 결손이다.
- `decisiveEvidence`: 기존 dual-role 경계의 재사용 가능성, 공식 패키지 크기/digest, Desktop runtime 증거 결손.
- `rejectedClaims`: 256K context를 즉시 운영 가능하다는 주장, 모델 크기만으로 우열을 확정하는 주장, Browser 선택이 최적화를 증명한다는 주장.
- `orderStable`: true.
- `nextSingleProof`: Desktop canonical root에서 section 12.2의 preflight 단계만 먼저 실행한다.
- `confidence`: M.
