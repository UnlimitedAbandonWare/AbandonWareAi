---
name: demo1-agents-md-budget
description: Use when adding or editing rule blocks in AGENTS.md, when agents seem to ignore rules that live past a byte budget (Codex 65,536 B here, agy ~24,000 B), or when AGENTS.md size needs checking. Detail SSOT docs live in docs/agents-rules/ and stubs point at them.
---

# demo1-agents-md-budget — AGENTS.md byte-budget guard

Why this exists: Codex reads only `project_doc_max_bytes` (65,536 on this
machine — see `scripts/test_codex_instruction_governance.py`), agy ~24,000 B.
Rule text past the cut is invisible to the agent. AGENTS.md is therefore a
**stub index**: BEGIN/END markers + original title + 1-2 line summary +
`docs/agents-rules/<BLOCK-ID>.md` pointer. Full rule bodies live in
`docs/agents-rules/` (each doc's first line records origin lines + moved sha).

## Commands (run from Project Root, stdlib only)

```powershell
python -B scripts/agents_md_budget.py report [--json]   # bytes, cut lines, blocks beyond each budget
python -B scripts/agents_md_budget.py check             # exit 1 on: >30,000 B, CRITICAL past 24,000 B,
                                                        # missing required ## headings, BEGIN/END mismatch
python -B scripts/agents_md_budget.py split --plan      # dry-run KEEP/MOVE classification -> plan.json
python -B scripts/agents_md_budget.py split --apply --plan-file plan.json --ledger <dir>
python -B scripts/agents_md_budget.py verify --ledger <dir>   # doc sha == source sha for every moved block
python -B scripts/agents_md_budget.py restore --from <backup> # full-byte restore
```

## Adding a rule to AGENTS.md

1. Write the full text as `docs/agents-rules/<NEW-ID>.md` (lease first:
   `__patch_drop__/source_edit_session.ps1 -Action begin -TargetManifest`).
2. In AGENTS.md add only the stub:
   `<!-- BEGIN NEW-ID -->` + `## Title` + `- <when-to-read summary> — 상세:` + `<!-- END NEW-ID -->`.
3. Run `check` — it must exit 0. Never let the file pass 30,000 B; the
   ~24,000 B (agy) and 65,536 B (Codex) cuts are reported by `report`.

Hard invariants: `DEMO1-PROJECT-ROOT`, `DEMO1-PRIMARY-SURFACE`,
`DEMO1-CORE-AUTO` stay inline inside the first 24,000 B; the `##` headings
`Desktop / Mac Mini / Notebook Workspaces`, `Safe Patch Rules`,
`Prompt, Search, And Provider Hygiene`, `Skill And Prompt Routing`,
`PatchDrop Bundle Rules`, `Redaction`, `Evidence And Verification`,
`Runtime Boundary And Active Runtime Map` must never be removed (governance
test anchors). Moving ≠ deleting: every moved body is sha-verified.

Korean user doc: `docs/agent-tooling/agents-md-budget-ko.md`.
