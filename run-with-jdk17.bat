@echo off
REM ========================================
REM Dev AI Platform - Java 17 环境启动脚本
REM ========================================

:menu
cls
echo ========================================
echo    Dev AI Platform - 启动菜单
echo ========================================
echo.
echo 1. 编译项目 (mvn compile)
echo 2. 打包项目 (mvn package)
echo 3. 运行应用 (mvn spring-boot:run)
echo 4. 清理项目 (mvn clean)
echo 5. 检查 Java 版本
echo 0. 退出
echo.
echo ========================================
set /p choice="请选择操作 [0-5]: "

if "%choice%"=="1" goto compile
if "%choice%"=="2" goto package
if "%choice%"=="3" goto run
if "%choice%"=="4" goto clean
if "%choice%"=="5" goto checkjava
if "%choice%"=="0" exit

echo 无效选择，请重试
pause
goto menu

:compile
echo.
echo [设置] Java 17 环境...
set JAVA_HOME=D:\ruanjian\IntelliJIDEA201914\IntelliJIDEA2023.2.4\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
echo [编译] 开始编译项目...
mvn compile
echo.
echo 编译完成！
pause
goto menu

:package
echo.
echo [设置] Java 17 环境...
set JAVA_HOME=D:\ruanjian\IntelliJIDEA201914\IntelliJIDEA2023.2.4\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
echo [打包] 开始打包项目...
mvn clean package -DskipTests
echo.
echo 打包完成！
pause
goto menu

:run
echo.
echo [设置] Java 17 环境...
set JAVA_HOME=D:\ruanjian\IntelliJIDEA201914\IntelliJIDEA2023.2.4\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
echo [验证] Java 版本...
java -version
echo.
echo ========================================
echo [启动] Dev AI Platform
echo ========================================
echo 提示：按 Ctrl+C 可停止应用
echo 访问地址：http://localhost:8081/index.html
echo ========================================
echo.
mvn spring-boot:run
goto menu

:clean
echo.
echo [清理] 开始清理项目...
mvn clean
echo.
echo 清理完成！
pause
goto menu

:checkjava
echo.
echo [设置] Java 17 环境...
set JAVA_HOME=D:\ruanjian\IntelliJIDEA201914\IntelliJIDEA2023.2.4\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
echo [Java版本]
java -version
echo.
echo [JAVA_HOME]
echo %JAVA_HOME%
echo.
pause
goto menu
