@echo off
REM [USER-ONLY] Manual user tool. Agents: do NOT run/modify/auto-invoke unless the user explicitly asks.
REM Packs demo-1 source (secret-free) into zipHome for backup (90% restore-ready) & GPT Pro upload.
REM Usage: Pack-GPTPro.bat [backup|main|core|full|ctx|brief|evidence] [--dry-run] [--list] [--max-zip-mb N] [--focus "kw,kw"] [--briefs DIR] [--drop-legacy] [--evidence-days N] [--no-codex]
REM   backup = 90% source restore-ready backup (default)
REM   ctx   = core + GPT Pro context sections (_START_HERE.._TEST_INDEX)
REM   brief = ctx + java skeleton bodies stripped (focus matches keep full text)
REM Non-interactive: GPTPRO_NOPAUSE=1, GPTPRO_NOEXPLORER=1 or --no-explorer
chcp 65001 >nul
setlocal EnableDelayedExpansion
cd /d "%~dp0"
set "PYTHONIOENCODING=utf-8"

set "PY="
where python >nul 2>nul && set "PY=python"
if not defined PY (
  where py >nul 2>nul && set "PY=py -3"
)
if not defined PY (
  echo [Pack-GPTPro] Python not found. Install Python 3.9+ and retry.
  pause
  exit /b 3
)

for /f %%G in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')"') do set "PACK_ID=%%G"
if not defined PACK_ID exit /b 3
set "MARKER=%TEMP%\gptpro_pack_%PACK_ID%.txt"
set "GPTPRO_PACK_MARKER=%MARKER%"

%PY% -B "%~dp0scripts\gptpro_pack.py" %*
set "RC=%ERRORLEVEL%"

set "NOEXPLORER=%GPTPRO_NOEXPLORER%"
echo "%*" | findstr /i /c:"--no-explorer" >nul && set "NOEXPLORER=1"
echo "%*" | findstr /i /c:"--dry-run" >nul && set "NOEXPLORER=1"
echo "%*" | findstr /i /c:"--list" >nul && set "NOEXPLORER=1"
if not defined NOEXPLORER if exist "%MARKER%" (
  set /p ZP=<"%MARKER%"
  if defined ZP if /i not "!ZP!"=="DRYRUN" if exist "!ZP!" explorer /select,"!ZP!"
)

if exist "%MARKER%" del /f /q "%MARKER%" >nul 2>nul
set "NOPAUSE=%GPTPRO_NOPAUSE%"
echo "%*" | findstr /i /c:"--no-pause" >nul && set "NOPAUSE=1"
if not defined NOPAUSE pause
exit /b %RC%
