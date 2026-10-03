@echo off
REM  cross-check.cmd <scope-path> [--cli agy] [--model m] [--test-log file]
REM  3-way plan-mode cross-verification (positive -> negative -> judge).
REM  Makes 3 live agent calls per run -- respects the caller's live-call budget.
setlocal
node "%~dp0cross-check.mjs" %*
exit /b %ERRORLEVEL%
