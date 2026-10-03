@echo off
setlocal
cd /d "%~dp0.."
title AbandonWare Agent Machine Context
set "AWX_MC_PY=python"
where %AWX_MC_PY% >nul 2>nul
if errorlevel 1 (
  echo {"schemaVersion":"awx.agent-machine-context.v1","status":"error","reason":"python-not-on-path"}
  exit /b 4
)
if "%~1"=="" (
  %AWX_MC_PY% -B "%~dp0agent_machine_context.py" --root "%~dp0.." --pretty
) else (
  %AWX_MC_PY% -B "%~dp0agent_machine_context.py" --root "%~dp0.." %*
)
set "AWX_MC_EXIT=%ERRORLEVEL%"
if not "%AWX_MC_EXIT%"=="0" echo [RESULT] agent-machine-context exit code: %AWX_MC_EXIT% ^(0=ok, 2=usage/probe failure, 4=python missing^)
if not defined AWX_RAG_NO_PAUSE pause
exit /b %AWX_MC_EXIT%
