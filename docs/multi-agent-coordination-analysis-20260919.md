# demo-1 다중 코딩 에이전트 공조 기반 분석 보고서

- 작성: Devin (Devin Desktop, SWE-2 Max) · 2026-09-19 · 작업 등록: `multi-agent-infra-analysis-8f04b751`
- 대상: `C:\AbandonWare\demo-1\demo-1\src` 에서 병행 운용되는 **Devin · Codex · Cline · Grok Build**
- 목적: 역할 분업이 아니라, 어느 에이전트가 작업해도 현재 소스·규칙을 정확히 읽고 다음 에이전트가 충돌 없이 이어갈 수 있는 **공통 코어 + 얇은 어댑터** 구조의 현황 진단과 개선안
- 근거: 각 제품 공식 문서(로컬 설치본 문서 + 공식 docs 사이트), `grok inspect` 라이브 출력, 실제 설정 파일·스크립트·저널 실측. 추정은 표기함.

---

## 0. 판정 요약 (결론 먼저)

1. **공통 코어는 이미 상당히 갖춰져 있다.** `AGENTS.md`(공용 SSOT, ~31KB) + `.agents/skills/`(98개, Codex·Devin·Cline 공용 발견 경로) + `docs/PROJECT_STATUS.md` + `work_journal.py`/`codex_work_checkpoint.py`(작업 원장·preimage) + `__patch_drop__/source_edit_session.ps1`(파일 대상 lease) + `guarded_source_edit.js` 조합은 "공통 코어 + 얇은 어댑터" 모델의 대부분을 이미 구현한다.
2. **가장 큰 실질 공백은 Grok이다.** `grok inspect` 라이브 확인: `Project trusted: no`, `Project Instructions: 0`, 프로젝트 스킬 0개, 훅 0개. Grok은 이 체크아웃에서 **AGENTS.md도 읽지 못하는 상태**다(trust 미부여 + 프로젝트 `.agents/skills`가 Grok 발견 경로가 아님).
3. **두 번째 공백은 훅 패리티.** `.codex/hooks.json`의 UserPromptSubmit 훅(디바이스 버스 + 소스편집 트리아지)은 Codex 세션에서만 실행된다. Devin은 `.devin/hooks.v1.json`을 공식 지원하므로 이식 가능하며, Cline은 Windows에서 스크립트 훅 자체가 미지원이라 대상外다.
4. **세 번째는 인벤토리 정합성 문제 몇 건.** `in_progress`로 남은 완료 작업 저널 1건, 사라진 Cline 전역 룰 파일, 존재하지 않는 `.devin/rules` 경로를 가리키는 스킬 본문, 루트의 구형 단발성 지시 문서들(`GPT_PRO_AGENT_INSTRUCTIONS.md` 등).
5. **비용 관점의 잔여 과제:** AGENTS.md는 61KB→31KB로 줄었으나 여전히 always-on 최대 단일 비용이고, `__patch_drop__/producer-kit`의 중첩 `.agents/skills` 6키트(~82+디렉터리)는 `.gitignore`로 완화했으나 gitignore를 무시하는 스캐너에는 여전히 노출된다.
6. **새로운 도구·라이브러리·MCP 추가가 필요한 항목은 거의 없다.** 필요한 것은 (a) Grok 얇은 어댑터, (b) Devin 훅 이식, (c) 소수 정합성 수정, (d) 상태·리스 조회를 한 번에 보여주는 공통 프리플라이트 명령 뿐이다. 대규모 설치·재구성은 모두 비채택.

---

## 1. 네 에이전트 공식 기능 지원 현황 (2026-09 공식 문서 기준)

| 기능 | Devin (CLI/Desktop) | Codex (CLI 0.144.1) | Cline (Desktop 0.0.32) | Grok Build (1.0.30) |
|---|---|---|---|---|
| AGENTS.md | ✔ 자동 로드 (`.devin/rules`, `.windsurf/rules`, `CLAUDE.md`, `.cursor/rules`도 병행 지원) | ✔ 계층 로드, `project_doc_max_bytes` 기본 32KiB | ✔ 자동 감지 (공식 크로스툴 경로로 명시) | ✔ 지원. 단 **프로젝트 trust 전제** — 미신뢰 시 로드 0 (실측) |
| 프로젝트 스킬 | `.agents/skills` ✔ + `.devin/skills` + `.windsurf/skills` | `.agents/skills` ✔ (cwd→repo root 스캔, 공식 REPO 경로) | `.agents/skills` ✔ (실측 92/97 발견) + `.cline/skills` | `.grok/skills` + 플러그인 스킬만. **프로젝트 `.agents/skills`는 발견 경로가 아님** |
| 글로벌 스킬 | `~/.agents/skills`, `%APPDATA%\devin\skills` | `~/.agents/skills` (+ `~/.codex/skills` deprecated) | 스킬 UI 토글 방식 | `~/.grok/skills`, `~/.agents/skills` |
| 조건부/경로 규칙 | `.windsurf/rules` glob / `.devin/rules` trigger frontmatter | 없음(AGENTS.md 계층뿐) | `.clinerules/*.md` `paths:` frontmatter | `.grok/rules/*.md`(frontmatter glob 미지원 — 상시 로드) |
| 라이프사이클 훅 | `.devin/hooks.v1.json` / `.devin/config.json` / `.claude/settings*.json` — 8 이벤트, `additionalContext`·`updatedInput`·`decision` 지원 | `.codex/hooks.json` + `config.toml [hooks]` — PreToolUse/PostToolUse/UserPromptSubmit/SessionStart/Subagent*/Stop 등 | `.clinerules/hooks` + `~/Documents/Cline/Hooks` — **Windows 미지원**(shebang+exec 모델, 공식 README 명시) | `.grok/hooks/*.json`(프로젝트, `/hooks-trust` 필요) + `~/.grok/hooks` + `.claude`/`.cursor` 호환 — command/http 타입 |
| MCP | `.devin/mcp_config*.json`, `.mcp.json`(Claude 호환 경로로 임포트) | `config.toml [mcp_servers]` (주로 사용자 레벨) | `cline_mcp_settings.json`(UI 관리) | `.mcp.json` ✔ + `~/.claude.json` + `.cursor/mcp.json` + `config.toml`; `grok mcp doctor` |
| 서브에이전트 | 내장 `subagent_explore`/`subagent_general` + `.devin/agents/*.md` 또는 `.agents/agents/*.md` 커스텀, `model:` 핀 지정 가능 | 내장 `default`/`worker`/`explorer` + `.codex/agents/*.toml` + `~/.codex/agents`; `[agents] max_threads/max_depth` | 네이티브 커스텀 서브에이전트 없음(태스크 분할·포커스 체인 수준) | 내장 `general-purpose`/`explore`/`plan` + 워크트리 지원; `GROK_SUBAGENTS` |
| 플러그인/마켓플레이스 | 플러그인 = 스킬+룰 번들(설치/공유 가능) | `[marketplaces]` + `[plugins.*]` 활성화 — 이 머신에 다수 설치됨 | SDK `AgentPlugin`(TS)만 — 훅 대체 경로 | `.grok/plugins` + 마켓플레이스 + Claude 플러그인 호환 |
| 체크포인트/되돌리기 | 없음(세션 재개만) | 사용자 레벨 `autonomy-checkpoints` | **Checkpoints(shadow git) — 공유 체크아웃에서 위험**, 브리지 룰로 복원 금지 명시 | 없음 |
| 구성 자가 검증 | `/hooks`, `/skills` 슬래시 | `codex` 설정 로드 + trust | Settings UI | **`grok inspect` — 유일한 원스톱 로딩 검증** |
| 세션 간 인수인계 | `/handoff` → 클라우드 Devin; 오픈소스 `devin-handoff` 플러그인(다른 에이전트에서도 Devin 클라우드로 인계 가능) | 클라우드 태스크/managed sessions | Desktop 내 병렬 세션(제품 UI 기능, 이 보고서에서 미검증) | headless/ACP |
| 부가 기능(클라우드) | Session Insights, Dynamic Workflows, Knowledge, Playbooks, Code Scans, Managed Devins — **전부 클라우드 Devin 전용, 로컬 CLI/Desktop 대상外** | Codex 클라우드 태스크, automations | Memory Bank(마크다운 관례, 내장 아님) | — |

> 로컬 4에이전트 병행에서 Devin 클라우드 기능(Knowledge/Playbooks/Insights/Dynamic Workflows/Code Scans)은 **해당 없음** — Knowledge≈AGENTS.md+스킬, Playbook≈스킬, Insights≈work_journal+PROJECT_STATUS로 이미 대응 중.

---

## 2. 현재 로드 맵 — 각 에이전트가 실제로 읽는 것 (2026-09-19 실측)

| 계층 | 파일/디렉터리 | Devin | Codex | Cline | Grok |
|---|---|---|---|---|---|
| 공통 SSOT | `AGENTS.md` (~31KB, always-on) | ✔ | ✔ (`project_doc_max_bytes=65536`로 상향됨) | ✔ | ✘ **미로드** (untrusted → 0) |
| 상태/원장 | `docs/PROJECT_STATUS.md`, `work_journal.py`, `codex_work_checkpoint.py`, `data/agent-handoff/codex-autonomy/` | ✔ 규칙상 의무 | ✔ 규칙상 의무 | ✔ (브리지 룰로 명시) | ✘ |
| 스킬 | `.agents/skills/` 98개 + `INDEX.md` + `semantic-catalog.yaml` | ✔ | ✔ | ✔(92) | ✘(유저 3개만) |
| 어댑터 | `.windsurf/rules/` 2개 | ✔ | ✘ | ✘ | ✘ |
| 어댑터 | `.devin/` RULES_SSOT+PROMPTS (룰 아님, 문서) | 문서 | ✘ | ✘ | ✘ |
| 어댑터 | `.codex/` config.toml + hooks.json + shared-runtime.json | ✘ | ✔ | ✘ | ✘ |
| 어댑터 | `.clinerules/` 5개 + `.cline/skills/` 5개 + `.clineignore` | ✘ | ✘ | ✔ | ✘ |
| 어댑터 | `.grok/` | — | — | — | **없음** |
| 훅 | `.codex/hooks.json` UserPromptSubmit×2 | ✘ | ✔ | 불가(Windows) | 없음(미신뢰) |
| MCP | `.mcp.json`(supabase) | ✔ 임포트 | 사용자 config 별도 | 미연결(의도적) | ✔ 로드됨(3개) |
| 비밀 | `.secrets/`, env 이름만, `.ignore`/`.rgignore`/`.clineignore` 차단 | ✔ | ✔ | ✔ | (파일 접근은 가능 — 규칙 미로드라 금지 인식 없음) |
| 소스 쓰기 게이트 | `source_edit_session.ps1` lease + `guarded_source_edit.js` | ✔ | ✔ | ✔(브리지 명시) | ✘ |
| 사용자 레벨 | `~/.codex/AGENTS.md`(9KB), `~/.codex/agents/glm_worker.toml`, `~/.codex/skills/` 8개, `~/.agents/skills/` 3개, `%USERPROFILE%\Documents\Cline`(**없음**), `~/.grok/config.toml`(최소) | `~/.agents/skills` 3개 공유 | 풍부 | 글로벌 룰 **부재 확인** | 최소 구성 |

**실측 메모**
- `grok inspect` (2026-09-19, 이 루트에서 실행): `Project trusted: no`, `Project Instructions (0)`, `Skills (3)`=user 전용 3개, `Hooks (0)`, `MCP Servers (3)`=`.mcp.json`+cursor 호환 경로, `Compat: cursor/claude on`.
- `~/.codex/config.toml`: 이 프로젝트 `trust_level="trusted"`, `[agents] max_threads=3 max_depth=1`, `[features] multi_agent=true`, MCP에 `awx-control-tower`(repo `awx_mcp_stdio_server.py`)·`awx-shared`·`glm_agent`·`gemini-agy`·`metaWearables` 등록됨, 플러그인 다수 활성.
- `Documents\Cline` 디렉터리 부재 — 오늘 보고서에서 생성했다고 기록된 전역 룰 `demo1-user-defaults.md`가 현재 디스크에 없음(미지속 또는 삭제됨).
- 저널: `devin-config-optimization-apply-20260919-8aec0fa1`이 여전히 `in_progress`(마지막 갱신 05:40 UTC) — 완료 선언 후 미종결 사례.

---

## 3. 이미 갖춰진 공통 기반 (재구축 금지)

1. **규칙 SSOT**: `AGENTS.md` 단일 파일 + BEGIN/END 마커 절 구조. 네 에이전트 중 3개가 네이티브 로드. `verify_codex_instructions.ps1`로 구조 검증 존재.
2. **스킬 SSOT**: `.agents/skills/` — Codex·Devin이 공식 경로로 직접 발견, Cline도 실측 발견. `INDEX.md`(typed routing), `semantic-catalog.yaml`/`semantic-index.json`/`SEMANTIC_INDEX.md`, `validate_demo1_skill_family*.ps1` 검증기, 스킬별 `agents/openai.yaml` Codex 페어링 메타.
3. **상태 SSOT**: `docs/PROJECT_STATUS.md` — 상태 어휘(source/built/running/onGlasses/unverified/failed/stale)와 "보고상 완료 ≠ 검증" 규칙이 이미 정의됨.
4. **작업 원장**: `work_journal.py`(agent-neutral, taskId별 journal.json) + `codex_work_checkpoint.py`(cycle별 preimage/postimage/diff/결과, 게이트 평가 `assess` 포함) + `data/agent-handoff/codex-autonomy/<taskId>/`.
5. **동시쓰기 게이트**: `source_edit_session.ps1` 대상 경로 scoped lease(heartbeat/quarantine/lease.json) + `guarded_source_edit.js`(begin→verify→edit→end 단일 호출) + `미기록 외부 변경` 규칙. 에이전트 중립적(OwnerId 필드).
6. **장치/리소스 버스**: `awx_device_bus.py`(registry TTL, inbox 참조, hook 모드로 Codex UserPromptSubmit 연결됨) + `config/project-resources.json`(provider env 이름 카탈로그) + `.secrets/` 체계.
7. **검증 진입점**: `Start-RAG.bat`/`Close-RAG.bat`/`Start-Meta-Display.bat`/`Close-Meta-Display.bat`, `start_rag_stack.ps1 -CheckOnly`, DevWatch `[DEV-RELOAD] socket ready`, `check-model-lock.ps1`, `verify_control_plane_topology.ps1`, `verify_ydrive_backing_identity.ps1`, `run_verified_command.py`, `agent_code_evidence_gate.py`, 다수 `*_contract_tests.*`/`test_*.py`.
8. **크로스머신 교환**: `__patch_drop__/` PatchDrop v3 + janitor 스크립트 + dispatch 패킷 + `awx_mcp_toolbox.*`(stdio 서버 `awx_mcp_stdio_server.py` — Codex MCP로 등록됨).
9. **리뷰 브리지**: `demo1-grok-subscription-review`(Grok), `gemini-agy`/`glm_agent`(구독 리뷰 어댑터) — "한 에이전트가 다른 모델 리뷰를 요청"하는 채널이 이미 존재.

---

## 4. 충돌·공백 분석 (검증된 것만)

### 4-1. 커버리지 공백

| # | 공백 | 증거 | 영향 |
|---|---|---|---|
| G1 | **Grok이 프로젝트 규칙·스킬·상태를 전혀 못 봄** | `grok inspect`: instructions 0, skills 3(user), hooks 0, untrusted | Grok 세션이 AGENTS.md·ledger·lease 없이 소스를 수정할 수 있음 — **네 에이전트 중 유일하게 무방비** |
| G2 | Codex 훅의 패리티 부재 | `.codex/hooks.json`은 Codex 전용 | Devin/Cline/Grok 세션은 디바이스 버스 자동 probe와 소스편집 트리아지 안내 없이 진입(AGENTS.md 수동 규칙에 의존) |
| G3 | `.clinerules` 외 조건부 규칙이 다른 에이전트에 없음 | `.windsurf/rules` glob는 Devin 전용 | display 경로 작업 시 Cline만 조건부 안내를 받음. Codex/Grok은 AGENTS.md 요지 + 스킬 description에 의존(수용 가능) |
| G4 | Cline 전역 룰 파일 부재 | `Documents\Cline` 디렉터리 없음 | 오늘 보고서가 생성을 기록했으나 현재 부재 — 전역 기본값(무료 모델 유지 등) 미적용 상태일 수 있음 |
| G5 | `positive-negative-neutral-judge` 스킬이 `.devin/rules/demo1-operating-style.md`를 가리키나 파일 없음 | `.devin/`에 rules 없음(의도적 설계) | 스킬 본문 stale 포인터 — 혼란 유발, 소규모 수정 필요 |

### 4-2. 충돌·오염 위험

| # | 위험 | 증거 | 평가 |
|---|---|---|---|
| C1 | `__patch_drop__/producer-kit/` 6키트 내 중첩 `.agents/skills` (~82+ dirs) | 디스크 실측(키트: continuation9h, mcp-control-loop, mcp-stdio-bridge-verification, multi-device-r3, public-domain-migration, trace-memory-runtime-proof) | `.gitignore` 추가로 gitignore 준수 스캐너는 완화됐으나, 재귀·무시 스캐너에는 여전히 노출. 페이로드 구조 변경은 생산자 계약 확인 필요(보류) |
| C2 | `in_progress` 미종결 저널 1건 | `devin-config-optimization-apply-20260919-8aec0fa1` | 다음 에이전트가 "진행 중"으로 오독 가능 — 종결 또는 보류 전환 필요 |
| C3 | Cline Checkpoints | 브리지 룰로 복원 금지 명시됨 | UI 기능은 켜져 있을 수 있음 — 사용자 수동 사용 위험 잔존(파일로 강제 불가) |
| C4 | 루트 구형 지시 문서 | `GPT_PRO_AGENT_INSTRUCTIONS.md`(다른 과거 작업의 실행 지시서), `AUTO_BUILD_FIX_REPORT*`, `HOW_TO_APPLY.md`, `amp-playbook.md` 등 | 에이전트가 현재 지시로 오독해 구형 Spring/Redis 계획을 실행할 위험 — `docs/legacy/` 이동 또는 폐기 표기 권장 |
| C5 | `semantic-catalog.yaml` validate invalid + `verify_codex_instructions.ps1` FAIL(2건) | PROJECT_STATUS §5·§6 기록(선행 문제) | 검증 FAIL이 상시화되면 "깨진 창문" 효과 — 수정 또는 명시적 quarantine 표기 |
| C6 | 같은 기능의 다중 정의 | `Start-RAG`/`dev_reload` 계열 스킬 4개(`demo1-dev-reload`, `start-rag-reload`, `force-restart-meta-display`, `compile-verify-smoke`) + 유사 triad 스킬 2개(`demo1-triad-deliberation`, `positive-negative-neutral-judge`) | 경미한 라우팅 중복 — INDEX.md가 canonicalId를 구분해 관리 중. 신규 병합 금지, 포인터 정합성만 유지 |
| C7 | `apikey.txt` 평문 파일 | 루트 존재, ignore 계열로 차단됨 | 값은 보호되나 파일 존재 자체가 유인 — `.secrets/` 이동 검토(사용자 결정) |
| C8 | Codex 사용자 config의 머신 종속 절대경로 | `~/.codex/config.toml`의 hermes venv 경로 | 사용자 레벨이라 repo 무관 — repo 측 조치 불필요 |

### 4-3. 없어도 되는 것(비채택 후보 사전 판정)

- 새 MCP 서버/라이브러리/CLI 설치 — 기존 toolbox·스크립트가 커버.
- Cline용 훅 이식 — Windows 미지원(공식), SDK 플러그인은 과잉.
- Devin 클라우드 기능(Knowledge/Playbooks/Insights/Dynamic Workflows/Code Scans)의 로컬 구현 — 이미 파일 기반 대응물 존재.
- `.devin/rules` 신규 작성 — `.windsurf/rules`가 동일 로드되므로 이중화만 발생(기존 RULES_SSOT 판단 유지).
- 네 벌의 동일 스킬 복제 — `.agents/skills` 단일본 + 어댑터 포인터가 정답(현 구조 유지).

---

## 5. 후보별 평가 (긍정 → 부정 → 판정)

### 후보 A. Grok 얇은 어댑터 (`.grok/` + trust)

- **긍정**: Grok이 무방비로 체크아웃에 들어오는 최대 공백 해소. `.grok/rules/*.md`는 Grok 공식 프로젝트 규칙 경로. `grok inspect`로 적용 즉시 검증 가능(유일한 자가 검증 도구). 비용 ~1KB.
- **부정**: Grok 프로젝트 규칙은 glob/frontmatter 미지원 → 파일 내용이 상시 로드(AGENTS.md 요지 수준으로 얇게 유지해야 함). `.grok/config.toml` 프로젝트층은 mcp_servers/plugins/permission만 기여 — skills 경로는 사용자 config에서만 추가 가능(저장소 밖 결정 필요).
- **판정**: **채택(최우선)**. `.grok/rules/demo1-bridge.md` 1개 = 프로젝트 루트 고정 + AGENTS.md/PROJECT_STATUS/ledger/lease 참조 + 무료·로컬 우선 + 비밀/금지 요지 + `grok inspect` 검증 포인터. 프로젝트 스킬 노출은 선택안(아래 후보 E)으로 분리.

### 후보 B. Devin 훅 이식 (`.devin/hooks.v1.json`)

- **긍정**: `.codex/hooks.json`의 UserPromptSubmit 훅 2개(awx_device_bus probe, source_edit_triage)를 Devin도 실행 가능 — 공식 8 이벤트 + `commandWindows` 상당의 powershell 명령 지원, `additionalContext` 주입 동일. Codex 훅 스크립트를 그대로 재사용 가능(이식이 아니라 재호출).
- **부정**: 훅 실패 시 세션 시작 지연·차단 가능 — timeout(2~40s)과 fail-open 설계 유지 필요. `.devin/hooks.v1.json`은 프로젝트 전체에 적용되므로 이 체크아웃 외 컨텍스트에서의 부작용 점검 필요(루트 감지 로직은 이미 파일 존재 기반 fail-closed).
- **판정**: **채택**. 동일 스크립트 재호출로 중복 로직 0. 검증: `/hooks` 슬래시 + 실제 UserPromptSubmit 시 additionalContext 관찰.

### 후보 C. 공통 프리플라이트 한 줄 명령 (`scripts/agent_preflight.py` 신설)

- **긍정**: "작업 시작 시 읽어야 할 것"을 하나로 통합 — `awx_device_bus.py start` + `work_journal.py list --active` + `source_edit_session.ps1 -Action status` + PROJECT_STATUS 헤더 요약을 한 번에 JSON으로 출력. 네 에이전트 모두 동일하게 사용 가능, 새 의존 없음(기존 스크립트 재조합).
- **부정**: 이미 각각 존재하는 명령의 래퍼 — 중복 문서화 위험. AGENTS.md 진입 절차와의 SSOT 경합 주의(내용이 아니라 호출만 통합해야 함).
- **판정**: **조건부 채택(중간 우선순위)**. 출력 스키마는 `awx.*` 계열 관례(`schemaVersion`, evidence 필드)를 따르고, 실패 시 부분 결과만 반환(fail-soft). 기존 스크립트 수정 없음.

### 후보 D. 정합성 수정 묶음 (저널 종결, stale 포인터, 구형 루트 문서, Cline 전역 룰 확인)

- **긍정**: 각각 수 분짜리 수정으로 "다음 에이전트의 오독" 제거 — 리포트가 지적한 실제 사고 경로(C2, C4, G4, G5)를 직접 차단.
- **부정**: C4의 문서 이동은 파일 이동=2 targets checkpoint 대상이며 외부 참조가 있을 수 있음 — 이동 전 참조 grep 필요.
- **판정**: **채택(즉시 실행 항목으로 분리)**. 아래 §7에 실행 지시 단위로 기술.

### 후보 E. Grok 프로젝트 스킬 노출

- **긍정**: 98개 스킬을 Grok도 발견하면 라우팅 지식(`$demo1-*`)이 살아남.
- **부정**: `.grok/skills/`에 98개 커넥터를 두면 유지 비용·오염. Grok의 `~/.grok/config.toml [skills] paths`는 **사용자 파일** — 저장소 변경으로 해결 불가(사용자 승인 필요). 플러그인 경유는 과잉.
- **판정**: **부분 채택(보류 기본)**. 1차는 `.grok/rules/demo1-bridge.md`에 "스킬은 `.agents/skills/INDEX.md`에서 선택해 파일로 읽어라"는 지시만 둠(Grok은 파일 읽기로 SKILL.md 사용 가능 — 자동 발견이 아니라 수동 발견). 사용자가 `~/.grok/config.toml`에 `[skills] paths=["C:/AbandonWare/demo-1/demo-1/src/.agents/skills"]`를 추가하면 자동 발견까지 해결 — 이건 사용자 결정 항목.

### 후보 F. AGENTS.md 추가 축소 (31KB → <20KB)

- **긍정**: always-on 최대 비용 절감, 공식 권장("Rules는 최소로, 절차는 Skills로")과 일치. 잔여 절차성 절(PatchDrop 번들 상세, AutoLearn 리뷰, Workspaces/SMB, 동시편집 lease 수명주기, Runtime Boundary 맵)은 스킬/INDEX로 포인터화 가능.
- **부정**: 공용 SSOT라 한 에이전트 단독 축소 시 다른 에이전트의 상시 안전 지침이 깎일 수 있음 — Cline 브리지가 커버하지 않는 상시 규칙(비밀, lease, live-runtime 보호)은 반드시 잔류. BEGIN/END 마커와 INDEX contractRefs 동기 수정 필요.
- **판정**: **단계적 채택(후순위)**. 이번 라운드에서 하지 말고, §7 실행 후 안정화되면 절 단위 staged 진행(각 절 = 별도 checkpoint cycle + verify_codex_instructions).

### 후보 G. `.mcp.json`에 awx-control-tower stdio 추가

- **긍정**: Devin/Grok이 toolbox MCP 툴(archive_search/restore, run_pipeline, verify_boot, build_error_miner, dispatch)을 네이티브 MCP로 사용 가능 — Codex의 `awx-control-tower` 등록과 패리티.
- **부정**: `.mcp.json`을 읽는 모든 호환 클라이언트가 자동 spawn — Grok은 trust 후, Devin은 즉시. 툴 네임스페이스(`mcp__awx-control-tower__*`)와 기존 `awx_mcp_toolbox.*` 호출의 이중 경로 발생. supabase만 담긴 현 파일의 의도가 "읽기 전용 외부 서비스만"이었을 수 있음.
- **판정**: **보류**. toolbox 스크립트 직접 호출이 이미 동일 기능 제공 — MCP 등록의 실증 이득이 크지 않음. 필요 시 사용자 승인 후 추가.

### 후보 H. 에이전트별 네이티브 서브에이전트 정의

- **긍정**: `.devin/agents/`(또는 `.agents/agents/`) md 정의, `.codex/agents/*.toml` 정의로 "read-only 조사자/테스트 러너" 같은 재사용 역할을 프로젝트에 둘 수 있음. Devin 커스텀 서브에이전트는 `model:` 핀으로 저가 모델 고정 가능 — 비용 제어.
- **부정**: `~/.codex/agents/glm_worker.toml`이 이미 사용자 레벨에 존재. 프로젝트 레벨 정의는 유지 대상 증가 — 탐색·검증은 내장 explore/worker가 커버.
- **판정**: **보류**. 반복 패턴이 실제로 발생하면(예: 매번 같은 read-only 감사) 그때 1개만 추가. 사전 생성은 불필요 복잡성.

### 후보 I. `.claude/` 공용 어댑터 디렉터리

- **긍정**: `.claude/rules/`와 `.claude/settings.json` 훅은 Devin·Grok이 **동시에** 읽는 호환 경로 — 하나의 파일로 두 에이전트 패리티 가능.
- **부정**: 저장소에 `.claude/` 신설 = Claude Code 사용자에게도 노출(의도치 않은 로드), Devin `read_config_from.claude` 토글 의존, Cline·Codex는 무관 — "공용" 효과가 2/4에 그침. AGENTS.md가 이미 상시 커버.
- **판정**: **비채택**. `.grok/`과 `.devin/` 어댑터가 더 정직한 위치.

### 후보 J. Cline `~/Documents/Cline/Rules` 전역 룰 복구 + Memory Bank

- **긍정**: 오늘 보고서가 설계한 전역 기본값(무료 모델, 사실/추정 구분, 비밀 보호)은 개인 전역이라 이 프로젝트 외 작업에도 안전하게 작용.
- **부정**: Memory Bank는 별도 마크다운 관례 — 이 repo에는 PROJECT_STATUS.md가 동일 역할을 이미 수행(이중 SoT 위험). 전역 룰은 사용자 파일 — repo가 자동 생성 불가.
- **판정**: **전역 룰 복구는 채택(사용자 확인 후)**, Memory Bank 도입은 **비채택**(PROJECT_STATUS.md가 SoT).

---

## 6. 우선순위 및 적용 순서

| 순위 | 항목 | 유형 | 대상 파일 | 검증 | 되돌리기 | 중단 조건 |
|---|---|---|---|---|---|---|
| P0 | Grok trust 부여 + `.grok/rules/demo1-bridge.md` | 신규 어댑터 | `.grok/rules/demo1-bridge.md`(신규) | `grok inspect` → Project Instructions≥1, Rules 목록에 표시 | 파일 삭제 + `/hooks-trust` 해제 | trust 부여가 UI/정책상 불가하면 보류 |
| P0 | 정합성 묶음 D-1: stale 저널 종결 | 기존 데이터 | journal `devin-config-optimization-apply-20260919-8aec0fa1` | `work_journal.py list --active`가 해당 taskId 미표시 | — (기록 수정 아님, close 호출) | close 전 이벤트 3건을 읽어 미완 주장이면 보류 전환 |
| P0 | 정합성 묶음 D-2: `positive-negative-neutral-judge` 스킬의 `.devin/rules` stale 포인터 | 스킬 본문 1줄 | `.agents/skills/positive-negative-neutral-judge/SKILL.md` | `validate_demo1_skill_family.ps1 -Skills positive-negative-neutral-judge` | checkpoint cycle | — |
| P1 | Devin 훅 이식 `.devin/hooks.v1.json` | 신규 어댑터 | `.devin/hooks.v1.json`(신규) — `.codex` 훅 스크립트 재호출 | `/hooks` 로드 확인 + 테스트 프롬프트에서 additionalContext·registry ref 확인 | 파일 삭제 | 훅이 세션 시작을 차단하면 즉시 삭제 |
| P1 | Cline 전역 룰 부재 확인·복구(사용자 파일) | 사용자 파일 | `%USERPROFILE%\Documents\Cline\Rules\demo1-user-defaults.md` | Cline Rules UI에 표시 | 파일 삭제 | 사용자가 전역 룰 미사용 원하면 보류 |
| P1 | 루트 구형 문서 격리 | 파일 이동(2-target checkpoint) | `GPT_PRO_AGENT_INSTRUCTIONS.md`, `AUTO_*.md`, `HOW_TO_APPLY.md`, `amp-playbook.md` → `docs/legacy/` | 이동 후 참조 grep 0 + compile 무변경 | rescue에서 복원 | 다른 문서/스크립트가 경로로 참조하면 해당 파일만 보류 |
| P2 | 공통 프리플라이트 `scripts/agent_preflight.py` | 신규 스크립트 | `scripts/agent_preflight.py` + `test_agent_preflight.py` | 합성 fixture + 실 실행 JSON 스키마 검증 | 파일 삭제 | 기존 3개 스크립트 호출로 충분하다 판정되면 중단 |
| P2 | Grok 스킬 수동 발견 경로 문서화 | 어댑터 내용 보강 | `.grok/rules/demo1-bridge.md`에 1~2줄 | — | — | — |
| P3 | `semantic-catalog.yaml` validate 수정 + `verify_codex_instructions` 선행 FAIL 정리 | 기존 자산 | `.agents/skills/semantic-catalog.yaml` 등 | `catalog.py validate` + `verify_codex_instructions.ps1` green | checkpoint | 선행 문제 범위가 크면 별도 taskId로 분리 |
| P3 | AGENTS.md 추가 축소(F) | SSOT 단계 수정 | `AGENTS.md` + `INDEX.md` contractRefs | `verify_codex_instructions.ps1` + 크기 측정 | checkpoint | Cline 브리지 미커버 상시 규칙은 절대 추출 금지 |
| 보류 | E(스킬 자동발견 사용자 설정), G(.mcp.json tower), H(서브에이전트 정의), R1(producer-kit 구조 변경), apikey.txt 이동 | — | — | — | — | 사용자 결정 필요 |

---

## 7. 다음 세션에서 그대로 실행 가능한 개선 지시 (ready-to-run)

> 각 항목은 독립 실행 가능. 파일 변경 작업은 DEMO1-WORK-LEDGER 절차(journal open → checkpoint begin → 변경 → 검증 → finish → status 갱신 → close)를 따른다.

1. **`.grok/rules/demo1-bridge.md` 신설**(신규 파일 — checkpoint `sha256:null`). 내용: 프로젝트 루트 고정 / 작업 시작 시 `docs/PROJECT_STATUS.md`+`work_journal.py list --active` 읽기 / 파일 변경은 ledger·lease 절차 / 비밀은 env 이름만 / 무료·로컬 우선 / 스킬은 `.agents/skills/INDEX.md`에서 골라 파일로 읽기 / `grok inspect`로 로딩 확인. 이후 `grok inspect` 재실행으로 Instructions≥1 확인.
2. **stale 저널 종결**: `python -B scripts/work_journal.py status --task-id devin-config-optimization-apply-20260919-8aec0fa1`로 이벤트 확인 후, 실제 적용이 끝났으면 `close --result verified`(또는 `partial`), 미완이면 `note --kind hold`로 사유 기록.
3. **`positive-negative-neutral-judge/SKILL.md` 1줄 수정**: "Always-on contract also lives in `.devin/rules/demo1-operating-style.md`" → 실제 경로(`.windsurf/rules/demo1-hard-constraints.md` 또는 AGENTS.md 절)로 정정. `validate_demo1_skill_family.ps1 -Skills positive-negative-neutral-judge` 확인.
4. **`.devin/hooks.v1.json` 신설**: `.codex/hooks.json`의 두 UserPromptSubmit 핸들러를 Devin 형식으로 옮기되 `commandWindows` powershell 명령 재사용, `timeout` 동일(2s/40s), `additionalContextLimit` 상당 적용. `/hooks`로 로드 확인.
5. **루트 구형 문서 격리**: 대상 4~9개를 `docs/legacy/`로 이동 — 이동 전 `GPT_PRO_AGENT_INSTRUCTIONS.md` 등 파일명 참조 grep, 각 파일은 별도 cycle(2-target). 단 `EXTERNAL_SKILLS.md`는 AGENTS.md에서 참조되므로 **대상外**.
6. **Cline 전역 룰 복구 여부 사용자 확인**: `Documents\Cline` 부재 확인됨 — 오늘 인계 패키지 §A의 `demo1-user-defaults.md` 내용을 사용자 승인 후 재생성(사용자 파일이므로 repo 밖 작업).
7. **PROJECT_STATUS.md §4·§5 갱신**: 이 보고서 등록, stale 저널 종결 기록, Grok untrusted 발견을 §5 관찰 항목에 추가.

---

## 8. 장기 구조 권고 (이번 미적용, 방향만)

- **"읽는 법"을 어댑터가 아니라 코어에**: 어댑터 파일은 어디까지나 "네이티브 로더를 코어 SSOT로 연결하는 포인터"로 유지 — 규칙 본문을 어댑터에 복제하면 네 에이전트×N파일의 드리프트가 발생(이미 `.windsurf/rules`의 Meta 수치 중복이 그 예).
- **스킬/룰 신설 시 4에이전트 로드 표를 의무 기록**: `.agents/skills` 표준을 쓰면 Codex·Devin·Cline에 자동 도달, Grok은 수동 경로 안내. 신규 `.clinerules`/`.windsurf/rules`/`*.grok*` 파일을 만들 때마다 "누가 읽는가"를 파일 머리에 1줄 명기하면 중복 판단이 빨라진다.
- **저널 운영 규칙**: `in_progress`가 세션 종료 시 자동 close되지 않으므로, 작업 시작 시 `list --active`에서 본인 외 stale 저널을 먼저 확인하는 습관을 AGENTS.md §8 권장 순서에 이미 있음 — 실제로 지켜지는지 주기적 점검.
- **Grok은 "생성 전 검증" 채널로**: `demo1-grok-subscription-review`의 acceptance-window 설계상 Grok은 일상적 코드 생성보다 **bounded 리뷰**에 적합 — 어댑터는 "Grok에게 소스 수정을 맡기는 경로"가 아니라 "Grok이 규칙을 읽고 리뷰·소규모 조사를 할 수 있는 경로"로 설계하는 것이 현재 정책과 정합.

---

## 9. 검증 명령 모음 (이 보고서의 근거 재현)

```powershell
# 프로젝트 진입 확인(기존 절차)
python -B scripts/awx_device_bus.py start
python -B scripts/work_journal.py list --active

# Grok 로딩 실측(이 보고서의 핵심 증거)
grok inspect          # → Project trusted: no / Instructions 0 / Skills 3(user) / Hooks 0

# Codex 설정 확인
codex --version       # 0.144.1
# ~/.codex/config.toml: [projects.'c:\abandonware\demo-1\demo-1\src'] trusted,
#   project_doc_max_bytes=65536, awx-control-tower/awx-shared/glm_agent MCP, plugins

# 지침 구조 검증(선행 FAIL 2건은 알려진 상태)
powershell -NoProfile -File scripts/verify_codex_instructions.ps1 -Quiet

# producer-kit 중첩 스킬 실측
dir /b /s /a:d __patch_drop__\producer-kit | findstr /i "agents"
```

---

## 10. 미해결·사용자 결정 필요 항목

1. `~/.grok/config.toml`에 `[skills] paths` 추가 여부(프로젝트 스킬 자동 발견) — 사용자 파일, repo 밖.
2. `producer-kit` 페이로드 구조 변경(`.agents`→비발견명) — 생산자(Notebook/Mac) 압축 해제 계약 확인 필요.
3. `.mcp.json`에 `awx-control-tower` stdio 추가 여부 — 이중 경로 수용 판단.
4. `apikey.txt` → `.secrets/` 이동 — 참조 소비자 확인 필요.
5. `Documents\Cline` 전역 룰 복구 여부.
6. Cline Desktop의 Checkpoints 기능 OFF 여부(UI 설정) — 공유 체크아웃 보호를 위해 권장하나 사용자 판단.

*본 보고서는 읽기+보고서 1개 신규 작성만 수행. 소스·설정 변경 없음. 변경 증거는 저널 `multi-agent-infra-analysis-8f04b751`.*
