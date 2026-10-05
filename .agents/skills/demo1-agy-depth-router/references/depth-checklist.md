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

## Origin note

Items 5–6 port the principles of agy's internal `<deepagent_effort>` prompt
(spend the effort; a hypothesis that survived is not a verified hypothesis)
into checkable prose. The internal `enable-deepagent` flag is **not** a public
setting and is not used; this checklist is the user-visible equivalent.
