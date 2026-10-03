# Scripts supporting `--json-out` (inventory, 2026-10-03)

Prefer `--json-out <file>` + read the file over capturing JSON in tool
output. Source: `rg -l 'json-out|json_out' scripts -g '*.py'`. Test files
that merely exercise the flag are omitted.

- `scripts/agent_access_bundle.py`
- `scripts/agent_recovery_status.py`
- `scripts/broad_catch_classifier.py`
- `scripts/classify_h2_ddl_warnings.py`
- `scripts/codex_config_consumer_map.py`
- `scripts/codex_session_friction.py`
- `scripts/db_migration_ledger.py`
- `scripts/ddl_auto_noise_classify.py`
- `scripts/demo1_skill_quality_audit.py`
- `scripts/f01b_admission_key_demo.py`
- `scripts/f01b_evidence_pack.py`
- `scripts/f01b_focused_verify.py`
- `scripts/f01b_job_receipt_read.py`
- `scripts/f01b_schema_gate.py`
- `scripts/f01b_tm_probe.py`
- `scripts/job_receipt_inspect.py`
- `scripts/json_safe_run.py`
- `scripts/trace_dock_a11y_scan.py`
- `scripts/trace_dock_cost_guard.py`

Wrapper for everything else (any command → file + one-line summary):
`python -B scripts/json_safe_run.py --json-out <f> --require-json -- <cmd>`
