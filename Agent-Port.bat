@echo off
setlocal
cd /d "%~dp0"
title AbandonWare Agent Port Lease
set "AWX_PORT_PY=python"
where %AWX_PORT_PY% >nul 2>nul
if errorlevel 1 (
  echo [RESULT] python not found on PATH; run scripts\agent_port_lease.py with Python 3.
  exit /b 1
)
%AWX_PORT_PY% -B "%~dp0scripts\agent_port_lease.py" %*
set "AWX_PORT_EXIT=%ERRORLEVEL%"
if not "%AWX_PORT_EXIT%"=="0" echo [RESULT] agent-port-lease exit code: %AWX_PORT_EXIT% ^(0=ok, 2=usage, 3=refused, 4=health-or-verify-failed, 1=error^). Only this owner/session lease is stopped.
if not defined AWX_RAG_NO_PAUSE pause
exit /b %AWX_PORT_EXIT%
