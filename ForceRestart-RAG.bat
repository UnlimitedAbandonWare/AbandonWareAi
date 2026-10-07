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
chcp 65001 >nul
set "PYTHONIOENCODING=utf-8"
cd /d "%~dp0"
title AbandonWare RAG Force Restart (no browser)
rem Usage: ForceRestart-RAG.bat [extra start_rag_stack.ps1 args]
rem Set AWX_FORCE_RESTART_DRY=1 to print the planned commands without touching the runtime.
if defined AWX_FORCE_RESTART_DRY (
  echo [DRY] powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop_rag_stack.ps1" -MetaDisplay
  echo [DRY] powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start_rag_stack.ps1" -MetaDisplay -ForceRestart -DevWatch %AWX_ARGS%
  exit /b 0
)
echo [INFO] Stopping RAG stack (MetaDisplay)...
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop_rag_stack.ps1" -MetaDisplay
set "AWX_STOP_EXIT=%ERRORLEVEL%"
if not "%AWX_STOP_EXIT%"=="0" (
  echo [RESULT] stop_rag_stack exit code: %AWX_STOP_EXIT% - aborting restart.
  if not defined AWX_RAG_NO_PAUSE pause
  exit /b %AWX_STOP_EXIT%
)
echo [INFO] Waiting for ports to release...
timeout /t 3 /nobreak >nul
echo [INFO] Starting RAG stack clean (backend + DevWatch + ForceRestart, no browser)...
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start_rag_stack.ps1" -MetaDisplay -ForceRestart -DevWatch %AWX_ARGS%
set "AWX_START_EXIT=%ERRORLEVEL%"
echo.
if "%AWX_START_EXIT%"=="0" (
  echo [RESULT] RAG stack restarted. Verify with Debug-RAG.bat or Verify-RAG.
) else (
  echo [RESULT] start_rag_stack exit code: %AWX_START_EXIT%
)
if not defined AWX_RAG_NO_PAUSE pause
exit /b %AWX_START_EXIT%
