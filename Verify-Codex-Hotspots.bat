@echo off
setlocal
cd /d "%~dp0"
title AbandonWare Codex Hotspots Verify

rem Headless / agent mode detection
set "HOTSPOT_ARGS="
if defined AWX_AGENT set "HOTSPOT_ARGS=--json"
if defined CI set "HOTSPOT_ARGS=--json"

rem Pause setting for interactive runs
set "VRF_PAUSE=1"
if defined HOTSPOT_ARGS set "VRF_PAUSE="
if defined AWX_NO_PAUSE set "VRF_PAUSE="
if defined VRF_PAUSE echo %cmdcmdline% | findstr /i /c:"/c " >nul && set "VRF_PAUSE="

python -B "%~dp0scripts\verify_codex_recent_hotspots.py" %HOTSPOT_ARGS% %*
set "VRF_EXIT=%ERRORLEVEL%"

if defined VRF_PAUSE (
  echo.
  pause
)

exit /b %VRF_EXIT%
