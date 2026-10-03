# devin-plugin-roles-v2 — REPORT (2026-10-02)

- taskId: `devin-plugin-roles-v2-ae1a205a` (agent: devin)
- checkpoint: cycle-01 `verified` (verificationExitCode=0, commandId
  `a99e8212-94f3-4419-b83b-b584eb487ad5`) / cycle-02 본 문서·PROJECT_STATUS
- 지시서: Grok Bot DEVIN 지시서 — Codex 플러그인 역할표 v2 (2026-10-02)
- 원문 입력: `C:\Users\nninn\Downloads\USER_PLUGIN_POLICY_20261002.md`

## K1~K10 반영 위치 (SKILL.md v2)

| K | 요구 | 반영 위치 |
| --- | --- | --- |
| K1 | 우선순위 한 줄(로컬 소스 > AGENTS·goal/PASTE > 스킬 > 플러그인 결과) | 도입부 `Priority:` 문장 |
| K2 | Visualize 1개·AWX "기본 → 기본 health 실패 시만 복구용" 단일 순서 | 플러그인 계약 `AWX Control Tower`, `Meta Wearables … Visualize …` 통합 불릿 |
| K3 | seam→RED→최소 수정→GREEN + 수정 후 회귀·focused·Verify-RAG + `PARTIAL(H2 DDL baseline N)` 표기 | `Superpowers` 불릿 |
| K4 | Browser 기본 세트(안녕?/일반 질문/변경 경로 1~2) + main /chat interview OFF 판정 + 18180 우선 + kro.kr ≤3·`[codex-test]` + 18180 프로필 복구 + HTTP 상태/reasonCode/requestId/첫 본문 시간 + 200·SSE≠성공 | `Browser` 불릿 |
| K5 | 로컬 status/HEAD/branch → origin(AbandonWareAi) SHA 비교; 미커밋 파일은 GitHub diff 증거 금지 | `GitHub` 불릿 |
| K6 | 공식 도메인만, 세션 ≤5회, "확인일·적용 버전" 한 줄 | `Exa` 불릿 |
| K7 | 경로 순서 native→glm_agent MCP→SESSION_UNAVAILABLE, glmKeyPresent boolean, deliveryMarker·task_received, 세션 ≤3회, 400/401/403/429·크레딧 무재시도, "zai/glm-5.2 미지원"=provider=openai 설정 문제 | `glm_worker` 불릿 |
| K8 | 원문 허용/금지 + smoke 401/403 = `auth-blocked` + API_ROUTING_SPEC 세부 분류(401=KEY_INVALID_OR_EXPIRED, 403=PLAN_GATE|FORBIDDEN_REGION_OR_IP) 병기 | `Vercel` 불릿 |
| K9 | Computer 최후 수단(Windows/localhost), Meta Wearables=Display 작업만, 나머지 이유 한 줄+읽기 위주 | `Computer`, `Meta Wearables …` 불릿 |
| K10 | 보고 블록: `외부 API:` 줄 + `PLUGIN_USAGE:` + GLM 형식 | `Report block` 섹션 + lint 도구 |

## 충돌 표 (원문 vs 기존 스킬 → 채택)

| # | 원문(2026-10-02) | 기존 SKILL.md | 채택 | 이유 |
| --- | --- | --- | --- | --- |
| 1 | glm_worker = 읽기 탐색 + 반박 검토 두 용도, 세션 ≤3회 | "one post-patch rebuttal pass" | 원문 | 사용자 원문이 최신이고 더 구체적; 기존 rebuttal 용도는 원문 ②에 포함 |
| 2 | Browser: UI·스트리밍·인증 영향 시 기본 세트 + 대상 화면 규칙 | "only the repro the goal text or target file names" | 병합 | 기본 세트 채택 + "해당 목표가 지명한 repro/시나리오" 조건 유지; General Java edit은 여전히 Browser 불필요 |
| 3 | Vercel smoke 401/403 → `auth-blocked` + 시도한 인증 경로 | 세부 분류, auth-blocked로 collapse 금지 | 둘 다 | K8이 라벨+세부 분류 병기를 명시; 기존 세부 분류는 삭제하지 않음 |
| 4 | Verify-RAG H2-DDL-only partial → `PARTIAL(H2 DDL baseline N)` 별도 표기 | (해당 규칙 없음) | 원문 | 신규 규칙, 충돌 아님 |
| 5 | Visualize 1개/AWX 단일 순서 | 이미 동일(중복 없음) | 기존 유지 | 충돌 아님, K2는 정리 확인 |

Self-Ask 판정 대상이었던 문장은 없음(모든 충돌이 위 표에서 AUTO 결정) —
AUTO_DECISION 로그는 저널/최종 보고에 기록.

## 검증 결과

- `python -B -m unittest scripts.test_codex_plugin_usage_lint` → **18 tests, exit 0**
- `python -B -m unittest scripts.test_selfask_triad` → **exit 0** (MARKER_RE 재사용 경로 무손상)
- 두 모듈 묶음을 `run_verified_command.py`로 기록 실행 → runId `a99e8212-94f3-4419-b83b-b584eb487ad5`, exit 0
- 실제 Codex 보고서 lint(읽기 전용): `data/agent-handoff/codex-p6-r2/REPORT.md` → **exit 2 FORMAT** (`missing-line:외부 API`, `missing-block:PLUGIN_USAGE`) — 도입 전 보고서에 블록이 없는 현 상태를 정상 탐지. 대상 파일 sha256 `2A1FB51E6EC3…` 실행 전후 불변.
- 스캐너 자기점검: `build_error_miner.SECRET_FRAGMENT_RE`를 변경 파일 전체에 적용 → hits 0.

## 파일 크기·sha256 (앞 12)

| 파일 | bytes | sha256 앞12 |
| --- | --- | --- |
| `.agents/skills/demo1-codex-plugin-roles/SKILL.md` | 8952 | `c6c9905de653` |
| `.agents/skills/demo1-codex-plugin-roles/references/user-policy-20261002.md` | 6710 | `105f90d5d9ab` (= Downloads 원본 동일 sha) |
| `.agents/skills/demo1-codex-plugin-roles/agents/openai.yaml` | 406 | `a2082c073385` |
| `AGENTS.md` | 82039 | `37de78b427c8` (preimage `28ebaa45…`, 본 작업 변경은 104~105행 ±2줄만) |
| `agent-prompts/codex-plugin-roles-shortcut.md` | 1109 | `3aeaeb4ee39c` |
| `docs/operations/codex-plugin-roles-footer.txt` | 781 | `3e3b9a8623a8` |
| `scripts/codex_plugin_usage_lint.py` | 7774 | `7474627424b9` |
| `scripts/test_codex_plugin_usage_lint.py` | 6234 | `d74d54719d6d` |

## Acceptance 증거 요약

- A1 PASS — 원문 바이트 보존(sha `105f90d5d9ab…` = Downloads 원본)
- A2 PASS — K1~K10 반영, 매트릭스 4종·Vercel 세부 분류 보존, 8952B ≤ 9KB
- A3 PASS — AGENTS.md preimage↔현재 diff = 104~105행 -2/+2, 다른 블록 0
- A4 PASS — footer 781B ≤900B, shortcut 갱신, openai.yaml default_prompt 반영
- A5 PASS — lint+18 tests exit 0, test_selfask_triad exit 0
- A6 PASS — codex-p6-r2 REPORT.md에 lint 실행(읽기), exit 2 기록, 파일 sha 불변
- A7 PASS — `main/` 변경 0건 추가(git dirty 223줄 전후 동일), chat.js sha256 `4225D9447524…` 시작=끝, 타인 lease 경로 변경 0, 비밀값 출력 0
- A8 — 본 문서 + PROJECT_STATUS 1행 + lease 해제로 마감

## 제안 — Codex "사용자 지정 지침" 한 줄 (사용자가 직접 설정, HOLD 범위)

> 모든 작업에 `$demo1-codex-plugin-roles` v2를 자동 적용하고, 최종 보고서에는 `외부 API:` 줄과 `PLUGIN_USAGE:` 블록을 포함한다. 검사: `python -B scripts/codex_plugin_usage_lint.py --report <보고서>`
