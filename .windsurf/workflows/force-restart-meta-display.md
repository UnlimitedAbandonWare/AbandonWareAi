# ForceRestart Meta Display (manual)

Use only when you explicitly want a live Spring/Meta Display recycle. Not for every tiny edit.

## Steps
1. Confirm workspace root is `C:\AbandonWare\demo-1\demo-1\src` (or this opened folder).
2. If Java/YAML changed: `gradlew.bat compileJava` (or let DevWatch compile).
3. Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start_rag_stack.ps1 -MetaDisplay -ForceRestart`
   (or `Start-RAG.bat` / project Start-RAG entry that ForceRestarts Meta Display profile).
4. Wait until logs show READY on `http://127.0.0.1:18180` (management often `18181`).
5. Report READY URL + log folder under `var\rag-launcher\` — do not claim success from a previous PID.
