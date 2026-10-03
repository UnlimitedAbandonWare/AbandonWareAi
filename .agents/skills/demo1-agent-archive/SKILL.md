---
name: demo1-agent-archive
description: Use when a demo-1 agent needs a report, card, or script — query the
  archive (agent_archive.py find/show) before grepping the tree, and route new
  durable facts to cards instead of new report files.
---

# demo1-agent-archive — 리포트·스크립트 아카이브 입구

SSOT: `docs/agent-archive/` (README = 4칸 규칙·보존 기간·카드 형식).
Catalog: `docs/agent-archive/catalog.jsonl` (마지막 scan 기준).

## 찾기 (grep 전에 먼저)

```powershell
python -B scripts/agent_archive.py find <키워드|태그>   # 카드(999점) + 대표 리포트 상위 10
python -B scripts/agent_archive.py show <path|card-id> # 카드 본문 또는 catalog 행
```

카드는 `docs/agent-archive/cards/<topic>-<id>.md` — 주장 한 줄, 종류, 근거 경로(file:line),
날짜 KST, 상태(still-true/stale/conflict/확인 필요), 재확인 명령 한 줄.

## 쓰기 규칙

- 새 세션 보고: 세션 폴더 `REPORT.md` 1개, 앞 20줄에 결론. 진행 로그는 ledger에만.
- 오래 남길 사실(root-cause/repro/measured-number/failure-signature/checklist)은 카드로.
- 다른 문서·코드와 모순되면 `CONFLICT-*.md` 카드 — 양쪽 나란히, 판정 없이 확인 담당만.
- 같은 주장의 카드는 금지(`dup_cards` 정규화 검사).

## 정리 규칙

삭제·격리는 손으로 하지 않는다: `scan → plan → dry-run → apply --plan-sha`
(copy_residue_cleanup.py가 sha 재검증, drift/lock은 skip). 상세:
`references/archive-rules.md` + `docs/agent-archive/README.md`.
