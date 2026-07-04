$installer = "D:\download\tesseract-ocr-w64-setup-5.4.0.20240606.exe"
$targetDir = "D:\tools\Tesseract-OCR"

Write-Host "正在静默安装 Tesseract OCR 到: $targetDir" -ForegroundColor Cyan

# 创建目标目录
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

# 静默安装
Start-Process -FilePath $installer -ArgumentList "/VERYSILENT /DIR=`"$targetDir`" /ALLUSERS" -Wait -NoNewWindow

Write-Host "✅ 安装完成" -ForegroundColor Green
