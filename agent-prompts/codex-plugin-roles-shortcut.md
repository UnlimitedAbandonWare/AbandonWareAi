# Codex plugin roles — one-line paste

긴 플러그인 역할 문단을 매번 붙이는 대신, 아래 한 줄만 Codex에 붙인다.

```text
@demo1-codex-plugin-roles 이번 작업에서 플러그인 역할 제한해서 써. 플러그인은 전부 쓰지 마.
```

- SSOT(작업 타입별 허용/제한 매트릭스 + 플러그인별 do/don't):
  `.agents/skills/demo1-codex-plugin-roles/SKILL.md`
- `AGENTS.md`의 `DEMO1-CODEX-PLUGIN-ROLES` 블록이 항상 그 스킬을 가리킨다.
- 도구 카탈로그 자체는 `EXTERNAL_SKILLS.md`가 소유한다. 이 파일은 호출 문구만 유지한다.
