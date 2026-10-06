# depth self-review checklist (L2 once, L3 per round)

Run against the draft before delivering. Every NO is a fix, not a comment.

1. Every file:line / number / command claim was re-checked in this session —
   or is explicitly marked `미확인` with the command that would check it.
2. Protected paths (AGENTS.md, GEMINI.md, %USERPROFILE%\.gemini writes,
   credentials, other sessions' leases) are untouched.
3. Acceptance criteria are measurable (exit codes, counts, file:line) —
   "works" / "improved" without a check is rewritten.
4. No secret value, env var value, or token text appears in output (names only).
5. Alternatives that were excluded are listed with the rejection reason —
   excluding alternatives does NOT prove the remaining hypothesis.
6. One more verification pass was done on the riskiest claim; "likely"/"아마"
   appears zero times outside the 미확인 list.

## Skill authoring (addendum)

- 스킬 생성·개편 지시서/작업은 **≥L2**로 판정한다 (blast cost ≥1: 다른
  에이전트가 그 산출물을 그대로 쓴다).
- 키워드가 "앞으로 방식 / 관문 / ratchet / 게이트"이면 L3로 올리고,
  산출물은 `demo1-agy-skill-pack`의 **L 팩**을 요구한다
  (`docs/agents-rules/DEMO1-AGY-SKILL-PACK.md`).

## Origin note

Items 5–6 port the principles of agy's internal `<deepagent_effort>` prompt
(spend the effort; a hypothesis that survived is not a verified hypothesis)
into checkable prose. The internal `enable-deepagent` flag is **not** a public
setting and is not used; this checklist is the user-visible equivalent.
