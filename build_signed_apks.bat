@echo off
setlocal EnableExtensions

set "REPO_DIR=%~dp0"
set "ANDROID_DIR=%REPO_DIR%src\android"
set "OUTPUT_DIR=%REPO_DIR%artifacts"
set "APK_SOURCE=%ANDROID_DIR%\app\build\outputs\apk\optimized\release\app-optimized-release.apk"
set "APK_OUTPUT=%OUTPUT_DIR%\Sakura-optimized-release.apk"
set "DEFAULT_KEYSTORE=%REPO_DIR%..\secrets\suyu-release.keystore"
set "KEYSTORE_PASS_FILE=%REPO_DIR%..\secrets\keystore.pass"

rem Use the workspace-local PortableGit tools for patch/git when present.
if exist "%REPO_DIR%..\tools\PortableGit\usr\bin\patch.exe" (
    set "PATH=%REPO_DIR%..\tools\PortableGit\usr\bin;%REPO_DIR%..\tools\PortableGit\cmd;%REPO_DIR%..\tools\PortableGit\mingw64\bin;%PATH%"
)

rem Use the workspace-local glslangValidator for host shader compilation when present.
if exist "%REPO_DIR%..\tools\glslang\bin\glslangValidator.exe" (
    set "GLSLANGVALIDATOR=%REPO_DIR%..\tools\glslang\bin\glslangValidator.exe"
)

rem Locate a JDK (17+) when JAVA_HOME is not already configured.
if not defined JAVA_HOME (
    for %%J in (
        "%REPO_DIR%..\tools\jdk"
        "%LOCALAPPDATA%\Programs\Android Studio\jbr"
        "C:\Program Files\Android\Android Studio\jbr"
        "C:\Program Files\Java\jdk-21"
        "C:\Program Files\Java\jdk-17"
    ) do (
        if not defined JAVA_HOME if exist "%%~J\bin\java.exe" set "JAVA_HOME=%%~J"
    )
)
if not defined JAVA_HOME (
    for /d %%J in ("C:\Program Files\Java\jdk-*") do (
        if not defined JAVA_HOME if exist "%%~J\bin\java.exe" set "JAVA_HOME=%%~J"
    )
)
if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME is not set and no JDK was found on this machine.
    echo Install a JDK 17+ or set JAVA_HOME to your JDK directory.
    exit /b 1
)
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: JAVA_HOME points to a directory without bin\java.exe: "%JAVA_HOME%"
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo Using JDK: "%JAVA_HOME%"

rem Use explicitly supplied signing values when present; otherwise use this workspace's release key.
if not defined ANDROID_KEYSTORE_FILE set "ANDROID_KEYSTORE_FILE=%DEFAULT_KEYSTORE%"
if not defined ANDROID_KEY_ALIAS set "ANDROID_KEY_ALIAS=suyu-release"

if not defined ANDROID_KEYSTORE_PASS (
    if not exist "%KEYSTORE_PASS_FILE%" (
        echo ERROR: Signing password file not found: "%KEYSTORE_PASS_FILE%"
        echo Set ANDROID_KEYSTORE_PASS in the environment or provide the password file.
        exit /b 1
    )
    <nul set /p "=Reading signing password from the local password file... "
    set /p "ANDROID_KEYSTORE_PASS="<"%KEYSTORE_PASS_FILE%"
    echo done.
)

if not exist "%ANDROID_KEYSTORE_FILE%" (
    echo ERROR: Signing keystore not found: "%ANDROID_KEYSTORE_FILE%"
    exit /b 1
)

if not exist "%ANDROID_DIR%\gradlew.bat" (
    echo ERROR: Android Gradle wrapper not found: "%ANDROID_DIR%\gradlew.bat"
    exit /b 1
)

pushd "%ANDROID_DIR%"
if errorlevel 1 (
    echo ERROR: Could not enter the Android project directory.
    exit /b 1
)

echo Building the signed Optimized Release APK only...
call gradlew.bat :app:assembleOptimizedRelease --console=plain
set "BUILD_EXIT=%ERRORLEVEL%"
popd

if not "%BUILD_EXIT%"=="0" (
    echo.
    echo Build failed with exit code %BUILD_EXIT%.
    exit /b %BUILD_EXIT%
)

if not exist "%APK_SOURCE%" (
    echo.
    echo ERROR: Optimized Release APK was not produced: "%APK_SOURCE%"
    exit /b 1
)

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
copy /Y "%APK_SOURCE%" "%APK_OUTPUT%" >nul
if errorlevel 1 (
    echo.
    echo ERROR: Could not copy the signed APK to "%OUTPUT_DIR%".
    exit /b 1
)

echo.
echo Build complete. Signed Optimized APK: "%APK_OUTPUT%"
endlocal
exit /b 0
