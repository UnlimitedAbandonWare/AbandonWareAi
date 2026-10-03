---
trigger: always_on
---
# Devin I/O pitfalls

- Write `data\agent-handoff` files with PowerShell `[IO.File]::WriteAllText` and a UTF-8 no-BOM encoding. The editor write tool skips paths that `.gitignore` matches.
- Re-read `agents.md` immediately before an edit. Replace an exact current snippet and keep that file's CRLF and encoding.
- Edit a file only after you confirm no other agent is saving it. If the editor says the file changed, re-read and stop that edit.
- Do not print secrets. A refused write is not an empty file.
