@echo off
REM ============================================================
REM  Start-Grok-CLI.bat
REM  Grok Build CLI (grok.exe) interactive launcher.
REM
REM  Why this exists:
REM    grok.exe discovers AGENTS.md / project config by walking up
REM    from the CURRENT directory. Launching it from anywhere else
REM    (e.g. %USERPROFILE%\.grok\bin) means NO project rules,
REM    work-ledger, or lease policy is loaded. This BAT forces the
REM    project root as cwd every time.
REM
REM  Auth: uses the cached auth.x.ai session in %USERPROFILE%\.grok
REM  (same subscription pool as the Grok Bot desktop app).
REM ============================================================
setlocal

set "PROJECT_ROOT=C:\AbandonWare\demo-1\demo-1\src"
set "GROK_EXE=%USERPROFILE%\.grok\bin\grok.exe"

if not exist "%GROK_EXE%" (
    echo [Start-Grok-CLI] grok.exe not found: %GROK_EXE%
    echo Install Grok Build CLI first: irm https://x.ai/cli/install.ps1 ^| iex
    if "%AWX_RAG_NO_PAUSE%"=="" pause
    exit /b 1
)

cd /d "%PROJECT_ROOT%"

echo [Start-Grok-CLI] cwd=%CD%
echo [Start-Grok-CLI] starting Grok Build CLI (Ctrl+Q to quit)
echo.

"%GROK_EXE%" %*
set "EXITCODE=%ERRORLEVEL%"

echo.
echo [Start-Grok-CLI] session ended (exit=%EXITCODE%)
if "%AWX_RAG_NO_PAUSE%"=="" pause
exit /b %EXITCODE%
