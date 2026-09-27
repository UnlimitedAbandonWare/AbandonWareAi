# Devin Desktop 룰·스킬·설정 최적화 감사 보고서

- 작성: Devin (Devin Desktop, SWE-2 Max) · 2026-09-19
- 작업 루트: `C:\AbandonWare\demo-1\demo-1\src`
- 근거: 설치본 공식 문서 `...\Programs\Devin\resources\app\extensions\windsurf\devin\share\devin\docs\*.mdx` + 로컬 파일 실측. `{스터프6}` 지시문은 검증 대상으로만 사용.
- 성격: 감사 보고서 + **적용 결과**(2026-09-19 후속 승인으로 §8 추가). §1–§7은 감사 시점 원문; 실제 적용·검증·미해결은 §8 참조.

---

## 1. 환경 검증 결과

| 항목 | 실측 |
|---|---|
| Devin Desktop 설치 | `C:\Users\nninn\AppData\Local\Programs\Devin\Devin.exe` 존재 (226MB, 2026-09-16 설치) |
| 앱 데이터 | `%APPDATA%\devin\` = `C:\Users\nninn\AppData\Roaming\devin\` (config.json: org/theme/shell만, `read_config_from` 없음 → 모든 레거시 임포트 활성) |
| 사용자 전역 룰 | `%APPDATA%\devin\AGENTS.md` 없음, `~/.devin/` = extensions+argv.json만 (rules/global_rules 없음), `~/.claude/CLAUDE.md` 없음 |
| 레거시 전역 룰 | `~\.codeium\windsurf\memories\global_rules.md` 존재하나 **0바이트(빈 파일)** — Devin이 읽어 주입하지만 비용 0 |
| 전역 스킬 | `~\.agents\skills\` 3개 (awx-source-surgeon, glm-offload, token-efficient-agents) 로드됨. `~\.codeium\windsurf\skills\`·`%APPDATA%\devin\skills\` 없음 |

## 2. {스터프6} 환각 검증

| 주장 | 판정 |
|---|---|
| 설치 경로 `...\Programs\Devin` | ✅ 정확 |
| 전역 룰 = `~\.codeium\windsurf\memories\global_rules.md` | ⚠️ 부분 환각 — 파일은 실재·로드되나 **비어 있음**. 공식 전역 룰 경로는 `%APPDATA%\devin\AGENTS.md`(Windows). 이 파일은 레거시 Cascade 호환 위치일 뿐 |
| 프로젝트 룰 `.devin\rules\*.md` 우선 확인 | ✅ 공식 우선 위치 맞음. 단 현재 **미존재** (`.devin\`엔 RULES_SSOT.md+PROMPTS뿐, 둘 다 룰로 로드되지 않음) |
| `.windsurf\rules`, `.windsurfrules`, `AGENTS.md` 로드 | ✅ 모두 로드됨 (`.windsurfrules`는 파일 없음) |
| Devin 스킬 위치 `.windsurf\skills\` | ⚠️ 유효 경로지만 미존재. 실제 스킬은 `.agents\skills\` — 이것도 공식 지원 위치(".agents skills standards") |
| 전역 스킬 `~\.codeium\windsurf\skills\` | ✅ 유효 경로(`~/.codeium/<channel>/skills/`), 미존재 |
| `.agents\skills\` 다중 에이전트 스킬 | ✅ 정확, 97개 로드 중 |
| `.windsurf\workflows\` 조사 | ⚠️ 파일은 존재하나 **Devin은 workflows를 임포트하지 않음**(공식 문서 명시). 스터프6의 "자동 선택" 전제 자체가 무의미 — 이미 동명 스킬(compile-verify-smoke, force-restart-meta-display)로 이전 완료 |
| Cascade Memory 이전 검토 | 불필요 — global_rules.md가 비어 있어 이전할 내용 없음 |
| 스터프6가 빠뜨린 것 | `%APPDATA%\devin\AGENTS.md` 전역 룰 경로, `~/.devin/rules/`, `read_config_from` 토글, `__patch_drop__` 내부 중첩 스킬 오염, `.codex` 훅 미적용 |

## 3. 현재 로드 맵 (언제 무엇이 읽히는가)

| 파일/디렉터리 | 로드 조건 | 크기 | 비고 |
|---|---|---|---|
| `AGENTS.md` | **매 세션 항상(always-on)** | 60,751B / 443줄 | 다중 에이전트 공용 SSOT. 세션당 ≈15K 토큰 — 최대 단일 비용 |
| `.windsurf\rules\demo1-hard-constraints.md` | always_on | 1,169B | AGENTS.md 5개 절과 내용 중복(이중 로드) |
| `.windsurf\rules\meta-rayban-display-runtime.md` | glob: `assets/display/**`, `meta-display/**`, `*Conversate*`, `application-meta-display.yml`, `META_DISPLAY*` | 1,881B | AGENTS.md §Meta runtime의 압축 복제 — 그런데 AGENTS.md 쪽이 더 김(설계 의도 역전) |
| `.windsurf\workflows\*.md` (2개) | **로드 안 됨** (Devin 미임포트) | 477B+711B | Cascade 전용 유물, 동명 스킬 존재 |
| `.agents\skills\` 97개 | 이름+description 매 세션 목록 주입, 본문은 호출 시 | ≈3K 토큰/세션 | 모두 SKILL.md 보유. `INDEX.md`(47KB)는 SKILL.md가 아니라 비로드·온디맨드 — 정상 |
| `__patch_drop__\producer-kit\*\.agents\skills\` | **4개 키트, 82개 스킬 디렉터리** | — | 전송 페이로드인데 재귀 스킬 탐색에 잡힘 → 검색 오염 + canonical 스킬 밀어냄 |
| `data\agent-handoff\*\preimages\.agents\skills\` | 체크포인트 사본 ≥3트리 | — | 동일 오염원 |
| `.devin\RULES_SSOT.md`, `.devin\PROMPTS\*.md` | 로드 안 됨(문서/붙여넣기용) | — | PROMPTS는 UTF-8 정상(콘솔 깨짐만). RULES_SSOT 내용 정확 |
| `.codex\` (config.toml, hooks.json) | **Devin 미임포트** — Codex 전용 | — | UserPromptSubmit 훅(디바이스 버스+소스편집 트리아지)은 Devin 세션에서 미실행 |
| `.mcp.json` | 임포트됨 (Claude 채널 경로) | — | 유지 |
| `~\.codeium\windsurf\memories\global_rules.md` | 매 세션 로드 | 0B | 빈 파일, 비용 0 |

## 4. 발견된 문제 (영향도 순)

1. **[최대 오염원] `__patch_drop__` 중첩 스킬 82개 + preimage 사본.** producer-kit 4개(mcp-control-loop 6, multi-device-r3 65, public-domain-migration 6, trace-memory-runtime 6)는 Mac/Notebook 생산자용 **전송 페이로드**인데 `.agents/skills` 구조 그대로라 스킬 `search`가 재귀 스캔해 canonical보다 키트 사본을 먼저 노출(설명 문구도 다름). 실증: canonical `demo1-source-edit-three-way-preflight`, `demo1-subsystem-patch-directive`가 디스크에 존재함에도 세션 스킬 목록에서 누락 — 목록이 포화돼 canonical이 밀려난 정황.
2. **AGENTS.md 60KB always-on.** 공식 문서도 "Rules/AGENTS는 최소로, 절차는 Skills로" 권장. PatchDrop 번들 규칙(11줄), AutoLearn 리뷰(7줄), SMB/워크스페이스 상세(9줄), 동시편집 리스 수명주기(10줄), Active Runtime Map(13줄) 등 절차성 내용이 다수 — 스킬 참조 한 줄 + 상세는 스킬/INDEX로 내리는 게 방향. 단 다중 에이전트 SSOT라 단계적 정리 필요.
3. **룰 이중화.** `demo1-hard-constraints.md`(always_on)는 프로젝트 루트/Git-local-first/work-ledger/minimal-diff/openssl 등 AGENTS.md 절을 재진술 → 매 세션 이중 로드(≈300토큰). `meta-rayban-display-runtime.md`(glob)는 AGENTS.md §Meta runtime과 수치 동일하게 중복 — 본래 의도(AGENTS=짧은 요지, glob=상세)와 달리 AGENTS 쪽이 더 길어진 상태.
4. **`.windsurf\workflows\` 사장.** Devin 미임포트(문서 명시). 동명 스킬이 이미 존재해 기능 공백 없음 — Windsurf Cascade를 아직 쓰면 호환 유지, Devin만 쓰면 삭제 후보.
5. **Codex 훅 패리티 공백.** `.codex\hooks.json`의 UserPromptSubmit 훅(awx_device_bus + source_edit_triage)은 Devin 세션에서 실행되지 않음. 디바이스 버스는 AGENTS.md의 수동 실행 규칙으로 보완 중(본 세션도 수동 실행: status=observed, inbox 참조 수신). 소스편집 트리아지 훅은 Codex 전용 — Devin에선 apply-time `guarded_source_edit.js`가 남아 있으나 프롬프트 단계 트리아지는 부재.
6. **(소) 22/97 스킬 `agents/openai.yaml` 부재** — Codex CLI 페어링 메타데이터로 Devin엔 무관. 참고만.
7. **(정상 확인)** 스킬 description 길이 ≤86자로 양호, 다중행 YAML 정상, `$skill` 참조 30개 전부 실재 디렉터리와 일치, `.cursor`/`.claude`/`.github/skills`/`AGENTS.local.md`/`CLAUDE.md` 없음, `read_config_from` 미설정이나 임포트 대상 자체가 없어 비용 0.

## 5. 판정표

| 대상 | 판정 | 근거 |
|---|---|---|
| `AGENTS.md` | **수정(축소) 후보** | always-on 60KB. 절차 절 → 스킬/INDEX 포인터화. 다중 에이전트 SSOT라 단계적 |
| `.windsurf\rules\demo1-hard-constraints.md` | **유지**(선택적 다이어트) | 압축 안전층으로 가치 있음. Git 문장만 AGENTS 포인터로 대체 가능 |
| `.windsurf\rules\meta-rayban-display-runtime.md` | **통합 후보** | AGENTS §Meta runtime과 중복. 상세를 glob 룰로 모으고 AGENTS는 포인터화 권장 |
| `.windsurf\workflows\*.md` | **구형 호환 유지 또는 삭제 후보** | Devin 미로드, 비용 0. Cascade 사용 여부로 결정 |
| `.agents\skills\` (97) | **유지** | 공식 위치, description 양호 |
| `__patch_drop__\producer-kit\*\.agents\skills\` (82) | **수정 후보(격리)** | 전송 페이로드가 라이브 탐색을 오염. 키트 구조 변경 or 워크스페이스 밖 이동 검토 |
| `data\agent-handoff\*\preimages\.agents\skills\` | **유지(복구 사본)** | 체크포인트 무결성상 삭제 불가. 탐색 제외 방법이 있다면 적용 |
| `.devin\RULES_SSOT.md`, `PROMPTS\` | **유지** | 문서 목적 정확, 비로드 |
| `.devin\rules\`, `.devin\skills\`, `.devin\config.json` | **생성 불필요** | `.windsurf\rules`가 이미 동일 로드. RULES_SSOT.md가 이중작성 금지 명시 — 현 구조 정합 |
| `.codex\` | **유지** | Codex 전용 구성, Devin 미간섭으로 오염 없음 |
| `~\.codeium\windsurf\memories\global_rules.md` | **유지(빈 파일)** | 비용 0. 전역 룰 필요 시 `%APPDATA%\devin\AGENTS.md` 사용 |
| `INDEX.md` | **유지** | 비로드·온디맨드 설계 정상 |
| `.mcp.json`, `.env*`류 | **유지/비밀 유지** | 값 미출력 원칙 준수 |

## 6. 권장 최소 수정 (감사 시점 권장안 — 실제 적용은 §8)

- **R1 (최우선)**: `__patch_drop__\producer-kit\*\.agents\skills` 라이브 탐색 격리. 선택지: (a) producer-kit 전체를 워크스페이스 스캔 범위 밖(예: `src` 형제 디렉터리)으로 이동 — PatchDrop 교환 규약 확인 필요; (b) 키트 내부 `.agents` 디렉터리명을 페이로드명(예: `agents-payload`)으로 변경해 로더가 못 읽게 — 생산자 측 압축 해제 계약 확인 필요. **다중 머신 규약이라 사용자 결정 필요.**
- **R2**: AGENTS.md §`DEMO1-META-RAYBAN-DISPLAY-RUNTIME`(34줄)을 3~4줄 포인터로 축소하고, 누락 상세(rolling trigger 120자/2.5s/10s 쿨다운/20s TTL 등)를 `.windsurf\rules\meta-rayban-display-runtime.md`로 이전 → always-on ≈3KB 절감. glob 패턴이 디스플레이 관련 파일을 모두 덮는지 함께 점검.
- **R3**: AGENTS.md 절차성 절(PatchDrop 번들, AutoLearn 리뷰, 워크스페이스/SMB 상세, 동시편집 리스, Active Runtime Map 등)을 스킬 참조 1줄 + 상세 이전으로 단계적 축소(목표 <20KB). INDEX.md 경로 갱신 동반.
- **R4**: `.windsurf\workflows\` — Devin 전용 사용이 확실하면 삭제, Cascade 병용이면 유지(비용 0).
- **R5 (선택)**: Devin용 소스편집 트리아지 훅 패리티가 필요하면 `.devin` 훅/설정으로 source_edit_triage 이식 검토.
- **R6 (선택)**: 개인 전역 룰이 필요하면 `%APPDATA%\devin\AGENTS.md` 신설 — 현재 불필요.

## 7. 기대 효과·남은 위험

- 효과: R1로 스킬 탐색에서 중복 ~82건 제거·canonical 스킬 밀림 해소; R2+R3로 always-on 토큰 50%+ 절감(60KB→~20KB대); 나머지는 위생.
- 위험: R1은 Notebook/Mac 생산자의 키트 추출 계약에 영향 — 미확인 시 변경 금지. R2/R3는 Codex·Grok 공용 SSOT를 건드리므로 staged 적용 + `INDEX.md` contractRefs 동기 갱신 필요. glob 룰 이전 시 패턴 누락하면 상세 지침이 도달 불가가 됨.
- 미수정 사유(감사 시점): 사용자 요청 산출물은 보고서이며, 대상 파일들이 다중 에이전트 공용 SSOT라 stepwise-ask 규칙상 단독 변경 부적합.

---

## 8. 적용 결과 (2026-09-19, task `devin-config-optimization-apply-20260919-8aec0fa1`)

### 8.1 가설 재판정

| 감사 주장 | 판정 | 근거 |
|---|---|---|
| "키트 82사본이 canonical 스킬을 밀어냈다" | **기각** | 실제 원인 = canonical 2개 SKILL.md frontmatter의 따옴표 없는 `: ` → YAML 파싱 실패 → 로더가 스킵 → 검색이 키트 사본을 대신 노출. YAML 수정 후 라이브 스킬 목록에 canonical 정상 등장 확인 |
| 키트 4개/82개 | **부분 확인** | 감사 시점 4키트/82파일 정확. 작업 중 생산 활동으로 6키트/100파일로 증가 — 디렉터리 단위 제외라 신규 키트도 자동 제외됨 |
| `.devinignore`로 스킬 스캔 제외 가능 | **기각** | 실측: `.devinignore`는 파일 접근을 차단하지만 스킬 스캐너는 제외 못함(키트 계속 노출). 되돌림 |
| `.gitignore`가 스킬 스캔에 적용 | **확인** | `data/agent-handoff/`(기존 ignore) 하위 preimage 스킬이 스캔 결과에 없음으로 입증. 같은 메커니즘으로 `__patch_drop__/producer-kit/` 제외 적용 |
| AGENTS.md ≈60KB/443줄 | **확인** | 실측 61,294B/444줄(감사 후 미세 증가) |

### 8.2 실제 변경 목록

| 파일 | 변경 | 이유 |
|---|---|---|
| `.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md` | frontmatter description 따옴표 수정 | YAML 파싱 오류 → 스킬 미로드 원인 |
| `.agents/skills/demo1-subsystem-patch-directive/SKILL.md` | 동일 | 동일 |
| `.gitignore` (L214-217) | `/__patch_drop__/producer-kit/` 제외 추가(주석 포함) | 스킬 스캔 오염 제거. 전송 경로·파일 접근·원격 설치 계약 무손상 |
| `AGENTS.md` | 61,294B → 34,542B (-43.6%) | 절차성 절 → 스킬/참조 포인터화. BEGIN/END 마커 전쌍 보존, INDEX `AGENTS.md#` 앵커 전수 해소 확인 |
| `.agents/skills/demo1-meta-display-simple-caption/SKILL.md` | `Runtime policy (FIELD_TESTED)` 절 추가 | Meta 상세의 공통 정본 — Codex/Grok도 도달 가능(`.windsurf` 전용 아님) |
| `.agents/skills/demo1-rag-strategy-orchestration/references/strategy-map.md` | canonical seam/트리거 상세 추가 | AGENTS Active Runtime Map의 수용처 |
| `.windsurf/rules/meta-rayban-display-runtime.md` | glob에 `**/lms/assist/**` 추가(Display Java seam 커버), 본문→얇은 안전층+정본 포인터 | 중복 상세 제거, Java 측 glob 공백 메움 |
| `.devin/hooks.v1.json` | 신설 | R5: UserPromptSubmit→`source_edit_triage.ps1` 재사용; PreToolUse `^(write|edit|apply_patch|notebook_edit)$`→`devin_pre_edit_guard.ps1` |
| `scripts/devin_pre_edit_guard.ps1` | 신설 | 쓰기 직전 외부 활성 리스 충돌 검사(`Get-AwxSourceEditConflictDecision` 재사용). block=exit2, allow=0, guard오류=1(비차단) |
| `scripts/work_journal.py` | `invalid-event-kind`/`invalid-close-result` 오류에 허용값 표기(KINDS/RESULTS 재사용) | 첨부 기록의 반복 guess-실패 차단 |
| `scripts/test_work_journal.py` | 신설(4 테스트) | 상기 수정 회귀 방지 |
| `.devin/PROMPTS/meta-display-paste-daily.md` | 자동 @칩 6→5, judge는 조건부 안내로 | 일상 패치마다 triad 팬아웃은 과잉(spend-guard 취지) |
| `.devin/PROMPTS/meta-display-paste-debug.md` | 자동 @칩 14→9, 검색복구·재시작·smoke·judge는 조건부 줄로 | 상황 무관 대량 호출 제거. 15개 @이름 전부 실재 스킬 확인(데드 칩 없음) |

### 8.3 검증 결과

- **YAML**: `.agents/skills` 97개 frontmatter 전수 파싱 — 오류 0, 이름 중복 0.
- **스킬 로더**: 수정한 2개 스킬 라이브 목록 등장 확인(canonical 경로).
- **탐색 격리**: `.gitignore` 제외 메커니즘 실측 적용; 키트 파일·전송 경로는 그대로 존재(6키트/100파일).
- **AGENTS.md**: 마커 쌍 전부 보존; INDEX.md `AGENTS.md#` 앵커 전수 해소(56개 중 `legacyLocation`의 `Reusable Prompt Packs:L*`은 원래부터 없는 절을 가리키는 기존 사멸 참조 — 이번 변경 무관).
- **훅 계약**: 공식 stdin JSON → stdout JSON 계약 테스트 — 리스 보유 경로 `decision:block`+exit2, 무관 경로 exit0, malformed 입력 exit1(stderr 사유), 비편집 프롬프트 무출력. 라이브 프로브 리스로 차단 경로 실증 후 리스 정상 종료.
- **journal**: 유효 kind/result 수용, 무효값은 허용값을 나열한 오류 반환, `scripts/test_work_journal.py` 4/4 통과.

### 8.4 미달성·보류·잔여 위험

- **AGENTS.md ≤20KB 목표 미달(34.5KB)**: keep-list 필수 제약 + 작업 중 `lens-hint` 계열 동시 작업이 추가한 `DEMO1-SERVER-LIFECYCLE-VERIFY` 절(≈4.6KB, 타 태스크 소유 — 미건드림). 필수 제약을 지워 수치를 맞추지 않음.
- **`.windsurf/workflows/*.md` 보류**: 동명 스킬이 "Cascade-only slash runbooks"로 명시 참조 — Cascade 소비자 검증 불가라 삭제 보류(Devin 비용 0).
- **원격 키트 계약**: `INSTALL.notebook.ps1`의 `.agents/skills` 페이로드 경로는 원격 미검증이라 경로 변경 없이 ignore-제외로만 처리.
- **복구 사본 상태**: `before/AGENTS.md`(61,294B, sha256 `4b27ee…9202`, 내용 완전 재구성·크기 검증), `before/.agents/skills/...`(caption 4,060B·strategy-map 7,457B CRLF 복원), `.windsurf` 룰은 **바이트 미보존** — sha256(`bc53aa…d869` in `manifest-windsurf-rule.json`) 수준 증거만(편집 전 lease begin이 해시 검증은 통과). 편집 순서 오류는 journal에 기록.
- **동시편집**: `cline-strength-impl` 리스가 caption SKILL.md를 커버한 상태에서 내 편집이 먼저 기록됨(내용 무손상·sha 확인). 이후 해당 리스는 만료/종료. 타 태스크의 `.cline/skills`, `.clinerules` 제안 트리는 관찰만.
- **미검증 항목**: 새 세션에서의 Devin 훅 실제 발화(`/hooks` 목록 노출)는 이 세션에선 검증 불가 — 명령·스크립트 계약 단위 테스트로 대체. `.cline/skills`가 Devin 스킬 스캔에 잡히는지 미확인(소규모·커넥터형).

### 8.5 되돌리는 방법

- 개별 파일: `data/agent-handoff/codex-autonomy/devin-config-optimization-apply-20260919-8aec0fa1/cycle-03-agents-md-reduction/before/` 의 보존 사본으로 대상별 복원(`.windsurf` 룰은 sha256 대조만 가능).
- `.gitignore` L214-217 제거 시 producer-kit이 다시 스캔 후보가 됨.
- `.devin/hooks.v1.json` 삭제 시 두 훅 모두 비활성화(스크립트 자체는 남음).
- `scripts/work_journal.py` 오류 문구 수정은 동작에 무영향(메시지만).
