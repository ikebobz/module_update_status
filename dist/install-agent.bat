@echo off
setlocal EnableExtensions
REM ==================================================================
REM  Installs the Module Update Agent as a Windows scheduled task that
REM  runs as SYSTEM every 30 minutes and at startup. No staff action is
REM  needed after this. Run as Administrator from the extracted folder.
REM ==================================================================
set "INSTALL_DIR=C:\ModuleUpdater"
set "TASK=Module Update Agent"
set "EVERY_MINUTES=30"

net session >nul 2>&1
if errorlevel 1 (
    echo Please right-click install-agent.bat and choose "Run as administrator".
    pause
    exit /b 1
)

REM ---- Find Java (the same JRE the application uses is fine, Java 8+) ----
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for /f "delims=" %%J in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%J"
if not defined JAVA_EXE (
    echo [ERROR] java.exe not found. Set JAVA_HOME or add Java to PATH, then re-run.
    pause
    exit /b 1
)
echo Using Java: %JAVA_EXE%

REM ---- Copy files (an existing updater.properties is never overwritten) ----
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
copy /Y "%~dp0module-updater.jar" "%INSTALL_DIR%\" >nul || goto :copyfail
copy /Y "%~dp0release-public.pem" "%INSTALL_DIR%\" >nul || goto :copyfail
copy /Y "%~dp0run-now.bat" "%INSTALL_DIR%\" >nul
copy /Y "%~dp0check.bat" "%INSTALL_DIR%\" >nul
copy /Y "%~dp0status.bat" "%INSTALL_DIR%\" >nul
copy /Y "%~dp0uninstall-agent.bat" "%INSTALL_DIR%\" >nul
if not exist "%INSTALL_DIR%\updater.properties" copy "%~dp0updater.properties" "%INSTALL_DIR%\" >nul

REM ---- Wrapper the scheduled task calls (written line by line: safe with "Program Files (x86)") ----
set "W=%INSTALL_DIR%\updater-task.bat"
> "%W%" echo @echo off
>>"%W%" echo cd /d "%INSTALL_DIR%"
>>"%W%" echo set "CMD=%%~1"
>>"%W%" echo if "%%CMD%%"=="" set "CMD=once"
>>"%W%" echo "%JAVA_EXE%" -jar "%INSTALL_DIR%\module-updater.jar" %%CMD%%

REM ---- Restrict the folder to SYSTEM + Administrators (config holds a password) ----
icacls "%INSTALL_DIR%" /inheritance:r /grant:r *S-1-5-18:(OI)(CI)F *S-1-5-32-544:(OI)(CI)F >nul

REM ---- Scheduled tasks ----
schtasks /Create /F /TN "%TASK%" /SC MINUTE /MO %EVERY_MINUTES% /RU SYSTEM /RL HIGHEST /TR "\"%W%\"" >nul || goto :taskfail
schtasks /Create /F /TN "%TASK% (startup)" /SC ONSTART /DELAY 0005:00 /RU SYSTEM /RL HIGHEST /TR "\"%W%\"" >nul || goto :taskfail

echo.
echo Installed to %INSTALL_DIR%
echo Scheduled: every %EVERY_MINUTES% minutes and 5 minutes after startup (runs as SYSTEM).
echo.
echo NEXT: edit %INSTALL_DIR%\updater.properties (facility.id, app.auth.password, ...)
echo       then run %INSTALL_DIR%\check.bat to confirm it can see the application.
pause
exit /b 0

:copyfail
echo [ERROR] Could not copy files to %INSTALL_DIR%. Is module-updater.jar / release-public.pem next to this script?
pause
exit /b 1

:taskfail
echo [ERROR] Could not create the scheduled task.
pause
exit /b 1
