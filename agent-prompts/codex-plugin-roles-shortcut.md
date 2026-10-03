# Codex plugin roles — emphasis shortcut (paste no longer required)

`$demo1-codex-plugin-roles` v2는 모든 Codex 작업에 자동 적용된다(AGENTS.md
`DEMO1-CODEX-PLUGIN-ROLES` + `agents/openai.yaml` `default_prompt`). 매번
붙여넣을 필요는 없고, 어느 작업에서든 이 정책을 특별히 강조하고 싶을 때만
아래 한 줄을 붙인다.

```text
@demo1-codex-plugin-roles 이번 작업에서 플러그인 역할 제한해서 써. 플러그인은 전부 쓰지 마.
```

- SSOT(작업 타입별 허용/제한 매트릭스 + 플러그인별 계약 + 보고 블록 규격):
  `.agents/skills/demo1-codex-plugin-roles/SKILL.md` (v2), 사용자 원문 보존:
  `.agents/skills/demo1-codex-plugin-roles/references/user-policy-20261002.md`
- 최종 보고서에는 `외부 API:` 줄 + `PLUGIN_USAGE:` 블록 필수 — 검사:
  `python -B scripts/codex_plugin_usage_lint.py --report <보고서>`
  (footer: `docs/operations/codex-plugin-roles-footer.txt`)
- 도구 카탈로그 자체는 `EXTERNAL_SKILLS.md`가 소유한다. 이 파일은 강조용
  호출 문구만 유지한다.
