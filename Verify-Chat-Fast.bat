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
if exist "%~dp0scripts\bat_run_hook.cmd" call "%~dp0scripts\bat_run_hook.cmd" begin "%~nx0" %*
title AbandonWare Chat Fast Verify (offline stream boundaries)
where node >nul 2>nul
if errorlevel 1 (
  echo [RESULT] node not found on PATH; install Node.js to run src\test\js\chat-stream-boundaries.test.cjs.
  if exist "%~dp0scripts\bat_run_hook.cmd" call "%~dp0scripts\bat_run_hook.cmd" end "%~nx0" 1
  exit /b 1
)
node --test "%~dp0src\test\js\chat-stream-boundaries.test.cjs"
set "AWX_CHAT_TEST_EXIT=%ERRORLEVEL%"
echo.
if "%AWX_CHAT_TEST_EXIT%"=="0" (
  echo [RESULT] chat-stream-boundaries PASS
) else (
  echo [RESULT] chat-stream-boundaries exit code: %AWX_CHAT_TEST_EXIT%
)
if not defined AWX_RAG_NO_PAUSE pause
if exist "%~dp0scripts\bat_run_hook.cmd" call "%~dp0scripts\bat_run_hook.cmd" end "%~nx0" %AWX_CHAT_TEST_EXIT%
exit /b %AWX_CHAT_TEST_EXIT%
