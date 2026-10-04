@echo off
REM ============================================================
REM  Git-Guard-Fast.bat
REM  Fast read-only secret-scan lane:
REM    scan    -> scripts\git_guard_fast.py   (default --staged)
REM    explain -> scripts\git_guard_explain.py (needs a scan JSON file or -)
REM    facts   -> scripts\brief_fact_check.py (needs brief txt files)
REM  Read-only on the repo index/tree. No write flags are defaulted here.
REM ============================================================
setlocal
set "PROJECT_ROOT=C:\AbandonWare\demo-1\demo-1\src"
cd /d "%PROJECT_ROOT%"
where py >nul 2>&1
if %ERRORLEVEL%==0 (set "PY=py -3") else (set "PY=python")
set "SUB=%~1"
shift
set "ARGS="
:collect
if "%~1"=="" goto dispatch
set "ARGS=%ARGS% "%~1""
shift
goto collect
:dispatch
if /i "%SUB%"=="scan" goto scan
if /i "%SUB%"=="explain" goto explain
if /i "%SUB%"=="facts" goto facts
echo usage: Git-Guard-Fast.bat scan [--staged ^| --diff A B ^| --paths-file f] ^| explain ^<scan.json^|-^> [--staged ^| --diff A B ^| --all] ^| facts ^<brief.txt...^>
exit /b 2
:scan
%PY% -B scripts\git_guard_fast.py %ARGS%
set "EXITCODE=%ERRORLEVEL%"
goto done
:explain
%PY% -B scripts\git_guard_explain.py %ARGS%
set "EXITCODE=%ERRORLEVEL%"
goto done
:facts
%PY% -B scripts\brief_fact_check.py %ARGS%
set "EXITCODE=%ERRORLEVEL%"
goto done
:done
if "%AWX_RAG_NO_PAUSE%"=="" pause
exit /b %EXITCODE%
