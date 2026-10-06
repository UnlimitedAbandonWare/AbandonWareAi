# legacy-directives-202609 (quarantined expired September directives)

Isolation date: 2026-10-05 (task `devin-meta-display-docs-purge-89d5c560`,
directive `PASTE_DEVIN_PURGE_STALE_META_DISPLAY_DOCS_20261005`).

## Why quarantined

These files are completed September 2026 directive/report snapshots. They are
no longer active rules: keeping them in `docs/` root caused agents to re-read
stale specs (fixed 280-char lens cap as an immutable constraint, `gpt-5.6-luna`
as the cue model) as if current. Current SSOT:

- Lens/display contract: `.agents/skills/demo1-meta-display-simple-caption/SKILL.md`
  + `docs/volatile-knobs.md` + `main/resources/application-meta-display.yml`
  (Fold `lensSettings` wins; 280 is a YAML factory default, settings-driven).
- Cue/focus routing: `conversate.cue.routes.*` (gemini-cue=`gemini-3.5-flash-lite`
  primary, preferred-provider `gemini`) and `conversate.focus.*`
  (`default-model`/`web-model`=`llmrouter.gemini-pro`, native `google_search` tool).

## Contents

| File | Role (2026-09) |
|---|---|
| `meta-display-settings-brief-20260919.md` | Meta Display settings brief |
| `devin-patch-spec-20260920.md` | Devin patch spec (executed) |
| `devin-report-followup-gap-20260920.md` | report-vs-source gap audit (executed) |
| `llm-api-consolidated-edit-directive-20260919.md` | consolidated LLM-API edit directive (executed) |
| `llm-api-source-audit-directive-20260919.md` | LLM-API source audit directive (executed) |
| `llm-api-source-audit-directive-20260919-verification.md` | audit verification record |

## Rules

- Read for history only; never treat content here as live spec.
- Restore only via an explicit user-approved directive referencing this folder.