@echo off
setlocal
rem 에이전트/CI 비대화형 세션 자동 감지 (무한 pause 방지) — ForceRestart-RAG.bat와 동일 규칙
if defined DEVIN set "AWX_RAG_NO_PAUSE=1"
if defined AGENT_SESSION set "AWX_RAG_NO_PAUSE=1"
if defined AWX_AGENT_WORKER set "AWX_RAG_NO_PAUSE=1"
if defined CI set "AWX_RAG_NO_PAUSE=1"
if defined CONTINUOUS_INTEGRATION set "AWX_RAG_NO_PAUSE=1"
if defined CODEX set "AWX_RAG_NO_PAUSE=1"
if defined CODEX_SESSION set "AWX_RAG_NO_PAUSE=1"
if defined CODEX_THREAD_ID set "AWX_RAG_NO_PAUSE=1"
if defined ANTIGRAVITY set "AWX_RAG_NO_PAUSE=1"
if defined AGY_SESSION set "AWX_RAG_NO_PAUSE=1"
if defined NONINTERACTIVE set "AWX_RAG_NO_PAUSE=1"
echo %cmdcmdline% | findstr /i /c:"/c " >nul && set "AWX_RAG_NO_PAUSE=1"
cd /d "%~dp0"
if exist "%~dp0scripts\bat_run_hook.cmd" call "%~dp0scripts\bat_run_hook.cmd" begin "%~nx0" %*
title AbandonWare Meta Display RAG Launcher
if defined AWX_RAG_NO_PAUSE (
  "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start_rag_stack.ps1" -MetaDisplay -ForceRestart -DevWatch -Preload %*
) else (
  "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start_rag_stack.ps1" -MetaDisplay -ForceRestart -DevWatch -OpenBrowser -Preload %*
)
set "RAG_EXIT=%ERRORLEVEL%"
echo.
if not "%RAG_EXIT%"=="0" echo [FAILED] Launcher exit code: %RAG_EXIT%. See the stage and log path above.
echo You may close this window. Running services remain available. DevWatch keeps reloading on source changes.
if exist "%~dp0scripts\bat_run_hook.cmd" call "%~dp0scripts\bat_run_hook.cmd" end "%~nx0" %RAG_EXIT%
if not defined AWX_RAG_NO_PAUSE pause
exit /b %RAG_EXIT%
