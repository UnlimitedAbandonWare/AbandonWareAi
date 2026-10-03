<!-- moved-from: AGENTS.md L473-L475 sha256=df21bd0117ec7e29cf91177f593c44cd195cddf8d30f7c466e486706efe2ee97 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- AWX-TEST-MODEL-POLICY:BEGIN -->
- RAG·챗봇 테스트는 `.agents/skills/demo1-test-model-policy`를 따른다. 2026-12-30까지는 `chatgpt-oauth:*` API 모델로 테스트하고, 화면 기본 모델(로컬 `qwen3.5:9b`)을 그대로 쓰지 않는다. resolve → select → send → check → record: `scripts/test_model_policy.py`.
- 브라우저·채팅 테스트에서는 이 정책이 goal의 "local first"보다 우선한다. 진입 스킬 = `.agents/skills/demo1-codex-browser-agent` (`scripts/codex_browser_agent.js` — smoke→golden→fault→fullapp→gesture→trace, 생성 ≤25/run, loopback 전용).
<!-- AWX-TEST-MODEL-POLICY:END -->
