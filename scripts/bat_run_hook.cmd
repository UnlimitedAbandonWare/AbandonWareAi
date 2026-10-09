@echo off
rem awx.bat_run hook (devin-bat-run-ledger-58a6f2c1).
rem   call "%~dp0bat_run_hook.cmd" begin "%~nx0" <args...>
rem   call "%~dp0bat_run_hook.cmd" end   "%~nx0" <exitcode>
rem Always exits 0 and never alters the caller's ERRORLEVEL contract:
rem callers must capture their exit code into a variable BEFORE calling "end".
rem Silently no-ops when python or the ledger is missing.
set "AWX_HOOK_PY=%~dp0bat_run_ledger.py"
if not exist "%AWX_HOOK_PY%" exit /b 0
if /i "%~1"=="begin" goto :hook_begin
if /i "%~1"=="end" goto :hook_end
exit /b 0

:hook_begin
set "AWX_BATRUN_BAT=%~2"
set "AWX_BATRUN_ARGS="
:hook_begin_args
if "%~3"=="" goto :hook_begin_go
set "AWX_BATRUN_ARGS=%AWX_BATRUN_ARGS% %~3"
shift
goto :hook_begin_args
:hook_begin_go
for /f "tokens=1,2" %%a in ('python -B "%AWX_HOOK_PY%" newrun 2^>nul') do (
  set "AWX_BATRUN_ID=%%a"
  set "AWX_BATRUN_T0=%%b"
)
if not defined AWX_BATRUN_ID exit /b 0
python -B "%AWX_HOOK_PY%" append --phase begin --bat "%AWX_BATRUN_BAT%" --runkey "%AWX_BATRUN_ID%" --args "%AWX_BATRUN_ARGS%" >nul 2>nul
exit /b 0

:hook_end
if not defined AWX_BATRUN_ID exit /b 0
python -B "%AWX_HOOK_PY%" append --phase end --bat "%~2" --runkey "%AWX_BATRUN_ID%" --exit "%~3" --t0 "%AWX_BATRUN_T0%" >nul 2>nul
set "AWX_BATRUN_ID="
set "AWX_BATRUN_T0="
set "AWX_BATRUN_ARGS="
set "AWX_BATRUN_BAT="
exit /b 0
