@echo off
setlocal enabledelayedexpansion
set SCRIPT_DIR=%~dp0
set ROOT=%SCRIPT_DIR%\..
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\build_error_guard.ps1"
exit /b %ERRORLEVEL%


call :CHECK_DUP_YAML

\
            :CHECK_DUP_YAML
            set ROOT=%~dp0..
            for %%F in ("%ROOT%\src\main\resources\application.yml" "%ROOT%\app\src\main\resources\application.yml" "%ROOT%\app\resources\application.yml" "%ROOT%\demo-1\src\main\resources\application.yml") do (
              if exist "%%~fF" (
                for /f %%C in ('findstr /R /N "^[ ]*retrieval:" "%%~fF" ^| find /c ":"') do set COUNT=%%C
                if not "%COUNT%"=="" if %COUNT% gtr 1 (
                  echo [guard] duplicate 'retrieval:' keys detected in %%~fF (count=%COUNT%)
                  exit /b 1
                )
              )
            )
            goto :EOF
