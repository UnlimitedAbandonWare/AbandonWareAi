# Decisions by operation and evidence

| Observation | Proceed after its own safety checks | Held scope / next deciding evidence |
| --- | --- | --- |
| Unknown zero-byte or old index.lock | Read/static analysis; declared index-free edits through scoped lease and preimage checks | Index/ref writes and unscoped apply; owner evidence is required for cleanup |
| Disjoint declared targets in another active session | Concurrent begin, verify, edit and owned release in the same source folder | Only a canonical target intersection or unknown scope holds the affected edit |
| Legacy lease contains a target-manifest hash but no paths | Bind the exact original manifest with `source_edit_session.ps1 -Action bind-scope` | A mismatched hash cannot establish scope; preserve the original lease bytes |
| Git missing, `.git` absent, dirty tree, CIM writer-check unavailable, or Git `ls-files` / `status` / `worktree list` reader | Target-scoped worktree-edit after lease + preimage checks | Index/ref writes; do not spawn git CLI as an ordinary source-edit gate (`AGENTS.md` `DEMO1-GIT-LOCAL-FIRST`) |
| `GIT_INDEX_FILE` or linked worktree | Filesystem `.git` dir, or `gitdir:` file, then `index` + `.lock` (`GIT_INDEX_FILE` when set) | Do not assume `.git` is a directory or resolve `index.lock` independently; do not call `git rev-parse` by default |
| Proven this-root `git.exe` writer, merge/rebase/cherry-pick/revert/sequencer, changed preimage or overlapping lease | Non-mutating investigation | That overlapping source operation only; refresh actual writer/lease and preimage evidence. Unproven Git CLI / missing `.git` is not this hold |
| Patch includes `--index`, `--cached`, `--3way`, `--intent-to-add`, `-3`, `-N` | Review the patch | This is not an index-free apply; use its real operation contract |
| Build requested while index is locked | A build with verified index-independent side effects, source concurrency and isolated caches | Unknown build hooks or source writes remain held; inspect the affected task |
| Owned resource changes PID/start time, file identity or lease hash before cleanup | Record the replacement; preserve it | Cleanup; do not infer identity from a reused PID, age or empty file |
| Candidate tests pass but active code is RED | Continue approved reconciliation | Active source completion; apply matching source and rerun active tests |
| Protected config/secret change in one candidate | Separate authorized nonoverlapping candidate | Unsafe candidate; preserve openssl/opnessl bytes and count-only secret evidence |
| Identical blocker fingerprint, no new proof/recovery | Independent work with its own gates | Repeated full audit; otherwise await a named external condition |
| Same patch/skill rerun | Verify current path/hash and behavior | No duplicate insertion; `NO_PATCH_NEEDED` when already correct |

The common PowerShell decision does not apply patches, delete `.git`/`index.lock`,
or kill `git.exe`. Git index locks are not a repository-wide source-edit ban.
`verify` checks the source lease, fingerprint, target hashes, and proven this-root
Git writers / worktree-mutation markers immediately before apply. A successful
check cannot make a later changed preimage safe; the caller must still match
candidate/preimage bytes and inspect exact postimages. Preserve failed evidence
and task-only rollback.

## GPU and fallback evidence

Keep device, endpoint, model and function separate. A healthy 3060 is an execution
candidate even when the 3090 is lost; it is not proof that the target model runs.
Preserve `nvidia-smi` exit code, stdout and stderr together: partial output plus an
error is neither whole-device success nor whole-device failure.

Record independently: device query, API response, model presence, loaded model,
GPU VRAM allocation, inference result, and application fallback semantic result.
`/api/version` proves reachability only. Empty `/api/ps` means no observed loaded
model. If the exact model exists, memory/capabilities fit and execution is authorized,
use one bounded preparation request through the existing manager; use its cold-start
timeout rather than the ordinary response timeout. Do not replace the target with a
smaller model. CPU or partial GPU placement must be labelled accordingly.

GPU UUID environment selection affects only a newly owned child. It does not alter
an external Ollama process. Reuse healthy existing services; never terminate or reset
unknown processes or devices. A failed primary GPU does not prohibit an already
authorized healthy-GPU/CPU/provider fallback, but that path needs its own evidence.

Fallback proof must correlate request ID, route, adapter call, transmission, provider
response and final answer meaning. Missing transmission/response is `not_observed`.
A separate catalog-list 403 is not a generation-failure cause without same-request
evidence. Preserve `MCP_STYLE` and the existing declared protocol revision.

## Output and resume contract

For HOLD use `holdScope`, `firstBlockingRule`, `blockingEvidence`,
`independentWorkCompleted`, `repositoryWideHold`. The last field can be true only
when all authorized lanes are unsafe or no meaningful verification is possible.
Keep application, tests and live acceptance as separate statuses. Keep existing
goal-next exits (0 success, 2 evidence_needed, 4 secret risk) and source-session
failure codes. The Codex goal tool's own status rules remain authoritative.

Hash blocker reason/scope, relevant source/config and resource identity, excluding
observation clocks and audit counters. Keep one state record with the unchanged
count and last observation. New proof, source/config change, writer/lease/index
identity change or changed requested capabilities triggers a fresh decision.
Do not repeatedly ask for the same lock-cleanup approval; finish independent work.
