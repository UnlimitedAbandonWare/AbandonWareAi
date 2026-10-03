---
name: demo1-agent-brief-writer
description: Use this when the user asks for a copy-paste task brief (지시서/PASTE) for Codex, Devin, Clean (Cline/Kimmi), Grok CLI or GPT Pro, or wants an existing brief rewritten, split by agent, or shortened.
---

# demo-1 Agent Brief Writer

Write briefs that another coding agent can run cold. The user pastes them into Codex, Devin, Clean (the user's name for Cline, also called 킴미/Kimmi), Grok CLI or GPT Pro. Write in Korean. Keep file paths, symbols, commands and status words in English.

## 0. Before writing
1. Pin the Project Root: `C:\AbandonWare\demo-1\demo-1\src`. Read `AGENTS.md`, `docs/PROJECT_STATUS.md` and the related `.agents/skills/*` pointers.
2. Look for existing seams first: scripts, skills, earlier briefs under `agent-prompts/`, handoff folders. The brief must extend what exists, never create a parallel stack. Cite `file:line` from the live tree, not from a ZIP snapshot.
3. Decide the recipient role (default split):
   - **Codex**: product source patches.
   - **Devin**: assist only (tools, scripts, rules, verification rails, runtime evidence). No product source unless the user says so.
   - **Clean/Kimmi**: hygiene, rails, independent verification, red-team.
   - **Grok CLI**: auxiliary rails. Rules go in `.grok/rules` as a pointer plus at most 5 lines.
   - **GPT Pro**: analysis and directive drafting from attachments.
4. If another agent is working on the same files, add an ownership table and a no-concurrent-edit rule.

## 1. Pick a size
- **S (default)**: Template A, one screen.
- **M**: `PASTE_SHORT` in chat plus the full file.
- **L**: Template B, numbered WP sections. Use for multi-WP source work.

## 2. Template A (short)
```
# <Agent> — <THE ONE 한 줄 제목>
[Contract: DEMO1-<AGENT>-<TOPIC>-<YYYYMMDD>]

## Goal
<1–2문장>
완료 = Acceptance 전부 PASS. "읽기만/플랜만" ≠ 완료. push 금지.

## Project Root
C:\AbandonWare\demo-1\demo-1\src

## Evidence
- <file:line / 명령 결과>

## Work (최소 seam)
1) …
2) …
3) Prove: …

## Must NOT
push · add -A · reset --hard/force-push · secrets 출력 · AbandonWare3 · proto-open 강화/admin harden ·
타 세션 lease/foreign staging 훼손 · 전역 clean/전체 suite · 유료 hammer · 중복 서비스/새 스택 · <작업별 금지>

## Acceptance
[ ] …
[ ] 검증 명령 + exit (안 돌린 건 NOT_RUN)
[ ] push/secrets 없음

## Report
<NAME>: DONE|PARTIAL|HOLD · FILES_TOUCHED · EVIDENCE_COMMANDS · NOT_RUN · HARD_STOP_CHECK
SSOT: agent-prompts/<agent>-<topic>-<yyyymmdd>/PASTE_<AGENT>.txt
```

## 3. Template B (long, WP)
Sections: `0) EXTEND/SUPERSEDE` (relation to earlier packets, handoff folder) → `1) Mission (한 줄)` → `2) LIVE 증거` (file:≈line list plus 해석) → `3) Project Root + 규칙` → `4) Self-Ask 5` (요청 행동? / 현재 증거? / 애매? / 바꾸면 안 되는 것? / 최소 seam?) → `5) WP0–WPn` (each: 목표, Allow/Forbid, AC checkboxes, output `WPn-*.md`) → `6) 검증/Done` → `7) Must NOT` → `8) 첫 메시지 형식` (`ACK <slug> / Root / Plan / No … / Starting WP0`).

## 4. Project defaults to include when relevant
- Prototype Light: PROTO_OPEN stays on. Admin login, wrong-account and logout-block checks are never completion criteria.
- Java 17 · LangChain4j 1.0.1 · keep existing property and env names.
- Verification: `.\gradlew.bat test --tests "<Class>" --console=plain` (focused), `Verify-RAG.bat -Json`, `node --test …`, `python -B -m unittest scripts.test_* -v`. For multi-session work, set `AWX_SPLIT_BUILD_OUTPUTS=1` plus `AWX_BUILD_HOST_ID`. Never run global clean or the full suite.
- Spend (user decision 2026-10-03, firepower first): 1) Codex credits (currently 62,500) -> 2) external paid API -> 3) free tier -> 4) local Ollama (RTX 3090) only as the last-resort fallback. The old free/local-first order is retired. Set a live API call cap per brief (about 25 for a full browser self-check), with no retries on 401/403/429.
- External API errors: root-cause them and put an "외부 API" line at the top of the report. A mock pass counts as NOT_RUN for live.
- Vibe autonomy: reversible local steps go through Self-Ask (긍정/부정/반례 → 중립 심판) and run AUTO. Irreversible steps get ASK_ONCE with one question. AUTO is the default setting for Grok Bot, agy and the other agents.
- For Codex: never let the goal title be "목표 파일 읽기". Add `[ANTI-STOP] 읽기=intake, Done은 구현+검증 증거`. If the user wants full push-through, add an OVERRIDE table (what is released vs what stays OUT) and keep the hard stops.
- For Devin skill lines: one deduplicated `@skill` line, only skills that exist under `.agents/skills`.

## 5. Deliver
1. Save the brief as UTF-8 under `agent-prompts/<agent>-<topic>-<yyyymmdd>/`. If the user wants it, also save a copy as `C:\Users\nninn\Downloads\PASTE_<AGENT>_<topic>_<date>.txt`. Writing to the user's PC needs local-tool approval.
2. **Verify each file exists and report its size.** Never report a path you have not checked.
3. Reply in chat:
   - `받는 이 | 파일 | 패킷 이름` table (when there is more than one recipient)
   - `말로: 「<one sentence the user can say to the agent>」`
   - `핵심:` 3–5 bullets, including the order when several agents are involved
   - the full brief text, or the short version if the full one is long
4. Never embed secret values (API keys, passwords) in a brief. Refer to env var names only.
5. Never post briefs to any room, channel or DM without explicit approval. Draft only.
