# Codex 세션·메모리 정리 — 실행 결과 (2026-09-19)

실행 taskId: `codex-session-memory-cleanup-exec-20260919` (agent: devin)
기준 문서: `docs/codex-session-cleanup-audit-20260919.md`(v2, 당시 관측 기록으로 보존),
`docs/codex-session-cleanup-directive-20260919.md`
주 대상: `C:\Users\nninn\.codex` (CODEX_HOME). 격리 위치: `C:\AbandonWare\_rescue\codex-quarantine-20260919\`

> 본 문서는 **적용 결과** 기록이다. 감사 문서의 수치는 당시 관측값이며 아래 적용 측정과 구분된다.

## 1. 완료 상태 6분리

| 항목 | 상태 | 근거 |
|---|---|---|
| 차단 적용 | **적용됨(설정 파일 수준)** | `config.toml` `[memories]` `generate_memories=false`, `use_memories=false` 적용·재독 확인. `[features] memories=true` 유지(기능 영구 off 아님). 백업 `config-backup/config.toml.bak` |
| 오염 입력 처리 | **부분 — 미해결 잔존** | `memories_1.sqlite` `stage1_outputs` 76행(전부 phase2 선정) 잔존. 지원되는 파일/설정 기반 제외 수단 없고 SQLite 직접 편집은 금지 범위 → 미해결. `memories\` 디렉터리는 리셋 상태(2파일, 4KB) |
| 세션·캐시 정리 | **격리 적용 → 페이로드 영구 삭제 완료** | 1,952항목 ≈ 5,663 MiB(≈5.53 GiB) 이동 격리 후, 사용자 승인으로 격리본 전량 영구 삭제(2026-09-19 ~15:00 KST). C: 여유 +5.64 GiB 실측. 복구 불가 |
| 인코딩 복구 | **완료** | `docs/PROJECT_STATUS.md` 클린 UTF-8 복구(아래 §5) |
| 새 세션 검증 | **미검증(앱 실행 중)** | Codex Desktop 실행 중 — 설정은 다음 스레드/재시작부터 적용 추정. 신규 세션의 기억 미사용을 런타임 관찰하지 못함 |
| 기억 재생성 검증 | **미완료** | 재생성 파이프라인 미관찰. "차단 적용 확인, 재생성 검증 미완료" |

## 2. 메모리 차단 — 실제 적용 내용

설치 표면: Codex Desktop `OpenAI.Codex_26.915.4065.0`(실행 중, 19 프로세스), npm CLI `@openai/codex 0.144.1`.
`CODEX_HOME` 환경변수 미설정 → 기본 `C:\Users\nninn\.codex` 사용 확인.

- 변경: `config.toml` `[memories]` 섹션 2키만 수정(L142-145 범위, 최소 diff).
  `generate_memories = false` — 새 대화의 기억 생성 입력 포함 차단.
  `use_memories = false` — 이후 세션의 기존 기억 주입 차단.
- 미변경: `[features] memories = true`(마스터 스위치, 기능 자체는 유지), `disable_on_external_context = true`(기존값 유지), `memories = true` 최상위 플래그 유지.
- 스레드별 제어 증거: `state_5.sqlite` `threads.memory_mode` 컬럼 실재(disabled 2,666 / enabled 1,977) — `/memories` 대화별 제어의 데이터 모델. DB 직접 편집은 하지 않음; 대화별 제어는 UI 조작 필요(런타임 확인 미완료).
- 잔존 위험(미해결): `memories_1.sqlite` `stage1_outputs` 76행이 phase2 통합 입력으로 남아 있음. `generate_memories=false`는 신규 입력을 막지만, 기존 stage1 산출물의 후속 통합 여부는 미확인 — 앱의 메모리 관리 UI 또는 공식 제외 수단 확인 필요.
- 재개 순서(계획): stage1_outputs 재사용 경로 확인 → `use_memories` 복귀(현재 메모리 코퍼스 비어 무해) → 입력 코퍼스 정제 확인 후 `generate_memories` 복귀. 확인 불가 시 차단 유지 상태를 미해결로 명시(현재 상태).

## 3. 세션·캐시 격리 — 적용 내용

도구: `scripts/codex_home_quarantine.py`(신규, 단일 목적 — candidates/preview/apply/restore).
선별 규칙(지시서 반영, 보수적): 최근 7일·archived(13)·is_pinned·`session_index.jsonl` 등재·`fileChange`/`userMessage` 아이템 보유 스레드 전부 보호. **미투영(thread_items 0건) 스레드는 rollout 바이트 스캔으로 쓰기/대화 증거 검사** — `session_meta` 지시문 내 `apply_patch` 언급은 무시하고 `response_item` function_call 인자·`event_msg user_message`만 증거로 인정. 부분 투영 스레드는 `projection_state` 오프셋 이후 꼬리만 스캔.

| 클래스 | 건수 | 크기 | 선별 근거 |
|---|---|---|---|
| child-stale-no-evidence | 1,910 | 2,458.5 MiB | spawn 자식 + stale + 증거 없음(DB 또는 스캔 검증) |
| stale-db-copy | 10 | 277.0 MiB | `sqlite/` 6월 사본, 60일+ 유휴(live `codex-dev.*` 제외) |
| tmp-globalstate | 18 | 5.0 MiB | `*codex-global-state.json.tmp-*` 원자 쓰기 잔재 |
| viz-gradle-cache | 14 | 2,922.5 MiB | `visualizations/**/gradle*/caches` 의존성 캐시만(증거·png·ndjson·소스 미포함) |
| **합계** | **1,952** | **≈5,663 MiB ≈ 5.53 GiB** | manifest `manifest.jsonl` |

검증: rollout 스캔이 쓰기 증거 스레드 991건(exec 979·apply_patch 9·기타)을 추가 제외 — 미투영 스레드 무검증 통과 허점 제거. `\\?\` 확장 경로 6건은 접두사 정규화 후 이동.
결과: `sessions/` 4,631→2,721 파일. `automation` 스레드는 보호 규칙(인덱스/최근/증거)에 전부 걸려 후보 0건.

영구 삭제(사용자 승인, 2026-09-19 ~15:00 KST): 격리 페이로드 4개 디렉터리(`child-stale-no-evidence`·`stale-db-copy`·`tmp-globalstate`·`viz-gradle-cache`)를 `rd /s /q`(`\\?\` 접두사)로 전량 삭제. C: 여유 885.34→890.98 GiB(+5.64 GiB) — 매니페스트 측정 5.53 GiB와 일치. `manifest.jsonl`·`apply-log.jsonl`·`config-backup/`은 감사·설정 복구 근거로 보존. 삭제 후 Codex 프로세스 4개 생존·sessions 2,721 유지·차단 설정 유지 확인.

복구: 격리 페이로드는 영구 삭제되어 더 이상 복구 불가. `restore` 명령은 삭제 이전 시점의 `apply-log.jsonl` 기준으로만 동작 — 현재는 원본이 없어 실행하지 않는다. 설정 복구는 `config-backup/config.toml.bak`으로 가능.
잔존 위험: `threads` DB 행은 유지 — 앱에서 이동된 자식 스레드 재개 시 rollout 부재로 실패 가능(비인덱스 자식이라 선택자에 안 보임). `session_index.jsonl`·SQLite·WAL 미접촉.

## 4. 쓰기 정지 상태

Codex Desktop 실행 중(종료 없이 진행). 세션 파일 최종 쓰기 ~5시간 유휴, 파일럿 이동 후 60초 관찰 — 재생성·오류·재작성 없음. sqlite 본체·WAL·인덱스는 미수정. `memories_1.sqlite-wal` 13:34 기록은 변경 전 엔진 활동(이후 미관찰).

## 5. PROJECT_STATUS.md 인코딩 복구 — 완료

- 진단: UTF-8 BOM + 이중 깨짐 + 리터럴 `?` 929개 → 부분 비가역 손상 확정. CP949 역변환 round-trip 실패.
- 원인: `source_health_scorecard.py`·`smoke_supabase_readonly_snapshot_tests.ps1`는 참조만 하고 쓰지 않음 — repo 갱신 스크립트 아님. 손상 기록은 외부 에이전트 쓰기 경로(미식별, 13:33 KST)로 추정.
- 복구 방법: `lens-hint-display-fix-9d469bb7/project-status-restore-candidate.md`(클린 UTF-8, lens-hint 작업이 자체 제작한 복구 후보) + 손상본의 ASCII 잔존 원문 7행(§3 BAT lifecycle, §4 close-bat·server-lifecycle, §6 검증 4행 — 전부 ASCII라 원문 그대로) 재조립. 추측 복원 없음; §5에 복구 내력 기록.
- 결과: 16,493B/137행, 한글 정상, `?` 1개(복구 메모 문구 내 정상 사용). checkpoint `c1` sealed→verified, 손상 원본은 `c1/before/6.bin`에 보존.

## 6. 자동 참조 경로 — 검증 결과 (변경 없음)

- `~/.codex/AGENTS.md`: git 참조는 "기억=힌트, 재검증" 규칙뿐 — git 기준 복원 지시 없음.
- repo `AGENTS.md`: `DEMO1-GIT-LOCAL-FIRST`/`DEMO1-STALE-HANDOFF-REFERENCE` 이미 존재(과거 문서=참고용 원칙).
- `.clinerules/00-demo1-cline-bridge.md`: AGENTS.md SSOT 위임 구조, 충돌 없음.
- `~/.codex/rules/default.rules`: 명령 allowlist — 지시문 아님.
- `_rescue`: CODEX_HOME 외부 경로 — 세션 수집·기억 생성·지침 로딩 경로 밖(토폴로지). Cline 전체 import 계속 보류.
- automations: `demo-1-project-monitor` = `PAUSED`(세션 생성 중단 상태), `zerossl-30` = ACTIVE(연 1회 인증서 알림 — 정상 사용자 선호, 유지).
- 결론: 실제 로딩 지침에 충돌·구식 규칙 없음 → 최소 수정 원칙에 따라 변경 없음.

## 7. 변경 파일·설정 목록

- `C:\Users\nninn\.codex\config.toml` — `[memories]` 2키 false(백업 보존)
- `C:\AbandonWare\demo-1\demo-1\src\scripts\codex_home_quarantine.py` — 신규 단일목적 도구
- `C:\AbandonWare\demo-1\demo-1\src\docs\PROJECT_STATUS.md` — 인코딩 복구
- `C:\AbandonWare\demo-1\demo-1\src\docs\codex-session-cleanup-execution-20260919.md` — 본 문서
- 격리 대상 1,952건 — `C:\AbandonWare\_rescue\codex-quarantine-20260919\`에서 영구 삭제됨(manifest.jsonl·apply-log.jsonl·config-backup은 보존)

## 8. 미해결·후속 필요

1. `stage1_outputs` 76행 재사용 경로 — 공식 제외 수단 미확인(DB 편집 금지). 앱 메모리 관리 UI 확인 필요.
2. 설정 런타임 적용 — 앱 재시작/신규 스레드에서 `use_memories=false` 실효 확인 필요.
3. `logs_2.sqlite` 1.46GB·`tmp\` 603MB·`cache\` 672MB·`.tmp\` 209MB — 이번 범위 밖(보존), 후속 후보.
4. ~~격리본 영구 삭제~~ — **해결됨**: 사용자 승인 하에 1,952건 전량 영구 삭제(2026-09-19), C: +5.64 GiB 실측.
5. Cline 선별 import 방식 — 미확인, 전체 import 보류 유지. 필요 시 검증된 결론만 담은 짧은 인계문 대안.
