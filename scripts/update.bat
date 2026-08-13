@echo off
setlocal enabledelayedexpansion

:: Check for native executable first to execute update without JVM dependency
set NATIVE_EXEC=
for %%f in (obsidianscout-server-native*.exe obsidianscout-server-native*) do (
    if exist "%%f" (
        set NATIVE_EXEC=%%f
    )
)

if defined NATIVE_EXEC (
    echo [ObsidianScout Native] Running native update utility: !NATIVE_EXEC! --update
    !NATIVE_EXEC! --update %*
    exit /b %ERRORLEVEL%
)

:: Ensure GraalVM JDK 21 is available for high-performance execution
set GRAAL_JAVA=%USERPROFILE%\.graalvm\graalvm-jdk-21\bin\java.exe
if not exist "!GRAAL_JAVA!" (
    if defined GRAALVM_HOME if exist "%GRAALVM_HOME%\bin\java.exe" set GRAAL_JAVA=%GRAALVM_HOME%\bin\java.exe
)
if not exist "!GRAAL_JAVA!" (
    echo [ObsidianScout] GraalVM JDK 21 not detected. Auto-installing GraalVM for maximum performance...
    if exist install-graal.bat (
        call install-graal.bat
    ) else if exist scripts\install-graal.bat (
        call scripts\install-graal.bat
    )
    if exist "%USERPROFILE%\.graalvm\graalvm-jdk-21\bin\java.exe" set GRAAL_JAVA=%USERPROFILE%\.graalvm\graalvm-jdk-21\bin\java.exe
)

if exist "!GRAAL_JAVA!" (
    set JAVA_EXEC="!GRAAL_JAVA!"
) else (
    set JAVA_EXEC=java
)

:: Clear any previous update state
if exist .update_result del /q .update_result

:: Run the interactive Java update utility
%JAVA_EXEC% -cp obsidianscout-server.jar com.obsidianscout.utils.UpdateHelperKt %*

:: If the helper completed successfully and wrote the path of the new files
if not exist .update_result (
    echo Update failed or was cancelled.
    pause
    exit /b 1
)

set /p SRC_ROOT=<.update_result
del /q .update_result

if not exist "!SRC_ROOT!" (
    echo Error: Extracted update files not found at !SRC_ROOT!
    pause
    exit /b 1
)

echo Finalizing update (copying new files)...

:: Create backup of current files
if not exist .backup mkdir .backup
if exist obsidianscout-server.jar copy /y "obsidianscout-server.jar" ".backup\" >nul
for %%f in (obsidianscout-server-native*) do (
    if exist "%%f" copy /y "%%f" ".backup\" >nul
)
for %%s in (run.sh run.bat update.sh update.bat reset-superadmin.sh reset-superadmin.bat install-graal.sh install-graal.bat install-graal.ps1) do (
    if exist "%%s" copy /y "%%s" ".backup\" >nul
)

:: If incoming release is JAR-only, clean up old native executables
dir /b "!SRC_ROOT!\obsidianscout-server-native*" >nul 2>&1
if errorlevel 1 (
    del /q obsidianscout-server-native* >nul 2>&1
)

:: Recursively copy all updated files and subdirectories
xcopy /e /y /h /i "!SRC_ROOT!\*" "." >nul

:: Clean up temp folder
for %%i in ("!SRC_ROOT!\..") do set TEMP_DIR=%%~fi
rd /s /q "!TEMP_DIR!"
if exist .update_tmp rd /s /q .update_tmp >nul 2>&1

echo Update completed successfully!
pause


