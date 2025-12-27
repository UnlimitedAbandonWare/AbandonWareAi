@echo off
setlocal enabledelayedexpansion
set SCRIPT_DIR=%~dp0
set ROOT=%SCRIPT_DIR%\..
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\build_error_guard.ps1"
exit /b %ERRORLEVEL%
