---
name: demo1-display-luna-auth-latency-assist-20261006
description: Read-only pin, coverage, gap, and diff checks for the Codex Display Luna auth and first-useful-sentence brief. Product source stays with Codex.
---

# Display Luna auth and search latency assist (2026-10-06)

## When
Codex is patching `DISPLAY-LUNA-AUTH-SEARCH-LATENCY-20261006`, and the assist side needs anchors, the delta-slice gap, or a diff forbid check.

## SSOT
`var/codex-assist-display-luna-auth-latency-20261006/README.md`

## Check
```
python -B scripts/display_luna_auth_latency_assist.py pin --root .
python -B scripts/display_luna_auth_latency_assist.py cover --root .
python -B scripts/display_luna_auth_latency_assist.py gap --root .
python -B scripts/display_luna_auth_latency_assist.py diff-forbid --root . --diff <owned.diff>
```

## Do not
1. Edit `main/java`, product resources, `chat.js`, or product tests from this skill.
2. Treat exit 0 as a product PASS, a live OAuth call, a TTFT number, or glasses proof.
3. Force-release live leases. At assist open those were `devin-display-fast-guard` on `NovaFocusAnswerService.java`, and `gemini-rescue-main` on `ChatWorkflow.java` and `chat.js`.
4. Rewrite plain `gpt-5.6-luna` into `chatgpt-oauth:gpt-5.6-luna`, send Gemini grounding text into Luna, or add `service_tier`.
