@echo off
echo 正在配置 Tesseract 环境变量...
setx TESSDATA_PREFIX "D:\ruanjian\tesseract\tessdata" /M
setx Path "%%Path%%;D:\ruanjian\tesseract" /M
echo.
echo 配置完成！请重启终端和 IDE 生效。
pause