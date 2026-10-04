<!-- moved-from: AGENTS.md L166-L170 sha256=60f0e558e3cfd1706bb8e859206f77b08777fe91e4c26d7cec69252b31859bfd movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-NOVA-FOCUS -->
## Nova Focus ('노바' wake-word focused conversation)
- `노바` focused-conversation mode (Conversate transcript -> focus UI on Fold + lens -> persistent room -> sequential answers -> idle auto-close) -> `$demo1-nova-focus` (`.agents/skills/demo1-nova-focus/SKILL.md`); spec `agent-prompts/nova-focus/` (Text-Flow addendum supersedes the base doc's paged-answer + first-render-timer design).
- Focus answers are a separate `focus` wire field — never `hint`; followup-idle starts at `presentation_done`. General hint TTL/paging/`ld-*` and `hintsEnabled` stay untouched.
<!-- END DEMO1-NOVA-FOCUS -->
