# CODEX HANDOFF

```
DEMO1-AUTOGRADE-B-ASSIST-GROK-20260928-R1 → Codex
parent: DEMO1-AUTOGRADE-B-TARGETS-20260928-R1
product_src_diff: 0
B00_rail: scripts/autograde_b_rail.py
  B00 RuleBreak=present; imports=present; importsRuleBreak=absent; componentScan=present; zipSha=match; action=NO_CHANGE_VERIFIED
remap_table: docs/diagnostics/autograde-b-assist-20260928/remap.md
recommended_pick: B02 (symptom=search failure collapsed into ok + empty results)
repro: docs/diagnostics/autograde-b-assist-20260928/b02_repro.md
focused_cmd: .\gradlew.bat test --tests com.abandonware.ai.agent.tool.AgentWebSearchToolConditionalWiringTest
  Grok gradleProof=not-run
  Grok rail proof: python -B scripts/test_autograde_b_rail.py → 6 tests OK, exit 0
log_keys: web.search.tool.status, zeroResults, returnedCount, failReason, failMsgHash, skipped.reason, queryHash, executionStatus
rules_skills_added: .grok/rules/autograde-b-assist-20260928.md
  packet: docs/diagnostics/autograde-b-assist-20260928/
NOT_FOR_CODEX: A* editable, global HOLD, RuleBreak re-register, maiaswsn F01-F08 mixed into this patch
conflicts_ATT_CTX_NW: none for the B02 file. ChatWorkflow stays out of this pick (P14 live definition line 7720, file SHA STALE).
blocker_for_codex: none from Grok. Own journal autograde-b02-0928-74c07d0e already lists WebSearchTool.java, src/test/java/com/abandonware/ai/agent/tool/impl, docs/PROJECT_STATUS.md. Grok did not take those files and did not write a PROJECT_STATUS row.
HEAD: not committed
```

B00은 문서 STALE이 아니다. P01/P02 ZIP SHA가 live와 같다. 재등록 불필요.

B02에서 P06 ZIP SHA도 live와 같다. `execute` 44–85행 힌트는 이 파일에 한해 아직 맞다. 고칠 경계는 예외 경로의 응답 본문이 `results=[]`만 싣는 지점과, 그 본문만 읽는 소비자다. trace에는 이미 `FAIL_SOFT`가 있다.

기존 `webSearchToolFailsSoftAndLeavesReasonOnException`은 그 빈 본문을 잠그고 있다. RED는 그 단언을 지우지 않은 채 본문 구분 필드를 요구하는 새 단언이다. 위치는 Codex 범위 안의 테스트다. 인접 파일 `AgentWebSearchToolConditionalWiringTest.java`는 선언된 `impl` 디렉터리 밖이다.

A03/A04 단위 재현 GREEN을 앱 Done으로 올리지 않는다.
