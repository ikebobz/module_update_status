@echo off
REM Removes the scheduled tasks. Files in the install folder (logs, cache, state) are left in place.
schtasks /Delete /F /TN "Module Update Agent" 2>nul
schtasks /Delete /F /TN "Module Update Agent (startup)" 2>nul
echo Scheduled tasks removed. You can now delete the install folder if no longer needed.
pause
