@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "PROJECT_DIR=%~dp0"
set "APK=%PROJECT_DIR%app\build\outputs\apk\debug\app-debug.apk"

set "SDK=%ANDROID_SDK_ROOT%"
if not exist "%SDK%\platform-tools\adb.exe" set "SDK=%ANDROID_HOME%"
if not exist "%SDK%\platform-tools\adb.exe" set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
if not exist "%ADB%" for /f "delims=" %%A in ('where adb.exe 2^>nul') do if not defined ADB_FOUND set "ADB_FOUND=%%A"
if not exist "%ADB%" set "ADB=!ADB_FOUND!"

if not exist "%ADB%" (
  echo [ERROR] ADB not found. Please set ANDROID_SDK_ROOT or add adb to PATH.
  pause
  exit /b 1
)
if not exist "%APK%" (
  echo [ERROR] APK not found. Run build-apk.bat first.
  pause
  exit /b 1
)

"%ADB%" start-server >nul
echo Waiting for Android device...
"%ADB%" get-state 1>nul 2>nul
if errorlevel 1 (
  echo.
  "%ADB%" devices -l
  echo [ERROR] No connected Android device found.
  echo 1. Connect the USB cable and unlock the phone.
  echo 2. Enable USB debugging in Developer options.
  echo 3. Accept the "Allow USB debugging" prompt on the phone.
  echo 4. If the phone shows offline, unplug and reconnect it.
  pause
  exit /b 1
)

if /i "%~1"=="-Clean" (
  echo Removing old app and data...
  "%ADB%" uninstall com.jelly.fudai
  "%ADB%" install "%APK%"
) else (
  "%ADB%" install -r "%APK%"
)

if errorlevel 1 (
  echo.
  echo [ERROR] APK install failed. If signature changed, run:
  echo   install-apk.bat -Clean
  pause
  exit /b 1
)

echo.
echo Granting accessibility auto-recovery permission...
"%ADB%" shell pm grant com.jelly.fudai android.permission.WRITE_SECURE_SETTINGS
if errorlevel 1 (
  echo [WARN] Optional WRITE_SECURE_SETTINGS permission was not granted; installation itself succeeded.
)

set "SERVICES="
for /f "usebackq delims=" %%S in (`"%ADB%" shell settings get secure enabled_accessibility_services`) do set "SERVICES=%%S"
if /i "!SERVICES!"=="null" set "SERVICES="
echo !SERVICES! | findstr /i /c:"com.jelly.fudai/.FudaiAccessibilityService" >nul
if errorlevel 1 (
  if defined SERVICES (set "SERVICES=!SERVICES!:com.jelly.fudai/.FudaiAccessibilityService") else set "SERVICES=com.jelly.fudai/.FudaiAccessibilityService"
)
"%ADB%" shell settings put secure enabled_accessibility_services "!SERVICES!" 1>nul 2>nul
"%ADB%" shell settings put secure accessibility_enabled 1 1>nul 2>nul
"%ADB%" shell am start -n com.jelly.fudai/.MainActivity >nul

echo.
echo [OK] APK installed and launched.
echo If accessibility was not enabled automatically, tap "2. 开启无障碍服务" in the app.
pause
