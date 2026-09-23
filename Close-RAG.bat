@echo off
setlocal
cd /d "%~dp0"
title AbandonWare Meta Display RAG Stopper
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop_rag_stack.ps1" -MetaDisplay %*
set "RAG_EXIT=%ERRORLEVEL%"
echo.
if not "%RAG_EXIT%"=="0" echo [FAILED] Stop exit code: %RAG_EXIT%. See the stage and log path above.
if not defined AWX_RAG_NO_PAUSE pause
exit /b %RAG_EXIT%
