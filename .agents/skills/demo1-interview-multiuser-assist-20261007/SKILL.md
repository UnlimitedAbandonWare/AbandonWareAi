---
name: demo1-interview-multiuser-assist-20261007
description: Read-only pin, hash, coverage, state, diff-review, and lease checks for the Codex briefs INTERVIEW_FEATURE_STATUS_PRESENTATION_20261007 (same-answer summary/provider projection) and SMALL_MULTIUSER_SEARCH_RESILIENCE_20261007 (2-5 user burst fairness/cancel/429). Product source stays with Codex.
---

# Interview-status + small-multiuser assist (2026-10-07)

## When
Codex is patching `PASTE_CODEX_INTERVIEW_FEATURE_STATUS_PRESENTATION_20261007`
(owning journal `codex-interview-feature-ee02e904`, scope-claimed 7 targets)
or `PASTE_CODEX_SMALL_MULTIUSER_SEARCH_RESILIENCE_20261007` (WP0-WP4 queued)
and the assist side needs anchor freshness, brief-hash drift, acceptance
coverage, forbidden-line cues, lease overlap, or a journal timing gate.

## SSOT
`var/codex-assist-interview-multiuser-20261007/README.md`

## Check
```
python -B scripts/interview_multiuser_assist.py state --root .
python -B scripts/interview_multiuser_assist.py hashes --root .
python -B scripts/interview_multiuser_assist.py pins --root .
python -B scripts/interview_multiuser_assist.py cover --root .
python -B scripts/interview_multiuser_assist.py diff-review --root . [--diff <owned.diff>]
python -B scripts/interview_multiuser_assist.py lease --root .
python -B scripts/interview_multiuser_assist.py snapshot --root .
python -B scripts/interview_multiuser_assist.py journals --root .
python -B var/codex-assist-interview-multiuser-20261007/selftest_spec.py
```

## Do not
1. Edit product Java/JS/CSS/HTML or product tests from this skill — brief A
   targets are leased/scope-claimed by `codex-interview-feature-ee02e904`;
   brief B seams belong to the owning Codex session when it claims them.
   ChatApiController/StandardPromptBuilder were foreign-lease candidates at
   brief time (session469-evidence-reload-green / codex-p0-packing-*).
2. Treat OBSERVED/state/cover exit 0 as a product PASS — the owning session's
   focused Gradle (`--offline --tests <Fqcn>`) and `node --test` runs decide.
3. Reclaim or force-release a live source lease; `lease` is read-only status.
4. Run Gradle while an owning session is mid-work — `journals` is the timing
   gate; shared build lane.
5. Write an old SHA back after `hashes` reports DRIFTED — re-read the file.
6. Promote local permit-wait failure to provider-down, add per-provider search
   badges to the answer summary, or enable paid routes — all brief-forbidden.
