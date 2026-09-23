# Devin Desktop customizations (demo-1)

## How constraints apply (no manual `@rules:`)
1. Root `AGENTS.md` — always-on project guidance (includes short Meta Ray-Ban Display runtime).
2. `.windsurf/rules/` — Windsurf/Devin Desktop workspace Rules:
   - `demo1-hard-constraints.md` — `always_on`
   - `meta-rayban-display-runtime.md` — `glob` on display/Conversate/meta-display paths
3. `.agents/skills/` — multi-step procedures (auto or `/skill-name` / `@skill-name`). Judgment: `positive-negative-neutral-judge`.
4. `.windsurf/workflows/` — Cascade-only manual `/slash` runbooks (Devin Local: use Skills instead).

Do **not** store Skills under `...\Programs\Devin`. Global user skills: `%APPDATA%\devin\skills\`.
Do **not** duplicate the same Rule in both `.windsurf/rules` and `.devin/rules`.
