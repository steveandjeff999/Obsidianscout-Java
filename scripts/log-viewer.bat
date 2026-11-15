@echo off
rem Opens the viewer for the server's console log (the logs\ folder next to this file).
rem Another folder can be given as the first argument.
start "" powershell -NoProfile -ExecutionPolicy Bypass -STA -WindowStyle Hidden -File "%~dp0log-viewer.ps1" %*
