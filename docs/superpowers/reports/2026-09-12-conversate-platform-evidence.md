# Meta Display platform evidence

Checked 2026-09-12. No callable Meta documentation MCP was present in this session's tool inventory. The public documentation page `/docs/develop/webapps/build` returned “Not Logged In”; it was not treated as read. No plugin was installed, AGENTS.md overwritten, account changed or public deployment performed.

Official sources read:

- [Meta Wearables](https://developers.meta.com/wearables/) offers Web Apps for Display and mobile Device Access Toolkit.
- [Official Web App toolkit README](https://github.com/facebook/meta-wearables-webapp) describes standard HTML/CSS/JavaScript, 600×600 viewport, D-pad/Neural Band controls and a publicly reachable HTTPS deployment for glasses. Repository `main` was read; an immutable commit revision was not returned by the web API, so SDK revision pinning remains pending. The first output uses browser standards and adds no Meta SDK runtime dependency.
- [Official display guidelines](https://raw.githubusercontent.com/facebook/meta-wearables-webapp/main/plugins/meta-wearables-webapp/references/display-guidelines.md) establish additive black background, bright readable text and keyboard-focusable controls.
- [Official connect-api reference](https://raw.githubusercontent.com/facebook/meta-wearables-webapp/main/plugins/meta-wearables-webapp/skills/connect-api/SKILL.md) describes remote API/WebSocket updates. Its cache examples are intentionally inapplicable to the user's explicit no-persistence contract; live content is never put in browser storage or service-worker caches.

No official evidence read here establishes raw Web App microphone capture, global overlay or background/locked-phone continuity. Phone capture is a separate input lane; browser and device capabilities will be tested separately. No Android DAT fallback is selected merely because hardware is currently absent. Browser A1 verification cannot certify physical display, Korean font optics, Bluetooth microphone routing or device battery life.
