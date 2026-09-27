@echo off
setlocal
cd /d "%~dp0"
title AbandonWare Agent Scope Lease
set "AWX_SCOPE_PY=python"
where %AWX_SCOPE_PY% >nul 2>nul
if errorlevel 1 (
  echo [RESULT] python not found on PATH; run scripts\agent_scope_lease.py with a Python 3 interpreter.
  exit /b 1
)
%AWX_SCOPE_PY% -B "%~dp0scripts\agent_scope_lease.py" --root "%~dp0." %*
set "AWX_SCOPE_EXIT=%ERRORLEVEL%"
echo.
if not "%AWX_SCOPE_EXIT%"=="0" echo [RESULT] agent-scope-lease exit code: %AWX_SCOPE_EXIT% ^(0=ok, 7=scope conflict, 6=evidence/session error, 3=owner/identity, 2=usage^). Leases are cooperative; expired foreign leases still block overlapping targets.
if not defined AWX_RAG_NO_PAUSE pause
exit /b %AWX_SCOPE_EXIT%
