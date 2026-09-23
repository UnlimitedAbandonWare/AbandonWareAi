@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-https-tunnel.ps1" -Action start %*
