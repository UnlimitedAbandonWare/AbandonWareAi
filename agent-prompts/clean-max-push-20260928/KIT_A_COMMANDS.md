# Kit A — M9, ReleaseGate, B04+ regression

Use this kit while `CURRENT_KIT.md` says `kit: A`.
Dot-source the env in the same PowerShell window before Gradle. Output goes
under the Codex task directory when Codex runs the command. Clean does not run
these product tests as a substitute for Codex.

```powershell
. .\scripts\max_push_kit_env.ps1
```

That sets `AWX_SPLIT_BUILD_OUTPUTS=1`, `AWX_BUILD_HOST_ID=codex-maxpush`, and
`AWX_AGENT_SPEND_GUARD=1` for the current window only. Gradle reads those env
names in `build.gradle.kts` and writes under `build/codex-maxpush/`. The live
Start-RAG launcher keeps host id `desktop-meta-display`.

Lease check, one shot:

```powershell
python -B scripts/work_journal.py list --active
powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__/source_edit_session.ps1 -Action status -Json
```

Skip any path that journal `max-push-b-perf-0928-50df21d2` is already editing.

## Focused commands

Live classes confirmed in this checkout on 2026-09-28:

| Seam | Class or file |
|---|---|
| M9 | `com.example.lms.api.RagMemSteerDynamicToggleProbeTest` |
| M5 / merger regression | `com.example.lms.api.ChatSessionMetaMergerTest` |
| B02 evidence | `com.example.lms.api.ChatApiAgentPromptEvidenceTest` |
| D1 node | `src/test/js/chat-search-outcome.test.cjs` |
| ReleaseGate | `com.example.lms.service.ChatWorkflowFinalVerificationReleaseGateTest` |

M9 and merger, recorded:

```powershell
$out = "data/agent-handoff/codex-autonomy/max-push-b-perf-0928-50df21d2/verify-m9"
python -B scripts/run_verified_command.py --output $out -- .\gradlew.bat test --tests com.example.lms.api.RagMemSteerDynamicToggleProbeTest --tests com.example.lms.api.ChatSessionMetaMergerTest --no-daemon --console=plain
```

B02 plus the node search-outcome file:

```powershell
$out = "data/agent-handoff/codex-autonomy/max-push-b-perf-0928-50df21d2/verify-b02-d1"
python -B scripts/run_verified_command.py --output $out -- .\gradlew.bat test --tests com.example.lms.api.ChatApiAgentPromptEvidenceTest --no-daemon --console=plain
node --test src/test/js/chat-search-outcome.test.cjs
```

ReleaseGate:

```powershell
$out = "data/agent-handoff/codex-autonomy/max-push-b-perf-0928-50df21d2/verify-release-gate"
python -B scripts/run_verified_command.py --output $out -- .\gradlew.bat test --tests com.example.lms.service.ChatWorkflowFinalVerificationReleaseGateTest --no-daemon --console=plain
```

B04+ stays off this card until a failure reproduces. Do not start a full `:test`.

## Rule pointer

M9 is an addition on `ChatSessionMetaMerger` using the same sticky pattern as
`memoryMode`. Do not change the search-outcome policy while touching M9.
R2 and R3 behavior stays. A green focused run is not a full-suite Done.
