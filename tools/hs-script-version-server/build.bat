@echo off
echo Building hs-script-version-server...

go build -ldflags="-s -w" -o hs-script-version-server.exe cmd/server/main.go

if %errorlevel% == 0 (
    echo Build successful: hs-script-version-server.exe
) else (
    echo Build failed!
    exit /b 1
)
