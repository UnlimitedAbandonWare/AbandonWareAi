<!-- moved-from: AGENTS.md L480-L483 sha256=a1b1e5e99ebcf75c81059ce0a625656b2c3f42a4218f0c6b65a463186c4bf068 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- AWX-TEST-MODEL-POLICY:BEGIN -->
- RAG·챗봇 테스트는 `.agents/skills/demo1-test-model-policy`를 따른다. 2026-12-30까지는 `chatgpt-oauth:*` API 모델로 테스트하고, 화면 기본 모델(로컬 `qwen3.5:9b`)을 그대로 쓰지 않는다. resolve → select → send → check → record: `scripts/test_model_policy.py`.
- 브라우저로 /chat 챗봇을 연습하거나 테스트할 때도 화면 기본 모델을 쓰지 말고 `scripts/chat_practice_browser.js` 또는 `test_model_policy.py resolve`로 모델을 고른다(자유 대화 연습 포함). 2026-12-30까지는 chatgpt-oauth API 모델.
<!-- AWX-TEST-MODEL-POLICY:END -->
