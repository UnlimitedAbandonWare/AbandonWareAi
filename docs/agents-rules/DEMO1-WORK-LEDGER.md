<!-- moved-from: AGENTS.md L53-L57 sha256=85cea99faa792967195e2381a31a8924f99b6f365fa2cfea1221a9a0ee26cdf4 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-WORK-LEDGER -->
## Work Ledger: status, journal, per-change backup (Git-free)
- Principle: **start = read status + register scope; before each change = preserve current bytes; after = record change + real verification.** Detail SSOT: `.agents/skills/demo1-work-ledger/SKILL.md` (`$demo1-work-ledger`); applies to file-changing work only.
- Status doc: `docs/PROJECT_STATUS.md` (only overall-status entry point). Journal: `work_journal.py open|note|close`; preimage/recover: `codex_work_checkpoint.py` (failed `begin` = no change starts); status rows: `status_doc.py --expect-sha256`. Unrecorded external change = `미기록 외부 변경`: stop overwrite/restore on that file, never invent author/reason; journal `in_progress` means 진행 여부 미확인, not done.
- Goal-bound verification: test counts alone do not prove feature coverage. When editing an existing control, cover its presence/accessibility and ON/OFF/unset roundtrip across reload/session/owner boundaries in the existing test seam; unexercised behavior stays `NOT_PROVEN`. Record source, served build, and runtime evidence separately.
- Reusing verification: run `common_verifier.py adjudicate` against the current declared source/test hashes; a changed or missing bound file invalidates old PASS, and a stored blocking verdict cannot be promoted without a fresh verification. Keep historical receipts unchanged.
<!-- END DEMO1-WORK-LEDGER -->
