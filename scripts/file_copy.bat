@echo off
chcp 65001 >nul 2>&1
REM file_copy.bat - 复制文件
REM 用法: file_copy.bat <源文件> <目标路径>

setlocal enabledelayedexpansion

set "SRC=%~1"
set "DST=%~2"

if "%SRC%"=="" (
    echo [ERROR] 请提供源文件路径
    echo 用法: file_copy.bat ^<源文件^> ^<目标路径^>
    exit /b 1
)
if "%DST%"=="" (
    echo [ERROR] 请提供目标路径
    exit /b 1
)

if not exist "%SRC%" (
    echo [ERROR] 源文件不存在: %SRC%
    exit /b 1
)

REM 如果目标是目录，确保以\结尾
if exist "%DST%\*" (
    copy "%SRC%" "%DST%\" /y >nul
) else (
    REM 确保目标目录存在
    for %%i in ("%DST%\..") do (
        if not exist "%%~i" mkdir "%%~i" 2>nul
    )
    copy "%SRC%" "%DST%" /y >nul
)

if %ERRORLEVEL% equ 0 (
    echo [OK] 复制成功: %SRC% -^> %DST%
) else (
    echo [ERROR] 复制失败
    exit /b 1
)
