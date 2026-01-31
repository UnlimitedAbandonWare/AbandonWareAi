# GPT‑Pro Agent 정리/정비 지시서 (요약)

- 에이전트 system 프롬프트와 trait 프롬프트를 분리 보관하고 prompts.manifest.yaml로 바인딩합니다.
- 표준 스키마:
```
agent_scaffold/
  agents/<agent-id>/{system.md, meta.yaml}
  traits/<trait-id>.md
  prompts.manifest.yaml
  build.py
```
- 병합 순서: trait → system (trait 우선)
- 품질 루프: Pass0–Pass4 (요구-응답 매핑, 후보 생성, 점수화, 합성, 검증)
- 결과물: out/<agent-id>.prompt