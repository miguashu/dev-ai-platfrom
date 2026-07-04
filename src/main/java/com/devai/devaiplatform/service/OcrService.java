package com.devai.devaiplatform.service;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * OCR 图片文字识别服务
 * 用于处理扫描版PDF、图片PDF等无法直接提取文本的文档
 * 
 * 核心功能：
 * 1. 检测PDF是否包含可提取文本
 * 2. 对图片型PDF进行OCR识别
 * 3. 提取图片中的文字内容
 */
@Service
public class OcrService {

    private static final String LANGUAGE = "chi_sim+eng"; // 简体中文 + 英文
    
    private final Tesseract tesseract;

    public OcrService(@Value("${tesseract.data-path:./tessdata}") String tessdataPath) {
        this.tesseract = new Tesseract();
        
        // 配置OCR参数
        try {
            // 设置 tessdata 路径（从 application.properties 读取 tesseract.data-path）
            System.out.println("[OCR服务] 使用 tessdata 路径: " + tessdataPath);
            tesseract.setDatapath(tessdataPath);
            tesseract.setLanguage(LANGUAGE);
            
            // OCR性能优化配置
            tesseract.setPageSegMode(3); // 自动页面分割
            tesseract.setOcrEngineMode(1); // LSTM OCR引擎模式
            
            System.out.println("[OCR服务] 初始化完成，支持中英文识别");
        } catch (Exception e) {
            System.err.println("[OCR服务] 初始化警告: " + e.getMessage());
            System.err.println("[OCR服务] 请确保已下载 tessdata 文件到 ./tessdata 目录");
        }
    }

    /**
     * 智能提取PDF文本
     * 优先尝试直接提取，如果失败则使用OCR
     * 
     * @param pdfPath PDF文件路径
     * @return 提取的文本内容
     */
    public String extractTextFromPdf(String pdfPath) {
        System.out.println("\n[OCR处理] 开始处理PDF: " + pdfPath);
        
        try {
            File pdfFile = new File(pdfPath);
            
            // 第一步：尝试直接提取文本（适用于文本型PDF）
            String directText = tryDirectTextExtraction(pdfFile);
            if (directText != null && !directText.trim().isEmpty()) {
                System.out.println("[OCR处理] ✓ 直接提取成功，文本长度: " + directText.length() + " 字符");
                
                // 判断是否为图片型PDF（文本很少但文件很大）
                if (isLikelyImagePdf(pdfFile, directText)) {
                    System.out.println("[OCR处理] 检测到图片型PDF，启动OCR识别...");
                    return performOcrOnPdf(pdfFile);
                }
                
                return directText;
            }
            
            // 第二步：直接提取失败或无文本，使用OCR
            System.out.println("[OCR处理] 直接提取失败或无文本，启动OCR识别...");
            return performOcrOnPdf(pdfFile);
            
        } catch (Exception e) {
            System.err.println("[OCR处理] 处理失败: " + e.getMessage());
            e.printStackTrace();
            return "[OCR处理失败] " + e.getMessage();
        }
    }

    /**
     * 尝试直接提取文本
     */
    private String tryDirectTextExtraction(File pdfFile) {
        try {
            dev.langchain4j.data.document.DocumentParser parser = 
                new dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser();
            dev.langchain4j.data.document.Document doc = 
                dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocument(pdfFile.toPath(), parser);
            return doc.text();
        } catch (Exception e) {
            System.out.println("[OCR处理] 直接提取异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 判断是否为图片型PDF
     */
    private boolean isLikelyImagePdf(File pdfFile, String extractedText) {
        long fileSizeKB = pdfFile.length() / 1024;
        int textLength = extractedText.trim().length();
        
        // 启发式判断：文件大但文本很少，可能是图片型
        if (fileSizeKB > 500 && textLength < 100) {
            return true;
        }
        
        // 文本密度过低（文件大于100KB且文本长度小于文件大小1/10）
        if (fileSizeKB > 100 && textLength < fileSizeKB / 10) {
            return true;
        }
        
        return false;
    }

    /**
     * 对PDF执行OCR识别
     */
    private String performOcrOnPdf(File pdfFile) {
        StringBuilder ocrText = new StringBuilder();
        
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            
            System.out.println("[OCR处理] PDF共 " + pageCount + " 页，开始逐页OCR...");
            
            for (int page = 0; page < pageCount; page++) {
                System.out.println("  处理第 " + (page + 1) + "/" + pageCount + " 页...");
                
                // 将PDF页面渲染为图片（300 DPI）
                BufferedImage image = pdfRenderer.renderImageWithDPI(page, 300);
                
                // 对图片进行OCR识别
                String pageText = recognizeTextFromImage(image);
                
                if (pageText != null && !pageText.trim().isEmpty()) {
                    ocrText.append(pageText).append("\n\n");
                    System.out.println("    ✓ 识别成功，本页文字: " + pageText.length() + " 字符");
                } else {
                    System.out.println("    ✗ 本页识别失败或无文字");
                }
                
                // 释放图片资源
                image.flush();
            }
            
            System.out.println("[OCR处理] ✓ OCR完成，总文本长度: " + ocrText.length() + " 字符");
            
        } catch (IOException e) {
            System.err.println("[OCR处理] PDF处理失败: " + e.getMessage());
            e.printStackTrace();
        }
        
        return ocrText.toString();
    }

    /**
     * 从图片中识别文字
     */
    public String recognizeTextFromImage(BufferedImage image) {
        try {
            // 图片预处理（提高识别率）
            BufferedImage processedImage = preprocessImage(image);
            
            // 执行OCR
            String text = tesseract.doOCR(processedImage);
            return text;
            
        } catch (TesseractException e) {
            System.err.println("[OCR] 图片识别失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从图片文件识别文字
     */
    public String recognizeTextFromImageFile(String imagePath) {
        try {
            File imageFile = new File(imagePath);
            BufferedImage image = ImageIO.read(imageFile);
            
            if (image == null) {
                return "[错误] 无法读取图片文件: " + imagePath;
            }
            
            return recognizeTextFromImage(image);
            
        } catch (IOException e) {
            System.err.println("[OCR] 读取图片失败: " + e.getMessage());
            return "[错误] " + e.getMessage();
        }
    }

    /**
     * 图片预处理（提升OCR准确率）
     */
    private BufferedImage preprocessImage(BufferedImage image) {
        // 转换为灰度图
        BufferedImage grayImage = new BufferedImage(
            image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        grayImage.getGraphics().drawImage(image, 0, 0, null);
        
        // 二值化处理（可选，进一步提升识别率）
        BufferedImage binaryImage = new BufferedImage(
            grayImage.getWidth(), grayImage.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        binaryImage.getGraphics().drawImage(grayImage, 0, 0, null);
        
        return binaryImage;
    }

    /**
     * 批量处理文件夹下的所有PDF
     */
    public List<OcrResult> batchProcessFolder(String folderPath) {
        System.out.println("\n[OCR批量处理] 开始处理文件夹: " + folderPath);
        
        List<OcrResult> results = new ArrayList<>();
        File folder = new File(folderPath);
        File[] pdfFiles = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
        
        if (pdfFiles == null || pdfFiles.length == 0) {
            System.out.println("[OCR批量处理] 未找到PDF文件");
            return results;
        }
        
        System.out.println("[OCR批量处理] 找到 " + pdfFiles.length + " 个PDF文件");
        
        for (File pdfFile : pdfFiles) {
            try {
                String text = extractTextFromPdf(pdfFile.getAbsolutePath());
                
                OcrResult result = new OcrResult();
                result.setFileName(pdfFile.getName());
                result.setText(text);
                result.setSuccess(!text.startsWith("[OCR处理失败]"));
                
                results.add(result);
                
            } catch (Exception e) {
                System.err.println("[OCR批量处理] 文件处理失败: " + pdfFile.getName());
                e.printStackTrace();
            }
        }
        
        System.out.println("[OCR批量处理] 完成，成功处理 " + results.size() + "/" + pdfFiles.length + " 个文件");
        return results;
    }

    /**
     * OCR结果封装类
     */
    public static class OcrResult {
        private String fileName;
        private String text;
        private boolean success;

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
    }
}
