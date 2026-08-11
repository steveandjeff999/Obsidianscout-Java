@echo off
setlocal enabledelayedexpansion

:: Clear any previous update state
if exist .update_result del /q .update_result

:: Run the interactive Java update utility
java -cp obsidianscout-server.jar com.obsidianscout.utils.UpdateHelperKt

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
for %%s in (run.sh run.bat update.sh update.bat reset-superadmin.sh reset-superadmin.bat) do (
    if exist "%%s" copy /y "%%s" ".backup\" >nul
)

:: Copy JAR
copy /y "!SRC_ROOT!\obsidianscout-server.jar" "." >nul

:: Copy scripts
if exist "!SRC_ROOT!\run.sh" copy /y "!SRC_ROOT!\run.sh" "." >nul
if exist "!SRC_ROOT!\run.bat" copy /y "!SRC_ROOT!\run.bat" "." >nul
if exist "!SRC_ROOT!\reset-superadmin.sh" copy /y "!SRC_ROOT!\reset-superadmin.sh" "." >nul
if exist "!SRC_ROOT!\reset-superadmin.bat" copy /y "!SRC_ROOT!\reset-superadmin.bat" "." >nul
if exist "!SRC_ROOT!\update.sh" copy /y "!SRC_ROOT!\update.sh" "." >nul
if exist "!SRC_ROOT!\update.bat" copy /y "!SRC_ROOT!\update.bat" "." >nul

:: Clean up temp folder (parent of SRC_ROOT since it was extracted inside temp directory)
for %%i in ("!SRC_ROOT!\..") do set TEMP_DIR=%%~fi
rd /s /q "!TEMP_DIR!"
if exist .update_tmp rd /s /q .update_tmp >nul 2>&1

echo Update completed successfully!
pause
