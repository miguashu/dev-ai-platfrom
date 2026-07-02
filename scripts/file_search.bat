@echo off
chcp 65001 >nul 2>&1
REM file_search.bat - 在指定目录下搜索文件名匹配关键字的文件
REM 用法: file_search.bat <目录路径> <关键字>

setlocal enabledelayedexpansion

set "TARGET=%~1"
set "KEYWORD=%~2"

if "%TARGET%"=="" set "TARGET=%CD%"
if "%KEYWORD%"=="" (
    echo [ERROR] 请提供搜索关键字
    echo 用法: file_search.bat ^<目录路径^> ^<关键字^>
    exit /b 1
)

if not exist "%TARGET%" (
    echo [ERROR] 目录不存在: %TARGET%
    exit /b 1
)

echo === 搜索: %TARGET% 关键字: %KEYWORD% ===
echo.

set COUNT=0
for /r "%TARGET%" %%f in ("*%KEYWORD%*") do (
    echo %%f
    set /a COUNT+=1
)

echo.
echo === 找到 %COUNT% 个匹配文件 ===
