@echo off
setlocal
rem 에이전트/CI 비대화형 세션 자동 감지 (무한 pause 방지)
if defined DEVIN set "AWX_RAG_NO_PAUSE=1"
if defined AGENT_SESSION set "AWX_RAG_NO_PAUSE=1"
if defined AWX_AGENT_WORKER set "AWX_RAG_NO_PAUSE=1"
if defined CI set "AWX_RAG_NO_PAUSE=1"
if defined CONTINUOUS_INTEGRATION set "AWX_RAG_NO_PAUSE=1"
set "AWX_ARGS=%*"
if defined AWX_ARGS set "AWX_ARGS=%AWX_ARGS:--no-pause=%"
if defined AWX_ARGS set "AWX_ARGS=%AWX_ARGS:-NoPause=%"
if not "%AWX_ARGS%"=="%*" set "AWX_RAG_NO_PAUSE=1"
set "AWX_TASK_ARG=%~1"
if /i "%~1"=="--no-pause" set "AWX_TASK_ARG=%~2"
if /i "%~1"=="-NoPause" set "AWX_TASK_ARG=%~2"
chcp 65001 >nul
set "PYTHONIOENCODING=utf-8"
cd /d "%~dp0"
title AbandonWare Lease Reclaim
set "AWX_SCOPE_PY=python"
where %AWX_SCOPE_PY% >nul 2>nul
if errorlevel 1 (
  echo [RESULT] python not found on PATH; run scripts\lease_conflict_autoflow.py with a Python 3 interpreter.
  exit /b 1
)
echo [INFO] Reclaiming stale/orphan source-edit leases (live leases are never touched)...
%AWX_SCOPE_PY% -B "%~dp0scripts\lease_conflict_autoflow.py" --root "%~dp0." reclaim
set "AWX_RECLAIM_EXIT=%ERRORLEVEL%"
if not "%AWX_RECLAIM_EXIT%"=="0" echo [RESULT] lease_conflict_autoflow reclaim exit code: %AWX_RECLAIM_EXIT%
echo.
if "%AWX_TASK_ARG%"=="" (
  %AWX_SCOPE_PY% -B "%~dp0scripts\agent_scope_lease.py" --root "%~dp0." who
) else (
  echo [INFO] Releasing claims for task "%AWX_TASK_ARG%"...
  %AWX_SCOPE_PY% -B "%~dp0scripts\agent_scope_lease.py" --root "%~dp0." done --task "%AWX_TASK_ARG%"
)
set "AWX_SCOPE_EXIT=%ERRORLEVEL%"
echo.
if not "%AWX_SCOPE_EXIT%"=="0" echo [RESULT] agent-scope-lease exit code: %AWX_SCOPE_EXIT% ^(0=ok, 7=scope conflict, 6=evidence/session error, 3=owner/identity, 2=usage^).
if not "%AWX_RECLAIM_EXIT%"=="0" set "AWX_SCOPE_EXIT=%AWX_RECLAIM_EXIT%"
if not defined AWX_RAG_NO_PAUSE pause
exit /b %AWX_SCOPE_EXIT%
