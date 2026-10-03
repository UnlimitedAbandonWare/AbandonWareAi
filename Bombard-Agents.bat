@echo off
REM ============================================================
REM  Bombard-Agents.bat
REM  Single entry point for the three agent CLIs on this checkout.
REM
REM  Usage:
REM    Bombard-Agents.bat agy    [args...]   -> Start-Agy-CLI.bat
REM    Bombard-Agents.bat grok   [args...]   -> Start-Grok-CLI.bat
REM    Bombard-Agents.bat codex  [args...]   -> Start-Codex-CLI.bat
REM    Bombard-Agents.bat doctor             -> Doctor-Agents.bat ($0 probe)
REM    Bombard-Agents.bat                    -> pick a lane interactively
REM
REM  Sequential by design: each invocation runs ONE lane. Do NOT
REM  run two lanes on this tree in parallel unless their write
REM  scopes are disjoint (lease/journal machinery decides that,
REM  not this launcher).
REM ============================================================
setlocal
set "ROOT=%~dp0"

set "LANE=%~1"
if /i "%LANE%"=="agy"    goto lane
if /i "%LANE%"=="grok"   goto lane
if /i "%LANE%"=="codex"  goto lane
if /i "%LANE%"=="doctor" goto doctor
if /i "%LANE%"==""       goto menu
echo [Bombard-Agents] unknown lane "%LANE%" (expected agy^|grok^|codex^|doctor)
exit /b 2

:menu
echo [Bombard-Agents] pick one lane (sequential default):
echo   [A] Antigravity (agy)   [G] Grok   [C] Codex   [D] Doctor ($0 health)
choice /c AGCD /n /m "lane: "
if errorlevel 4 goto doctor
if errorlevel 3 set "LANE=codex" & goto lane
if errorlevel 2 set "LANE=grok"  & goto lane
set "LANE=agy"
goto lane

:lane
shift
if /i "%LANE%"=="agy"   call "%ROOT%Start-Agy-CLI.bat" %*
if /i "%LANE%"=="grok"  call "%ROOT%Start-Grok-CLI.bat" %*
if /i "%LANE%"=="codex" call "%ROOT%Start-Codex-CLI.bat" %*
exit /b %ERRORLEVEL%

:doctor
call "%ROOT%Doctor-Agents.bat"
exit /b %ERRORLEVEL%
