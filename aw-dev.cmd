@echo off
rem aw-dev: repo-local dev tool dispatcher. ASCII only (codepage-safe).
rem All logic lives in tools\local_dev\aw_dev.py; this wrapper only finds
rem an interpreter and forwards argv + the exit code unchanged.
setlocal EnableExtensions EnableDelayedExpansion
set "ROOT=%~dp0"
set "PY="

rem Resolution order: AWX_PYTHON > bindings.local.json > uv cpython > PATH.
if defined AWX_PYTHON set "PY=%AWX_PYTHON%"

if not defined PY (
  rem bindings.local.json is JSON; let any probe interpreter resolve it.
  set "PROBE="
  if exist "%USERPROFILE%\.local\bin\python3.11.exe" set "PROBE=%USERPROFILE%\.local\bin\python3.11.exe"
  if not defined PROBE (
    for /f "delims=" %%i in ('where python 2^>nul') do (if not defined PROBE set "PROBE=%%i")
  )
  if defined PROBE (
    for /f "usebackq delims=" %%i in (`"!PROBE!" -B "!ROOT!tools\local_dev\aw_dev.py" resolve-python 2^>nul`) do (
      if not defined PY set "PY=%%i"
    )
  )
)

if not defined PY if exist "%USERPROFILE%\.local\bin\python3.11.exe" set "PY=%USERPROFILE%\.local\bin\python3.11.exe"
if not defined PY (
  for /f "delims=" %%i in ('where python 2^>nul') do (if not defined PY set "PY=%%i")
)
if not defined PY (
  echo aw-dev: no Python interpreter found; set AWX_PYTHON or run bootstrap 1>&2
  exit /b 10
)

"%PY%" -B "%ROOT%tools\local_dev\aw_dev.py" %*
exit /b %ERRORLEVEL%
