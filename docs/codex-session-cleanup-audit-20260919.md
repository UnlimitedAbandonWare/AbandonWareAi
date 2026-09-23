# Codex 세션 기록 정리 감사 — 2026-09-19 (v2 정정)

배경: Cline이 "기존 작업 세션 4,118개 import" 안내를 띄움. 사용자는 기억 메모리를 켠 상태이며, 대부분 Codex 세션으로 보임 → 불필요 세션 식별 + 정리 가능 여부 리포트 요청.

Scope: `C:\Users\nninn\.codex`, `C:\Users\nninn\.claude`, `C:\Users\nninn\.local\share\opencode`. **읽기 전용 감사 — 삭제/이동/설정 변경 0건.** 용량은 MiB/GiB(2진)로 표기한다.

## v2 정정 요약 (v1 대비)

1. **분류를 '후보 분류'로 강등**: 제목 패턴·스폰 구조·턴 수·파일 크기는 후보 탐색 신호일 뿐, 불필요/오염 확정 근거가 아니다. 동일 제목으로 서로 다른 패치를 수행한 세션, 자식 세션에만 남은 유효 테스트 증거가 있을 수 있다.
2. **회수량 정정**: v1의 "세션에서 8–10GB 회수 가능"을 철회. 세션 ID·파일 경로 중복 제거 후 후보 합계는 **6,444 MiB ≈ 6.29 GiB**이며, 이것도 '정리 후보의 단순 합계'이지 삭제 확정 용량이 아니다. >50MB 파일은 다른 분류와 겹치므로 별도 합산하지 않는다.
3. **메모리 리셋 완료 = 근거 부족**: `memories\`의 MD 파일은 리셋됐으나 `memories_1.sqlite`에 stage1 산출물 76행(전부 phase2 선정)·jobs 405행·consolidation 1행이 잔존함을 확인. 안내문의 "live sessions에서만 재생성"은 엔진 필터/리셋 완료 증거가 아니다.
4. **`visualizations\` = 순수 캐시 미확정**: Gradle 캐시성 파일이 대부분이나 .png 스크린샷·.ndjson·evidence-snapshot.md·gradle 소스·browser-proof 결과가 혼재. 하위 경로별 확인 없이 통째 삭제 대상으로 확정하지 않는다.
5. **`_rescue` 이동 = 격리량이지 디스크 회수량이 아니다** (같은 C: 드라이브).
6. **파일 보존 판단과 기억 생성 대상 판단은 분리**한다. 보존된 세션도 오염 전제 확인 시 기억 생성 대상에서 제외할 수 있어야 한다.
7. WAL·VACUUM·라이브 DB 복사 관련 주의 추가; "앱 UI 삭제가 모든 DB·파일을 정리한다", "rollout 이동 시 앱이 참조를 자동 복구한다"는 미확인 표현으로 하향.

---

## 0. 요약

- 세션 파일의 **~99%가 Codex**: `~\.codex\sessions\`에 rollout `.jsonl` **4,631개 / 11,922.6 MiB (≈11.64 GiB)**. Claude 12파일(~2MB), opencode 8파일(~0.5MB) — 무시 수준.
- `~\.codex` 전체는 **약 26–28 GiB**(추정): 세션 11.64 GiB + `visualizations\` 10.45 GiB + `thread_history_1.sqlite` 2.46 GiB + `logs_2.sqlite` 1.46 GiB + 지원 디렉터리 등.
- 정리 **후보(미확정)**: 서브에이전트 스폰 3,364개(4.33 GiB), 자동화 모니터 79개(31 MiB), `/goal`·Directive 패턴 355개(3.57 GiB), >50MB 파일 24개(2.64 GiB, 위 클래스와 중복). 7일 보존 기준 **후보 합계 6,444 MiB ≈ 6.29 GiB** — 실제 삭제 가능량은 후보별 내용 확인 후 결정.
- 메모리: `memories\` MD는 오늘 리셋됐으나 **`memories_1.sqlite`에 생성 파이프라인 산출물이 잔존** — 전체 리셋 완료 여부는 `근거 부족`. 재오염 차단이 용량 회수보다 우선이다(후속 지시서 §Phase 1).
- Cline import: **Skip 권장 유지** — 선별 방식·관리 경로는 설치본 직접 확인 필요.

## 1. 도구별 세션 저장소

| 도구 | 위치 | 파일 수 | 크기 | 비고 |
|---|---|---|---|---|
| Codex | `~\.codex\sessions\YYYY\MM\DD\rollout-*.jsonl` | **4,631** | **11,922.6 MiB** | `state_5.sqlite.threads` 4,643행 등록, rollout_path 누락 0 |
| Codex | `~\.codex\archived_sessions\` | 13 | 21 MiB | 사용자가 이전에 수동 보관한 것 (2~7월) — 우선 보존 |
| Claude Code | `~\.claude\projects\`, `sessions\` | 12 | ~2MB | demo-1 프로젝트 3스레드 |
| opencode | `~\.local\share\opencode\` | ~8 + db | ~0.5MB | storage/session_diff 7파일 + sqlite |

Cline의 "4,118개"는 스캔 시점·필터 차이로 보이며(현재 계측 4,631), 실질적으로 Codex만 다루면 된다. 정확한 산출 기준은 미확인.

## 2. Codex 스레드 후보 분류 (`state_5.sqlite.threads` 기준, 4,643개)

분류는 **구조+제목 휴리스틱 후보 분류**다. "불필요/오염/반복 입증"이 아니라 "확인 우선순위"의 의미다.

| 클래스 | 스레드 | MiB | 최근 7일 | 판정 근거와 한계 |
|---|---|---|---|---|
| A_subagent_spawn | **3,364 (72%)** | 4,436 | 135 / 754 | `thread_spawn_edges` 자식 엣지 — 구조적 사실. 단, 자식에만 유효 증거(테스트 결과 등)가 남은 경우가 있을 수 있어 내용 확인 없이 삭제 불가 |
| B_automation | 79 | 31 | 0 | 제목 `Automation: demo-1 project monitor` 접두사 — 자동 실행으로 보이나 내용 미검증 |
| C_goal_loop | 355 | 3,656 | 107 / 925 | 제목 `/goal`, `[@Directive locator]`, `@superpowers`, `# Files mentioned` 패턴. **동일 제목의 반복 실행으로 보이나, 같은 제목으로 다른 패치를 수행했을 수 있어 '중복' 확정 아님** |
| D_cli_exec | 5 | 1 | 4 | `codex exec` 비대화형 |
| E_manual | 840 | 3,819 | 12 / 68 | 위 패턴에 안 걸린 잔여. **"사용자 직접 작업"도 미확정 — 패턴 미매칭 자동화 세션일 수 있음** |

반복 제목 상위(탐색용 참고): `<untitled>`×2,319 / `/goal Read the Codex goal objective file`×100 / `/goal st_x(2).txt…`×90 / `/goal [@Directive locator]…`×87 / `Automation: demo-1 project monitor`×79 / `/goal @Superpowers…`×57 / `/goal 아래 9시간짜리 패치`×49+22.

### 보조 지표 (모두 '확정 근거'가 아닌 탐색 신호)
- **턴 수 ≤1**: 3,403개 / 3,148 MiB — A와 대부분 겹침.
- **크기**: 0~50KB 3개 · 50–500KB 1,246 · 0.5–5MB 2,998 · >5MB 384 · >20MB 85(4.46 GiB) · **>50MB 24(2.64 GiB, 최대 451 MiB 단일)** — >50MB는 다른 클래스와 겹치므로 별도 합산 금지.
- **월별 파일**: 2월 145 · 3월 99 · 5월 366 · 6월 449 · 7월 955 · 8월 2,093 · 9월 524 — 7~8월 위임·자동화 실행 증가와 일치(추정).
- **cwd**: `demo-1\demo-1\src` 4,278 (92%) · `AbandonWareX` 252 · `Web\_x` 59 · OneDrive 바탕화면 29 · 기타.
- `session_index.jsonl` 1,528항목 = **VS Code 확장(codex_vscode) 인덱스**로 보임. 미등록 3,108파일(4.16 GiB)은 샘플상 대부분 originator=`Codex Desktop` — 데스크톱 앱 스레드(`thread_history_1.sqlite` 프로젝션 대상). "인덱스 고아"≠쓰레기; 삭제 시 데스크톱 이력 표시·프로젝션 행 처리 방식은 미확인.

## 3. `~\.codex` 비세션 대형 항목

| 항목 | 크기 | 내용·판정 |
|---|---|---|
| `visualizations\` | **10.45 GiB** | 스레드별 작업 디렉터리. .jar 7,152개(7.4 GiB)+.zip 22개(2.75 GiB) 등 Gradle 캐시성이 대부분이나, **.png 스크린샷·.ndjson·evidence-snapshot.md·build/init/settings.gradle 소스·browser-proof pid/result.json 등 실험·실기 증거 혼재 확인** → 순수 캐시 미확정. 일부 경로 열거 실패(MAX_PATH 추정, 손상 증거 아님) |
| `thread_history_1.sqlite` | 2.46 GiB | rollout 읽기 프로젝션(`thread_items` 400,439행). rollout 삭제 후 행 처리(고스트/정리) 방식 미확인 — 1건 시험 필요 |
| `logs_2.sqlite` | 1.46 GiB | `logs` 208,176행 앱 로그. 보존 정책 미확인 |
| `tmp\`,`cache\`,`integrations\`,`plugins\`,`.sandbox-bin\`,`sqlite\`,`.tmp\` | ~3.2 GiB | 지원 디렉터리 — 개별 확인 필요 |
| `..codex-global-state.json.tmp-*` | 18파일 ~5 MiB | 원자적 쓰기 잔여로 보임(0B 다수 + 1.5 MiB 완전본 3개) — 삭제 후보이나 확인 후 판단 |
| `state_5.sqlite`/`goals_1.sqlite`/`queue_1.sqlite` | ~84 MiB | 스레드 레지스트리 / thread_goals 228건 / 큐 |

## 4. 정리 후보 등급 (파일 보존 판단 — 기억 생성 대상 판단과 별개)

보존 기본선: **최근 7일(258스레드 ~1.7 GiB) + E_manual(재검토 전) + archived_sessions + 진행 중 작업·필요 부모/자식 참조.**

| 등급 | 후보 | 규모 | 성격 |
|---|---|---|---|
| A | 7일 초과 서브에이전트 스폰 | ~3,229개 / ~3.6 GiB | 최우선 후보. 부모는 자체 rollout 보유하나, 자식 단독 증거 확인이 삭제 조건 |
| B | 자동화 모니터 + `/goal`·Directive 패턴(최근 7일 제외) | ~330개 / ~2.7 GiB | 내용 확인 후 판정 — 동일 제목≠동일 작업 |
| C | >50MB 대형 파일 24개 | 24개 / 2.64 GiB | A·B와 중복 가능. 개별 내용 확인 |
| D | 연령 컷(예: 메모리 리셋 기준일 2026-09-17 이전 잔여) | 미확정 | 사용자 결정 필요 — E_manual 이력 포함 가능 |
| E | `visualizations\` 재생성 가능·미사용 확인 하위 경로 | 부분적 | 최대 잠재량이나 혼재 증거 확인이 선행 |
| — | **후보 합계 (A+B+C dedup, C 제외)** | — | **6,444 MiB ≈ 6.29 GiB** — 확정 삭제량 아님 |

## 5. 수정/삭제 방법 (평가 — 실행은 후속 지시서)

- **앱 기능 우선 검토**: 보관(archive)·삭제·기억 제외는 **서로 다른 동작**이다. "앱 UI 삭제가 모든 관련 DB와 파일을 정리한다"는 미확인 — 범위 확인 후 사용.
- **수동 격리 조건**: 쓰기 정지 확인 → 일관 복구본 + 원위치 목록 확보 → 원본·대상·ID·크기·선정 이유·처리 결과 기록 → 이동 직전 파일 변경 재확인 → 소량 시험(목록 표시·세션 재개·부모/자식 참조·복구) → 동일 조건 배치. 시험 1건으로 전체 완료 보고 금지.
- **제외 항목**: sqlite 본체·인덱스 직접 수정; WAL 삭제(WAL에는 본체 미반영 커밋 가능 — 라이브 DB 본체만 복사도 금지, 공식 백업 또는 쓰기 정지된 일관 사본 사용); `VACUUM` 임의 실행(논리 정리·참조 복구 기능이 아닌 빈 공간 회수).
- **미확인 유지**: rollout 이동 시 `thread_history` 프로젝션 행의 reconcile 동작; `session_index.jsonl` 고스트 제목 처리; `memories\` 외 메모리 저장 경로 전체.
- **손대지 말 것**: `memories\`(리셋 직후), `auth.json`, `config.toml`, `.sandbox*\`, sqlite 본체, `archived_sessions\` 13개, 키 파일·opnessl 관련.

## 6. 메모리 상태 실측 (2026-09-19 확인)

- `config.toml`: L132 `memories = true` / `[memories]` L143 `generate_memories = true`, L144 `use_memories = true` — 키 실존·현재 ON(설치 버전 문서와 의미 대조 필요).
- `version.json`: `latest_version 0.148.0`(확인 시각 2026-08-20 — 구식일 수 있음). `CODEX_HOME` env 미설정 → 기본 `~\.codex` 사용 중.
- `~\.codex\AGENTS.override.md` 부재(.bak만) → `AGENTS.md`가 실제 적용 전역 지침.
- **`memories_1.sqlite` 잔존 상태**:
  - `stage1_outputs` 76행 — 전부 `selected_for_phase2=1`, `generated_at` 범위 ≈ 2026-06-20~07-30. 아카이브된 `memories\rollout_summaries` 76파일과 수 일치.
  - `jobs` 405행 — `memory_stage1` done 399 / error 5 / `memory_consolidate_global` done 1.
  - `consolidation_progress` 1행(`max_thread_count=0`).
  - 해석: **stage1 처리 대상은 4,643개가 아닌 ~399 스레드**였음(연령·유휴·처리량 등 엔진 제한 존재 추정 — 한계값 미확인). MD 리셋과 별개로 sqlite 산출물·큐 상태가 남아 있어, 이것이 재생성 입력으로 재사용되는지는 미확인.
- `memories\MEMORY.md` 안내문: "Reset 2026-09-19, prior corpus archived to `C:\AbandonWare\_rescue\codex-context-cleanup-20260919\codex-home\memories`, stale beyond 2026-09-17, regenerate from live sessions only, do not re-import the archive" — **운영자 메모이며 엔진 필터 증거 아님**.

## 7. Cline import

- 실체는 위 분류상 ~72% 서브에이전트 스폰 + 자동화·goal 반복 후보 → 이어작업 가치 낮은 트랜스크립트가 대부분.
- 공식 발표상 타 에이전트 대화 가져오기는 확인되나, **설치본의 선별 방식·이후 관리 경로는 미확인** → 전체 import 보류 유지, 필요 작업 선별 또는 짧은 인계문으로 새 작업 시작을 우선.

## 8. 미확인(verification needed)

- 서브에이전트 자식 스레드의 데스크톱 목록 렌더 방식 — rollout 삭제 시 부모 내 drill-in 참조 손실 여부.
- rollout 이동 시 `thread_history_projection_state` reconcile 동작(행 정리 vs 고스트).
- `memories_1.sqlite` 잔존 산출물(stage1_outputs·jobs)이 재생성 입력으로 재사용되는지.
- 메모리 생성 대상의 연령·유휴·처리량 한계값(공식 설정).
- `logs_2.sqlite` 보존 정책(in-app purge vs 수동).
- `visualizations\` 하위 경로별 캐시/증거 분리 분류.
- Cline 4,118 산출 기준.

## 9. 후속 문서

실행 순서·허용 범위·검증·중단/복구 조건: `docs/codex-session-cleanup-directive-20260919.md`.
