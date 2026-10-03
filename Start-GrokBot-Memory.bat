@echo off
REM Start-GrokBot-Memory.bat — one-click GrokBot memory warm-start.
REM   1) incremental sync of GrokBot sessions -> sessions_index.json / rule / doc
REM   2) build the <=2500-char 3-tier primer and copy it to the clipboard
REM Paste the clipboard at the top of a new GrokBot session.
setlocal
cd /d "%~dp0"

echo [GrokBot-Memory] 1/2 incremental memory sync...
python -B scripts\grok_to_agy_memory_bridge.py sync --incremental
if errorlevel 1 echo [GrokBot-Memory] WARN: sync failed; primer will use the last good index.

echo [GrokBot-Memory] 2/2 building primer ^(clipboard copy on^)...
python -B scripts\grokbot_memory_primer.py --format markdown --copy

endlocal
