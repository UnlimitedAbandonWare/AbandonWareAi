---
trigger: glob
globs: "**/settings*.html,**/settings-page.js,**/settings-routing.js,**/chat-settings-bridge.js,**/SettingsPageController.java,**/RoutingSettings*.java,**/RoutingProfile*.java,**/RunRoutingSnapshot*.java,**/RoutingOutcome*.java"
---

# demo1-settings-routing-guard

Guardrails for the Codex `/settings` page + six-role routing work
(`agent-prompts/codex-settings-page-routing-v3-20261002/`). Devin verifies;
Codex implements. Enforcement = `scripts/settings_routing_guard.py`
(`--snapshot` / `--check`), not this text.

- S1 `main/resources/static/js/chat.js` drift since baseline = 0 → **G1**.
  (Baseline itself may already carry a foreign hunk — preserved, never
  enlarged or shrunk.)
- S2 `chat-ui.html` additions over baseline ≤ 2 lines (menu `/settings` link
  + bridge `<script>` only) → **G2**.
- S3 Security package (`com/example/lms/security/**`), domain/entity
  packages, `db/migration|ddl|*.sql`, `assets/interview/**`,
  `SettingsController.java`, `ModelSettingsController.java`,
  `PageController.java` byte-identical to baseline; no new files under those
  paths → **G4**.
- S4 `chat.settings.routing.enabled` never defaults `true` — absent or
  `false` in every `application*.properties` and every `@Value` default →
  **G5**.
- S5 Every other-session hunk in the 8 M files stays byte-identical in the
  worktree diff (added lines preserved, removed lines stay removed) →
  **G2/G3**.
- S6 New endpoints only under page `/settings` and `/api/settings/routing/*`;
  nothing touches the existing `/api/settings` contract → **G6**. Devin never
  calls `routing/save` and makes 0 paid/external-LLM calls; probe POSTs are
  limited to proven-pure `read`/`preview`, once each (see
  `scripts/settings_page_probe.py`).
- S7 Devin writes only inside its lease scope (`scripts/settings_*.py`,
  `scripts/test_settings_*.py`, this rule, `.agents/skills/demo1-settings-
  routing-assist/`, `docs/diagnostics/settings-routing-v3-20261002/`,
  `var/settings-guard/`, 1 line in `.devin/RULES_SSOT.md`) — `main/` product
  source is never edited by Devin → D7.

Companion checks: new `@Entity` / `CREATE TABLE` / `*.sql` → **G8**; secret-
looking literals in new/edited lines → **G7** (reports file:line, never the
value).
