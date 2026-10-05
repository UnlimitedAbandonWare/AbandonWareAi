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
REM  Token actually lives in Windows Credential Manager (gemini:antigravity); switch with Agy-Auth.bat
REM ============================================================
setlocal
chcp 65001 >nul

set "PROJECT_ROOT=%~dp0"
set "PROJECT_ROOT=%PROJECT_ROOT:~0,-1%"

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
REM  2026-09-30). Override once: set "AWX_AGY_EFFORT=max" (low|medium|high|xhigh|max;
REM  flash tops out at high) or set "AWX_AGY_EFFORT=off" to attach no flag. An explicit --effort in
REM  your own args wins. Session continuity passes straight through %%*:
REM  `Start-Agy-CLI.bat -c` (most recent) / `--conversation <id>` (by id).
REM  Depth profile (2026-10-05, devin-agy-flash-dynamic-depth): default 'depth'
REM  auto-follows the newest same-tier Flash id (agy_model_latest.ps1 -Pick)
REM  and keeps effort at Flash's top level (high; xhigh/max are Pro-only and
REM  are rejected for gemini-*-flash models). set "AWX_AGY_PROFILE=lite" or
REM  "off" restores the pre-depth behavior (no model flag, effort high only).
if not defined AWX_AGY_PROFILE set "AWX_AGY_PROFILE=depth"
if /i "%AWX_AGY_PROFILE%"=="depth" (
    if not defined AWX_AGY_MODEL set "AWX_AGY_MODEL=latest"
    if not defined AWX_AGY_EFFORT set "AWX_AGY_EFFORT=high"
    if /i "%AWX_AGY_EFFORT%"=="xhigh" echo [Start-Agy-CLI] WARN: flash effort tops at high - xhigh likely rejected
    if /i "%AWX_AGY_EFFORT%"=="max" echo [Start-Agy-CLI] WARN: flash effort tops at high - max likely rejected
)
if not defined AWX_AGY_EFFORT set "AWX_AGY_EFFORT=high"
set "EFFORT_FLAG="
if /i not "%AWX_AGY_EFFORT%"=="off" set "EFFORT_FLAG=--effort %AWX_AGY_EFFORT%"
set "PASSED_ARGS=%*"
if defined PASSED_ARGS if not "%PASSED_ARGS%"=="%PASSED_ARGS:--effort=%" set "EFFORT_FLAG="
if defined EFFORT_FLAG echo [Start-Agy-CLI] AWX_AGY_EFFORT=%AWX_AGY_EFFORT%: reasoning effort flag attached

REM  Latest-model notification + opt-in model flag (Mode B, 2026-10-04).
REM  One status line per launch (AWX_AGY_MODEL_CHECK=0 skips it). Opt-in:
REM    AWX_AGY_MODEL=latest -> --model <newest same family/tier id>
REM    AWX_AGY_MODEL=<id>   -> --model <id>
REM  Unset (default) attaches no --model flag; the /config pick is kept.
REM  An explicit --model in your own args wins, same as --effort.
set "MODEL_FLAG="
if /i "%AWX_AGY_MODEL%"=="latest" (
    for /f "usebackq delims=" %%M in (`powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\agy_model_latest.ps1" -Pick`) do set "MODEL_FLAG=--model %%M"
) else if defined AWX_AGY_MODEL (
    set "MODEL_FLAG=--model %AWX_AGY_MODEL%"
)
if defined PASSED_ARGS if not "%PASSED_ARGS%"=="%PASSED_ARGS:--model=%" set "MODEL_FLAG="
if defined MODEL_FLAG echo [Start-Agy-CLI] AWX_AGY_MODEL=%AWX_AGY_MODEL%: model flag attached
if not "%AWX_AGY_MODEL_CHECK%"=="0" powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\agy_model_latest.ps1"

REM  Optional agy account switch via Agy-Auth.bat (2026-10-05). Opt-in:
REM    set AWX_AGY_ACCOUNT=<saved-name>  ->  "Agy-Auth.bat use <name>" runs first.
REM  One account status line per launch (AWX_AGY_AUTH_STATUS=0 skips it).
REM  AWX_RAG_NO_PAUSE is forced during the helper calls so they never block.
set "AWX_RAG_NO_PAUSE_PREV=%AWX_RAG_NO_PAUSE%"
set "AWX_RAG_NO_PAUSE=1"
if defined AWX_AGY_ACCOUNT (
    call "%~dp0Agy-Auth.bat" use "%AWX_AGY_ACCOUNT%"
    if errorlevel 1 echo [Start-Agy-CLI] WARN: Agy-Auth use %AWX_AGY_ACCOUNT% failed - continuing with current account
)
if not "%AWX_AGY_AUTH_STATUS%"=="0" (
    for /f "usebackq tokens=2 delims= " %%A in (`call "%~dp0Agy-Auth.bat" status 2^>nul ^| findstr /b /l /c:"[agy-auth] account="`) do echo [Start-Agy-CLI] %%A
)
set "AWX_RAG_NO_PAUSE=%AWX_RAG_NO_PAUSE_PREV%"

REM  Session seed + quota hint (non-blocking, 2026-10-05): writes
REM  var\agy-seed\latest.md for the depth router and prints a one-line hint
REM  when recent cli logs show RESOURCE_EXHAUSTED/quota/429. Never blocks.
if exist "%~dp0scripts\agy_session_seed.py" python -B "%~dp0scripts\agy_session_seed.py" --launcher 2>nul

REM  Launch summary: resolved profile/model/effort at a glance.
set "AGY_MODEL_ID=(saved)"
for /f "tokens=2" %%m in ("%MODEL_FLAG%") do set "AGY_MODEL_ID=%%m"
set "AGY_ACCT=%AWX_AGY_ACCOUNT%"
if not defined AGY_ACCT set "AGY_ACCT=UNSAVED"
echo [Start-Agy-CLI] profile=%AWX_AGY_PROFILE% model=%AGY_MODEL_ID% effort=%AWX_AGY_EFFORT% account=%AGY_ACCT% (flash top effort=high; L3 adds verify+subagent passes)

if "%AWX_AGY_DRYRUN%"=="1" (
    echo [Start-Agy-CLI] DRYRUN cmdline: "%AGY%" %YOLO_FLAG% %EFFORT_FLAG% %MODEL_FLAG% %*
    exit /b 0
)

if not "%AWX_CONTEXT_PREAMBLE%"=="0" powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\agent_context_preamble.ps1" -Agent agy

REM  Presence SSOT (2026-10-05): ONLINE just before agy.exe starts so the
REM  fleet (devin/orchestra) knows agy is up; OFFLINE after it exits. Never
REM  blocks the launch - failures are swallowed by 2>nul.
if exist "%~dp0scripts\agy_presence.py" python -B "%~dp0scripts\agy_presence.py" set --status ONLINE --account %AGY_ACCT% 2>nul
"%AGY%" %YOLO_FLAG% %EFFORT_FLAG% %MODEL_FLAG% %*
set "EXITCODE=%ERRORLEVEL%"

REM  Presence OFFLINE + exit-context snapshot for the next launch's
REM  "[Start-Agy-CLI] resume:" line (non-blocking).
if exist "%~dp0scripts\agy_presence.py" python -B "%~dp0scripts\agy_presence.py" set --status OFFLINE --account %AGY_ACCT% 2>nul
if exist "%~dp0scripts\agy_session_seed.py" python -B "%~dp0scripts\agy_session_seed.py" --on-exit 2>nul
echo.
echo [Start-Agy-CLI] session ended (exit=%EXITCODE%)
if "%AWX_RAG_NO_PAUSE%"=="" pause
exit /b %EXITCODE%
