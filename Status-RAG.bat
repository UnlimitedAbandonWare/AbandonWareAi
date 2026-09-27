@echo off
setlocal
cd /d "%~dp0"
title AbandonWare RAG Status (dev, alive-only)
set "RAG_STS_PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
"%RAG_STS_PS%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\debug_rag_stack.ps1" -Role dev -Action status %*
set "RAG_STS_EXIT=%ERRORLEVEL%"
echo.
echo [NOTE] Status is an alive-only observation, NOT freshness proof of recent edits. Post-edit proof: Verify-RAG.bat.
if not "%RAG_STS_EXIT%"=="0" echo [RESULT] RAG status exit code: %RAG_STS_EXIT% ^(0=ready, 3=not-running, 4=degraded/unproven, 1=tool error^). See [DBG ...] output above and var\debug\ logs.
if not defined AWX_RAG_NO_PAUSE pause
exit /b %RAG_STS_EXIT%
