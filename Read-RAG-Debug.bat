@echo off
setlocal
cd /d "%~dp0"
title AbandonWare RAG Debug Trail (read-only; -AiAssist optional)
set "RAG_TRAIL_PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
"%RAG_TRAIL_PS%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\read_rag_debug_trail.ps1" %*
set "RAG_TRAIL_EXIT=%ERRORLEVEL%"
echo.
if not "%RAG_TRAIL_EXIT%"=="0" echo [RESULT] RAG debug-trail exit code: %RAG_TRAIL_EXIT% ^(0=ready, 3=not-running/no-LATEST, 4=degraded/failure, 1=tool error^). SSOT: var\rag-launcher\LATEST.json
if not defined AWX_RAG_NO_PAUSE pause
exit /b %RAG_TRAIL_EXIT%
