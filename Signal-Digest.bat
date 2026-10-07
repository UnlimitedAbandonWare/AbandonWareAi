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
title AbandonWare Agent Signal Digest
set "AWX_SCOPE_PY=python"
where %AWX_SCOPE_PY% >nul 2>nul
if errorlevel 1 (
  echo [RESULT] python not found on PATH; run scripts\agent_signal_digest.py with a Python 3 interpreter.
  exit /b 1
)
%AWX_SCOPE_PY% -B "%~dp0scripts\agent_signal_digest.py" --root "%~dp0." %AWX_ARGS%
set "AWX_DIGEST_EXIT=%ERRORLEVEL%"
echo.
if not "%AWX_DIGEST_EXIT%"=="0" echo [RESULT] agent_signal_digest exit code: %AWX_DIGEST_EXIT%
if not defined AWX_RAG_NO_PAUSE pause
exit /b %AWX_DIGEST_EXIT%
