@echo off
REM ============================================================
REM  Doctor-Agents.bat
REM  $0-cost CLI agent health report (paths/versions/headless flags/
REM  MCP config locations/env set-ness/login evidence).
REM  Makes NO generation calls. Read-only.
REM ============================================================
setlocal
chcp 65001 >nul
set "PROJECT_ROOT=%~dp0"
set "PROJECT_ROOT=%PROJECT_ROOT:~0,-1%"
cd /d "%PROJECT_ROOT%"
node "%PROJECT_ROOT%\tools\agents\doctor.mjs" %*
set "EXITCODE=%ERRORLEVEL%"
node "%PROJECT_ROOT%\tools\agents\skill-lint.mjs" --doctor-line
if "%AWX_RAG_NO_PAUSE%"=="" pause
exit /b %EXITCODE%
