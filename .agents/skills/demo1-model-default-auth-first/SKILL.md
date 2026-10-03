---
name: demo1-model-default-auth-first
description: 채팅/에이전트 기본 모델·모델 선택 순서를 auth(ChatGPT OAuth) 우선으로 다룰 때 — 기본값 변경, fallback 순서, effective default 판정. SSOT는 룰 문서.
---

# demo1-model-default-auth-first

SSOT: `docs/agents-rules/DEMO1-MODEL-DEFAULT-AUTH-FIRST.md` — 기본 `chatgpt-oauth:gpt-5.5`, 순서 auth→유료API→무료→로컬, 로컬은 마지막 폴백, oauth 실패 재시도 금지, `local_fallback` lane 보호.
확인: `python -B scripts/model_default_probe.py --json`. 제품 패치는 Codex 게이트(OAuth stream fix + wp3 lease) 뒤.
