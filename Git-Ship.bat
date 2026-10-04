@echo off
REM ============================================================
REM  Git-Ship.bat
REM  Thin wrapper -> scripts\git_ship.py
REM  Usage: Git-Ship.bat status ^| junk ^| scan ^| commit --message "msg" [--apply] ^| push ^| verify ^| ship
REM  no args -> Git-Ship-Easy.bat (interactive menu for the user)
REM  default is dry-run: real changes need --apply. main/master and force
REM  pushes are refused. secret values are never printed. the approval env
REM  vars (AWX_PUBLISH_APPROVED, AWX_SHIP_SKIP_GUARD) are NOT set here --
REM  the caller grants them only for that one run.
REM ============================================================
setlocal
chcp 65001 >nul
set "PROJECT_ROOT=%~dp0"
set "PROJECT_ROOT=%PROJECT_ROOT:~0,-1%"
cd /d "%PROJECT_ROOT%"
if "%~1"=="" goto easy
where py >nul 2>nul
if %ERRORLEVEL%==0 (
  py -3 -B "%PROJECT_ROOT%\scripts\git_ship.py" %*
) else (
  python -B "%PROJECT_ROOT%\scripts\git_ship.py" %*
)
set "EXITCODE=%ERRORLEVEL%"
if "%AWX_RAG_NO_PAUSE%"=="" pause
exit /b %EXITCODE%

:easy
call "%~dp0Git-Ship-Easy.bat" %*
exit /b %ERRORLEVEL%
