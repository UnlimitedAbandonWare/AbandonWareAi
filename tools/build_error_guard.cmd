@echo off
setlocal
python "%~dp0\build_error_guard.py" %*
endlocal
