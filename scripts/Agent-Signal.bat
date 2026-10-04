@echo off
rem Agent-Signal.bat - one-click quick ping-pong between Codex/Devin/Grok.
rem Wraps scripts\agent_quick_signal.py (emit|inbox|copy|done|counts).
rem All local files; nothing is sent automatically - the user pastes.
rem Run from project root. Network 0. Paid calls 0.
setlocal EnableDelayedExpansion
cd /d "%~dp0\.."

if "%~1"=="" goto :menu
if /i "%~1"=="--help" goto :help
if /i "%~1"=="-h" goto :help
if /i "%~1"=="/?" goto :help
if /i "%~1"=="help" goto :help
python -B scripts\agent_quick_signal.py %*
exit /b %errorlevel%

:help
echo Agent-Signal.bat - Codex/Devin/Grok quick signal ping-pong
echo.
echo   Agent-Signal.bat                 interactive menu (waiting counts + choices)
echo   Agent-Signal.bat emit --from ^<me^> --to ^<target^> --summary "..." [--files a,b] [--clip]
echo   Agent-Signal.bat inbox --agent ^<agent^>
echo   Agent-Signal.bat copy --agent ^<agent^> ^| --id ^<id^> [--print]
echo   Agent-Signal.bat done --id ^<id^>
echo   Agent-Signal.bat counts
echo.
echo emits: signal new -^> route --apply -^> var\orchestra\outbox\PASTE_*.txt + mallow line
exit /b 0

:menu
python -B scripts\agent_quick_signal.py counts
echo.
echo   1. emit  - new signal + route + PASTE file (+clipboard)
echo   2. copy  - latest PASTE body to clipboard
echo   3. board - orchestra status board
echo   4. quit
set /p CHOICE=choice^>
if "%CHOICE%"=="1" goto :emit
if "%CHOICE%"=="2" goto :copy
if "%CHOICE%"=="3" goto :board
if "%CHOICE%"=="4" exit /b 0
if "%CHOICE%"=="" exit /b 0
goto :menu

:emit
set /p FROM=from (user/grokbot/agy/gptpro/devin/codex/clean/grokcli)^>
set /p TO=to   (user/grokbot/agy/gptpro/devin/codex/clean/grokcli)^>
set /p SUMMARY=summary (one line)^>
set /p FILES=files (comma-separated, optional)^>
if "%FROM%"=="" goto :menu
if "%TO%"=="" goto :menu
if "%FILES%"=="" (
  python -B scripts\agent_quick_signal.py emit --from %FROM% --to %TO% --summary "%SUMMARY%" --clip
) else (
  python -B scripts\agent_quick_signal.py emit --from %FROM% --to %TO% --summary "%SUMMARY%" --files "%FILES%" --clip
)
echo.
goto :menu

:copy
set /p AGENT=agent (codex/devin/gptpro/agy/grokbot/clean/user)^>
if "%AGENT%"=="" goto :menu
python -B scripts\agent_quick_signal.py copy --agent %AGENT%
echo.
goto :menu

:board
python -B scripts\orchestra_board.py --md
echo.
goto :menu
