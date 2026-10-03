# A4 — script inventory and AGENTS fragment proposal

`AGENTS.md` was not edited. The block below is a proposal for a later owner. Do not paste it over the file.

No new skill was added. A skill file would need `.agents/skills-intent-index.yaml`, which is already dirty in the worktree and is outside this assist.

## Scripts and tools that already exist

| Tool | What help or schema showed |
|---|---|
| `python -B scripts/conditional_local_git.py` | subcommands `policy`, `check`, `scan`, `commit`, `lock` |
| `python -B scripts/agent_git_vibe_commit.py` | the only agent commit entry. Not invoked. Paste forbids an unapproved commit |
| `python -B scripts/work_journal.py` | `open`, `note`, `close`, `status`, `list`, `handoff` |
| `python -B scripts/codex_work_checkpoint.py` | `assess`, `begin`, `apply`, `seal`, `finish`, `restore`, `status` |
| `.\gradlew.bat test --tests <Fqcn>` | focused proof. Full suite is out of scope |
| AWX MCP `awx-control-tower__build_error_mine` | required argument `log_path`. Optional `requestId`, `sessionId`, `nodeRole`, `audit_log`. Use only after a failed build log exists |
| `Verify-RAG.bat` | post-edit dev proof. Not a plugin |

`production_hard_caps` is a spend-guard policy key, not a script.

## Proposal — do not merge in this task

Suggested marker name: `DEMO1-CHAT-WAIT-LAYERS`. Place beside the existing evidence-release and spend-guard blocks. Wording:

```text
## Chat wait layers
Ordinary /chat (first answer or a clear failure, and a finite generation
while tokens are still arriving) is a seconds-layer. Starter targets live
in the NW directive: about 5s to first public answer or terminal, at most
30s of generation. They are not the web/RAG/long-tool wall clock.

Web search, RAG, and a long agent tool loop keep their own wall clock of
about 5 minutes on public.request-budget.max-time-budget-ms. A single
Brave or Naver call stays on its own seconds-level timeout. Do not raise
that call to 300s, and do not copy 300s onto ordinary /chat.

agent-api-spend-guard production_hard_caps: false means the agent/dev
guard does not hard-cap production traffic. It does not mean a chat
request may wait without a deadline. Heartbeats and "still working"
text do not extend the first-answer deadline.

When a live lease holds api-routing.yaml or the request-budget yaml,
leave those files untouched and record the lease. Do not "fix" a 300s
default by editing a leased key.
```

## Why this is not a product change

The 300000 default and the `chat_wait` yaml keys are the Devin lease in `A6-git-lease-note.md` / `CODEX_HANDOFF.md`. Writing the paragraph into `AGENTS.md` during that lease would still be a rule edit, not a fix of the Java `Math.max(..., 180)` path. Codex changes the Java consumers on unleased files. The yaml stays with the lease owner.
