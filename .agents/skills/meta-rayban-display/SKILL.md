---
name: meta-rayban-display
description: "Use when working on Meta Ray-Ban Display lens hints, cueHint, or meta/index.html UI"
triggers:
  - user
  - model
redirect: demo1-meta-display-simple-caption
deprecated: true
---

# meta-rayban-display

## Source of truth (alias)
Read and follow `.agents/skills/demo1-meta-display-simple-caption/SKILL.md`. Edit the SSOT, not this alias.

Fold 안경 표시 설정에서 전사/힌트 유지시간·페이지 전환 간격 **및 힌트 생성 주기**(20초 `display-ttl`/`hintHoldUntil`, ~2초 페이지 하한, 2.5초 `trigger-quiet-ms`, 10초 `cooldown-ms`, 180초 `force-after-ms`)를 정수 초로 넣고 **이전 저장값을 복원**하게 하는 요청은 **기존 소스를 최소 수정**한다. YAML 기본값은 공장값이다. 저장 후 실제 생성·표시 주기가 바뀌어야 한다. 보고서만 쓰지 말 것. `#segment-preset`(수음 구간)과 혼동하지 말 것. AGENTS.md의 최신 “settings-driven cycle” 문장이 스킬의 옛 “do not touch generation knobs”보다 이긴다.

## Hard stops
- Minimal diff. No secret values. No openssl key name/value/format/structure changes.
- Do not delete Grok Bot skills.
