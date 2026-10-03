# demo-1 debug studio UI

- Screen: `main/resources/static/assets/interview/` (`debug-studio.js`, `debug-studio.css`, `index.html`).
- Open: `/debug/studio` when that route exists, otherwise `/assets/interview/index.html`. Loopback only.
- This is not the primary surface. Primary chat is `/chat` (`/chat-ui`). See `docs/PRIMARY_SURFACE.md`.
- Do not store or copy raw questions, answers, cookies, tokens, or Authorization headers. Collapse state uses sessionStorage only.
- Product Java, `chat-ui.html`, and `chat.js` stay with Codex and Devin. Skill: `.agents/skills/demo1-debug-studio/SKILL.md`.
