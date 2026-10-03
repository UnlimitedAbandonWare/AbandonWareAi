<!-- moved-from: AGENTS.md L432-L437 sha256=2b47a9be676568242ab19f4f9fe374102b96808060971b6f469e7611caf96797 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-VIBE-SELFASK-JUDGE-AUTO -->
## Vibe Self-Ask judge (approval-quiz reduction)
- 사용자에게 승인 퀴즈(1/2/3)를 던지기 **전에** `$demo1-vibe-selfask-judge-auto`(`.agents/skills/demo1-vibe-selfask-judge-auto/SKILL.md`): POSITIVE → NEGATIVE → COUNTEREXAMPLE → NEUTRAL JUDGE가 정확히 하나를 출력 — `AUTO`(가역·로컬·근거 있음, 묻지 않고 진행) / `ASK_ONCE`(불가역·비용·정책 소유, 질문 1개만) / `HOLD`(blocker+재개 조건).
- 판정 후 journal에 `SELFASK_JUDGE AUTO|ASK_ONCE|HOLD | reason | paths` 한 줄 기록. AUTO도 기록해 재질문을 막는다.
- 이 루프는 hard constraint(lease·secret·git remote 금지·flag)를 약화하지 않는다 — commit/push·운영DB·secret 출력·foreign lease는 여전히 ASK/STOP.
<!-- END DEMO1-VIBE-SELFASK-JUDGE-AUTO -->
