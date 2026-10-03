# plan5_core_truth_verify 판정 명령 기록 (DV6)

`--allow-lost-hunk <path>:<header>`는 반복 가능 additive 옵션이다. 허용 목록과 (path, header)가 정확히 일치하는 lost hunk만 `intendedReplacements`로 옮겨 판정에서 제외된다. 옵션 없이는 기존과 동일하게 NOT_READY다.

## 직전 코덱스 결과 재판정 (실행됨, exit 0)

```powershell
python -B scripts\plan5_core_truth_verify.py --root . `
  --xml-dir "data/agent-handoff/codex-autonomy/codex-plan5-core-truth-e63ed263/verify/final-focused2" `
  --snapshot "data/agent-handoff/devin-plan5-assist-685f4d65/foreign-snapshot.json" `
  --allow-lost-hunk "main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java:@@ -2413,0 +2898 @@" `
  --allow-lost-hunk "main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java:@@ -2478,0 +3088,15 @@"
```

- 결과: `READY_FOR_REVIEW` (exit 0). 산출 `data/agent-handoff/devin-chat-timeout-assist-44f7a8c5/dv6-rejudge.json`.
- 대조: 동일 입력에서 옵션 제거 시 `NOT_READY` (exit 3), 사유 `foreign-hunk-lost` 2건 — 기본 동작 불변 확인.

## 이번 작업용 판정 명령 (대상 3파일 스냅샷 + failover XML 클래스)

```powershell
python -B scripts\junit_owned_summary.py --xml-dir "verify\runtime-failover-observation" --class ai.abandonware.nova.orch.aop.LlmRouterRuntimeDeviceFailoverTest --json ; python -B scripts\foreign_hunk_preserve_check.py --root . check --snapshot "data/agent-handoff/devin-chat-timeout-assist-44f7a8c5/foreign-snapshot.json" --json
```

- junit: failover 클래스의 tests/failures/failedMethods 집계(XML만, Gradle 미실행).
- foreign-hunk check: 코덱스 대상 3파일(`FallbackAwareChatModel`, `OllamaNativeChatModel`, `LlmRouterAspect`)의 DV1 스냅샷 대비 lost hunk — 코덱스 수정 뒤 재실행하면 의도된 교체는 `--allow-lost-hunk` 동등 방식으로 허용한다.
