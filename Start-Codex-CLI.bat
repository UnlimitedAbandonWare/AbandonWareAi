@echo off
REM ============================================================
REM  Start-Codex-CLI.bat
REM  Codex CLI (codex.cmd) interactive launcher.
REM
REM  Why this exists:
REM    codex reads AGENTS.md / project config from the working
REM    root. Launching from anywhere else means NO project rules,
REM    work-ledger, or lease policy. This BAT pins cwd AND passes
REM    -C <root> so the agent's working root is the project.
REM
REM    codex is on PATH (%APPDATA%\npm\codex.cmd), so resolution:
REM      %CODEX_EXE% -> `where codex` -> %APPDATA%\npm\codex.cmd
REM
REM  Approval tiers (flags verified on codex-cli 0.144.1):
REM    AWX_CODEX_YOLO=0  -> plain codex (default prompts/sandbox)
REM    AWX_CODEX_YOLO=1  -> -s workspace-write -a never
REM                         no approval prompts; model commands stay
REM                         sandboxed to the workspace (+ GRADLE_USER_HOME)
REM    AWX_CODEX_YOLO=2  -> --dangerously-bypass-approvals-and-sandbox
REM                         full bypass; only inside externally
REM                         sandboxed runs
REM
REM  Default is tier 1: removes approval interrupts while keeping
REM  the sandbox's damage limit. Tier 2 opt-in drops the sandbox.
REM
REM  Standing limits regardless of tier (also in AGENTS.md):
REM    no git push/fetch, no secret printing, no edits outside the
REM    project root, no bulk source deletion.
REM ============================================================
setlocal

set "PROJECT_ROOT=C:\AbandonWare\demo-1\demo-1\src"
cd /d "%PROJECT_ROOT%"

REM  Shared toolchain env (same block as Start-Agy-CLI.bat / Start-Grok-CLI.bat):
REM  JDK17 + Git + Node/npm + agy bin on PATH so agent-spawned subprocesses see
REM  the same tools regardless of the launching shell.
set "JAVA_HOME=C:\jdk\jdk-17.0.13"
set "PATH=C:\jdk\jdk-17.0.13\bin;F:\git\cmd;C:\Program Files\nodejs;%APPDATA%\npm;%LOCALAPPDATA%\agy\bin;%PATH%"

REM  Split Gradle outputs per host so parallel agents don't fight over one
REM  build cache dir (see .gradle-<host>-* dirs already present at root).
if not defined AWX_SPLIT_BUILD_OUTPUTS set "AWX_SPLIT_BUILD_OUTPUTS=1"
if not defined AWX_BUILD_HOST_ID set "AWX_BUILD_HOST_ID=desktop"

set "CODEX="
if defined CODEX_EXE set "CODEX=%CODEX_EXE%"
if not defined CODEX (
    for /f "delims=" %%i in ('where codex 2^>nul') do if not defined CODEX set "CODEX=%%i"
)
if not defined CODEX set "CODEX=%APPDATA%\npm\codex.cmd"

if not exist "%CODEX%" (
    echo [Start-Codex-CLI] codex not found: %CODEX%
    echo Install Codex CLI first, or set CODEX_EXE to the full path.
    if "%AWX_RAG_NO_PAUSE%"=="" pause
    exit /b 1
)

if not defined AWX_CODEX_YOLO set "AWX_CODEX_YOLO=1"

set "CODEX_FLAGS=-C "%PROJECT_ROOT%""
if "%AWX_CODEX_YOLO%"=="1" (
    echo [Start-Codex-CLI] AWX_CODEX_YOLO=1: -s workspace-write -a never
    set "CODEX_FLAGS=-s workspace-write -a never -C "%PROJECT_ROOT%""
    REM  Gradle cache lives outside the workspace; make it writable.
    if defined GRADLE_USER_HOME set "CODEX_FLAGS=%CODEX_FLAGS% --add-dir "%GRADLE_USER_HOME%""
)
if "%AWX_CODEX_YOLO%"=="2" (
    echo [Start-Codex-CLI] AWX_CODEX_YOLO=2: --dangerously-bypass-approvals-and-sandbox
    set "CODEX_FLAGS=--dangerously-bypass-approvals-and-sandbox -C "%PROJECT_ROOT%""
)

echo [Start-Codex-CLI] cwd=%CD%
echo [Start-Codex-CLI] exe=%CODEX%
echo.

call "%CODEX%" %CODEX_FLAGS% %*
set "EXITCODE=%ERRORLEVEL%"

echo.
echo [Start-Codex-CLI] session ended (exit=%EXITCODE%)
if "%AWX_RAG_NO_PAUSE%"=="" pause
exit /b %EXITCODE%
