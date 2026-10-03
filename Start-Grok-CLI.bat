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

REM  Shared toolchain env (same block as Start-Agy-CLI.bat / Start-Codex-CLI.bat):
REM  JDK17 + Git + Node/npm + agy bin on PATH so agent-spawned subprocesses see
REM  the same tools regardless of the launching shell.
set "JAVA_HOME=C:\jdk\jdk-17.0.13"
set "PATH=C:\jdk\jdk-17.0.13\bin;F:\git\cmd;C:\Program Files\nodejs;%APPDATA%\npm;%LOCALAPPDATA%\agy\bin;%PATH%"

REM  Split Gradle outputs per host so parallel agents don't fight over one
REM  build cache dir (see .gradle-<host>-* dirs already present at root).
if not defined AWX_SPLIT_BUILD_OUTPUTS set "AWX_SPLIT_BUILD_OUTPUTS=1"
if not defined AWX_BUILD_HOST_ID set "AWX_BUILD_HOST_ID=desktop"

if not exist "%GROK_EXE%" (
    echo [Start-Grok-CLI] grok.exe not found: %GROK_EXE%
    echo Install Grok Build CLI first: irm https://x.ai/cli/install.ps1 ^| iex
    if "%AWX_RAG_NO_PAUSE%"=="" pause
    exit /b 1
)

cd /d "%PROJECT_ROOT%"

echo [Start-Grok-CLI] cwd=%CD%
echo [Start-Grok-CLI] starting Grok Build CLI (Ctrl+Q to quit)

REM  Auto-approve default ON so pipelines never stall on permission prompts.
REM  Flag verified on installed Grok Build CLI (--permission-mode accepts
REM  default|acceptEdits|auto|dontAsk|bypassPermissions|plan).
REM  Opt out per-launch:  set "AWX_GROK_YOLO=0"  before calling this BAT.
if not defined AWX_GROK_YOLO set "AWX_GROK_YOLO=1"
set "GROK_FLAG="
if "%AWX_GROK_YOLO%"=="1" (
    echo [Start-Grok-CLI] AWX_GROK_YOLO=1: --permission-mode bypassPermissions
    set "GROK_FLAG=--permission-mode bypassPermissions"
)
echo.

"%GROK_EXE%" --cwd "%PROJECT_ROOT%" %GROK_FLAG% %*
set "EXITCODE=%ERRORLEVEL%"

echo.
echo [Start-Grok-CLI] session ended (exit=%EXITCODE%)
if "%AWX_RAG_NO_PAUSE%"=="" pause
exit /b %EXITCODE%
