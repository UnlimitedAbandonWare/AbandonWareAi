# SCRIPTS_INDEX — 스크립트 아카이브 계열 표

Generated: 2026-10-03 KST · task `devin-report-script-triage-33e668d9`

## W5 판정 결과: 이동 0건

대상 조건(지시서 §W5): **참조 0 + 미추적 + 7일 경과 + 재사용성 2점**인 `scripts/` 최상위 스크립트.

실측 (post-apply catalog, 23,020 rows):

| 단계 | 수 |
|---|---:|
| scripts 최상위 스크립트 | 704 |
| 미추적 | 317 |
| 미추적 + 참조 0 | 65 |
| 미추적 + 참조 0 + 7일 경과 | **0** |

→ 조건을 모두 만족하는 스크립트가 없어 이동 0건. `scripts/_archive/`는 생성하지 않음
(before.json 기준선에서도 부재였음 — 후속 세션이 후보가 생기면 같은 규칙으로 재판정).

## 왜 0건인가

- 미추적 + 참조 0 스크립트 65개 전부가 7일 이내 생성·수정 — 활성 작업장이라 recency
  보호(`recent-72h` / `KEEP-LIVE`)에 걸림. W5의 "7일 경과" 조건이 안전장치로 작동.
- 참조가 있는 스크립트(`referenced_by` ≥1)는 이동하면 `.bat`/`agents.md`/skill/import가
  깨지므로 대상 아님.
- 참조 0 + 미추적 + 7일 이상이라도 재사용성 <2인 것은 `_archive`가 아니라 COLD 격리
  대상 — 이번 배치에서 이미 quarantine 처리됨(예: score<4 스크립트들).

## 계열 조회 방법 (아카이브 대신 카탈로그)

```powershell
python -B scripts/agent_archive.py find <family>     # 예: find jev / find p6dbg / find orchestra
python -B scripts/agent_archive.py show scripts/<name>.py
```

catalog.jsonl에서 스크립트 행은 `topic` = 파일 stem, `tags` = 토큰,
`run_hint` = 실행 명령 예시, `referenced_by` = 참조 문서 목록.

## 되돌린 것과 이유

| 파일 | 원래 의도 | 되돌린 이유 |
|---|---|---|
| `scripts/test_checkpoint_js_numeric_counter.py` | (과거 archive_to 후보) | 7일 미경과 + 짝 본체(`checkpoint_js_numeric_counter.*`) 부재 → COLD 격리로 전환 (r2 apply, 2026-10-03) |

## 재판정 조건

- 미추적 + 참조 0 + mtime > 7일 + reu=2 스크립트가 생기면:
  `scripts/_archive/<family>/`로 이동 + 계열 README.md + 이 표 갱신 + 이동 후 참조 깨짐 0건 확인.
- 짝 테스트(`test_<name>.py`)는 본체와 같이 이동. 하나라도 참조가 있으면 원위치 유지.
