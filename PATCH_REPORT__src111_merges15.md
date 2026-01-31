# PATCH REPORT — src111_merges15
- Generated: 2025-10-27T11:17:54.412608

## 1) YAML onnx 중복 병합 결과
| 파일 | 병합상태 | 병합블록수 | 하위키 중복해소 | probe/retrieval 토글 추가 |
|---|---:|---:|---:|---:|
| prompts.manifest.yaml | no-merge | 0 | 0 | yes |
| formulas.yaml | no-merge | 0 | 0 | yes |
| addons/formulas_pack/MANIFEST.yaml | no-merge | 0 | 0 | yes |
| app/resources/application-local.yml | no-merge | 0 | 0 | yes |
| app/src/main/resources/application-addons-example.yml | no-merge | 0 | 0 | yes |
| app/src/main/resources/application-diag.yml | no-merge | 0 | 0 | yes |
| app/src/main/resources/application.yml | merged | 5 | 0 | no |
| app/src/main/resources/application-features-example.yml | no-merge | 0 | 0 | yes |
| app/src/main/resources/application-gap15-stubs.yml | no-merge | 0 | 0 | yes |
| app/src/main/resources/application-prod.yml | no-merge | 0 | 0 | yes |
| app/src/main/resources/application-nova-next.yml | no-merge | 0 | 0 | yes |
| app/src/main/resources/application-machine.yml | merged | 2 | 0 | no |
| app/src/main/resources/application-hypernova.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/application-local.yml | no-merge | 0 | 0 | yes |
| app/src/main/resources/configs/models.manifest.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/flows/kakao_ask.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/planner/plans/brave.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/planner/plans/safe.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/brave.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/safe_autorun.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/recency_first.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/kg_first.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/rulebreak.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/ap11_finance_special.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/ap1_auth_web.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/ap3_vec_dense.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/ap9_cost_saver.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/brave.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/safe_autorun.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/zero_break.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/hyper_nova.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/zerobreak.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/default.v1.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/plans/hypernova.v2.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/prompts/prompts.manifest.yaml | no-merge | 0 | 0 | yes |
| app/src/main/resources/domain-profiles/news.yml | no-merge | 0 | 0 | yes |
| cfvm-raw/src/main/resources/cfvm_errorbreak_presets.yaml | no-merge | 0 | 0 | yes |
| cfvm-raw/src/main/resources/application-local.yml | no-merge | 0 | 0 | yes |
| config/application-example.yml | merged | 2 | 0 | yes |
| config/application-zerobreak.yml | merged | 2 | 0 | yes |
| config/sample-merge156.yml | no-merge | 0 | 0 | yes |
| config/application-sa16-patch.yaml | no-merge | 0 | 0 | yes |
| configs/application-balanced.yml | no-merge | 0 | 0 | yes |
| configs/application-news.yml | no-merge | 0 | 0 | yes |
| configs/application-rag.yml | no-merge | 0 | 0 | yes |
| configs/models.manifest.yaml | no-merge | 0 | 0 | yes |
| configs/prometheus/agent-alerting-rules.yml | no-merge | 0 | 0 | yes |
| configs/prometheus/agent-recording-rules.yml | no-merge | 0 | 0 | yes |
| demo-1/src/main/resources/application-local.yml | no-merge | 0 | 0 | yes |
| docs/kakao_ask.yaml | no-merge | 0 | 0 | yes |
| extras/gap15-stubs_v1/src/main/resources/application-stubs.yml | no-merge | 0 | 0 | yes |
| extras/gap15-stubs_v1/src/main/resources/application-local.yml | no-merge | 0 | 0 | yes |
| ops/prometheus/rules.yml | no-merge | 0 | 0 | yes |
| ops/zerobreak/plans/brave.v1.yaml | no-merge | 0 | 0 | yes |
| ops/zerobreak/plans/safe_autorun.v1.yaml | no-merge | 0 | 0 | yes |
| ops/zerobreak/plans/zero_break.v1.yaml | no-merge | 0 | 0 | yes |
| ops/zerobreak/policies/rulebreak.policies.yaml | no-merge | 0 | 0 | yes |
| ops/hypernova/policies/hypernova.policies.yaml | no-merge | 0 | 0 | yes |
| plans/recency_first.v1.yaml | no-merge | 0 | 0 | yes |
| plans/kg_first.v1.yaml | no-merge | 0 | 0 | yes |
| plans/safe_autorun.v1.yaml | no-merge | 0 | 0 | yes |
| plans/brave.v1.yaml | no-merge | 0 | 0 | yes |
| plans/zero_break.v1.yaml | no-merge | 0 | 0 | yes |
| plans/hypernova.v1.yaml | no-merge | 0 | 0 | yes |
| prompts/prompts.manifest.yaml | no-merge | 0 | 0 | yes |
| prompts/agents/resume_llm_mcp/meta.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/application-dev.yml | merged | 2 | 0 | yes |
| src/main/resources/application-example.yml | merged | 2 | 0 | yes |
| src/main/resources/application-local.yml | no-merge | 0 | 0 | yes |
| src/main/resources/application-secrets.yml | no-merge | 0 | 0 | yes |
| src/main/resources/orgs.yml | no-merge | 0 | 0 | yes |
| src/main/resources/matrix_policy.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/application-recency30.yml | no-merge | 0 | 0 | yes |
| src/main/resources/application-prod.yml | merged | 2 | 0 | yes |
| src/main/resources/application-features-example.yml | no-merge | 0 | 0 | yes |
| src/main/resources/application-merge16.yml | merged | 2 | 0 | yes |
| src/main/resources/application-patch.yml | merged | 2 | 0 | yes |
| src/main/resources/application-nova-next.yml | no-merge | 0 | 0 | yes |
| src/main/resources/application.disabled.yml | merged | 2 | 0 | no |
| src/main/resources/application.yml | merged | 2 | 0 | yes |
| src/main/resources/agent_scaffold/prompts.manifest.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/agent_scaffold/agents/resume_llm_mcp/meta.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/app/resources/application-local.yml | no-merge | 0 | 0 | yes |
| src/main/resources/artplate/ap1_auth_web.yml | no-merge | 0 | 0 | yes |
| src/main/resources/artplate/ap3_vec_dense.yml | no-merge | 0 | 0 | yes |
| src/main/resources/artplate/ap9_cost_saver.yml | no-merge | 0 | 0 | yes |
| src/main/resources/catalog/concepts.yml | no-merge | 0 | 0 | yes |
| src/main/resources/catalog/orgs.yml | no-merge | 0 | 0 | yes |
| src/main/resources/configs/models.manifest.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/plans/brave.v1.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/plans/safe_autorun.v1.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/plans/safe_autorun.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/plans/brave.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/plans/recency_first.v1.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/plans/kg_first.v1.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/plans/rulebreak.v1.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/plans/ap11_finance_special.v1.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/plans/ap1_auth_web.v1.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/plans/ap3_vec_dense.v1.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/plans/ap9_cost_saver.v1.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/plans/zero_break.v1.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/plans/safe.v1.yaml | no-merge | 0 | 0 | yes |
| src/main/resources/plans/hyper_nova.v1.yaml | no-merge | 0 | 0 | yes |
| src/test/resources/application.disabled.yml | merged | 2 | 0 | yes |
| src/test/resources/application-local.yml | no-merge | 0 | 0 | yes |
| src/test/resources/plans/safe_autorun.v1.yaml | no-merge | 0 | 0 | yes |
| src/test/resources/plans/brave.v1.yaml | no-merge | 0 | 0 | yes |
| src/test/resources/plans/zero_break.v1.yaml | no-merge | 0 | 0 | yes |
| tools/build_matrix.yaml | no-merge | 0 | 0 | yes |
| agent-prompts/prompts.manifest.yaml | no-merge | 0 | 0 | yes |
| agent-prompts/agents/resume_llm_mcp/meta.yaml | no-merge | 0 | 0 | yes |
| agent_scaffold/agents/resume_llm_mcp/meta.yaml | no-merge | 0 | 0 | yes |

## 2) Java 컴파일 오류 패치 (DppDiversityReranker)
- 위치: `src/main/java/service/rag/rerank/DppDiversityReranker.java` / 기존파일존재: True
- 조치: 제네릭 경계 인터페이스 `HasVectorAndScore<T>` 도입, 오버로드 추가, cosine 오버로드 정리

## 3) 저장된 빌드 오류 패턴 스캔(레포 내부)
| 패턴 | 발견 파일수 | 예시 |
|---|---:|---|
| DuplicateKeyException | 11 | BUILD_FIX_NOTES.md — `## 2025-10-09 05:28:45 — Fix: SnakeYAML DuplicateKeyException (application.yml)` |
| cannot find symbol | 39 | BUILD_FIX_NOTES.md — `- These address the cascading 'cannot find symbol get*/set*' and 'log' errors across shared sources compiled by `lms-core`.` |
| getVector\(\) | 10 | app/src/main/java/com/abandonware/ai/addons/complexity/ComplexityGatingCoordinator.java — `return new RetrievalHints(props.getWeb().getTopKDefault(), props.getVector().getTopKDefault(),` |
| getScoreFrom | 2 | app/src/main/java/service/rag/rerank/DppDiversityReranker.java — `* Requires Object (with getScoreFrom(in.get(i)), getVector()) in classpath.` |
| non-zero exit value | 1 | build-logs/error_patterns_detail.json — `"* What went wrong:\nExecution failed for task ':bootRun'.\n> Process 'command 'C:\\jdk\\jdk-17.0.13\\bin\\java.exe'' finished with non-zero exit value 1"` |
| Execution failed for task | 2 | build-logs/error_patterns_detail.json — `"* What went wrong:\nExecution failed for task ':bootRun'.\n> Process 'command 'C:\\jdk\\jdk-17.0.13\\bin\\java.exe'' finished with non-zero exit value 1"` |