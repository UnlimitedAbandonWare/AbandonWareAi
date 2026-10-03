@echo off
rem Start-Agy-GrokBot.bat - agy를 Grok Bot 대타 모드로 연다.
rem 기존 Start-Agy-CLI.bat와 다른 점: --dangerously-skip-permissions 를
rem 기본으로 붙이지 않는다 (AWX_AGY_YOLO=1 일 때만), 첫 메시지로 Grok Bot
rem 프라이머를 -i 로 주입한다. cwd는 레포 루트로 고정.
setlocal
set "ROOT=C:\AbandonWare\demo-1\demo-1\src"
cd /d "%ROOT%"

if defined AGY_EXE (set "AGY=%AGY_EXE%") else (
  where agy.exe >nul 2>nul && (set "AGY=agy.exe") || set "AGY=%LOCALAPPDATA%\agy\bin\agy.exe"
)
if not defined AGY_EFFORT set "AGY_EFFORT=%AWX_AGY_EFFORT%"
if not defined AGY_EFFORT set "AGY_EFFORT=high"

set "ARGS=--effort %AGY_EFFORT% --mode accept-edits"
if "%AWX_AGY_YOLO%"=="1" set "ARGS=%ARGS% --dangerously-skip-permissions"

set "PRIMER=agent-prompts\agy-grokbot-primer.md"
echo [agy-grokbot] Grok Bot 대타 모드 — primer: %PRIMER%
echo [agy-grokbot] 최근 크레딧/한도 판정: USABLE_BASE_QUOTA (2026-10-02 DV1 — cli.log 기준; G1 크레딧 0 표시와 별개로 기본 한도 동작 확인)

rem -c / --conversation 는 그대로 통과
"%AGY%" %ARGS% -i "%PRIMER%" %*
endlocal
