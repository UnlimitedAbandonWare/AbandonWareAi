# Kit E — multi-session and regression card

This kit stays beside A, B, C, or D. It does not replace
`docs/diagnostics/multi-session-build-coexist-0928.md`. That file is the
ops card. The lines below are the MAX-PUSH paste.

```text
[MULTI-SESSION] Project Root: C:\AbandonWare\demo-1\demo-1\src
AWX_SPLIT_BUILD_OUTPUTS=1; AWX_BUILD_HOST_ID=codex-maxpush
focused tests only; no clean / no full suite
one Spring/DevWatch owner; no second 18180-18182; no kill others' JVM
lease before edit; skip files already leased
no add -A / push; preserve foreign staging
spend-guard: no replay of a green check; no paid fanout
ambiguous build -> HOLD_AMBIGUOUS_BUILD; don't "fix" product on it
```

Codex journal `max-push-b-perf-0928-50df21d2` already lists `main/java`,
`src/test/java`, product `main/resources`,
`docs/diagnostics/max-push-progress-0928.md`,
`docs/diagnostics/max-push-b-perf-0928.md`, and `docs/PROJECT_STATUS.md`.
This contract also forbids Clean from editing `chat.js`. Clean does not write
those paths.

ForceRestart and DevWatch have one owner. Clean does not start a second
Spring on 18180–18182. A full suite or `clean` / `cleanTest` is not a Done
condition. Check a completion sentence with:

```powershell
python -B scripts/max_push_done_guard.py --text "<completion sentence>"
```

Exit 2 means the sentence claims a full suite or five admin-login scenarios
as Done. Exit 0 does not mean the product patch passed.

Git for this contract: no `add -A`, no `add .`, no push, no merge, no fetch.
Foreign staged paths stay staged. The standing entry point, if a later
explicit commit is asked, remains `python -B scripts/agent_git_vibe_commit.py`.
This Clean packet does not commit.

## H2 DDL 132

Use `H2_DDL_CLASSIFY_MEMO.md`. Classification only. Do not drop tables and do
not rebuild the schema.
