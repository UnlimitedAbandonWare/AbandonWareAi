@echo off
REM ============================================================
REM  Agy-Auth.bat - agy (Antigravity CLI) account save/switch helper.
REM  The login token lives in Windows Credential Manager (default
REM  target: gemini:antigravity). Saved copies are DPAPI(CurrentUser)
REM  encrypted under %LOCALAPPDATA%\awx-agy-auth - other Windows users
REM  and other PCs cannot open them.
REM
REM  Usage:
REM    Agy-Auth.bat status
REM    Agy-Auth.bat list
REM    Agy-Auth.bat save <name>      (saves the CURRENT credential)
REM    Agy-Auth.bat use <name>       (refuses while agy.exe is running;
REM                                  writes presence SWITCHING->OFFLINE to
REM                                  data/agent-handoff/agy-presence.json)
REM    Agy-Auth.bat login            (prints the one-time login steps)
REM    Agy-Auth.bat forget <name>
REM  Extra args pass through: -Target <t> -StoreDir <d> -ProcessName <p>
REM                           -Force -ShowAccount
REM ============================================================
setlocal
chcp 65001 >nul
set "ACTION=%~1"
set "NAMEARG=%~2"
set "SCRIPT=%~dp0scripts\agy_auth_switch.ps1"

if "%ACTION%"=="" goto :usage

set "EXTRA="
if not "%NAMEARG%"=="" set "EXTRA=-Name %NAMEARG%"
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" -Action %ACTION% %EXTRA% %3 %4 %5 %6 %7 %8 %9
set "EXITCODE=%ERRORLEVEL%"
if /i "%ACTION%"=="use" if exist "data\agent-handoff\agy-presence.json" echo [agy-auth] presence: data\agent-handoff\agy-presence.json
goto :done

:usage
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" -Action status
echo.
echo Usage: Agy-Auth.bat status ^| list ^| save ^<name^> ^| use ^<name^> ^| login ^| forget ^<name^>
echo   First-time per account: Start-Agy-CLI.bat -^> /logout -^> /login ^(pick the account in the browser^) -^> exit -^> Agy-Auth.bat save ^<name^>
echo   Passthrough args: -Target ^<t^> -StoreDir ^<d^> -ProcessName ^<p^> -Force -ShowAccount
set "EXITCODE=0"

:done
if "%AWX_RAG_NO_PAUSE%"=="" pause
exit /b %EXITCODE%
