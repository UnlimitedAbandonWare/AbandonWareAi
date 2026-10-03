# H2 DDL classification memo (Kit E)

Template for a new launcher log. Do not drop tables, do not delete the H2
file, and do not treat the count as a product-test failure.

Prior write-up, one log only:
`docs/diagnostics/h2-ddl-warnings-20260928.md`.
That page says Verify-RAG reported 132 exception lines for
`var/rag-launcher/20260928-181341-16d0942f/chat-ui-vibe-listener-18180.out.log`,
and `scripts/classify_h2_ddl_warnings.py` grouped them into 66 already-exists
DDL statements. Re-run the script on the new log before quoting 132 again.

```powershell
python -B scripts/classify_h2_ddl_warnings.py --latest
```

Record: log path, exception-line count, distinct statement count, and whether
every line is an already-exists DDL warning. Separate that count from the
focused test exit code. A platform `target=partial` from DDL noise is not a
failed F0x test and not a reason to repair the database.
