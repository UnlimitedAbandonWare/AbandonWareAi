# DEMO1-GIT-BRANCH-TOPOLOGY (SSOT)

이 체크아웃의 Git 브랜치 지형. 모든 에이전트(Codex/Devin/agy/Grok CLI/Cline)와
git 스크립트는 여기를 기준으로 삼는다. 브랜치 이름이 바뀌면
`configs/git-branch-context.json` 한 곳만 고치고 이 문서를 같이 갱신한다.

## 브랜치 역할

- **작업 브랜치 = `codex/owned-runtime-browser-restart`** — upstream은
  `origin/codex/owned-runtime-browser-restart`. 모든 diff·스캔·ahead/behind·
  리뷰 범위의 기준은 `@{u}`(upstream)다. `origin/main`이나 `origin/HEAD`를
  비교 기준으로 쓰지 않는다.
- **main = 2026-09-23 공개용 정리 스냅샷**(`b2eaba46`). 작업 브랜치와 역사가
  이어져 있지 않은 별도 브랜치다(`git merge-base HEAD origin/main` 없음).
  main과의 비교·merge·rebase·cherry-pick·PR 생성은 하지 않는다.
  "main보다 N개 앞섬/뒤처짐" 같은 숫자는 의미 없으므로 무시한다.
- **GitHub 첫 화면이 main(옛 스냅샷)으로 보이는 것은 정상**이다 — 저장소의
  기본 브랜치가 main이기 때문. 기본 브랜치 변경·main 덮어쓰기·
  `origin/HEAD` 변경은 사용자가 면접 일정에 맞춰 직접 한다. 에이전트는
  셋 다 절대 바꾸지 않는다.

## push 규칙

- push는 사용자가 명시적으로 요청할 때만, 작업 브랜치로만 한다.
  force-push 금지. `DEMO1-GIT-LOCAL-FIRST`의 기본 금지(push/pull/fetch/
  merge/rebase/reset/clean/stash, remote 변경)는 그대로다.

## 확인 명령

```powershell
python -B scripts/git_branch_context.py         # 사람용 요약
python -B scripts/git_branch_context.py --json  # 기계용
```

verdict: `OK_WORK_BRANCH` / `WRONG_BRANCH` / `NO_UPSTREAM` / `DETACHED`.
main 관계 `mainRelation`은 `SNAPSHOT_UNRELATED`가 정상이며 실패가 아니다.

## 설정 SSOT

`configs/git-branch-context.json`의 `workBranch`/`remote`/`snapshotBranch`가
유일한 값 출처다. `scripts/git_ship.py`의 범위 해석과
`scripts/baseline_sync_probe.py`의 보고 필드도 같은 파일을 읽는다.
