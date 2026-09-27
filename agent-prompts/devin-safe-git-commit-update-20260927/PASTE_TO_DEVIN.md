# Devin 붙여넣기 — 안전한 git 커밋 + 원격 업데이트 (2026-09-27)

너는 Devin이다. Project Root `C:\AbandonWare\demo-1\demo-1\src`에서 **선택적 로컬 커밋**을 만들고, 사용자 승인에 따라 **origin(AbandonWareAi)에 push**한다.

## 사용자 승인 (이번 세션)
- 로컬 `commit`: 허용
- `push` to sole remote AbandonWareAi: 허용 (안전하게)
- force-push / history rewrite / remote URL 변경 / AbandonWare3: **금지**

## LIVE 스냅샷 (건드리는 이유)
| 항목 | 값 |
|---|---|
| branch | `codex/owned-runtime-browser-restart` |
| HEAD | `847d3238` Focus cloud embedding opt-in… |
| origin | `https://github.com/UnlimitedAbandonWare/AbandonWareAi` |
| dirty | ~6442 porcelain lines (M~968, D~3670, untracked~1803) |
| already staged (FOREIGN) | `src/test/java/.../SelfAskPlannerOwnershipContractTest.java` — **보존, unstage 금지** |
| index.lock | 없음 |
| blast-radius (tool) | ≤40 paths / ≤15 deletions per commit candidate |

한 방에 전체 워킹트리 커밋은 **불가능·금지**. 주제 묶음으로 여러 번 선택 커밋.

## 진입점
```text
cd C:\AbandonWare\demo-1\demo-1\src
set GIT=F:\git\cmd\git.exe
python -B scripts/conditional_local_git.py policy --repo .
python -B scripts/conditional_local_git.py check --repo .
python -B scripts/conditional_local_git.py scan --repo .
```
Policy SSOT: `.grok/rules/demo1-conditional-local-git.md`  
Guards: `scripts/git_secret_guard.ps1`, `scripts/git_staged_guard.py`

커밋 예:
```text
python -B scripts/conditional_local_git.py commit --repo . --preserve-foreign-staged --message-file <msg.txt> --path <owned1> --path <owned2> ...
```
(또는 도구가 지원하는 동등 플래그. exact-match 기본 유지.)

## 작업 순서

### 0) Safety
1. `git status -sb`, `git remote -v`, `git log -1 --oneline` 기록.
2. AbandonWare3 URL이 remote에 있으면 **push 중단**하고 보고만 (remote 제거는 별도 승인).
3. SelfAskPlanner… staged 유지. 다른 세션 lease/madasin `build\desktop-madasin-*` / `verify-test-*` 삭제·커밋 강요 금지.
4. Soft-auto: stale 0-byte index.lock만 bak 후 제거; 그 외 lock은 BLOCKED.

### 1) Inventory → commit batches (필수)
Porcelain를 prefix/주제로 묶어 **커밋 계획**을 먼저 적어라 (채팅 또는 `agent-prompts/devin-safe-git-commit-update-20260927/COMMIT_PLAN.md`):
- Batch 예: skills 문서 묶음 / scripts / main java 소묶음 / docs / deletes-of-obsolete-.build (삭제 배치는 ≤15)
- 각 batch: path 목록, 예상 path수·삭제수, 커밋 메시지 한 줄
- **제외 기본:** `uploads/`, `.env` 실값, `*.sqlite`, 모델 가중치, 거대 로그, `data/agent-handoff/**` 활성 저널(필요 시 사용자 확인), secrets, 브라우저 쿠키/trace

Blast-radius 초과 batch는 쪼개라.

### 2) Commit loop
각 batch:
1. secret scan (staged/candidate)
2. `conditional_local_git.py commit ... --preserve-foreign-staged --path ...`
3. SHA + 메시지 기록
4. foreign staged 여전히 있는지 확인

실패(exit 2 blast-radius / secret / forbidden-remote): 고치지 말고 보고 후 다음 가능 batch 또는 PARTIAL.

### 3) Push / update (승인됨)
로컬에 새 커밋이 ≥1개 있고 scan 통과 후:
```text
%GIT% push -u origin HEAD
```
- force / `--force-with-lease` 기본 금지 (upstream 없거나 새 브랜치면 일반 push OK)
- pull/rebase/merge 자동으로 하지 마. divergent면 PARTIAL + 상황만 보고
- 브랜치 `codex/owned-runtime-browser-restart`가 origin에 없을 수 있음 → 첫 push로 생성 가능. 다른 기본 브랜치로 바꾸지 마

### 4) 금지
`add -A`, `add .`, `commit -a`, `--no-verify`, `reset --hard`, `clean -fdx`, history rewrite, secret print, AbandonWare3 fetch/push, 다른 사람 staged unstage, Java/Gradle kill “정리용”

### 5) Done 형식
```
SAFE_GIT_UPDATE: DONE|PARTIAL
commits: [sha message…]
pushed: yes|no (remote/branch/result)
preserved_foreign_staged: yes|no
remaining_dirty_approx: N
skipped_excluded: […]
NOT_RUN: […]
```

제품 기능 패치는 이 지시서 범위 밖이다. git만.