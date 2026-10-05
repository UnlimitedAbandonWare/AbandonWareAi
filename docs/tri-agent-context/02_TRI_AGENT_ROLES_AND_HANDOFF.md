---
doc_id: TRI-CTX-02
title: Tri-Agent Roles and Handoff Protocol
created_at: "2026-10-05T09:00:00+09:00"
expires_at: "PERPETUAL"
ttl_days: null
lifecycle: INVARIANT
validity_basis: "AGENTS.md DEMO1-* 블록 + work_journal/lease 실측 계약 (2026-10-05 KST)"
---

# 02. Tri-Agent 역할 분담과 핸드오프 프로토콜

> 소유권·리스·인계 계약은 불변(INVARIANT). 개별 작업의 "지금 누가 무엇을 붙들고 있는가"는
> `work_journal.py list --active`와 lease status가 답한다 — 이 문서는 절차만 고정한다.

## 역할 분담

| 에이전트 | 기본 소유 영역 | 쓰지 않는 것 |
|---|---|---|
| Codex | 코어 제품 소스: `main/java`, `main/resources`, `src/test` | 다른 에이전트의 in_progress 저널, 원격 Git |
| Devin | 런타임 검증, YAML/문서/`scripts/` 도구, 멀티세션 레일, 보고서 | 제품 코어 Java 소유 심(리스 있으면 skip) |
| Grok CLI | 도구/스크립트/모의체, 메모리 브리지·리콜 도구 | 제품 소스 직접 패치(지시서→Codex) |
| agy (Antigravity CLI) | 지시서 증폭·분담 지시서, `--mode plan` 기본 읽기 전용 | 리스/체크포인트 게이트 없는 쓰기 |

- 비용 순서: Codex 크레딧 → 외부 유료 API → 무료 → 로컬 Ollama(마지막). 제품 메인 챗은
  API/OAuth 우선, Ollama는 최종 폴백. RAG·임베딩은 3090 로컬 유지.
- 하나의 파일이 두 활성 리스에 속하면 그 파일은 skip — "세션 충돌"이 아니라 "타깃 겹침"이 충돌이다.

## Single-Seam 리스 + 저널 절차

파일 변경 작업의 표준 수명주기:

```text
agent_preflight.py --root . --agent <name>          # peer 신호/리스 인벤토리
docs/PROJECT_STATUS.md 읽기                        # 단일 대표 현황 문서
work_journal.py list --active                      # 목적 겹침 확인
demo1_goal_switch_barrier.py check                 # 새 목표면 switch 선행(필요 시 --keep-task)
work_journal.py open --task <slug> --scope <path>… # taskId 발급 — 전 작업에 재사용
source_edit_session.ps1 -Action begin              # 소스/실행 대상만; docs/*.md·.agents/skills/**.md는 불요
   -TargetManifest <json> -Topic <slug> -OwnerId <taskId> -TaskId <taskId>
codex_work_checkpoint.py begin --run <cycle> --target … [--lease <lease.json>]
   → patch → seal → 실제 검증 실행 → finish --exit-code <n>
work_journal.py note --kind change|verify          # 실 커맨드·exit·시각 기록
status_doc.py read --key … → update-row/append-row --expect-sha256 # §3 표 갱신
source_edit_session.ps1 -Action end                # 리스 정상 해제
```

- `begin` 실패 = 변경 시작 금지 (preimage 보존이 먼저). `finish` 실패 시 미변경 postimage만 자동 복원.
- journal `in_progress`는 "진행 여부 미확인"이지 done이 아니다 — 남의 저널을 완료로 읽지 않는다.

## 핸드오프 프로토콜

- **`FOR_<owner>` 문서**: `docs/diagnostics/<task>/FOR_CODEX.md` 같은 per-owner 인계 파일.
  수신자가 읽고 다음 행동만 추리면 되게 "사실(verified in this checkout)" vs "추정"을 표기한다.
- **`handoff` 패킷**: `python -B scripts/work_journal.py handoff --task <taskId>` →
  `<taskId>/handoff.json` (스코프·파일별 pre/post/current 해시·검증 런·미해결 hold·복구 디렉터리).
  수신자는 파일을 다시 읽고 `currentSha256`을 대조한다 — 패킷은 지도이지 증명이 아니다.
- **TOSS/릴레이**: 소유 심 밖 파일이 필요하면 소유자 채널에 정상 종료 요청 1건
  (`LEASE_RELEASE_REQUEST.md`)만 남긴다. 강제 해제·훔치기·삭제 금지. stale 락은
  `lease_conflict_autoflow.py reclaim` (TTL/heartbeat 만료+owner 불생) 경로만.
- **goal-switch**: 새 목표를 열 때 소유 in_progress 저널이 남아 있으면
  `demo1_goal_switch_barrier.py switch`로 superseded/abandoned 정리. 병렬 세션의 살아있는
  저널은 `--keep-task`로 보호 계산에서 제외한다 (닫지 않음).

## 병렬 세션 규칙 (요약)

- 세션당 고유 `--agent devin-<session>` + 자기 taskId + 자기 체크포인트 루트.
- 쓰기 전 active lane 인벤토리 필수 (`work_journal list --active` + lease status + cycle 디렉터리).
- mid-work drift: 편집 직전 재독 — 바뀐 바이트면 그 파일만 checkpoint `hold`, 타인 hunk 위에 강제 복원 금지.
- 공유 런타임 자원(서버 재시작, /chat 스모크, Gradle, Ollama GPU)은 미터링 대상.

## 완료-보고 분리

- 제안 / 적용 주장 / 검증 결과 / 보류 항목을 보고서에서 분리한다.
- 보고서 첫 줄 규격: `외부 API:` (호출 수·비용). 실행 못 한 항목은 `NOT_RUN` + 사유 — PASS로 바꾸지 않는다.
- acceptance 전부 PASS일 때만 완료; phase 통과는 전체 브리프 통과가 아니다.

## 관련 문서

- [01_ARCHITECTURAL_INVARIANTS.md](01_ARCHITECTURAL_INVARIANTS.md) — 불변 운영 상수
- [04_AGENT_TOOLING_AND_PROTOCOL_SPECS_90D.md](04_AGENT_TOOLING_AND_PROTOCOL_SPECS_90D.md) — 도구 진입 규격
- [README.md](README.md) — 카탈로그 인덱스
