---
name: demo1-work-ledger
description: "Local-first work ledger: task journal, per-change preimage, verify, recover"
---

# demo1 Work Ledger (Git-free work/backup/handoff procedure)

One consistent procedure so any agent can resume: what source/run paths are
live, which request changed which files, what is verified vs unverified, who
registered which scope, and where this change alone can be recovered from.
It reuses existing machinery — do not build a parallel VCS or new report tree.
Git is not the change log (`AGENTS.md` `DEMO1-GIT-LOCAL-FIRST`): preimage bytes,
SHA-256, and `difflib.unified_diff` in the cycle dir are the restore/diff evidence.

## When

- Use for any task that creates/modifies/deletes/moves files under the project
  root `C:\AbandonWare\demo-1\demo-1\src`, by any agent (Codex, Devin, Grok,
  Notebook producers).
- Skip for read-only explanation, search, or status checks — no journal, no
  preimage needed.
- Application-source edits still require the existing gates
  (`demo1-source-edit-three-way-preflight`, target-scoped lease). This skill
  records work; it never grants mutation authority.

## Layout (one root per task)

```
data/agent-handoff/codex-autonomy/<taskId>/     # historical dir name; agent-neutral
  journal.json                                # who/purpose/scope/events (work_journal.py)
  decision.json                               # optional risk packet (checkpoint assess)
  <cycle>/                                    # one bounded change-set (codex_work_checkpoint.py)
    manifest.json  before/*.bin  change.diff  checkpoint.json
```

- `taskId` is issued by `work_journal.py` (`<slug>-<8hex>`); do not reuse
  sessionId/ctx.memory and do not copy raw conversation into records.
- Preimage bytes live only under the task dir — already outside build/agent
  search paths. A hash without restorable bytes is not a backup.
- Never record secret values or collect `.secrets`, `.env*`, key material
  (`.pem/.key/.pfx/.p12/.jks`) — the tools refuse them; env names only.

## 1. Work start

1. Read `docs/PROJECT_STATUS.md` (single canonical status doc).
2. `python -B scripts/work_journal.py list --active` and
   `powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__/source_edit_session.ps1 -Action status -Json`.
   That inventory is global. `sourceLeaseBlockingCount` = active+corrupt+expired
   and is **not** a repository-wide hold. Expired + `owner-evidence-needed`
   still blocks **overlapping** targets only — do not delete those locks.
   To know whether *your* paths may proceed:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__/source_edit_session.ps1 `
     -Action status -Root . -Json -TargetManifest .\targets.json
   ```

   Proceed when `targetConflict.allowed` is true. `-Action` is only
   `begin|end|status|verify|bind-scope|heartbeat|recover` (no `open`).
   `-TargetManifest` is a JSON **file** `{ "targets": [{ "path", "sha256" }] }`
   (`sha256: null` = new still-absent file). There is no `-TargetPath`, and
   inline JSON is not a manifest. If Get-Help disagrees with param(), param()
   wins. `scripts/agent_preflight.py` prints the same rules as `leaseGuidance`.
3. Read only journals/checkpoints relevant to your scope; then inspect the
   actual target files — current files and live evidence beat old reports.
4. Register before changing anything:

```powershell
python -B scripts/work_journal.py open --root . --task <slug> --agent <name> `
  --purpose "<one-line request>" --scope <rel/path> [--scope <rel/path> ...]
# returns taskId — record it in your reply and reuse it for the whole task
```

## 2. Per change-set (bounded target group)

Source/executable targets need the existing lease first (see
`docs/codex-autonomous-work.md`); `docs/`, `agent-prompts/`,
`.agents/skills/**.md`, root Markdown need none. Then run one checkpoint cycle:

```powershell
$cycle = "data/agent-handoff/codex-autonomy/<taskId>/cycle-01"   # unique per set
python -B scripts/codex_work_checkpoint.py assess --decision data/agent-handoff/codex-autonomy/<taskId>/decision.json
python -B scripts/codex_work_checkpoint.py begin --root . --run $cycle `
  --decision data/agent-handoff/codex-autonomy/<taskId>/decision.json `
  --target <rel/path> [--target ...] [--lease __patch_drop__/source-edit-locks/<leaseName>/lease.json]
# begin preserves every target's current bytes + sha256; if begin fails, do NOT start the change
# ... apply the minimal patch to declared targets only ...
#   bounded alternative: apply via the checkpoint (refuses if target drifted
#   from its recorded preimage — a mid-work foreign change is a conflict):
python -B scripts/codex_work_checkpoint.py apply --root . --run $cycle `
  --target <rel/path> --content-file <local-file-with-new-bytes>
python -B scripts/codex_work_checkpoint.py seal --root . --run $cycle
# ... run the real verification command, capture its exit code ...
#   recorded runner (fresh dir per run; status|stop inspect/kill only that run):
python -B scripts/run_verified_command.py --output <fresh-out-dir> `
  [--source <rel/path>] -- <command>
python -B scripts/codex_work_checkpoint.py finish --root . --run $cycle --exit-code <n> --command-id <id> [--log <local-log>]
python -B scripts/work_journal.py note --root . --task <taskId> --kind change `
  --text "cycle-01: <what/why>, verify=<cmd> exit=<n>" --ref $cycle/checkpoint.json
```

- `begin` failing (backup cannot be preserved) means the change does not start.
- Create = `existed:false` → failed verify removes it; delete/move = postimage
  `null` on that target. Record a move as two targets (old path + new path).
- Files over the checkpoint 2 MiB limit: copy each preimage into
  `<taskId>/preimage/<name>.bin`, verify the copy's sha256 against the live
  file, and `note --kind preserve` with path+sha256 — still restorable bytes.
- Do not mark a checkpoint run "one consistent point in time" if foreign
  modification was detected mid-preserve; say which files were re-read.

## 3. Verify and record

- Record the real command, exit code and time in `note --kind verify`.
  "modified" is not "verified"; a failed `finish` auto-rolls-back only targets
  whose sealed postimage is untouched.
- After related sources change, stale verification becomes `stale/재검증 필요`,
  not current-pass.

## 4. Status doc update (single controlled path)

- Update `docs/PROJECT_STATUS.md` at change-set end and at task close: your
  task row in §4, §3 status levels, §6 last-verification, §5 holds.
- Keep levels distinct: `source`/`built`/`running`/`onGlasses` vs
  `unverified`/`failed`/`stale`. Record `docSavedAt` vs `evidenceCheckedAt`.
- Write rows only through `python -B scripts/status_doc.py`: `read --key <id>`
  returns the row + file sha256, then `update-row`/`append-row` with
  `--expect-sha256 <hash>` — a doc changed since the read conflicts instead of
  overwriting rows; a missing key is an error, never an auto-fill.
- The doc itself is still a change-set target; record its edit like any other.

## 5. Recovery

- Verify-failed cycle: `finish` already restored unchanged postimages → status
  `rolled_back` or `hold` (read `checkpoint.json` `firstBlockingRule`).
- Manual rollback of one cycle:
  `python -B scripts/codex_work_checkpoint.py restore --root . --run <cycle>`
  restores only targets still at their sealed postimage; any drifted target is
  reported as a conflict and left untouched. Add `--staging <scratch-dir>` to
  verify preimage copies in a separate location before touching live files.
- If any hash differs, STOP — another writer changed it; mark
  `미기록 외부 변경`, never invent author or reason.
- `restoring`/`hold` cycles resume by re-running `restore` (per-file idempotent)
  after reconciling hashes and the lease — never force-restore over a
  concurrent writer.

## 6. Interruption and handoff

- If the agent stops mid-task, `journal.json` (`in_progress`, events so far) and
  cycle states (`prepared`/`sealed`/`hold`/`restoring`) remain — the next agent
  resumes from the last `verified` cycle and the unfinished journal events.
- Emit a compact receiver packet with
  `python -B scripts/work_journal.py handoff --task <taskId>` →
  `<taskId>/handoff.json` (scope, per-file pre/post/current hashes,
  verification runs, unresolved holds, recovery dirs). Receivers re-read the
  files and compare `currentSha256` before editing — the packet is a map, not
  proof.
- Old journals/checkpoints alone never prove completion; an `in_progress`
  journal is `진행 여부 미확인`, not done and not free-to-overwrite.
- Session forensics: `python -B scripts/awx_session_evidence.py index` once,
  then `parents|search|sessions` (or MCP `session_evidence`) — read-only
  sources, bounded scans, byte-offset hits. Never replay old session commands.

## 7. Reports and synthesis

- Separate proposal / applied-claim / verified-result / held-item in every
  report and journal note. Repeated claims across reports are not independent
  verification; newest-dated docs are not automatically right.
- Old reports stay untouched; the status doc records the current judgment and
  links superseded ones. Rebuild the doc from journals + checkpoints — do not
  re-summarize every raw report each time.

## Limits (say them, don't overclaim)

- Declared targets only: unwatched/unrecorded files are not tracked.
- Partial recovery of a named change-set, never whole-project time travel.
- `running` claims need live evidence of THIS build; stale JVM 200 proves none.
- Lease/hash checks narrow concurrent-edit risk; they are not an atomic
  filesystem transaction.

## Related

- `docs/codex-autonomous-work.md` — checkpoint states, lease binding, rollback.
- `demo1-source-edit-three-way-preflight` — pre-mutation gate for app source.
- `scoped-blocker-recovery` (`references/lease-lifecycle.md`) — lease details.
- `demo1-artifact-trace-curator` — bounded inventory of handoff traces.
- `demo1-goal-complete-stop` — end the turn when the goal is verified.
