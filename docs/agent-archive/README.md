# docs/agent-archive — 리포트·스크립트 아카이브 SSOT

이 폴더는 demo-1 리포트와 스크립트 아카이브의 단일 진실 소스(SSOT)다.
에이전트는 리포트·스크립트를 찾을 때 grep 전에 여기부터 시작한다.

## 진입점

```powershell
python -B scripts/agent_archive.py find <키워드|태그>   # 카드+대표 리포트 상위 10개
python -B scripts/agent_archive.py show <path|card-id>  # 카드 본문·실행 힌트
python -B scripts/agent_archive.py scan                 # catalog.jsonl 재생성
python -B scripts/agent_archive.py plan                 # copy_residue_cleanup 형식 plan
python -B scripts/agent_archive.py prune --plan         # COLD 후보만 계산(적용은 다음 세션)
```

## 4칸 분류 규칙 (점수 = 사용+최근성+희소성+모순성+재사용성, 각 0~2)

| 칸 | 조건 | 처분 |
|---|---|---|
| DELETE | 원본이 남아 있는 같은 sha 사본, 재생성 캐시(`__pycache__`/`build/reports`/죽은 `.pid`), 빈 폴더, 0바이트 | 바로 삭제 |
| COLD | 점수 0~3, 새 리포트에 밀린 옛 판, 진행 로그만, 참조 0이고 7일 넘은 스크립트 | `_quarantine/` 격리 (manifest + restore.ps1, 14일) |
| RECYCLE | 점수 ≥6 또는 희소성 2 또는 모순성 2 | `cards/`에 사실 카드; 스크립트는 계열 아카이브 |
| KEEP-LIVE | 점수 4~5, 활성 lease·72h 이내, git 추적, 참조되는 스크립트 | 그대로 |

- git 추적 파일은 `TRACKED` — 색인만, 이동·삭제 금지.
- 애매하면 COLD. 참조가 깨지는 스크립트는 KEEP-LIVE.
- 내용이 있는 리포트는 점수가 낮아도 DELETE 아니라 COLD.

## 보존 기간

- 격리 보관 14일 (현 배치 만료: 2026-10-17, `C:\AbandonWare\demo-1\demo-1\_quarantine\report-script-triage-20261003\`).
- 적용은 항상 `plan → dry-run → apply --plan-sha` 순서. sha 불일치·드리프트·잠금 파일은 자동 skip/failed.

## 새 리포트를 쓰는 규칙

1. 세션 폴더에 `REPORT.md` 1개 — 앞 20줄 안에 결론.
2. 오래 남길 사실(근본 원인, 재현 절차, 실측 숫자, 오류 서명, 체크리스트, 모순)은 `cards/`에 카드로.
3. 진행 로그는 task ledger(`data/agent-handoff/<task>/journal.json`)에만.
4. CONFLICT 카드는 양쪽 주장과 경로를 나란히, 판정 없이 — "누가 확인해야 하나"만.

## 파일

| 파일 | 내용 |
|---|---|
| `catalog.jsonl` | 스캔 대상 전체 한 줄씩 {path,kind,topic,tags,bin,score,why,sha12,size,mtime,tracked,referenced_by,superseded_by,run_hint} |
| `cards/` | 재활용 사실 카드 (주장·종류·근거·날짜·상태·재확인 명령) |
| `REPORTS_INDEX.md` | 주제별 대표 리포트 + 카드 목록 |
| `SCRIPTS_INDEX.md` | 스크립트 아카이브 계열 표 + W5 판정 기록 |
