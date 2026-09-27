# Devin 지시서 — AbandonWare3 원격 **완전 삭제** + sole AbandonWareAi + commit 하드페일 (2026-09-26 rev2)

## Clarification
이전 문구 "AbandonWare3 부활 금지"는 **에이전트가 폐기 원격을 다시 유효 upstream으로 쓰지 말라는 Must NOT**이었다.
유저 의도 = **AbandonWare3 원격 엔트리 자체를 삭제**. rename으로 남겨 두지 말 것.

## Goal (완료 정의)
1. `git remote`에 AbandonWare3 URL이 **0개**.
2. 유일한 메인 원격 = `https://github.com/UnlimitedAbandonWare/AbandonWareAi` (보통 `origin`).
3. 에이전트 git/preflight: AbandonWare3 URL이 보이거나 intended remote와 불일치하면 **commit deferred + non-zero**.
4. **push / fetch / pull 실행 금지** (이번 작업에서). 유저가 나중에 명시하기 전엔 publish 없음.

완료 = Acceptance 전부 PASS. “파일만 읽음” ≠ 완료.

## Project Root
`C:\AbandonWare\demo-1\demo-1\src`

## Evidence (실측)
- `git remote -v` → `origin` = `https://github.com/UnlimitedAbandonWare/AbandonWare3.git`
- `branch.main` tracks `origin/main`
- AGENTS `DEMO1-GIT-REMOTE-SOLE`: 유효는 AbandonWareAi only; AbandonWare3 discarded
- `scripts/conditional_local_git.py`: `originMismatch` 계산하나 soft(리포트만) → 하드페일로 승격 필요

## Work

### A. 원격 정리 (삭제, 남기지 않음)
운영자/데빈이 Project Root에서 (push 없이):
```
git remote remove origin
git remote add origin https://github.com/UnlimitedAbandonWare/AbandonWareAi
git remote -v   # AbandonWare3 문자열 0건, AbandonWareAi만
```
- `abandonware3-discarded` 같은 **별칭으로 남겨 두지 말 것** (유저: 아예 삭제).
- 다른 이름의 remote에 AbandonWare3 URL이 있으면 그것도 `git remote remove <name>`.
- **금지:** `git fetch` / `git pull` / `git push` / force-push / `--no-verify` / AbandonWare3 URL 재추가.

### B. Fail-closed 코드 게이트
1. `scripts/conditional_local_git.py` (inspect/check/preflight):
   - remote URL에 `AbandonWare3` 포함 **또는** `originMismatch` → `deferredReason=origin-mismatch` (또는 `forbidden-remote`), **non-zero**, commit 차단.
2. `scripts/agent_git_vibe_commit.py` (+ soft-auto 진입점): 동일.
3. intended sole remote 상수/문서 = `https://github.com/UnlimitedAbandonWare/AbandonWareAi` only.
4. 기존 pre-push deny(`publish.allowTarget` / `AWX_PUBLISH_APPROVED`) 유지.

### C. Prove
- AbandonWare3 URL이 remote에 있으면 commit/vibe 경로 defer + non-zero.
- `git remote -v`에 AbandonWare3 0건, AbandonWareAi만.
- `git push`는 **실행하지 말 것**.

## Must NOT
- AbandonWare3를 유효 upstream으로 취급하거나 URL 재등록
- rename으로 “discarded” 원격 **잔존**
- secrets 출력/커밋/첨부
- push / force-push / `reset --hard` / `add -A` / 히스토리 rewrite
- proto-open / CSRF / chat permitAll 강화 (스코프 밖)
- Focus embedding / orphan Java 등 다른 Top10 동시 패치

## Soft-auto git
- foreign staging 보존, selective path only
- stale index.lock만 soft-auto (writer 없을 때)

## Acceptance
- [ ] `git remote -v`에 AbandonWare3 **0건**
- [ ] sole origin (또는 sole main remote) = AbandonWareAi
- [ ] AbandonWare3 URL 재주입 시 inspect/check/vibe → defer + non-zero
- [ ] push/secrets/proto-open 변경 없음
- [ ] 검증 로그(명령+결과, secret 없이) 작업 노트에 남김

## Shortlist 참고 (이번 THE ONE 아님)
2) Focus cloud embedding `matchIfMissing=true`
3) orphan `main/java/service|strategy` + SubQuestionPlanner

## SSOT
`agent-prompts/dangerous-origin-mismatch-20260926/brief.md` (이 파일 = rev2)
