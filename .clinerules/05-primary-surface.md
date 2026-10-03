# demo-1 — Primary surface

SSOT: `docs/PRIMARY_SURFACE.md` — pointer only, do not duplicate its body here.

- Primary surface = main `/chat` chat-ui (public `https://abandonwareai.kro.kr/chat`, local `http://127.0.0.1:18180/chat`).
- The interview page (`static/assets/interview/*`, "곁 RAG & Display Studio") is a local debug-only screen — keep it, never treat it as the product surface.
- "Chat works"/"boot OK" verdicts are judged on chat-ui only (`/js/chat.js` loaded, `<title>AbandonWare AI</title>`); interview-page results are auxiliary evidence. Under `demo.interview.enabled=true`, `/chat` forwards to the interview page — never judge the main surface from that result.
