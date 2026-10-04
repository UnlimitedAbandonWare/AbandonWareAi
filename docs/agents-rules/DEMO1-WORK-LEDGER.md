<!-- moved-from: AGENTS.md L53-L57 sha256=85cea99faa792967195e2381a31a8924f99b6f365fa2cfea1221a9a0ee26cdf4 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-WORK-LEDGER -->
## Work Ledger: status, journal, per-change backup (Git-free)
- Principle: **start = read status + register scope; before each change = preserve current bytes; after = record change + real verification.** Detail SSOT: `.agents/skills/demo1-work-ledger/SKILL.md` (`$demo1-work-ledger`); applies to file-changing work only.
- Status doc: `docs/PROJECT_STATUS.md` (only overall-status entry point). Journal: `work_journal.py open|note|close`; preimage/recover: `codex_work_checkpoint.py` (failed `begin` = no change starts); status rows: `status_doc.py --expect-sha256`. Unrecorded external change = `미기록 외부 변경`: stop overwrite/restore on that file, never invent author/reason; journal `in_progress` means 진행 여부 미확인, not done.
<!-- END DEMO1-WORK-LEDGER -->
