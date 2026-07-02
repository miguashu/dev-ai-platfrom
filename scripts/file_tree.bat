@echo off
chcp 65001 >nul 2>&1
REM file_tree.bat - 显示目录树结构（最多3层）
REM 用法: file_tree.bat [目录路径]

setlocal enabledelayedexpansion

set "TARGET=%~1"
if "%TARGET%"=="" set "TARGET=%CD%"

if not exist "%TARGET%" (
    echo [ERROR] 目录不存在: %TARGET%
    exit /b 1
)

echo === 目录树: %TARGET% ===
echo.
tree /f /a "%TARGET%" 2>nul | findstr /v /c:"File(s)" | findstr /v /c:"Dir(s)"
echo.
echo === 显示完毕 ===
