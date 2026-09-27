@echo off
setlocal
cd /d "%~dp0"
title AbandonWare Meta Display Wear Launcher
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start_rag_stack.ps1" -MetaDisplay -Wear -OpenBrowser %*
set "RAG_EXIT=%ERRORLEVEL%"
echo.
if not "%RAG_EXIT%"=="0" echo [FAILED] Launcher exit code: %RAG_EXIT%. See the stage and log path above.
echo You may close this window. The wear runtime stays up; generic ForceRestart and DevWatch will not stop it.
if not defined AWX_RAG_NO_PAUSE pause
exit /b %RAG_EXIT%
