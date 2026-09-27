# Git vibe recipes (referenced by demo1-git-vibe-workflow)

Operational cards for the five intents. Each card says what to run, what it
proves, and what it never does. Read only the card matching the classified
intent.

## A. review — "지금 변경 검토해줘"

- Compare current files against the task's checkpoint preimage first;
  `git diff` is a second view, not the baseline.
- Present staged / unstaged / untracked as three separate groups; an AM or
  MM file has two different versions — say which one is being reviewed.
- Judge "the feature works" by the real test/HTTP/log evidence. An empty
  `git diff` proves nothing about behavior.
- Never fold another session's staged paths into the review as if they were
  this task's work.

## B. diagnose — "어느 수정 때문에 망가졌는지 찾아줘"

- `git log`/`blame`/`diff` on in-scope files, checked against the current
  source. When no commit history covers the change, fall back to work
  journal + checkpoint records (`data/agent-handoff/codex-autonomy/<task>/`).
- `bisect`/`checkout`/`reset` are not read-only — they are never run on the
  canonical tree. If reproduction is needed, use a separately authorized
  isolated environment.
- Answer with `UNKNOWN` + evidence + next owner when the cause cannot be
  shown; do not guess a culprit commit.

## C. handoff — "데빈 작업을 코덱스에게 넘겨줘"

- Producer emits a PatchDrop bundle (`__patch_drop__/producer_bundle.py`):
  preimage-based patch + manifest + verification scope.
- Integrator verifies the live bytes still equal the recorded preimage
  before applying; mismatch → `NEEDS_RECONCILIATION`, not force-apply.
- Never overwrite canonical source with an old Git blob to make hashes
  match.
- Packet carries: paths, owner/session, pre/post sha256, patch, verification
  results, remaining risks — no secret values.

## D. checkpoint — "이번 작업 기록해줘"

- Default record = work journal + checkpoint cycle (Git-free, always
  available even when the index is locked).
- Conditional local commit additionally only when the authorized scope,
  ownership, and verified bytes line up — see `$demo1-conditional-local-git`
  for the gate contract. A blocked commit is recorded as
  "checkpoint-complete, commit-deferred" — two different states, both fine.
- If the dirty baseline is too large to claim tree reproducibility, mark the
  verification `scope-limited`; do not advertise a buildable snapshot.

## E. publish-review — "공개 준비 상태 확인해줘"

- Development history and public snapshot history are different things.
  Never retarget `origin` and push the current HEAD at AbandonWareAi.
- Review only: candidate tree + outgoing history secret scan
  (`scripts/git_publish_review.py`), target URL/owner/repo, ref/OID,
  ancestry — then report. No push, no fetch, no `--allow-unrelated-histories`,
  no force-push, no reset to align histories.
- A remote OID missing from the local object store means ancestry is
  `UNKNOWN` — report it; hooks never fetch to find out.
- A future approved publish builds the candidate in an isolated publish
  workspace (verified Ai tip as parent, allowlisted paths only); the
  canonical checkout stays read-only in that flow. That needs its own
  explicit authorization — this skill performs review only.

## F. worktree use

- Report the registered inventory first (`git worktree list --porcelain`);
  do not grow new worktrees by default.
- `prunable`/`offline` entries are observations — a share or external drive
  can be temporarily disconnected; pruning is a user decision.
- A new worktree cut from HEAD does not replicate the dirty canonical state;
  a worktree is not a way around `index.lock`.
