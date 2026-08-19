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
echo %CYAN%[INFO] Build debug update.exe%RESET%
echo %CYAN%======================================%RESET%

REM ---- Path config ----
set OUTPUT_DIR=bin\debug
set OUTPUT_EXE=%OUTPUT_DIR%\update.exe

REM ---- Ensure output dir ----
if not exist "%OUTPUT_DIR%" (
    echo %YELLOW%[WARN] Output directory not exist, creating...%RESET%
    mkdir "%OUTPUT_DIR%"
)

REM ---- Build ----
echo %CYAN%[INFO] Running go build (debug)...%RESET%
go build -gcflags="all=-N -l" -o "%OUTPUT_EXE%"
if errorlevel 1 (
    echo %RED%[ERROR] go build failed%RESET%
    exit /b 1
)

echo %GREEN%======================================%RESET%
echo %GREEN%[SUCCESS] Debug build completed%RESET%
echo %GREEN%Output: %OUTPUT_EXE%%RESET%
echo %GREEN%======================================%RESET%

endlocal

pause
