# 04 · C4 — AGENT SURFACE 스프롤 지도

Evidence: `scan-report.json` → `c4_surface` + 라이브 실측. 목적: Devin/Grok/Codex/Cline이 **같은 SSOT**를 보게 하고, 낡은 표면이 새 작업을 낚지 못하게 한다.

## 계층별 실측

| id | path | verdict | why_confuses_agents | proof | action_now | owner |
|----|------|---------|---------------------|-------|------------|-------|
| S01 | `.agents/skills/` (130 dirs) | KEEP+INDEX_ONLY | 스킬 수가 인덱스의 2.7배 — 라우터 미등록 82개는 라우팅 불능, 이름 충돌시 임의 선택 | 전원 `SKILL.md` 보유; `.agents/skills-intent-index.yaml`에 48개만 명명 | 인덱스 백필 또는 `archive`/`negative` 태그는 승인 큐(P0-후보) | unowned |
| S02 | `.agents/skills-intent-index.yaml` | KEEP | "인덱스에 없으면 없는 스킬"이라고 오인 | 라우터 SSOT: `python -B scripts/demo1_vibe_skill_router.py resolve "<ask>"` | — | active |
| S03 | `agent-prompts/` (64 dirs + 루트 파일) | INDEX_ONLY | 날짜형 디렉터리가 "최신 지시"로 보여 과거 지시 재사용 | `INDEX.md`, `prompts.manifest.yaml`, `_archive/` 존재하나 dated dir이 지배 | `README_ACTIVE.md`(활성 3~5개만) — 승인 큐 | unowned |
| S04 | `.clinerules/` 11 · `.grok/rules/` 8 · `.codex/` 8 · `.devin/` 13 · `.windsurf/rules/` 4 | KEEP+INDEX_ONLY | 5계층 규칙 — 에이전트별로 다른 SSOT를 읽어 규칙 충돌 | 계층당 파일 수 실측; AGENTS.md가 마스터 | 계층 우선순위 문구는 AGENTS.md 1줄 (승인) | unowned |
| S05 | `docs/diagnostics/` (37+ dated entries) | INDEX_ONLY | 날짜 폴더가 쌓여 "최신 진단"과 "완료 보고"가 섞임 | 본 폴더 포함 37+ entries; 완료 작업의 보고서가 영구 상주 | dated-entry 규칙 문서화 (`README` 1개, 승인) | unowned |
| S06 | `scripts/` (417 files) | KEEP+INDEX_ONLY | py 208 + ps1 160 + cjs/js/sh 혼재 — 어느 엔트리가 정본인지 불명 | 확장자 분포 실측; `.java` 1개(`DesktopMariaDbMetadataSnapshot.java`, 49KB)가 스크립트 폴더에 — 이형 | 스크립트 SSOT 표는 `docs/` 인벤토리로 (P1) | unowned |
| S07 | `docs/superpowers/plans|specs/` | INDEX_ONLY | 과거 설계 문서가 C5 유령 참조의 최다 발생원 | ghost refs 상당수가 여기서 유래 | INDEX_ONLY | unowned |

## 혼란 발생 패턴

1. **라우터 사각지대**: 인텐트 인덱스 미등록 스킬은 `$demo1-vibe-skill-router`가 안 잡음 → 에이전트가 "기능 없음"으로 오인하거나 중복 스킬을 새로 만든다.
2. **날짜 폴더 함정**: `agent-prompts/<task>-YYYYMMDD/`는 완료 후에도 디렉터리 명령문으로 남아 "지시"처럼 보인다 — dated dir = 기본 historical.
3. **규칙 계층 충돌**: 같은 주제가 `.windsurf/rules`·`.clinerules`·`.grok/rules`·`.devin`에 중복 선언되면 어느 쪽을 따를지 에이전트마다 다르다. 충돌 시 `AGENTS.md` + hard-constraints가 우선.
4. **유령 도구 참조**(→ C5): 활성 `SKILL.md`가 없는 스크립트를 가리켜 "도구 고장" 오진 유발 (최소 ~17건).

## 허용/금지

- 기본 허용: 인덱스 MD, `README_ACTIVE.md`, 라우터 negative/archive 태그, AGENTS.md 포인터 1줄.
- 승인 필요: 스킬 폴더 이동/삭제, rules 계층 통합, prompts 대량 아카이브.
