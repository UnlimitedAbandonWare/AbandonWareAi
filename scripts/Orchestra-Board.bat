@echo off
rem Orchestra-Board.bat — write var\orchestra\BOARD.md and print it.
rem Read-only. Network 0. Run from project root.
setlocal
cd /d "%~dp0\.."
python -B scripts\orchestra_board.py --md
exit /b %errorlevel%
