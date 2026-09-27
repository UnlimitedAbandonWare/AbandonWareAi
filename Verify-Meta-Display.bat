@echo off
setlocal
cd /d "%~dp0"
title AbandonWare Meta Display Verify (wear)
set "RAG_VRF_PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
"%RAG_VRF_PS%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\debug_rag_stack.ps1" -Role wear -Action verify -WithCompile %*
set "RAG_VRF_EXIT=%ERRORLEVEL%"
echo.
if not "%RAG_VRF_EXIT%"=="0" echo [RESULT] Meta Display verify exit code: %RAG_VRF_EXIT% ^(0=verified, 3=not-running, 6=checks-failed, 1=tool error^). See [DBG ...] output above and var\debug\ logs.
if not defined AWX_RAG_NO_PAUSE pause
exit /b %RAG_VRF_EXIT%
