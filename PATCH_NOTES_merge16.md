
merge16 (generated 2025-10-11T03:02:17.056341Z)

- Added orchestrator scaffolding under src/main/java/com/example/lms/service/rag/orchestrator/.
- Introduced PR checklist template at .github/pull_request_template.md.
- Added application-merge16.yml with toggles and sane defaults.
- Added OpenAPI draft at src/main/resources/openapi/merge16.openapi.json.
- Smoke test at src/test/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestratorSmokeTest.java.

This change is additive and safe to cherry-pick. Build-system specific annotations (@RestController, etc.) can be added without affecting the orchestrator core.
