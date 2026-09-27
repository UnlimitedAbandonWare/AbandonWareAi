# GPU 듀얼레인 / Ollama 라우팅 / 답변 보류 — Codex 소스수정 지시서 (stuff22)

작성: devin-desktop, 2026-09-24 · 입력: GPT-Pro 지시서(스터프1) + Devin 영상/실측 리포트(스터프2) + 본 체크아웃 라이브 대조
범위: `C:\AbandonWare\demo-1\demo-1\src` — 애플리케이션 소스·설정 수정은 Codex 몫. 진단/운영 도구와 라우팅은 이미 준비됨(§7).

## 0. 완료 기준 (acceptance)

> **"3090의 VRAM이 올라갔다"가 아니라, "선택한 모델이 의도한 GPU에서 실제 답을 생성하고 그 답이 화면까지 정상 도달했다"가 완료.**

한 요청에서 다음 체인을 끊기지 않고 증명할 것:
`선택 역할/모델 → 실제 적용 endpoint → 포트 소유 PID → 러너/serve PID → GPU UUID → 실제 생성(util 피크 또는 load_duration/eval_count) → 화면 도달 또는 정확한 HOLD reasonCode`

"포트가 응답한다" ≠ "의도한 GPU에서 실행된다" ≠ "모델이 생성했다" ≠ "답이 화면에 나왔다" — 보고서에서 별도 필드로 유지.

## 1. 사실 (이 체크아웃에서 재확인됨, verified)

| # | 파일:위치 | 사실 |
|---|---|---|
| F1 | `main/resources/application-llm.yaml:13` | `base-url: ${LLM_BASE_URL:${LLM_3090_BASE_URL:http://127.0.0.1:11435/v1}}` — "3090" 라벨 기본값이 **11435** |
| F2 | `application-llm.yaml:51` | fast `base-url: ${LLM_FAST_BASE_URL:${LLM_3060_BASE_URL:${llm.base-url}}}` — 3060 라벨도 env 없으면 같은 곳으로 붕괴 |
| F3 | `application-llm.yaml:219` | embed `base-url: ${EMBED_BASE_URL:${EMBED_3060_BASE_URL:http://127.0.0.1:11435/api/embed}}` |
| F4 | `main/resources/application-desktop-gpu-node.yml:37-39,68-78,84-125` | primary/fast/embed/judge/coder/vision 전부 기본 11435 (3090 계열은 `${llm.base-url}`=11434로 붕괴) — 원 지시서에 없던 **추가 파일** |
| F5 | `main/resources/configs/models.manifest.yaml:26` vs `application-llm.yaml:13` | `LLM_3090_BASE_URL` 기본값이 한쪽은 **11434**, 한쪽은 **11435** — **spec drift**, SSOT+라이브 포트로 재확인 필요 |
| F6 | `main/java/.../config/LocalLlmProcessManager.java:679-725` | `selectCudaVisibleDevice()`: GPU 1장이면 `auto_discovered_uuid` 핀, **2장 이상이면 `auto_discovery_ambiguous`만 기록하고 미지정 진행**; UUID 없으면 `CUDA_VISIBLE_DEVICES` 미설정(~:638-645) + 기존 응답 서버 재사용 경로(~:277-310) |
| F7 | `main/resources/application-local-llm.yml:27` | `warmup.model` 기본값 `${LOCAL_LLM_WARMUP_MODEL:${embedding.model:qwen3-embedding:4b}}` — **임베딩 모델을 /api/chat 워밍업** |
| F8 | `LocalLlmProcessManager.java` | `/api/chat` 워밍업(~:890)과 `/api/embed` 워밍업(~:914)이 공통 `postJson`→manager `ollamaBaseUrl()` 사용 — embed endpoint를 따로 설정해도 워밍업은 chat 서버로 갈 수 있음. 단 `warmupEmbedModelExplicit()`(~:820-842)이 yaml 중첩기본 embed-model을 "명시 아님"으로 처리하는 게이트가 이미 있음 — 패치 전 현재 게이트 동작부터 읽을 것 |
| F9 | `RagControlProjectionRenderer.heldNotice()`(:15) + `RagControlPresentationBoundary`(:148,:168-173) | `plan.shouldStop()`이면 semanticAnswer 대신 고정 보류 문구 반환 — 영상의 "검증된 근거가 추가로 필요해…" 문구와 대응 |
| F10 | `RagControlRuntimeAdapter.java:105-126` | 검색 0건은 이미 `DEGRADE` 처리 — "Evidence 0이면 무조건 보류"는 **현재 소스와 다름**. 어느 guard가 `verificationRequired`/최종 reasonCode를 만들었는지 추적이 먼저 |
| F11 | 라이브(2026-09-24 ~20:5x KST) | `ollama serve` 3개: PID19016→11434(트레이 자식), 26344→11435, 22320→**11438**(스펙 문서는 11437까지 — 소유자 미확인). `:11434`에 `gemma4:26b` 16.2GB 상주, `llama-server.exe` PID 51660이 **3090 UUID**(GPU-4032bebe-…e586)에 attach = 11434↔3090 레인 실재. 3060=디스플레이 GPU로 ~34개 데스크톱 프로세스 attach |

## 2. 추정 (근거 있으나 미확정 — 단정 금지)

- 영상 중 `젬마4:26b` 선택 요청이 실제 3090에서 크게 생성됐다는 util 피크 증거 없음 — 상주만 하고 실생성은 9b/3060이 했을 가능성 (요청-시간↔util 피크 1:1 대조 데이터 부족)
- CPU 폴백 경로(`OllamaNativeChatModel` ~:338-350 조건부 `num_gpu:0`) 존재하나 이번 영상에서 실제 발생 확인 안 됨
- 11438 serve의 출처/의도 — 스펙 외 인스턴스로 "확인 필요"이지 "불필요" 확정 아님

## 3. 수정 순서 (통과 조건 포함)

| 순서 | 작업 | 통과 조건 |
|---|---|---|
| 1 | 실제 profile·endpoint·포트 소유 PID·GPU UUID 확인 | "3090용 설정"이 아니라 **실제 실행 대상** 확인 — `scripts/ollama-status-snapshot.ps1` + 활성 프로필 확인 |
| 2 | 3090 단독 진단 경로 복구 | `:11434` 직접 API 호출에서 선택 모델 정상 답변 확보 (`/api/chat` 또는 `/v1/chat/completions`) |
| 3 | ambiguous GPU 선택(F6) + 잘못된 워밍업(F7,F8) + endpoint 붕괴(F1-F5) 수정 | 주 모델·fast·임베딩의 실행 모델·endpoint가 역할과 일치. 기존 UUID 고정·소유권 코드 재사용, 새 GPU 관리 체계 신설 금지. 역할 UUID 미정이면 임의 첫 GPU 실행 대신 "설정 필요" 상태 표시 |
| 4 | 앱 경유 non-RAG 요청 비교 | 직접 호출은 되는데 앱만 실패하는 경계 제거 |
| 5 | 임베딩·검색·judge·검증을 하나씩 복원 | 어느 단계부터 지연/보류가 생기는지 단계별 확인; `verificationRequired`를 잘못 세우는 분기만 수정 — 외부출처 검증이 필수 아닌 질문을 보류하는 결함이 목표. 검증 자체를 끄는 방식 금지 |
| 6 | 3060 보조 기능 복원 + 취소/재시도 검증 | 기능을 전부 끈 채 완료 처리 금지; 최종 구성에서 레인·모델·보류 경로 전부 재확인 |

## 4. 금지 사항

- `ollama.exe` 프로세스 무조건 kill 금지 — 11434는 트레이 앱 자식, 죽이면 재기동되거나 CLI 전체가 죽음
- `CUDA_VISIBLE_DEVICES=0` 복붙 금지 — 이 PC는 nvidia-smi index 0이 **3060**(Task Manager 번호와 역순). UUID만 사용
- 검증 없는 `ollama rm` 금지 — 재다운로드 수십 GB
- "프런트 60초 제한만 늘리기" 금지 — `streamClientDeadlineMs()`는 이미 null; 서버 실제 상태(대기열/모델로딩/생성/검증) 구분이 먼저
- Java/Spring 프로세스 임의 kill 금지 — 소유 서버 아님
- 시크릿 값 출력·커밋 금지 (env 이름만)
- "불안하니 API로" 회피 금지 — 3090 로컬 주력 전제 유지 (spend-guard 준수)
- `demo.interview.enabled`, 익명 채팅 경로, Display TTL/`ld-*`/`hintsEnabled` 무관 변경 금지
- Git: 조건부 로컬 Git만 (`agent_git_vibe_commit.py` 경유); push/pull/fetch/merge/rebase/reset/`add -A`/`commit -a`/`--no-verify` 금지
- 기존 구현 우회하는 중복 서비스/새 계층 신설 금지 — 누락 분기 수정이 먼저

## 5. 작업 절차 (프로젝트 계약 — 필수)

1. 진입: `python -B scripts/awx_device_bus.py start`, `python -B scripts/agent_preflight.py --root .`
2. `docs/PROJECT_STATUS.md` 읽기 → `work_journal.py open` (scope 명시) → 타겟 리스(`source_edit_session.ps1 -Action begin -TargetManifest`) → `codex_work_checkpoint.py` 사이클 begin→patch→seal→verify→finish → journal note → status_doc
3. 소스 편집 전 `demo1_vibe_skill_router.py resolve "<ask>"` 또는 직접 `$demo1-gpu-lane-evidence` + `$demo1-local-llm-gpu-gateway` + `$demo1-mutable-spec-policy` 로드
4. 모델/포트/엔드포인트는 SSOT(`configs/api-routing.yaml`,`docs/API_ROUTING_SPEC.md`,`ollama ls`)에서 재읽기 — 주석/구문서의 숫자는 변수
5. 검증: focused test + `compileJava`; 라이브 반영은 ForceRestart/DevWatch — stale JVM의 200은 증거 아님
6. 완료 시 `$demo1-goal-complete-stop`

## 6. 판정 언어 (보고 규약)

- `사실` = 이 체크아웃/라이브에서 확인, `추정` = 근거 있으나 미확정, `근거 부족` = 데이터 없음 — 구분 표기
- "도구가 돌았다" ≠ "타겟이 검증됐다" ≠ "빌드/테스트가 돌았다" ≠ "전체 검증 완료" — 별도 필드
- 미실행 필수 체크는 `run=skipped|blocked|not_observed`로 보고, pass 카운트에 넣지 않음
- 401/403 = `auth-blocked`(DOWN 아님); `stale-candidate` = 미확증 mtime 의심

## 7. 준비된 도구 (devin-desktop이 작성·검증 완료, 2026-09-24)

| 도구 | 용도 | 위험 |
|---|---|---|
| `scripts/ollama-status-snapshot.ps1` | serve 인스턴스/포트·/api/ps·GPU util/VRAM·compute앱 PID→GPU UUID·역전 휴리스틱 | 없음(읽기전용, 검증: live exit 0) |
| `scripts/ollama-unload-idle.ps1` | 상주 모델 개별 확인 후 unload (`-WhatIf`/`-Keep`) | 낮음(모델 언로드만) |
| `scripts/ollama-prefer-3090.ps1` | env/GPU 상태 진단 + 3090 고정 절차 안내 (`-WriteUserEnv`/`-LaunchPinned`는 opt-in) | 기본 읽기전용 |
| `scripts/ollama-light-preload.ps1` | 경량 모델만 warm-up 상주 (`PLACEHOLDER-EMBED-MODEL`은 `ollama ls`로 교체 필수) | 낮음 |
| `scripts/desktop_dual_ollama_gpu_setup.ps1` | **canonical** 레인 설정: `-Mode ValidateOnly`(검증) / `Start`(UUID핀·포트경합·소유권 검증·cold-reboot 게이트) | ValidateOnly 안전 |
| `.agents/skills/demo1-gpu-lane-evidence` | 위 검증 계약·하드웨어 사실·금지사항의 스킬 SSOT (`gpu-lane-evidence` 인텐트로 라우팅됨) | — |
| `agent-prompts/gpu-lane-repair-20260924/baseline-snapshot.txt` | 2026-09-24 라이브 베이스라인 스냅샷 | — |

## 8. 플러그인/외부도구 역할 제한

`codex_operating_card.md` 참조 — 스터프1의 역할제한(전부 쓰지 말 것)을 프로젝트 계약에 맞게 번역해 둠.

## 9. 참고 자료 (공식)

- Ollama GPU: UUID 권장 — https://docs.ollama.com/gpu
- keep_alive 상주 동작 — https://docs.ollama.com/faq
- OpenAI-compat + num_ctx(Modelfile PARAMETER 방식) — https://docs.ollama.com/openai
- /api/chat 응답의 load/eval 타이밍 필드 — https://docs.ollama.com/api/chat
- nvidia-smi WDDM 프로세스 메모리 N/A 제한 — NVIDIA docs
- Task Manager 엔진 그래프 ≠ 연산 소유 프로세스 — Microsoft DirectX devblog
