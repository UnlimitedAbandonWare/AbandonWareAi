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

## 스캐너의 4칸 후보 분류 (점수 = 사용+최근성+희소성+모순성+재사용성, 각 0~2)

| 칸 | 조건 | 처분 |
|---|---|---|
| DELETE | 같은 sha 사본·재생성 캐시·빈 폴더·0바이트 등 스캐너 후보 | 자동 삭제 아님 — 내용·참조·실행 의존·고유 증거를 확인한 불필요 파생문서만 복구 가능한 휴지통/보관 |
| COLD | 점수 0~3·구판·진행 로그·참조 0·오래된 파일 등 검토 후보 | 점수·나이는 처분 권한이 아님 — 대체 근거와 보존정책 확인 후 원본 경로·SHA256·복구본을 기록하고 실제 자동수집 제외 여부 검증 |
| RECYCLE | 점수 ≥6 또는 희소성 2 또는 모순성 2 | `cards/`에 사실 카드; 스크립트는 계열 아카이브 |
| KEEP-LIVE | 점수 4~5, 활성 lease·72h 이내, git 추적, 참조되는 스크립트 | 그대로 |

- git 추적 파일은 `TRACKED` — 색인만, 이동·삭제 금지.
- 애매하면 HOLD로 원본을 유지한다. 현재 지침·필수 SKILL/AGENTS·유일한 증거·미완료 보고서·fixture 참조 문서·보존정책/출처가 불확실한 자료와 참조가 깨지는 스크립트는 이동·삭제하지 않는다.
- 파일명·mtime·낮은 점수·같은 sha·참조 0만으로 불필요성을 판단하지 않는다. 같은 해시의 전후 검증 파일도 서로 다른 증거 역할이면 보존한다.

## 보존 기간

- 이전 배치의 14일 보관 기록: 2026-10-17, `C:\AbandonWare\demo-1\demo-1\_quarantine\report-script-triage-20261003\`. 이 날짜는 자동 삭제 승인이나 현재 작업의 보존기한이 아니다.
- 스캐너 plan은 후보일 뿐이다. 개별 처분 전에 원본 해시·대상 목록·복구 경로를 확정하고 동시 변경 파일은 건너뛴다. 기존 도구의 `plan → dry-run → apply --plan-sha`도 복구 조건을 충족할 때만 사용한다. 영구 삭제·휴지통 비우기·일괄 clean은 금지한다.

## 새 리포트를 쓰는 규칙

1. 새 리뷰·정리 보고서는 `docs/reports/agent-reviews/<task-id>/REPORT.md` 1개에 통합하고 기존 증거 경로는 유지한다 — 앞 20줄 안에 결론. 현재 저장 기준: `docs/agents-rules/DEMO1-DELIVERY-DOWNLOADS.md` (Downloads는 dot 최종 지시서만).
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
