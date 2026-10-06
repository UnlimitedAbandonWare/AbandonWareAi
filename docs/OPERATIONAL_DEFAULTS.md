> **역사/참고 전용 (2026-10-06 재분류)** — 당시 OpenAI placeholder 기본값 설명이다. 아래 모델명·환경변수 예시는 현재 기본값·지원 모델·OAuth 권한을 확정하지 않으며 그대로 설정하지 않는다.
> 현행 기준: [문서·P0 진입점](PRIMARY_SURFACE.md), [운영 규칙](../AGENTS.md), [현재 현황](PROJECT_STATUS.md), [답변 공개 계약](agents-rules/DEMO1-EVIDENCE-ZERO-RELEASE.md). 아래 원문은 회귀·설계 근거로 보존한다.

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
