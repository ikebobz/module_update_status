@echo off
REM Runs one update cycle immediately (still respects the install window unless a release is urgent).
call "%~dp0updater-task.bat" once
pause
