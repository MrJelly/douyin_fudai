@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "PROJECT_DIR=%~dp0"

rem 1. Locate JDK 17+ (respect existing JAVA_HOME first, then common installations, then PATH)
if not exist "%JAVA_HOME%\bin\java.exe" (
  for /d %%J in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*" "%ProgramFiles%\Java\jdk-17*" "%ProgramFiles%\Microsoft\jdk-17*") do (
    if exist "%%~fJ\bin\java.exe" set "JAVA_HOME=%%~fJ"
  )
)
if not exist "%JAVA_HOME%\bin\java.exe" (
  if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
  )
)
if not exist "%JAVA_HOME%\bin\java.exe" (
  set "JAVA_EXE="
  for /f "delims=" %%J in ('where java.exe 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%J"
  if defined JAVA_EXE (
    for %%J in ("!JAVA_EXE!") do set "JAVA_BIN=%%~dpJ"
    for %%J in ("!JAVA_BIN!..") do set "JAVA_HOME=%%~fJ"
  )
)
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JDK 17 not found. Please install JDK 17 or set JAVA_HOME.
  pause
  exit /b 1
)

rem 2. Locate Android SDK
if not exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
if not exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" (
  echo [ERROR] Android SDK not found. Please set ANDROID_SDK_ROOT or ANDROID_HOME.
  pause
  exit /b 1
)
set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
set "PATH=%JAVA_HOME%\bin;%ANDROID_SDK_ROOT%\platform-tools;%PATH%"

rem 3. Locate Gradle Wrapper or system Gradle
set "GRADLE=%PROJECT_DIR%gradlew.bat"
if not exist "%GRADLE%" (
  for /f "delims=" %%G in ('where gradle.bat 2^>nul') do if not defined GRADLE_FOUND set "GRADLE_FOUND=%%G"
  set "GRADLE=!GRADLE_FOUND!"
)
if not exist "%GRADLE%" (
  echo [ERROR] Gradle wrapper (gradlew.bat) or system Gradle not found.
  pause
  exit /b 1
)

cd /d "%PROJECT_DIR%"
echo JDK: %JAVA_HOME%
echo SDK: %ANDROID_SDK_ROOT%
echo Building signed APK...
call "%GRADLE%" :app:assembleDebug --no-daemon
if errorlevel 1 (
  echo.
  echo [ERROR] APK build failed.
  pause
  exit /b 1
)

echo.
echo [OK] Signed APK build succeeded:
echo %PROJECT_DIR%app\build\outputs\apk\debug\app-debug.apk
pause
