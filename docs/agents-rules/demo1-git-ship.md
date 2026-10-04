# demo1-git-ship — user-requested whole-tree commit/push lane

SSOT for `Git-Ship.bat` → `scripts/git_ship.py`. Use ONLY when the user
explicitly asks in chat to commit/push this tree ("커밋해줘", "푸시해줘").
For agent-owned work the lane stays `scripts/agent_git_vibe_commit.py`
(owned paths, one local commit, never push). git_ship is the opposite case:
the user asked for the whole staging area to be committed and published.

사용자용 더블클릭 진입은 `Git-Ship-Easy.bat`(대화형 메뉴)다 — 인수 없이
`Git-Ship.bat`을 열어도 여기로 넘어간다. 에이전트 호출은 인수를 붙여 그대로 둔다.

## Order

`Git-Ship.bat ship` runs `status → junk → scan → commit → push → verify`.
Every subcommand also runs standalone. Default is read-only or dry-run;
real changes need `--apply`.

## Refusal conditions

- `commit` / `push` refuse when `scan` finds any `real` secret (exit 2).
  `word` = pattern inside a longer token (e.g. `task-...`), `fake` = test
  path or marker value — neither blocks.
- `commit` waits on `index.lock` (3 s × up to 10). Timeout → exit 3
  "다른 에이전트 git 작업 중". The lock file is NEVER deleted or moved,
  and no git process is ever killed.
- `push` refuses `main`/`master` and any force/refspec form (exit 4).
  Push targets a new branch only — merging into `main` is the user's PR
  decision on GitHub, never this tool's job.
- `push` refuses blobs >100 MB (GitHub rejects them); >50 MB warns.
- `--amend`, `-a/--all`, history rewrites do not exist in the parser.

## Approval env vars (granted by the caller for that run only)

- `AWX_PUBLISH_APPROVED=1` — required with `push --apply`. The bat never
  sets it; the operator sets it for the single approved run.
- `AWX_SHIP_SKIP_GUARD=1` + `--skip-guard --reason "<user approval text>"`
  + `scan` real=0 — all three required before `--no-verify` is added.
  Missing any → exit 4. Normal path keeps the repo's hooks
  (`core.hooksPath=.githooks`: pre-commit secret guard, pre-push publish
  review). `publish.allowTarget`/`publish.allowRef` are passed as one-shot
  `git -c` values and are never written to `git config`.

## Secrets discipline

Scan output prints `rule`, `path@sha`, and `value[:6]…(len N)` only —
never a full key. Report files and chats follow the same rule.

## Sandbox proof

`python -B scripts/test_git_ship.py -v` runs 14 tests (T1–T11) in temp-dir
repos with a local bare origin — no network, no repo hooks, no real tree.
