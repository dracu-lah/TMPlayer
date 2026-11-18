@echo off
rem dockur/windows runs this once, at the end of the unattended install (README.md).
powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\OEM\setup.ps1 > C:\OEM\setup.log 2>&1
