@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

REM ==== ANSI colors ====
set ESC=
set RESET=%ESC%[0m
set RED=%ESC%[31m
set GREEN=%ESC%[32m
set YELLOW=%ESC%[33m
set CYAN=%ESC%[36m

echo %CYAN%======================================%RESET%
echo %CYAN%[INFO] Build release update.exe%RESET%
echo %CYAN%======================================%RESET%

REM ---- Path config ----
set OUTPUT_DIR=bin\release
set OUTPUT_EXE=%OUTPUT_DIR%\update.exe
set TARGET_EXE=..\..\hs-script-app\src\main\resources\exe\update.exe

REM ---- Build ----
echo %CYAN%[INFO] Running go build...%RESET%
go build -ldflags="-s -w" -o "%OUTPUT_EXE%"
if errorlevel 1 (
    echo %RED%[ERROR] go build failed%RESET%
    exit /b 1
)

REM ---- UPX ----
echo %CYAN%[INFO] Compressing with UPX...%RESET%
"external/upx.exe" -9 "%OUTPUT_EXE%"
if errorlevel 1 (
    echo %RED%[ERROR] UPX compression failed%RESET%
    exit /b 1
)

REM ---- Copy ----
echo %CYAN%[INFO] Copying to target path...%RESET%

if exist "%~dp0..\..\hs-script-app\src\main\resources\exe" (
    copy /Y "%OUTPUT_EXE%" "%TARGET_EXE%" >nul
    if errorlevel 1 (
        echo %RED%[ERROR] Copy failed%RESET%
        exit /b 1
    )
    echo %GREEN%[INFO] Target updated%RESET%
) else (
    echo %YELLOW%[WARN] Target directory not exist, skip copy%RESET%
)

echo %GREEN%======================================%RESET%
echo %GREEN%[SUCCESS] Release build completed%RESET%
echo %GREEN%Output: %OUTPUT_EXE%%RESET%
echo %GREEN%======================================%RESET%

endlocal

pause