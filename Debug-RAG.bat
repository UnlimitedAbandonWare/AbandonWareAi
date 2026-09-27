@echo off
setlocal
cd /d "%~dp0"
title AbandonWare RAG Debug (dev)
set "RAG_DBG_PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
"%RAG_DBG_PS%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\debug_rag_stack.ps1" -Role dev %*
set "RAG_DBG_EXIT=%ERRORLEVEL%"
echo.
if not "%RAG_DBG_EXIT%"=="0" echo [RESULT] RAG debug exit code: %RAG_DBG_EXIT% ^(0=ready/verified, 2=blocked, 3=not-running, 4=degraded, 5=verbose-not-applied, 6=verify-checks-failed, 1=failed^). See [DBG ...] output above and var\debug\ logs.
if not defined AWX_RAG_NO_PAUSE pause
exit /b %RAG_DBG_EXIT%
