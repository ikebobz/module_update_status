@echo off
setlocal
REM ==================================================================
REM  Release team: sign the release list after putting the new module
REM  JARs in .\jars and editing manifest.json (name, version, url only).
REM  sha256, size, sequence and published_at are filled in automatically.
REM ==================================================================
cd /d "%~dp0"
if not exist release-private.pem (
    echo release-private.pem not found. First time only, run:
    echo   java -jar module-updater.jar keygen release-private.pem release-public.pem
    echo and ship release-public.pem with the facility installer.
    pause
    exit /b 1
)
java -jar module-updater.jar sign release-private.pem manifest.json jars || (pause & exit /b 1)

REM Offline bundle for USB sticks: copy this folder to the root of a USB drive
if exist usb-bundle\module-updates rmdir /s /q usb-bundle\module-updates
mkdir usb-bundle\module-updates
copy /Y manifest.json usb-bundle\module-updates\ >nul
copy /Y manifest.json.sig usb-bundle\module-updates\ >nul
copy /Y jars\*.jar usb-bundle\module-updates\ >nul

echo.
echo Upload manifest.json, manifest.json.sig and jars\*.jar to the folder behind manifest.url.
echo For offline sites, copy usb-bundle\module-updates to the root of a USB stick.
pause
