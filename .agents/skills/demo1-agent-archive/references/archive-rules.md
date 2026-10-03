# archive-rules — agent_archive.py 상세

## 스캔 범위 (`agent_archive.py SCAN_DIRS`)

`docs/diagnostics`, `docs/superpowers`(색인만, 전부 추적), `docs/reports`,
`data/agent-handoff`, `var/codex-assist-*`, `var/debug`, `var/rag-launcher`,
`logs`, `build/reports`, `scripts/` 최상위 + `__pycache__`, `scripts/_archive`.

금지 접두어(FORBIDDEN_PREFIXES, copy_residue_cleanup과 동일): `main/`,
`frontend/`, `configs/`, `app/`, `.git`, `.secrets`, `var/meta-display-db`,
`.env*` 등 — SKIP.

## 점수 (각 0~2, 합 0~10)

- use: `.bat`/`agents.md`/skills/다른 스크립트의 import·subprocess 문자열,
  최근 7일 journal 언급.
- rec: 72h=2, 14d=1.
- rar: 고유 사실 파일(ROOT_CAUSE/FINDINGS/EVIDENCE/실측/서명류 이름·본문) = 2.
- con: 모순 신호(CONFLICT/ADDENDUM/OBSERVE/정정류) = 1~2.
- reu: 재사용 가능 도구(docstring/`main`/`help=`/SYNOPSIS).

## 칸 → 처분

| bin | plan class | 처분 |
|---|---|---|
| DELETE | DELETE | sha 재검증 후 삭제 (dup-sha/캐시/빈 dir/0B/죽은 pid) |
| COLD | QUARANTINE | `_quarantine/<batch>/` + manifest + restore.ps1, 14일 |
| RECYCLE | KEEP | 제자리 + 사실은 카드로 |
| KEEP-LIVE | KEEP | 제자리 |
| TRACKED | TRACKED_CANDIDATE | 추적 파일 — 색인만, 손대지 않음 |
| SKIP | SKIP | 금지 구역 |

## 안전

- 계획은 sha256-pin: `apply --plan-sha` 불일치 시 중단. 행별 rehash 후
  drift/lock은 `skipped`/`failed` 기록 — 강제 접촉 없음.
- 빈 dir는 tracked·lease·72h·journal 언급 시 보호.
- dup 그룹 생존자 선택: named/reference-like > shallow > KEEP-name,
  `/before/`·`/preimages/` 패널티.
- `archive_to`(scripts/_archive/<family>)는 미추적 + 참조 0 + 7일 경과 +
  reu=2 + 최상위 스크립트에만 설정 — W5 이동 후보 표시이며 실제 이동은
  별도 판정.
- 복원: `_quarantine/<batch>/restore.ps1` 또는 `copy_residue_cleanup.py restore`.
