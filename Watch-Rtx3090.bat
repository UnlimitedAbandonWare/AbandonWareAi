@echo off
setlocal
cd /d "%~dp0"
title AbandonWare RTX 3090 Health Watch
rem Read-only monitor: nvidia-smi + Ollama 11434/11435 + System event log.
rem Never changes power limit / clocks / PSU; never blocks GPU load.
rem Outputs: var\debug\rtx3090-watch\latest.json (+ alert-*.json, data\agent-handoff\rtx3090-watch\LATEST.md on anomaly)
rem Single run:  Watch-Rtx3090.bat
rem Loop mode:   Watch-Rtx3090.bat -IntervalSeconds 120
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\rtx3090_health_watch.ps1" %*
set "AWX_WATCH_EXIT=%ERRORLEVEL%"
echo.
if not "%AWX_WATCH_EXIT%"=="0" echo [RESULT] rtx3090-watch exit code: %AWX_WATCH_EXIT% ^(0=clean, 3=anomaly observed - see var\debug\rtx3090-watch + data\agent-handoff\rtx3090-watch\LATEST.md, 1=script error, 2=usage^). All probes are read-only.
if not defined AWX_RAG_NO_PAUSE pause
exit /b %AWX_WATCH_EXIT%
