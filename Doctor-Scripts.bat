@echo off
REM ============================================================
REM  Doctor-Scripts.bat
REM  Thin facade for scripts\script_doctor.py.
REM  Root comes from %~dp0. No pause, so callers can read ERRORLEVEL.
REM  Read-only. No paid calls. python -B avoids pycache writes.
REM ============================================================
chcp 65001 >nul
setlocal
set "PROJECT_ROOT=%~dp0"
set "PROJECT_ROOT=%PROJECT_ROOT:~0,-1%"
cd /d "%PROJECT_ROOT%"
if "%~1"=="" (
  python -B "%PROJECT_ROOT%\scripts\script_doctor.py" --summary --json "%PROJECT_ROOT%\var\diagnostics\script_doctor_report.json"
) else (
  python -B "%PROJECT_ROOT%\scripts\script_doctor.py" %*
)
set "EXITCODE=%ERRORLEVEL%"
exit /b %EXITCODE%
