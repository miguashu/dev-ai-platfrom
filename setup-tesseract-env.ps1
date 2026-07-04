# Tesseract 环境变量配置脚本（以管理员身份运行）
$tesseractPath = "D:\tools\Tesseract-OCR"

# 1. 添加到 PATH
$currentPath = [Environment]::GetEnvironmentVariable("Path", "Machine")
if ($currentPath -notlike "*$tesseractPath*") {
    [Environment]::SetEnvironmentVariable("Path", "$currentPath;$tesseractPath", "Machine")
    Write-Host "✅ PATH 已添加: $tesseractPath" -ForegroundColor Green
} else {
    Write-Host "⏭  PATH 已存在，跳过" -ForegroundColor Yellow
}

# 2. 设置 TESSDATA_PREFIX
$tessdataPath = "$tesseractPath\tessdata"
[Environment]::SetEnvironmentVariable("TESSDATA_PREFIX", $tessdataPath, "Machine")
Write-Host "✅ TESSDATA_PREFIX 已设置: $tessdataPath" -ForegroundColor Green

Write-Host ""
Write-Host "⚠  请重启终端或 IDE 使环境变量生效" -ForegroundColor Cyan
