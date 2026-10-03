@echo off
REM ============================================================
REM  Start-Agy-CLI-Yolo.bat
REM  Antigravity CLI (agy.exe) launcher with elevated permissions:
REM    --dangerously-skip-permissions (Auto-approve tool calls)
REM    --mode accept-edits (Allows workspace file modifications)
REM ============================================================
setlocal

set "AWX_AGY_YOLO=1"
set "PROJECT_ROOT=C:\AbandonWare\demo-1\demo-1\src"

set "AGY="
if defined AGY_EXE set "AGY=%AGY_EXE%"
if not defined AGY (
    for /f "delims=" %%i in ('where agy 2^>nul') do if not defined AGY set "AGY=%%i"
)
if not defined AGY set "AGY=%LOCALAPPDATA%\agy\bin\agy.exe"

if not exist "%AGY%" (
    echo [Start-Agy-CLI-Yolo] agy.exe not found: %AGY%
    echo Install Antigravity CLI first, or set AGY_EXE to the full exe path.
    if "%AWX_RAG_NO_PAUSE%"=="" pause
    exit /b 1
)

cd /d "%PROJECT_ROOT%"

echo [Start-Agy-CLI-Yolo] cwd=%CD%
echo [Start-Agy-CLI-Yolo] exe=%AGY%
echo [Start-Agy-CLI-Yolo] MODE: High Agency / Auto-Approve (--dangerously-skip-permissions --mode accept-edits)
echo.

"%AGY%" --dangerously-skip-permissions --mode accept-edits %*
set "EXITCODE=%ERRORLEVEL%"

echo.
echo [Start-Agy-CLI-Yolo] session ended (exit=%EXITCODE%)
if "%AWX_RAG_NO_PAUSE%"=="" pause
exit /b %EXITCODE%
