# Codex 주간 지시서 구현 검증 리포트 (2026-09-24)

- 지시서: `.devin/PROMPTS/codex-weekly-skill-directive-20260924.md`
- 작업 taskId: `codex-weekly-skill-directive-ea8509c1`
- lease: `codex-weekly-skill-directive.lock` (target-scoped, 스크립트 15개 대상)
- 체크포인트: `data/agent-handoff/codex-autonomy/codex-weekly-skill-directive-ea8509c1/cycle-01..04`

## 항목별 산출물과 검증

| # | 산출물 | 검증 | 결과 |
|---|---|---|---|
| P1 | `scripts/agent_worker_registry.py` + `test_agent_worker_registry.py` + `docs/agent-worker-contract.md` | `python -B scripts/test_agent_worker_registry.py` → 7/7 OK (cycle-01 verified) | cause 코드 필수(classify 거부), retry max-1/same-cause abort, sweep --mark → blocked/no-response |
| P2 | `scripts/agent_recovery_status.py` + 테스트 | `test_agent_recovery_status.py` → 4/4 OK (cycle-02, 실루트 read-only 실행 포함) | `status` 1회 = lease begin 가능 여부+충돌+quarantine 후보+in_progress 저널+nextActions; `guide --reason` begin 실패 매핑 |
| P3 | `scripts/patchdrop_bundle_guard.py` + 테스트 | `test_patchdrop_bundle_guard.py` → 9/9 OK | janitor inventory 미선행 시 `hold:inventory-required`; v3 스키마 강제; sha256 무결성·중복 slug·화이트리스트·임계 자동중단; reject 시 `--apply-moves`로만 rejected/ 이동 |
| P4 | `scripts/awx_skill_router.py` + 테스트 | `test_awx_skill_router.py` → 6/6 OK; `lint` 실루트 오류 0; `regression` 내장 13/13 | 중단됐던 0915a0 재개: resolve 위임 + schema/중복/깨진 참조/미인덱스 감지 + 한국어 회귀 세트 |
| P5 | `scripts/codex_context_status.py` + 테스트 | `test_codex_context_status.py` → 5/5 OK; `footprint --defaults` = 14,867 tok (AGENTS.md 12,366 + windsurf rules 2,501) | `--json --detailed` + 예산 임계(ok/compress/stop, over→exit 4); `footprint`/`compare`로 압축 전후 측정(≥30% 목표 판정) |
| P6 | `scripts/agentic_chat_postprocess.py` + 테스트 | `test_agentic_chat_postprocess.py` → 5/5 OK | 지시문/대화 잔류 라인 제거, @멘션 나열 제거, 한국어 NFC 정규화, 보고서 템플릿 갭+적용, 시크릿 패턴 차단(exit 5) |
| P7 | `scripts/quick_verify_loop.ps1` + `docs/templates/video-evidence-checklist.md` | PSParser 0 errors; 로컬 http.server 라이브: served==source 162ms exit 0, expect-text 실패 시 exit 4 | 저장→리로드→확인 10초 루프(JSON verdict, latencyMs/withinBudget) |
| P8 | `scripts/agent_work_pipeline.py` + 테스트 | `test_agent_work_pipeline.py` → 4/4 OK | 브리프→phase 분류(read-only/write/verify)+턴 예산(2+tools, 상한 12)+계획표 JSON/MD; `update`로 잔여 재계산 |

## 수용 기준 대조

- P1: blocked 원인 코드 100% 기록(classify가 `--cause` 없이 거부), 무응답 자동 감지(sweep), spawn/wait 흐름 무충돌(독립 JSON 레지스트리) — 충족
- P2: 복구 절차 명령 수 5+ → `status` 1회(+`guide`) — 충족; 외국 lease 강제 해제 없음(읽기전용)
- P3: inventory 선행 없이 apply 불가, 손상 번들 reject 이동 — 충족(검증은 픽스처 기준; 실 janitor 출력 형식 `topic=<slug> status=READY` 가정)
- P4: lint 오류 0, 회귀 13/13, 신규 스킬 미인덱스 자동 감지(warning) — 충족
- P5: 예산 임계 자동 중단(exit 4) + 압축 전후 측정 경로 — 충족; 실제 시스템 프롬프트 압축 편집은 범위 밖(측정 도구만)
- P6: 더티 커밋 메시지 정제·시크릿 차단 — 충족
- P7: 162ms 라이브 루프 시연 — 충족(로컬 fixture 서버 기준)
- P8: 브리프→JSON+요약 계획표 — 충족

## 한계

- P5의 실제 30~40% 시스템 메시지 압축(AGENTS.md→스킬 이동)은 미실행 — 측정 도구와 기준값(14,867 tok)만 제공. 압축 자체는 별도 지시 필요.
- P3의 janitor 파서는 `janitor_inventory.ps1` 출력 형식에 의존 — 형식 변경 시 재검증 필요.
- lease·journal은 기존 게이트와 병행하는 조정 장치이며 OS 접근 제어가 아니다.
