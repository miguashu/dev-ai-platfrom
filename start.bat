@echo off
chcp 65001 >nul
echo ========================================
echo   Dev AI Platform - 快速启动
echo ========================================
echo.

echo [1/3] 检查Java环境...
java -version
if errorlevel 1 (
    echo ❌ 未检测到Java环境，请先安装JDK 17+
    pause
    exit /b 1
)
echo ✅ Java环境正常
echo.

echo [2/3] 检查Maven环境...
mvn -version
if errorlevel 1 (
    echo ❌ 未检测到Maven，请先安装Maven
    pause
    exit /b 1
)
echo ✅ Maven环境正常
echo.

echo [3/3] 启动Spring Boot应用...
echo.
echo ========================================
echo   正在启动服务，请稍候...
echo ========================================
echo.
echo 提示：看到 "=== Dev AI Platform 启动成功 ===" 后即可访问
echo.
echo 访问地址：http://localhost:8081/index.html
echo.
echo ========================================
echo.

cd /d "%~dp0"
mvn spring-boot:run

pause
