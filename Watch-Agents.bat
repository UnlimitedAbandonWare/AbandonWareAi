@echo off
setlocal
cd /d "%~dp0"
title AbandonWare Agent Session Watch
set "AWX_WATCH_PY=python"
where %AWX_WATCH_PY% >nul 2>nul
if errorlevel 1 (
  echo [RESULT] python not found on PATH; run scripts\agent_session_watch.py with a Python 3 interpreter.
  exit /b 1
)
%AWX_WATCH_PY% -B "%~dp0scripts\agent_session_watch.py" %*
set "AWX_WATCH_EXIT=%ERRORLEVEL%"
echo.
if not "%AWX_WATCH_EXIT%"=="0" echo [RESULT] agent-session-watch exit code: %AWX_WATCH_EXIT% ^(0=clean, 3=warnings-only, 4=auto-findings+diagnostics, 2=usage, 1=failed^). All scans are read-only; session files are never modified.
if not defined AWX_RAG_NO_PAUSE pause
exit /b %AWX_WATCH_EXIT%
