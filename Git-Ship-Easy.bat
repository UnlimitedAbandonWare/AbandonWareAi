@echo off
REM ============================================================
REM  Git-Ship-Easy.bat
REM  Double-click helper -> scripts\git_ship_easy.py
REM  Interactive Korean menu over git_ship.py (Git-Ship.bat engine).
REM  ASCII-only on purpose: the .py prints the Korean text.
REM ============================================================
chcp 65001 >nul
setlocal
set "PROJECT_ROOT=C:\AbandonWare\demo-1\demo-1\src"
cd /d "%PROJECT_ROOT%"
if defined AWX_GIT_EXE (
  set "GITEXE=%AWX_GIT_EXE%"
) else if exist "F:\git\cmd\git.exe" (
  set "GITEXE=F:\git\cmd\git.exe"
) else (
  set "GITEXE=git"
)
where py >nul 2>nul
if %ERRORLEVEL%==0 (
  py -3 -B "%PROJECT_ROOT%\scripts\git_ship_easy.py" --git-exe "%GITEXE%" %*
) else (
  python -B "%PROJECT_ROOT%\scripts\git_ship_easy.py" --git-exe "%GITEXE%" %*
)
set "EXITCODE=%ERRORLEVEL%"
if "%AWX_RAG_NO_PAUSE%"=="" pause
exit /b %EXITCODE%
