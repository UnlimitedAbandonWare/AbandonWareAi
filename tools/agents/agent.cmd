@echo off
REM  agent.cmd <cli> <role> <prompt-file> [extra run-agent.mjs options]
REM  extras passthrough: --effort l|m|h|max (agy default=high), --mode,
REM  --timeout, --schema, --conversation <id> / --continue (agy session resume).
REM  Dispatcher: agy | grok | codex. Anything else -> NOT_SUPPORTED (exit 3).
setlocal
set "TOOLS_DIR=%~dp0"

if "%~1"=="" goto :usage
if "%~2"=="" goto :usage
if "%~3"=="" goto :usage
set "CLI=%~1"
set "ROLE=%~2"
set "PROMPT=%~3"

set "EXTRA="
:collect
if "%~4"=="" goto :run
set "EXTRA=%EXTRA% %~4"
shift
goto :collect

:run
if /i "%CLI%"=="agy"   goto :ok
if /i "%CLI%"=="grok"  goto :ok
if /i "%CLI%"=="codex" goto :ok
if /i "%CLI%"=="gemini" goto :gemini_removed
echo [agent] NOT_SUPPORTED: cli "%CLI%" (known: agy grok codex)
exit /b 3
:gemini_removed
echo [agent] NOT_SUPPORTED: cli "gemini" (gemini CLI removed 2026-09-30 - use agy)
exit /b 3
:ok
node "%TOOLS_DIR%run-agent.mjs" --cli "%CLI%" --role "%ROLE%" --prompt-file "%PROMPT%"%EXTRA%
exit /b %ERRORLEVEL%

:usage
echo usage: agent.cmd ^<cli^> ^<role^> ^<prompt-file^> [--model m] [--effort l^|m^|h^|max] [--mode plan^|accept-edits] [--timeout 5m] [--schema json^|file] [--conversation ^<id^>] [--continue]
exit /b 2
