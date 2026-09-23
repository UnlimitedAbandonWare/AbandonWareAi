# Cline Desktop 에이전트 강화 — 인계 패키지 (2026-09-19)

대상: GPT Pro 검토/실행용. 환경: Cline Desktop **0.0.32** (`D:\ai\Cline`, 스탠드얼론), provider = Cline OAuth, 모델 = **Kimi K3 (free) / Low**, 프로젝트 루트 = `C:\AbandonWare\demo-1\demo-1\src`.

이 문서는 (A) 이미 적용된 연결, (B) 추가 제안 — 비용/근거/방법 포함, (C) 불가·보류 항목(근거), (D) 검증 체크리스트로 구성한다.

---

## A. 이미 적용됨 (변경 완료, 되돌리기 방법 포함)

| 위치 | 내용 |
|---|---|
| `AGENTS.md` (자동 로드) | 프로젝트 규칙 SSOT. Cline이 자동 인식 — 복제 없이 재사용 |
| `.agents/skills/` (자동 검색) | Cline 네이티브 프로젝트 스킬 경로. 97개 중 92개 발견 가능(frontmatter 검증 완료) |
| `.clinerules/00-demo1-cline-bridge.md` | Cline 전용 브리지: 루트 고정, work-ledger 절차, checkpoint-restore 되돌리기 금지, 런타임 보호, 무료 모델 유지 |
| `.clinerules/10-meta-display-runtime.md` | `paths:` 조건부 규칙 — 실재 경로로 교정(`**/assets/display/**`, `**/*meta-display*/**`, `**/*Conversate*`, `**/application-meta-display.yml`, `**/META_DISPLAY*`, `**/receiver.js`) |
| `.clinerules/20-patchdrop-guard.md` (신규, 2차) | `__patch_drop__/**` 매칭 시에만 로드 — `$patchdrop-safe-patch-orchestrator` + `demo1-patchdrop-manual-default` 포인터, lease/lock 보호 |
| `.clinerules/30-skill-family-edits.md` (신규, 2차) | `.agents/skills/**`, `.clinerules/**`, `.cline/skills/**`, `AGENTS.md` 매칭 시 로드 — `$demo1-skill-family-postprocessor`, name=MCP tool id 규칙 |
| `.clinerules/40-frontend-bff.md` (신규, 2차) | `frontend/**` 매칭 시에만 로드 — `npm run lint`+`npm test`, `$nextjs-rag-bff` 포인터 |
| `.agents/skills/demo1-meta-display-simple-caption/SKILL.md` (2차 정리) | 중복 `## Runtime policy (FIELD_TESTED)` 1개 제거(42–50행). canonical 섹션 유지, 유일 신규 정보(`processResources`)만 병합 — 사용자 추가분 보존 |
| `.clinerules/workflows/{compile-verify-smoke,force-restart-meta-display}.md` | `.windsurf/workflows/` → Cline 슬래시 커맨드(`/<name>`)로 변환 |
| `.cline/skills/{verify-boot,archive-restore,archive-search,build-error-miner,run-pipeline}/SKILL.md` | name-mismatch 5개 스킬의 최소 커넥터(본문 미복제, 원본 읽기 지시) |
| `.clineignore` | `.secrets/`, `.env*`, `apikey.txt`, `shared.env*`, `config/secrets/`, `data/device-resources/` 차단 |
| `C:\Users\nninn\Documents\Cline\Rules\demo1-user-defaults.md` | 전역 기본값(한국어, 사실/추정 구분, 최소 변경, 비밀 보호, 무료 모델 유지) |

되돌리기: 위 파일들 삭제. 변경 증거 = `data/agent-handoff/codex-autonomy/cline-desktop-rules-kimi-cc998979/`, `cline-agent-strength-7b12ad40/` (journal + checkpoint).

---

## B. 추가 제안 (GPT Pro 작업 후보)

### B-1. AGENTS.md always-on 슬림화 — ✅ 사용자가 이미 적용, 추가 추출 불필요
- 실측: `AGENTS.md` 61,294 → **30,938 bytes**(세션 사이 사용자 슬림화). 지명된 저빈도 섹션(PatchDrop, AutoLearn, Workspaces, Autostart, BRAVE, Source-Directive-Auto)은 이미 2~4줄 포인터형.
- 잔여 본문은 소유권·비밀·실행 중 서비스 보호 등 상시 안전 제약 — 추가 추출은 task-entry 안전을 깎으므로 미적용(근거 기반 판정).
- 이번 라운드 실제 정리: `demo1-meta-display-simple-caption/SKILL.md`의 중복 Runtime policy 섹션 제거(-10줄).

### B-2. 조건부 규칙(`paths:`) 추가 — ✅ 적용 완료 (2차)
- `20-patchdrop-guard.md`, `30-skill-family-edits.md`, `40-frontend-bff.md` 신규 생성. `10-meta-display-runtime.md`의 경로를 실재 위치로 교정(루트 `assets/` 미존재 → `**/assets/display/**` 등).
- 정적 매칭 검증 16/16 PASS(정매칭 9 + 오매칭 7). 검증 증거: `cline-agent-strength-impl-dcfc9b3a/cycle-01`.

### B-3. name-mismatch 스킬 5개 — ✅ 조사 완료, 커넥터 유지가 정답
- 소비자 확인 결과: `verify_boot` 등 `name:` 값은 **MCP 도구 ID** — `agents/openai.yaml`("Use verify_boot through scripts\awx_mcp_toolbox.ps1"), `semantic-catalog.yaml`, `semantic-index.json`, `SEMANTIC_INDEX.md`, `INDEX.md`, `awx_mcp_toolbox*.py`, 테스트 스위트에서 참조.
- 원본 `name:` 정정 = 스킬명↔도구명 분리 + 카탈로그/테스트 깨짐 위험. `.cline/skills/` 커넥터 5개를 **유지**하는 것이 최소 변경·무손실 정답(본문 미복제, 포인터만).

### B-4. 스킬 메타데이터 비용 절감 (선택)
- ~97개 스킬 메타데이터가 세션당 상시 소비됨. Skills UI에서 당분간 안 쓸 스킬 개별 토글 OFF 권장(파일 삭제 금지 — 다른 에이전트 공용).
- 후보 OFF: Mac mini/Notebook 전용(`macmini-*`, `notebook-*`, `desktop-smb-ack`), MCP tower 계열, ablation/harmony 계열.

### B-5. `.mcp.json` supabase MCP (조건부)
- 현재 `${SUPABASE_PROJECT_REF}` env + OAuth 필요. Cline MCP 설정 = `~/.cline/data/settings/cline_mcp_settings.json`.
- 실제로 Cline 세션에서 DB 접근이 필요할 때만 연결; 불필요하면 건드리지 않음.

### B-6. Auto-approve 범위 (UI 설정, 파일 아님)
- 권장: 파일 읽기/검색 자동 승인, 쓰기·명령 실행은 승인 유지. "모든 명령 자동 승인" 금지(공유 체크아웃 + 라이브 서버).

### B-7. Kimi K3 "Low" 유지
- Low = reasoning effort로 추정. 소규모 대표 작업으로 Low/기본값 비교 후 필요 시에만 조정. 무료 한도 소진·모델 종료 시 중단 보고(규칙에 반영됨).

---

## C. 불가·보류 (근거)

| 항목 | 결론 | 근거 |
|---|---|---|
| `.codex/hooks`(UserPromptSubmit: awx_device_bus, source_edit_triage) Cline 이식 | **불가(Windows)** | Cline 스크립트 훅은 shebang/git-hook 방식, 공식 README "Windows: Not currently supported". SDK 플러그인(TS `AgentPlugin`)만 가능 — 현 시점 과잉 |
| Cline 체크포인트/되돌리기로 파일 복원 | **금지(운영 규칙)** | 공유 체크아웃 — 다른 에이전트 변경을 지울 수 있음. Settings에서 체크포인트 기능이 켜져 있으면 복원 버튼 사용 자제(끄는 것까지는 사용자 판단) |
| DevWatch/Start-RAG/ForceRestart 자동 호출 | **금지** | 착용 중 Meta Display 런타임 보호(브리지 규칙에 명시) |
| `.devin/`, `.windsurf/rules/` 내용 복제 | **불필요** | 각각 포인터 파일/AGENTS.md 부분집합 — 이미 커버 |
| 추가 CLI/확장/플러그인 설치 | **보류** | 필요 근거 없음 |

---

## D. 검증 체크리스트 (UI, 사용자 실행)

1. Customize → Rules: `AGENTS.md`, `.clinerules` 2개 파일, 글로벌 `demo1-user-defaults.md` 표시 + ON
2. Skills 탭: `.agents/skills` + `.cline/skills` 5개 커넥터 표시(없으면 Settings → Features → Enable Skills)
3. 워크플로우: `/compile-verify-smoke`, `/force-restart-meta-display` 슬래시 커맨드 인식
4. 조건부 규칙: `main/resources/static/assets/display/meta/receiver.js` 파일을 연 상태로 새 세션 → "Conditional rules applied: workspace:10-meta-display-runtime.md" 알림 확인. `__patch_drop__`/`frontend/` 파일로도 각각 20/40 규칙 발동 확인, 무관한 파일 작업 시 미발동 확인
5. 새 세션: "프로젝트 루트와 파일 변경 절차" 질문 → 루트 + work_journal/checkpoint 응답
6. 모델 표시가 `Kimi K3 (free)` 유지인지

*작성: Devin. 근거 파일 해시는 checkpoint cycle-01 (`cline-agent-strength-7b12ad40`) 참조. 2차 적용 증거: `cline-agent-strength-impl-dcfc9b3a/cycle-01` — 조건부 규칙 3개 신규 + 경로 교정 + SKILL.md 중복 제거, 정적 검증 16/16 PASS. Desktop/새 세션 검증은 사용자 수행 항목으로 미검증.*
