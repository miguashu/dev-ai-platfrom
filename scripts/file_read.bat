@echo off
chcp 65001 >nul 2>&1
REM file_read.bat - 读取文件内容
REM 用法: file_read.bat <文件路径>

setlocal enabledelayedexpansion

set "TARGET=%~1"
if "%TARGET%"=="" (
    echo [ERROR] 请提供文件路径
    echo 用法: file_read.bat ^<文件路径^>
    exit /b 1
)

if not exist "%TARGET%" (
    echo [ERROR] 文件不存在: %TARGET%
    exit /b 1
)

echo === 文件内容: %TARGET% ===
echo.
type "%TARGET%"
echo.
echo === 读取完毕 ===
