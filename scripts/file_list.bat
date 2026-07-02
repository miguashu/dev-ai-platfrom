@echo off
chcp 65001 >nul 2>&1
REM file_list.bat - 列出指定目录内容
REM 用法: file_list.bat [目录路径]

setlocal enabledelayedexpansion

set "TARGET=%~1"
if "%TARGET%"=="" set "TARGET=%CD%"

if not exist "%TARGET%" (
    echo [ERROR] 目录不存在: %TARGET%
    exit /b 1
)

echo === 目录内容: %TARGET% ===
echo.

for /f "delims=" %%i in ('dir /b /a "%TARGET%" 2^>nul') do (
    set "ATTR=%TARGET%\%%i"
    if exist "!ATTR!\*" (
        echo [DIR]  %%i
    ) else (
        for %%s in ("!ATTR!") do echo [FILE] %%i  (%%~zs bytes^)
    )
)

echo.
echo === 列出完毕 ===
