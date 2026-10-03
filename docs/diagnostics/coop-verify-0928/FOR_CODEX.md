# FOR_CODEX — cooperative verification hook/wrapper wiring

Contract: `DEMO1-DEVIN-COOP-VERIFY-RAILS-FOR-CODEX-20260928`. Devin rail is landed: `scripts/coop_verify.py`, fake suite green (11/11), skill `.agents/skills/awx-cooperative-verification/SKILL.md`, AGENTS block `DEMO1-COOP-VERIFY-RAILS`. **Codex owns:** completion policy + build-wrapper integration. Work is serialized with MAX-PUSH product work — don't interleave.

## Facts you start from (verified in this checkout)

- `.codex/hooks.json` today: `UserPromptSubmit` + `PreToolUse`/`PostToolUse` guards only. **No Stop/SessionEnd hook exists** — you are adding completion behavior, not editing one.
- Store: `data/agent-handoff/coop-verify/` (auto-created). All state JSON is atomic via `codex_work_checkpoint.write_json`.
- `coop_verify.py` exit codes: 0 PASS · 10 DEFERRED/verifier-busy/locked · 11 INVALIDATED · 20 FAILED · 30 ERROR/missing-input.
- Writer commands are token-fenced (`writerToken`); a foreign agent cannot close your edit_batch.
- `run-once` self-registers a transient runner — a manual call IS the runner, so `WAITING_FOR_RUNNER` tickets are pickable without a `watch` daemon.

## Wire-in (minimum)

### 1. Writer markers around real edits (hooks or wrapper)

Wherever Codex performs edits inside a turn (PreToolUse write adapter / `agent_work_guard.py` shell path), wrap the edit window:

```
coop_verify.py --root . writer-begin --agent codex --task <taskId> --lease-id <lease> --path <repo-rel>...
coop_verify.py --root . writer-heartbeat --batch-id <id> --token <tok> [--source-changed]
coop_verify.py --root . writer-end --batch-id <id> --token <tok>
```

- Call `writer-begin` BEFORE the first write of the batch; `writer-end` when the batch's writes stop. `writer-checkpoint` closes a logical batch (yields the verify slot) without ending the session.
- Never fabricate batches for read-only work. `--path` is the declared scope — verification overlap is judged against it.
- Marker failures (exit≠0) must stay visible in the turn output; they never grant permission to proceed unsafely.

### 2. Verification request + execution (build wrapper)

Inside the compile/verify wrapper (the thing that today runs `gradlew :compileJava -x test` / focused tests):

```
# after your edits are applied, before heavy verification:
coop_verify.py --root . request --agent codex --task <taskId> \
    --profile compile-focused --scope <repo-rel-paths> \
    --command-json '["powershell","-NoProfile","-ExecutionPolicy","Bypass","-File","scripts\\build_wrapper.ps1", ...]'

# single eligible ticket execution (also what a scheduled runner calls):
coop_verify.py --root . run-once            # or: run-once --ticket <id>
# optional daemon mode for a dedicated runner session:
coop_verify.py --root . watch --runner-id codex-runner-1
```

- **Enforce exclusivity inside the wrapper, not only via hook ordering**: treat `run-once` exit 10 as "not verified — stop retrying this turn". Do not loop `run-once` until 0.
- `request` merges duplicates for the same scope/profile (oldest timestamp wins) — do not dedupe client-side.
- The verify command recorded in the ticket is what `run-once` executes. Keep it the real command (`gradlew`/`pytest`), never a stub that echoes success.

### 3. Completion policy (Stop-equivalent — no Stop hook exists today)

Where Codex decides "turn over / job done" (`.codex/hooks.json` addition or the wrapper's epilogue), consult:

```
coop_verify.py --root . status --agent codex
```

The `turnEnd` block gives the honest end-state. Rules:

- `state=VERIFIED_PASS` + receipt ⇒ report pass.
- `state=DEFERRED|WAITING_FOR_RUNNER|QUIESCING` ⇒ end as **`APPLIED_PENDING_VERIFICATION`**: report applied change + ticketId + deferReason. Do NOT re-run the build inline, do NOT spin on `status`, do NOT report pass. One `run-once` attempt per turn is acceptable; if it returns 10, stop.
- `state=INVALIDATED` ⇒ a writer touched scope mid-verify; leave ticket open (auto-requeues), report INVALIDATED, end turn.
- `state=FAILED` ⇒ stable-source failure: report failure with receipt path; this is a real failure signal, not contention.
- `BLOCKED_UNKNOWN_OWNER` anywhere ⇒ surface it; run `coop_verify.py --root . recover` for the report, `--release` only with dead-owner evidence. Never silent-reclaim.
- **Anti-retry rule**: Stop must never translate exit 10 into another build attempt — the ticket is the resumption mechanism, not a retry loop. No LLM calls while waiting on a ticket.

### 4. Report template (mandatory shape)

```
applied: <files + summary>
verification: <VERIFIED_PASS|APPLIED_PENDING_VERIFICATION|INVALIDATED|FAILED|BLOCKED_UNKNOWN_OWNER>
ticket: <ticketId or ->    deferReason: <writer-active|verifier-busy|quiet-window|no-runner|unknown-owner|->
runner: <runnerId | WAITING_FOR_RUNNER>
executedChecks: [build..., focused tests...]   skippedChecks: [heavy suite..., runtime...]
evidence: source=<receipt.identity> built=<pass/fail/not_run> running=<not_run|evidence> onGlasses=<not_run|evidence>
receipt: data/agent-handoff/coop-verify/receipts/<ticketId>.json
```

## Do not

- Don't create a new busy.lock sole-truth, state server, or MCP server — the store under `data/agent-handoff/coop-verify/` is the only rail state.
- Don't touch product Java/RAG/routing or restart Meta Display runtime.
- Don't reclaim foreign leases/writers; expired-but-unknown ⇒ `BLOCKED_UNKNOWN_OWNER`.
- Don't treat build omission, heartbeat expiry, stale-JVM HTTP 200, or `APPLIED_PENDING_VERIFICATION` as PASS.
- No `|| true`, no `-x test` as a *final* bypass claim, no swallowed exceptions, no secrets in hook/wrapper logs.
- Don't modify `scripts/coop_verify.py` semantics without re-running `pytest scripts/test_coop_verify.py -v` (suite is the contract harness).
