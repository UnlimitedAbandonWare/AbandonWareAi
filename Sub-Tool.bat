@echo off
setlocal
cd /d "%~dp0"
if exist "%~dp0scripts\bat_run_hook.cmd" call "%~dp0scripts\bat_run_hook.cmd" begin "%~nx0" %*
title AbandonWare Agent Quick Sub-Tool

rem Headless / agent mode detection
set "SUBTOOL_ARGS="
if defined AWX_AGENT set "SUBTOOL_ARGS=--json"
if defined CI set "SUBTOOL_ARGS=--json"

rem Pause only for interactive no-arg runs (help screen)
set "ST_PAUSE="
if "%~1"=="" (
  set "ST_PAUSE=1"
  if defined SUBTOOL_ARGS set "ST_PAUSE="
  if defined AWX_NO_PAUSE set "ST_PAUSE="
  if defined ST_PAUSE echo %cmdcmdline% | findstr /i /c:"/c " >nul && set "ST_PAUSE="
)

if "%~1"=="" (
  python -B "%~dp0scripts\agent_quick_subtool.py" %SUBTOOL_ARGS% help
) else (
  python -B "%~dp0scripts\agent_quick_subtool.py" %SUBTOOL_ARGS% %*
)
set "ST_EXIT=%ERRORLEVEL%"

if defined ST_PAUSE (
  echo.
  pause
)

if exist "%~dp0scripts\bat_run_hook.cmd" call "%~dp0scripts\bat_run_hook.cmd" end "%~nx0" %ST_EXIT%
exit /b %ST_EXIT%
