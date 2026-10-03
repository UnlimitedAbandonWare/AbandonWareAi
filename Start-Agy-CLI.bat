@echo off
REM ============================================================
REM  Start-Agy-CLI.bat
REM  Antigravity CLI (agy.exe) interactive launcher.
REM
REM  Why this exists:
REM    agy.exe discovers AGENTS.md / GEMINI.md and the .agents/
REM    customization root by walking up from the CURRENT directory.
REM    Launching it from anywhere else means NO project rules,
REM    work-ledger, or lease policy is loaded. This BAT forces the
REM    project root as cwd every time.
REM
REM    agy is NOT on PATH, so resolution order is:
REM      %AGY_EXE% -> `where agy` -> %LOCALAPPDATA%\agy\bin\agy.exe
REM
REM  Auth: cached Google/Antigravity session under %USERPROFILE%\.gemini
REM ============================================================
setlocal

set "PROJECT_ROOT=C:\AbandonWare\demo-1\demo-1\src"

REM  Shared toolchain env (same block as Start-Grok-CLI.bat / Start-Codex-CLI.bat):
REM  JDK17 + Git + Node/npm + agy bin on PATH so agent-spawned subprocesses see
REM  the same tools regardless of the launching shell.
set "JAVA_HOME=C:\jdk\jdk-17.0.13"
set "PATH=C:\jdk\jdk-17.0.13\bin;F:\git\cmd;C:\Program Files\nodejs;%APPDATA%\npm;%LOCALAPPDATA%\agy\bin;%PATH%"

REM  Split Gradle outputs per host so parallel agents don't fight over one
REM  build cache dir (see .gradle-<host>-* dirs already present at root).
if not defined AWX_SPLIT_BUILD_OUTPUTS set "AWX_SPLIT_BUILD_OUTPUTS=1"
if not defined AWX_BUILD_HOST_ID set "AWX_BUILD_HOST_ID=desktop"

set "AGY="
if defined AGY_EXE set "AGY=%AGY_EXE%"
if not defined AGY (
    for /f "delims=" %%i in ('where agy 2^>nul') do if not defined AGY set "AGY=%%i"
)
if not defined AGY set "AGY=%LOCALAPPDATA%\agy\bin\agy.exe"

if not exist "%AGY%" (
    echo [Start-Agy-CLI] agy.exe not found: %AGY%
    echo Install Antigravity CLI first, or set AGY_EXE to the full exe path.
    if "%AWX_RAG_NO_PAUSE%"=="" pause
    exit /b 1
)

cd /d "%PROJECT_ROOT%"

echo [Start-Agy-CLI] cwd=%CD%
echo [Start-Agy-CLI] exe=%AGY%

REM  Auto-approve defaults ON so pipelines never stall on prompts.
REM  Opt out per-launch:  set "AWX_AGY_YOLO=0"  before calling this BAT.
if not defined AWX_AGY_YOLO set "AWX_AGY_YOLO=1"

set "YOLO_FLAG="
if "%AWX_AGY_YOLO%"=="1" (
    echo [Start-Agy-CLI] AWX_AGY_YOLO=1: auto-approving all tool permissions
    set "YOLO_FLAG=--dangerously-skip-permissions"
)

REM  Reasoning effort default: agy is the amplifier lane (hypothesis bursts,
REM  directive design), so 'high' is the floor, not an opt-in (user decision
REM  2026-09-30). Override once: set "AWX_AGY_EFFORT=max" (low|medium|high|max)
REM  or set "AWX_AGY_EFFORT=off" to attach no flag. An explicit --effort in
REM  your own args wins. Session continuity passes straight through %%*:
REM  `Start-Agy-CLI.bat -c` (most recent) / `--conversation <id>` (by id).
if not defined AWX_AGY_EFFORT set "AWX_AGY_EFFORT=high"
set "EFFORT_FLAG="
if /i not "%AWX_AGY_EFFORT%"=="off" set "EFFORT_FLAG=--effort %AWX_AGY_EFFORT%"
set "PASSED_ARGS=%*"
if not "%PASSED_ARGS%"=="%PASSED_ARGS:--effort=%" set "EFFORT_FLAG="
if defined EFFORT_FLAG echo [Start-Agy-CLI] AWX_AGY_EFFORT=%AWX_AGY_EFFORT%: reasoning effort flag attached

"%AGY%" %YOLO_FLAG% %EFFORT_FLAG% %*
set "EXITCODE=%ERRORLEVEL%"

echo.
echo [Start-Agy-CLI] session ended (exit=%EXITCODE%)
if "%AWX_RAG_NO_PAUSE%"=="" pause
exit /b %EXITCODE%
