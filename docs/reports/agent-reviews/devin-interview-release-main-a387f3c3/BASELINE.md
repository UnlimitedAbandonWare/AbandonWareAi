# W0 Release Baseline — devin-interview-release-main-a387f3c3

Recorded 2026-10-09 ~11:0x KST. Read-only git. `GIT_OPTIONAL_LOCKS=0`, git=`F:\git\cmd\git.exe`. `git fetch origin` exit 0.

| 항목 | 값 |
|---|---|
| current branch | `codex/owned-runtime-browser-restart` |
| HEAD | `6a8d0cd2202609df887db499632674045c4524c0` (2026-10-09T10:54:28+09:00 "chore: 자동 올리기 2026-10-09 10:54") |
| origin/main | `b2eaba4679f70ded860b052faa59b29073d0c859` |
| origin/codex/owned-runtime-browser-restart | `a3754a3f8faad760e90c2c23409a4064f3ae09da` |
| merge-base(HEAD, origin/main) | 없음 (exit 1) — 공통 조상 없음 → 일반 merge 불가 |
| ahead of origin/codex | 3 commits: `6a8d0cd2`, `a95fc15e`, `1c57582e` (모두 "chore: 자동 올리기") |
| root commit | `aa43b786a55112fa901c893ce2412210844eea5d` |
| total commits (HEAD) | 451 |
| git status --porcelain | 101 lines |
| remote | origin = https://github.com/UnlimitedAbandonWare/AbandonWareAi (fetch=push) |

DRIFT vs directive §1: porcelain 99→101 (+2 lines). 나머지 주장 전부 실측 일치.

Live pre-check (2026-10-09 11:0x KST): `GET http://127.0.0.1:18180/chat` → 200, `<title>AbandonWare AI</title>`; `GET :18181/actuator/health` → 200 `{"status":"UP"}`.