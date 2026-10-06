---
name: demo1-chat-oauth-model-order-assist-20261006
description: Read-only pin, coverage, diff, and fresh-default checks for the Codex chat OAuth model-order brief. Product source stays with Codex.
---

# Chat OAuth model-order assist (2026-10-06)

## When
Codex is patching `CHAT-OAUTH-MODEL-ORDER-20261006-v1`, and the assist side needs anchors, forbid lines, or the fresh-session default check.

## SSOT
`var/codex-assist-chat-oauth-model-order-20261006/README.md`

## Check
```
python -B scripts/chat_oauth_model_order_assist.py pin --root .
python -B scripts/chat_oauth_model_order_assist.py cover --root .
python -B scripts/chat_oauth_model_order_assist.py diff-forbid --root . --diff <owned.diff>
node --test scripts/chat_oauth_model_order_gap_tests.cjs
node --test src/test/js/chat-model-picker.test.cjs
```

## Do not
1. Edit `chat-model-picker.js`, `src/test/js/chat-model-picker.test.cjs`, `chat.js`, `chat-ui.html`, or catalog Java from this skill.
2. Treat exit 0 as a product PASS, an account-catalog proof, or a browser run.
3. Force-release lease `chat-oauth-model-order-20261006-6fa035b9`. Journal `frontend-fast-ui-d0d442f6` also names the picker.
4. Restore paste SHA12 `61386b0739da` over the live picker. Re-read the file.
5. Add a Luna alias, an auth field, a new `/api/models` route, or a new default model.
