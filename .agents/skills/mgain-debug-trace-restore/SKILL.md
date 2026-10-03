---
name: mgain-debug-trace-restore
description: Use when restoring the /chat per-answer expandable debug trace UI (mgain directive 2026-09-26). One-line rule, pinned SSOT paths, blockers, and the read-only smoke script. The product seam may be leased by task mgain-trace-restore-0926 — check before editing.
---

# mgain debug-trace restore (per-answer /chat panel)

**debug-trace restore = 기존 경로(`TraceHtmlBuilder → ChatStreamEvent.trace → POST SSE → 답변별 패널`) reconnect. 신규 엔진 금지 / WeakMap<assistantNode,panelNode> 소유권 / DOMPurify `RETURN_DOM_FRAGMENT` / 공용 parent `querySelector('[data-role="trace"]')` 금지.**

## Pinned paths

- Product root: `<repo>` (절대 Downloads·ZIP·attachments를 루트로 쓰지 않는다)
- SSOT (read-only): `%USERPROFILE%\Downloads\MGAIN_DEBUG_TRACE_RESTORE_2026-09-26\` — `goal-objective.md`(HARD 완료 조건), `mgain_debug_trace_restore_directive_2026-09-26.md`(§1–§10), `source_evidence.md`(E01–E16), `tools/read_only_renderer_probe.mjs`(**패치 전 baseline 전용**)
- Assist artifacts: `agent-prompts/mgain-debug-trace-devin-assist-20260926/` (codex-kickoff.md, t01-t16-verification-checklist.md)
- Static smoke + live anchor map: `python -B scripts/mgain_trace_smoke.py [--strict|--json|--anchors-only]`

## Completion

소스 패치 **+** T01–T16 표(명령·exit·관측) 보고. 지시서만 읽고 끝내는 것은 미완료. 기록표: `t01-t16-verification-checklist.md`.

## Guardrails (요약 — 지시서가 우선)

- 토글 ON → 스트림 요청 `debug=true` **query param**; 서버 권한은 `isAdmin` + `debug || exposeTrace`. `permitAll`/`WEB_TRACE_EXPOSE=true` 기본값/`SafeRedactor` 완화 금지. proto-open은 유지하되 `/api/diagnostics/**` ADMIN 체인은 그대로.
- prefetch/final/replay = 같은 패널 갱신; `rawTrace == null`도 B/C·상태 섹션; `turnTraces[].snapshotId`로만 상세 지연조회(`traceTurnId`/`turnId`와 혼동 금지); `/state.traceHtml` 해시 요약을 HTML로 렌더 금지.
- 레거시 ZIP 통째 이식·`script[data-trace-script]` 실행·`eval`/`new Function`·raw `innerHTML`·`git push`/`add -A` 금지. 다른 THE ONE(SelfAsk/Display reconnect)과 혼합 금지.
- Lease 선확인: `mgain-trace-restore-0926` lease가 product seam(chat.js, ChatApiController, TraceHtmlBuilder, chat-ui.html, chat-trace-ui.js/css 등 8 target)을 잡고 있을 수 있다 — `__patch_drop__/source_edit_session.ps1 -Action status -Json -TargetManifest <targets.json>`으로 targetConflict 확인 후 진행.
