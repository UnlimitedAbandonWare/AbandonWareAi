# Long Think Goal Composer Reference

This reference keeps command-heavy decomposition detail out of the skill
entrypoint.

## Intake Commands

Read the pasted or attached goal first. If output is garbled, reread with
explicit UTF-8.

```powershell
Get-Content -LiteralPath C:\path\to\attachment.txt -Encoding UTF8
```

Then verify the Desktop root:

```powershell
Set-Location "C:\AbandonWare\demo-1\demo-1\src"
Get-Location
$indexOp = (Test-Path ".git\MERGE_HEAD") -or (Test-Path ".git\CHERRY_PICK_HEAD") -or (Test-Path ".git\rebase-merge") -or (Test-Path ".git\rebase-apply")
if ($indexOp) { Write-Error "[AWX][desktop] index-operation-active"; exit 1 }
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
'{"nodeRole":"desktop","root":".","requestId":"long-think-source-scan","sessionId":"long-think"}' | python .\scripts\awx_mcp_toolbox.py --input-json - source_scan
```

Use current command output over memory or prompt claims.

## Goal Table

Before proposing a patch, complete this table from evidence:

| Field | Required value |
| --- | --- |
| `target_metric` | Desired observable outcome |
| `observed_current` | Current value from command, file, or log |
| `gap` | Difference between target and current |
| `suspected_causal_chain` | symptom -> direct cause -> root cause candidate |
| `active_surface` | exact file, class, API, script, prompt, or tool |
| `active_sourceSet` | sourceSet proven by Gradle or source_scan |
| `proof_command` | narrowest command that can falsify the candidate |

If any row cannot be completed, write an `evidence_needed` row with the missing
artifact and exact verification command.

## Candidate Scoring

Search using the goal's concrete noun plus demo-1 seams:

```powershell
rg -n "TraceStore|DebugEventStore|PromptBuilder|disabledReason|goal_next_auto|desktop_control_loop|PatchDrop|supabase|browser|computer-use" main app scripts src\test agent-prompts -S
```

Score only candidates that affect active source or active script behavior:

| Candidate | Evidence | causal_strength | patch_size | blast_radius | verification_cost | success_probability |
| --- | --- | --- | --- | --- | --- | --- |

Use 1-5 for numeric columns and L/M/H for probability. Drop weak-evidence
candidates instead of widening the patch.

## Decomposition Modes

Choose the smallest mode that fits current evidence:

- direct: one failing assertion, property default, UI label, schema flag, or
  script branch.
- 2-way: two independent proof axes, such as source behavior plus UI wording.
- 3-way: source ownership, external evidence, and usage/cost all matter.
- N-way: only when the goal explicitly asks for derived skills or independent
  agent lanes.

For SMB/Codex usage work, natural axes are runtime/source ownership, external
evidence requirements, and cost/usage loops.

## SUPER Title And Token

Before editing, derive a short title and safe token:

```text
SUPER_TITLE = Desktop Producer Proof Demotion
SUPER_TOKEN = SUPER::desktop-only-ready::producer-proof-overrequired::optional-supporting-evidence::goal-next-auto-tests
```

Rules:

- Use file, command, or log evidence.
- Avoid speculation words.
- Keep the token safe as a PatchDrop topic slug.
- Keep it narrow enough that the proof gate is obvious.

## Output Shape

Return these fields:

```text
SUPER_TITLE:
SUPER_TOKEN:
decomposition_decision:
goal_table:
candidate_scoreboard:
selected_branch:
patch_intent:
proof_command:
evidence_needed:
```

If source evidence already proves no patch is needed, return `no_patch_needed`
with the command that proved it.

## Derived Skill Branches

For the SMB decommission and Codex-usage family, treat the composer as the
intake/root branch and choose one follow-on branch for the next proof decision:

| Branch | Use when |
| --- | --- |
| `demo1-long-think-goal-composer` | pasted 5-9 hour goals need intake, scoring, or decomposition |
| `demo1-desktop-only-proof-loop` | Desktop-local proof may be enough without producer or UI proof |
| `demo1-patchdrop-manual-default` | PatchDrop queue, source leases, or producer sidecars affect ownership |
| `demo1-demand-driven-external-proof` | Browser, Computer, or Supabase proof must stay demand-driven |
| `demo1-codex-usage-triage` | repeated scans, dispatch packets, or full reports increase tool use |
| `demo1-superpowers-repo-evidence-guard` | process skills must stay below repo evidence and validator output |
| `demo1-skill-family-postprocessor` | generated skills need compact validation, trigger cleanup, or artifacts |

Use only the branch that affects the next proof decision. Do not load every
branch by habit.

## External Lane Defaults

- Browser and Computer are optional supporting evidence unless UI or Windows UI
  behavior is the changed surface.
- Supabase is read-only and `evidence_needed` unless project ref and auth are
  proven in the current run.
- Mac mini and Notebook producer evidence is supporting for Desktop-only source
  work unless explicit PatchDrop handoff is requested.
- Superpowers is a process helper; repo evidence and verification output remain
  higher authority.

## Guardrails

- Do not fabricate build, boot, Browser, Computer, Supabase, Mac mini,
  Notebook, or PatchDrop proof.
- Do not treat Browser/Computer/Supabase tags as edit authorization.
- Do not mutate Supabase or secrets while composing goals.
- Do not require producer bundles for Desktop-only work unless explicit handoff
  is requested.
- Prefer Desktop local source proof, external lanes as supporting evidence,
  count-only secret reports, artifact paths over raw logs, and exact failure
  classes over generic confidence.

## Skill-Family Verification

For skill-only updates, verify the changed skill and family report:

```powershell
$env:PYTHONUTF8 = "1"
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-long-think-goal-composer\scripts\validate_long_think_goal_composer_tests.ps1 -Root .
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py .\.agents\skills\demo1-long-think-goal-composer

powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -ReportPath .\data\agent-handoff\skill-family\latest.json -HistoryDir .\data\agent-handoff\skill-family\history -HistoryRetain 20 -DiscoverPrefix demo1- -CompactReport -SummaryJson
```

Read `nextActionSummary` first in the compact output. Open the full family
report only when that summary points to a count or target that needs detail.
If `nextActionSummary.repeatProbeRecommendation` is
`do_not_repeat_until_filesystem_state_changes`, carry the manual review blocker
forward without repeating the same filesystem probe in the current run.

Before treating a skill-family artifact as progress on a 5-9 hour goal, read
`goalCompletionClaimAllowed` and `goalUpdateRecommendation`. If
`goalCompletionClaimAllowed=false`, keep the active goal open and follow
`goalUpdateRecommendation`; `artifactCompletionStatus=artifact_ready` proves
only the skill artifact gate, not full `/goal` completion.

For repeated continuation turns, also read `fullReportRecommended`,
`fullReportActionMode`, and `summaryReuseCondition`. If
`fullReportRecommended=false`, reuse the latest compact summary until
`summaryReuseCondition` is no longer true, and follow `fullReportActionMode`
instead of opening the full report by habit.

When present, read `nextActionSummary.compactReportLine` before expanding
manual filesystem review detail. If the compact line says
`same blocker, no repeat probe`, reuse that line in progress and final reports,
carry the blocker forward, and do not repeat count-only filesystem probes until
`resumeCondition` changes.
