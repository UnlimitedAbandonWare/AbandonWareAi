---
name: judge-recovery
description: Diagnose and recover failures in the Spring Boot agent orchestrator
license: project-internal
metadata:
  version: "1.0.0"
  author: "abandonware-judge"
  last-updated: "2026-05-16"
  spec: "AGENT/60_ANTIGRAVITY_JUDGE_NODE_SPEC.md"
allowed-tools: read_file edit_file run_tests
---

## When to use this skill

* CriticNode 의 5 점검 (evidence / citations / schema / budget / policy) 중 하나라도 실패한 경우.
* Orchestrator TOOL 단계가 `Step.Retry.maxAttempts` 모두 소진하고도 예외를 던진 경우.
* SSE 스트림에 `agent.recovery.*` 이벤트가 관측되지만 후속 라운드가 진행되지 않는 경우.
* 신규 plan(YAML) 추가 시 `recovery-policy.yaml` 의 `degrade-map` 을 동시에 갱신해야 할 때.

## Core Directives

* Critic 은 순수 함수다. 본 스킬을 적용해 LLM 호출을 추가하지 말 것.
* 회복 라운드는 최대 `recovery.max-rounds` 까지만. 무한 루프 금지.
* POLICY 분류는 자동 우회 금지. 항상 ESCALATE.
* `application*.properties|yml` 의 기존 키 변경 금지. 새 키만 추가.
* 새 ThreadLocal 도입 금지. `TraceContext.flags` 를 재사용.

## Execution Procedure

1. 트리거 식별: `state.get("verdict")` 또는 step 예외에서 Verdict 생성.
2. 분류: `verdict.failureClass()` 를 `recovery-policy.yaml` 의 `map` 으로 lookup.
3. 라운드 카운트 검사: `state["recovery.round"] >= recovery.max-rounds` → 강제 ESCALATE.
4. 액션 실행: `RecoveryExecutor.apply(action, verdict, state, context)`.
5. 기록: `TraceContext.current().pushRecoveryRound(round)` 1 회 호출.
6. 검증: `RecoveryRoundTest` 의 4 시나리오로 회귀 확인.
7. SSE 발행: `agent.recovery.<action>` 이벤트가 1 라운드당 정확히 1 회 발행되는지 확인.

## Expected Output Format

회복 실행 후 state 에 다음 키가 갱신/존재해야 한다:

```
state["recovery.round"]    : Integer (1..max-rounds)
state["fallback.message"]  : String (FALLBACK 일 때만)
state["verdict"]           : Verdict (가장 최근 판정)
TraceContext.flags["recovery.rounds"]    : List<RecoveryRound>
TraceContext.flags["recovery.escalated"] : Boolean (true 면 종결)
```

ABORT 또는 ESCALATE 가 발행된 경우 `synth` 단계는 안전 요약 응답 모드로 진입한다.

## References

* 풀 스펙: `AGENT/60_ANTIGRAVITY_JUDGE_NODE_SPEC.md`
* Critic 본체: `app/src/main/java/com/abandonware/ai/agent/orchestrator/nodes/CriticNode.java`
* Orchestrator 본체: `app/src/main/java/com/abandonware/ai/agent/orchestrator/Orchestrator.java`
* TraceContext: `app/src/main/java/com/example/lms/trace/TraceContext.java`
* 회복 정책: `app/src/main/resources/policies/recovery-policy.yaml`
