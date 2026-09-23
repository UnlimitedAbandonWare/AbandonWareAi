# Mutable Spec Policy — SSOT Table

This repo's specs drift. Only the hard constraints are constants; every spec
value below must be re-read from its SSOT (and probed live when needed) before
implementing or verifying. Policy block: AGENTS.md `DEMO1-MUTABLE-SPEC-POLICY`;
procedure skill: `$demo1-mutable-spec-policy`.

## Hard constants (immutable)

- Project Root rules — AGENTS.md `DEMO1-PROJECT-ROOT`
- Secrets — never printed/committed/attached; env names only
- openssl/opnessl key name/value/format/structure — immutable
- Another agent's lease — never force-released or deleted
- Latest user text — outranks every older handoff/baseline

## Mutable spec (re-read before work)

| 영역 | SSOT | 라이브 확인 |
|---|---|---|
| API·모델 | `configs/api-routing.yaml`, `docs/API_ROUTING_SPEC.md` | `ollama ls`, `scripts/check-model-lock.ps1` |
| Display 런타임 | AGENTS.md `DEMO1-META-RAYBAN-DISPLAY-RUNTIME`, `main/resources/application-*.yml` | Fold 저장 설정(`lensSettings`), 실기 설정 |
| 재기동·포트 | `scripts/start_rag_stack.ps1`, `$demo1-dev-reload` | 포트 `READY`, `var/rag-launcher/*/result.json` |
| UI 카피 | served templates/assets under active sourceSets | live page fetch |
| 스킬·핸드오프 | current `.agents/skills/*/SKILL.md` body | drift → `$demo1-api-spec-drift-guard` |

## Rules

- A local doc/skill that conflicts with SSOT or live state loses; fix the
  spec/YAML or conform the code to the live contract, then demote the stale
  prose in the same task.
- Change a number in its single SSOT only; call sites stay config-driven. No
  magic numbers or stale model names in code/comments/skills.
