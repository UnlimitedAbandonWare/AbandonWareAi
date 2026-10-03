@echo off
setlocal
cd /d "%~dp0"
title AbandonWare RAG Status (dev, alive-only)
rem Headless/agent mode: AWX_RAG_JSON=1, AWX_AGENT or CI appends -JsonStdout so
rem the PowerShell stage emits exactly one JSON document on stdout, and pause is
rem skipped. AWX_RAG_NO_PAUSE skips pause alone; a "cmd /c" launcher
rem (non-interactive agent shell) is also treated as headless.
set "RAG_JSON_ARG="
if defined AWX_RAG_JSON set "RAG_JSON_ARG=-JsonStdout"
if defined AWX_AGENT set "RAG_JSON_ARG=-JsonStdout"
if defined CI set "RAG_JSON_ARG=-JsonStdout"
set "RAG_PAUSE=1"
if defined AWX_RAG_NO_PAUSE set "RAG_PAUSE="
if defined RAG_JSON_ARG set "RAG_PAUSE="
if defined RAG_PAUSE echo %cmdcmdline% | findstr /i /c:"/c " >nul && set "RAG_PAUSE="
set "RAG_STS_PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
"%RAG_STS_PS%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\debug_rag_stack.ps1" -Role dev -Action status %RAG_JSON_ARG% %*
set "RAG_STS_EXIT=%ERRORLEVEL%"
if defined RAG_JSON_ARG (
  echo [NOTE] Status is an alive-only observation, NOT freshness proof of recent edits. Post-edit proof: Verify-RAG.bat. 1>&2
  if not "%RAG_STS_EXIT%"=="0" echo [RESULT] RAG status exit code: %RAG_STS_EXIT% ^(0=ready, 3=not-running, 4=degraded/unproven, 1=tool error^). See var\debug\ logs. 1>&2
) else (
  echo.
  echo [NOTE] Status is an alive-only observation, NOT freshness proof of recent edits. Post-edit proof: Verify-RAG.bat.
  if not "%RAG_STS_EXIT%"=="0" echo [RESULT] RAG status exit code: %RAG_STS_EXIT% ^(0=ready, 3=not-running, 4=degraded/unproven, 1=tool error^). See [DBG ...] output above and var\debug\ logs.
)
if defined RAG_PAUSE pause
exit /b %RAG_STS_EXIT%
