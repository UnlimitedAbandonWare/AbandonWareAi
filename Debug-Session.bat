@echo off
setlocal
cd /d "%~dp0"
title AbandonWare Debug Session Watch
set "SESS_PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
"%SESS_PS%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\debug_session_watch.ps1" %*
set "SESS_EXIT=%ERRORLEVEL%"
echo.
if not "%SESS_EXIT%"=="0" echo [RESULT] session watch exit code: %SESS_EXIT% ^(0=finished/spawned/found, 2=session-already-active, 3=not-running/no-session, 4=still-finalizing, 1=failed^). See [DBG ...] output and var\debug\*-session\ dirs.
if not defined AWX_RAG_NO_PAUSE pause
exit /b %SESS_EXIT%
