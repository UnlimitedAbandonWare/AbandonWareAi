# Operational Defaults — OpenAI model configuration

This patch introduces safe defaults to avoid Spring placeholder resolution failures when no model is configured.

## Properties

- `openai.api.model` — defaults to `${OPENAI_API_MODEL:${OPENAI_MODEL:gpt-5-mini}}`
- `openai.chat.model-high-tier` — defaults to `${OPENAI_CHAT_MODEL_HIGH_TIER:${openai.api.model}}`
- `openai.chat.model-low-tier`  — defaults to `${OPENAI_CHAT_MODEL_LOW_TIER:${openai.api.model}}`

## Override via environment

```bash
export OPENAI_API_MODEL="gpt-5-chat-latest"
export OPENAI_CHAT_MODEL_HIGH_TIER="gpt-5-chat-latest"
export OPENAI_CHAT_MODEL_LOW_TIER="gpt-5-mini"
```

These values can also be set in profile-specific `application-*.yml` files.
