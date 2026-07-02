@echo off
chcp 65001 >nul 2>&1
REM file_create.bat - 创建目录（自动创建多级目录）
REM 用法: file_create.bat <目录路径>

setlocal enabledelayedexpansion

set "TARGET=%~1"
if "%TARGET%"=="" (
    echo [ERROR] 请提供目录路径
    echo 用法: file_create.bat ^<目录路径^>
    exit /b 1
)

if exist "%TARGET%" (
    echo [INFO] 目录已存在: %TARGET%
    exit /b 0
)

mkdir "%TARGET%" 2>nul
if %ERRORLEVEL% equ 0 (
    echo [OK] 目录创建成功: %TARGET%
) else (
    echo [ERROR] 目录创建失败: %TARGET%
    exit /b 1
)
