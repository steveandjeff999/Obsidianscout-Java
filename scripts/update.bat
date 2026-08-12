@echo off
setlocal enabledelayedexpansion

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
%JAVA_EXEC% -cp obsidianscout-server.jar com.obsidianscout.utils.UpdateHelperKt

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
for %%f in (obsidianscout-server-native*.exe) do (
    if exist "%%f" copy /y "%%f" ".backup\" >nul
)
for %%s in (run.sh run.bat update.sh update.bat reset-superadmin.sh reset-superadmin.bat install-graal.sh install-graal.bat install-graal.ps1) do (
    if exist "%%s" copy /y "%%s" ".backup\" >nul
)

:: Copy JAR if present
if exist "!SRC_ROOT!\obsidianscout-server.jar" copy /y "!SRC_ROOT!\obsidianscout-server.jar" "." >nul

:: Copy Native Executables and DLLs if present (or remove old native binaries if incoming release is JAR-only)
dir /b "!SRC_ROOT!\obsidianscout-server-native*" >nul 2>&1
if errorlevel 1 (
    del /q obsidianscout-server-native* >nul 2>&1
) else (
    for %%f in ("!SRC_ROOT!\obsidianscout-server-native*.exe") do (
        if exist "%%f" copy /y "%%f" "." >nul
    )
)
for %%d in ("!SRC_ROOT!\*.dll") do (
    if exist "%%d" copy /y "%%d" "." >nul
)

:: Copy scripts
if exist "!SRC_ROOT!\run.sh" copy /y "!SRC_ROOT!\run.sh" "." >nul
if exist "!SRC_ROOT!\run.bat" copy /y "!SRC_ROOT!\run.bat" "." >nul
if exist "!SRC_ROOT!\reset-superadmin.sh" copy /y "!SRC_ROOT!\reset-superadmin.sh" "." >nul
if exist "!SRC_ROOT!\reset-superadmin.bat" copy /y "!SRC_ROOT!\reset-superadmin.bat" "." >nul
if exist "!SRC_ROOT!\update.sh" copy /y "!SRC_ROOT!\update.sh" "." >nul
if exist "!SRC_ROOT!\update.bat" copy /y "!SRC_ROOT!\update.bat" "." >nul
if exist "!SRC_ROOT!\install-graal.sh" copy /y "!SRC_ROOT!\install-graal.sh" "." >nul
if exist "!SRC_ROOT!\install-graal.bat" copy /y "!SRC_ROOT!\install-graal.bat" "." >nul
if exist "!SRC_ROOT!\install-graal.ps1" copy /y "!SRC_ROOT!\install-graal.ps1" "." >nul

:: Clean up temp folder (parent of SRC_ROOT since it was extracted inside temp directory)
for %%i in ("!SRC_ROOT!\..") do set TEMP_DIR=%%~fi
rd /s /q "!TEMP_DIR!"
if exist .update_tmp rd /s /q .update_tmp >nul 2>&1

echo Update completed successfully!
pause

