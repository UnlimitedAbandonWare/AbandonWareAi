---
doc_id: TRI-CTX-09
title: Windows PowerShell Diagnostics and Dual GPU OAuth Endpoints Cheatsheet
created_at: "2026-10-05T21:40:00+09:00"
expires_at: "2027-01-03T21:40:00+09:00"
ttl_days: 90
lifecycle: VOLATILE
validity_basis: "AGENTS.md, application-llm.yaml, LlmConfig.java"
---

# 09. PowerShell 진단 & 듀얼 GPU/OAuth 엔드포인트 치트시트 (Tri-Agent 공유)

> DESKTOP-M5NOV6K(RTX 3060 + RTX 3090)에서 셸·포트·GPU 레인·ChatGPT OAuth
> 규격을 웹서치 없이 쓰는 카드. **포트↔GPU 매핑은 라이브 YAML이 기준**이며
> 지시서·메모리의 값과 다르면 YAML을 우선한다.

## 1. PowerShell 5.1 명령 실행 관례

- 세션 셸은 Windows PowerShell 5.1 — `&&`/`||`/`2>/dev/null`/bash 문법 전부 실패.
  구분자는 `;`, 조건 분기는 `if ($LASTEXITCODE -eq 0) { ... }`.
- 종료코드 확인 패턴 (모든 검증 명령 뒤에 붙인다):

```powershell
.\gradlew.bat :compileJava -x test; "exit=$LASTEXITCODE"
python -B scripts/test_codex_quick_doc.py; "exit=$LASTEXITCODE"
```

- UTF-8 no-BOM 강제 (의존 도구가 BOM을 거부):

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
```

- `data/agent-handoff` 하위 파일은 ignore 처리되어 에디터 쓰기가 막힌다 —
  반드시 `[IO.File]::WriteAllText`(UTF-8 no BOM)로 기록.
- 첫 명령 전: `Set-Location -LiteralPath 'C:\AbandonWare\demo-1\demo-1\src'`
  (Project Root 외 cwd에서 git/python/npm 실행 금지).

## 2. 프로세스·포트 점유 검사

| 목적 | 명령 |
|---|---|
| 에이전트 동적 포트 리스 | `python -B scripts/agent_port_lease.py status` |
| 포트 획득→기동→헬스→검증→정지→해제 | `acquire` `start` `health` `verify` `run` `stop`/`release` (`contract`로 순서 확인) |
| 리스 대상 범위 | `DEFAULT_RANGE 25000-25999`; `18180-18182`는 Meta Display 보호 포트 — 리스/킬 대상 아님 (agent_port_lease.py:39-40) |
| 포트 점유 프로세스 | `Get-NetTCPConnection -LocalPort 18180 -State Listen` |
| 리스 소유 프로세스 종료 | `Stop-Process -Id <pid> -Force` — **자기 리스의 pid만**. 서버/H2 락 해제 목적으로 JVM을 죽이지 않는다 (08 문서 §2) |
| 진단 묶음 | `diagnose`, `trace`, `reap` (고아 리스 회수) |

## 3. 듀얼 GPU 레인 잠금 (라이브 매핑 — application-llm.yaml 확인분)

| 포트 | GPU | 역할 | 근거 |
|---|---|---|---|
| `127.0.0.1:11434` | **RTX 3090** | `llm.base-url` 기본 chat, high/gemma, judge, coder (`/v1` OpenAI 호환) | application-llm.yaml:3-4,13,143,504-609; application-desktop-gpu-node.yml:37,68-108 (`device: rtx3090`) |
| `127.0.0.1:11435` | **RTX 3060** | fast/light, vision (`/v1`), embedding 전용 `/api/embed` | application-llm.yaml:54,222,504,609; desktop-gpu-node:38-39,84-125 (`device: rtx3060`); models.manifest.yaml:36-76 |
| `127.0.0.1:11437` | 예비 호스트 | `ollama.default_hosts` 3번째 | configs/api-routing.yaml:23 |

- env 오버라이드 이름표: `LLM_3090_BASE_URL`, `LLM_3060_BASE_URL`,
  `EMBED_3060_BASE_URL`, `LLM_FAST_BASE_URL`, `LLM_JUDGE_BASE_URL`,
  `LLM_CODER_BASE_URL`, `LLM_VISION_BASE_URL`.
- `nvidia-smi` 인덱스 **0 = RTX 3060, 1 = RTX 3090** (Task Manager 라벨과
  반대) — 레인 고정은 반드시 **GPU UUID**로 하고 `CUDA_VISIBLE_DEVICES=0`
  같은 인덱스 추측 금지 (docs/agents-rules/DEMO1-GPU-LANE-EVIDENCE.md §8).
- 작업 전 진입 예의: `scripts/ollama-status-snapshot.ps1` 1회 실행 후
  serve-instance/포트/`/api/ps`/GPU-UUID를 저널에 남긴다. "포트가 응답"은
  "그 GPU가 생성했다"의 증거가 아니다 — 완료는 요청→엔드포인트→포트PID→
  GPU UUID→실제 생성(`eval_count`/`load_duration`) 체인 전부 관측.
- 주의: **3060은 디스플레이(브라우저/OBS)도 겸함** — 3060 busy 단독으로
  Ollama 결함 판정 금지.

## 4. ChatGPT OAuth 엔드포인트 계약

- API: **Responses API** `POST {base}/v1/responses`
  (`OpenAiResponsesChatModel.java:52,160`).
- 엔드포인트 허용목록 (`validateOAuthEndpoint`, :358-365):
  `https://api.openai.com/v1/responses` 또는 로컬 픽스처
  `http://127.0.0.1:<port>/v1/responses`만. userInfo/query/fragment 포함 시
  `chatgpt_oauth_endpoint_not_allowed`.
- 스코프: `chatgpt.tokens.use.direct` (ChatGptOAuthRegistration.java:305).
- 페이로드 필수 필드: `store: false`, `stream: true`
  (OpenAiEndpointCompatibility.java:307-308, ModelLoadoutResolver.java:189).
- 스트림 요청은 `.accept(MediaType.TEXT_EVENT_STREAM)` +
  `bodyToFlux(ServerSentEvent<String>)` (OpenAiResponsesChatModel:395-412)
  — 07 문서 §1 패턴.
- 스위치: `chatgpt.enabled` ← `CHATGPT_OAUTH_ENABLED` (기본 true,
  application-meta-display.yml:278). 자격증명은
  `.secrets/chatgpt_oauth_credentials.json` (`CHATGPT_OAUTH_CREDENTIALS`) —
  **읽기·출력 금지**. 런타임 하네스: `scripts/chatgpt_oauth_flow.py`
  (login|status|refresh), 계약 `data/agent-handoff/chatgpt-oauth/CONTRACT.md`.
- 라우팅: `chatgpt_oauth`는 tier `subscription` — 메인 /chat 1순위이자
  3090 사고 시 llm 폴백 1순위 (configs/api-routing.yaml:138-142).

## 5. LlmConfig 빈 이름표 (main/java/com/example/lms/config/LlmConfig.java)

| 빈 이름 | 선언 | 역할 |
|---|---|---|
| `chatModel` (+ `redChatModel`) | `@Primary @Bean(name={...})` :79-80 | 메인 채팅 |
| `miniModel` | `:159` | 소형/보조 |
| `fastChatModel` (+ `greenChatModel`) | `:204` | fast 경로 |
| `exploreChatModel` | `:293` | 탐색/self-ask |
| `judgeChatModel` | `:351` | 심판/검증 |
| `highModel` | `:408` | 고성능 경로 |
| `nightmareBreaker` | `:471` | 유틸 LLM 회로차단기 |
| `localChatModel` | `:577` | `miniModel` 위임 로컬 경로 |

새 ChatModel 빈은 같은 파일 같은 패턴으로만 추가 — 별도 `@Configuration`
신설 금지 (06 문서 §2, §4).

## 관련 문서

- [07_SPRING_WEBCLIENT_SSE_RESILIENCE_CHEATSHEET.md](07_SPRING_WEBCLIENT_SSE_RESILIENCE_CHEATSHEET.md) — SSE/Resilience4j 패턴
- [08_H2_DBAGENT_AND_LOCK_RECOVERY_CHEATSHEET.md](08_H2_DBAGENT_AND_LOCK_RECOVERY_CHEATSHEET.md) — DB 락/exit 3 계약
- [06_SPRING_BOOT_3_3_4_LANGCHAIN4J_1_0_1_SSOT.md](06_SPRING_BOOT_3_3_4_LANGCHAIN4J_1_0_1_SSOT.md) — 플랫폼 순수성 게이트
- [03_LIVE_LLM_RAG_REGISTRY_90D.md](03_LIVE_LLM_RAG_REGISTRY_90D.md) — 가변 모델/라우팅 레지스트리
- [README.md](README.md) — 카탈로그 인덱스와 TTL 정책
