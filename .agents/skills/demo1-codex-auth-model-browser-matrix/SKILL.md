---
name: demo1-codex-auth-model-browser-matrix
description: Use after Codex patches /chat, streaming, error display or chat UI — open the browser and test across chatgpt-oauth models with varied prompts and symptom-driven gestures, then fix only failing cells.
---

# demo1 Codex auth-model browser matrix

Post-patch verification loop for `/chat`: prove the fix in a real browser
across `chatgpt-oauth:*` models, varied prompts, and the gesture that
reproduces the video symptom — then re-patch only the failing cells.

## SSOT files

- Gestures (symptom → steps → asserts): `configs/browser-gesture-catalog.yaml` (G01-G08)
- Prompt corpus (8 categories × ≥4): `configs/chat-test-prompts-varied.json`
- Runner: `scripts/chat_auth_model_matrix_browser.js` (reuses `test_model_policy.py`,
  `browser_model_select.js`, `chat_rag_golden_browser.js`, `chat_ui_browser_fault_fixture.js`)
- Reports: `data/agent-handoff/test-model-policy/matrix-<ts>.{json,md}`

## Loop

1. **Before patching**: pick the gesture matching the symptom from
   `browser-gesture-catalog.yaml`, then
   `node scripts/chat_auth_model_matrix_browser.js --dry-run --gestures G0x`
   to see the cell plan (0 generations).
2. `needsModelCall=false` gestures run on the fault fixture at 0 cost:
   `--fixture --gestures G01,G02,G03` → confirm **RED** on current source.
3. Patch the source → same fixture run must go **GREEN**.
4. Then `--tier smoke --live` (≤8 generations, 2 auth models). A failing cell
   → fix only that cell's cause → re-run just it via
   `--only-failed <prev-matrix.json>`.
5. Final verdict needs one `--tier regression --live` run (≤15 generations).
   A fixture/mock PASS is never a live verification.
6. Report the cell table + `gen_used` + the NOT_OBSERVED list. An unobserved
   cell is never a PASS.
7. `chat.js`/`chat-style.css`/`main/**` edits only when the governing
   directive's scope includes them — otherwise record HOLD with the reason.

## VIBE_OPEN

- VIBE_OPEN(`configs/vibe-open.yaml`)이면 Display·Fold6·웹 Display 접근 인증
  검사는 `DEFERRED_SECURITY`로만 기록하고 잠금·토큰 게이트를 제안하지 않는다
  (`docs/security/VIBE_OPEN.md`).

## Never

- pick a model outside `configs/agent-test-model-policy.yaml` or a
  `neverSelect` id (`chatgpt-oauth:codex-auto-review`),
- retry the same model after a 401/403/429 — move one rank down, once,
- store answer text, tokens, cookies or trace bodies in reports/Git,
- send to the public URL beyond the 3-send `[devin-test]` cap — default is
  loopback `http://127.0.0.1:18180` only.
