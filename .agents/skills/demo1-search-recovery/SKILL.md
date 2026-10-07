---
name: demo1-search-recovery
description: Use when demo-1 main chat search errors recur after a correction, a follow-up search fails, or search cancellation leaves recovery unproved; Korean triggers include 검색 오류, 교정 반복, 후속 검색 실패.
---

# Demo1 Search Recovery

Resolve one demonstrated break in a search correction lifecycle. Counts and completion labels are observations, not proof of useful evidence or recovery.

Reuse the five-slot first-two-turn intake and acceptance contract in [demo1-evidence-debugging](../demo1-evidence-debugging/SKILL.md) and its [case contract](../demo1-evidence-debugging/references/case-contract.md). Do not copy those contracts, replace SelfAsk task packets, or add a global orchestrator. This skill supplies search-specific classification and recovery checks.

1. **Bind the case.** Record surface, owner/session, request/turn IDs, question hash, intended subject/relation, model/version and checked-at time. Separate current source, installed hash/provenance and actual runtime from report date. Read bounded authorized journals/handoffs; an older report is evidence of its own run. No invocation log means skill usage/frequency is unknown.
2. **Locate one break.** Use [the lifecycle table](references/lifecycle-contract.md) to distinguish provider execution, admission, alias, timeout, empty, filter, fetch, mainbody, packing, dispatch, citation and cancel recovery. Select the earliest demonstrated discontinuity in one correlated request, or one distinguishing probe at the earliest unknown boundary. Unknown is not a defect diagnosis. Competing lanes retain separate status.
3. **Test the hypothesis.** Select SAFE for bounded ordinary diagnosis, NEEDLE for one discriminating observation, BRAVE only for an evidence-justified alternate observation under existing permissions/budgets, and TRIAD only for unresolved competing causes using the existing triad. These are work choices inspired by old UAW, not product mode activation. Never invent probabilities or external-model/MCP authority.
4. **RED, minimal fix, regression.** Reproduce the missing contract before changing its confirmed owner through existing lease/preimage guards. Fixture errors and mocks are not product RED. On recurring failure change the observation target; do not repeat blind searches or hide empty evidence with a stronger paid model. Preserve domain contracts, providers, official-source policy, citation minimums and prompt boundaries unless causal evidence justifies a change. Never hardcode the question's answer.
5. **Complete only the exercised boundary.** Replay the unchanged first question A and immediate B in the same owner/session; check useful semantics, B's referent and supporting body through actual dispatch and citation. For cancellation, also run a new same-session search after Stop and verify resource release, no stale/late publication and its complete evidence/answer path. Check the currently installed artifact and actual serving runtime. Missing live checks remain NOT_RUN/NOT_PROVEN.

Keep model reception unproved when its receipt is missing; do not call the trace NOT_OBSERVED unless trace observation itself is missing. Prior-conversation contamination is a hypothesis; provider disabled requires current stage state/reason. URL/count/HTTP200/exit0, metadata completion, simulated PASS and test totals prove neither search validity nor deployment.

Report static, offline behavior and runtime results separately, with current file hashes and provenance. Repo registration and fresh-context use do not prove automatic invocation. Stop at the verified goal; this skill grants no product patch, restart, kill, commit, paid API or package-install authority.
