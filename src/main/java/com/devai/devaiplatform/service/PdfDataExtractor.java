package com.devai.devaiplatform.service;

import com.alibaba.fastjson2.JSON;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import technology.tabula.*;
import technology.tabula.detectors.NurminenDetectionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * PDF 数据全面提取服务
 * <p>
 * 三大核心能力：
 * 1. 文本提取（基于 PDFBox TextStripper）
 * 2. 表格提取 → JSON（基于 tabula-java，自动识别表格并结构化）
 * 3. 图片提取 → OCR 文字识别（基于 PDFBox 提取图片 + Tesseract OCR）
 * <p>
 * 将所有数据合并为统一的 JSON 结构，确保 PDF 中所有信息（文本、表格、图片文字）完整获取。
 */
@Service
public class PdfDataExtractor {

    // 【修复】启动时清除 PDFBox 字体缓存，避免损坏的系统字体文件触发 EOFException
    static {
        try {
            String userHome = System.getProperty("user.home");
            File fontCacheDir = new File(userHome, ".pdfbox");
            if (fontCacheDir.exists()) {
                for (File f : fontCacheDir.listFiles()) {
                    if (f.getName().endsWith(".cache") || f.getName().endsWith(".lock")) {
                        f.delete();
                    }
                }
            }
            System.out.println("[PDF提取] PDFBox 字体缓存已清理");
        } catch (Exception ignored) {
        }
    }

    @Value("${pdf.extract.ocr-language:chi_sim+eng}")
    private String ocrLanguage;

    @Value("${pdf.extract.image-temp-dir:./uploads/temp/images}")
    private String imageTempDir;

    @Value("${tesseract.data-path:}")
    private String tesseractDataPath;

    private static final int MIN_TABLE_ROWS = 2;  // 表格最少行数

    /**
     * 全面提取 PDF 的所有数据
     *
     * @param pdfPath PDF 文件路径
     * @return 结构化的提取结果（JSON格式）
     */
    public ExtractedPdfData extractAll(String pdfPath) throws IOException {
        System.out.println("\n========== PDF数据全面提取开始 ==========");
        System.out.println("文件: " + pdfPath);

        ExtractedPdfData result = new ExtractedPdfData();
        result.setFileName(Path.of(pdfPath).getFileName().toString());
        result.setFilePath(pdfPath);

        try (PDDocument document = PDDocument.load(new File(pdfPath))) {
            int totalPages = document.getNumberOfPages();
            result.setTotalPages(totalPages);
            System.out.println("总页数: " + totalPages);

            // 1. 文本提取（三层降级容错）
            System.out.println("\n[步骤1] 文本提取...");
            String fullText = extractTextSafely(document);
            result.setFullText(fullText);
            System.out.println("  文本长度: " + fullText.length() + " 字符");

            // 2. 表格提取 → JSON
            System.out.println("\n[步骤2] 表格提取...");
            List<ExtractedTable> tables = extractTables(document);
            result.setTables(tables);
            System.out.println("  提取表格数: " + tables.size());

            // 3. 图片提取 → OCR
            System.out.println("\n[步骤3] 图片提取与OCR...");
            List<ExtractedImage> images = extractImages(document);
            result.setImages(images);
            System.out.println("  提取图片数: " + images.size());

        }

        // 4. 构建合并后的完整文本（文本 + 表格JSON + 图片OCR文字）
        StringBuilder mergedBuilder = new StringBuilder();
        mergedBuilder.append(result.getFullText()).append("\n\n");

        if (!result.getTables().isEmpty()) {
            mergedBuilder.append("=== 文档中的表格数据 ===\n");
            for (int i = 0; i < result.getTables().size(); i++) {
                ExtractedTable table = result.getTables().get(i);
                mergedBuilder.append("\n【表格").append(i + 1).append("】第")
                        .append(table.getPage()).append("页\n");
                mergedBuilder.append(JSON.toJSONString(table)).append("\n");
            }
        }

        if (!result.getImages().isEmpty()) {
            mergedBuilder.append("\n=== 图片中的文字（OCR识别） ===\n");
            for (int i = 0; i < result.getImages().size(); i++) {
                ExtractedImage image = result.getImages().get(i);
                if (image.getOcrText() != null && !image.getOcrText().isBlank()) {
                    mergedBuilder.append("\n【图片").append(i + 1).append("】第")
                            .append(image.getPage()).append("页\n");
                    mergedBuilder.append(image.getOcrText()).append("\n");
                }
            }
        }

        result.setMergedText(mergedBuilder.toString());

        System.out.println("\n========== 提取完成 ==========");
        System.out.println("合并后文本总长度: " + mergedBuilder.length() + " 字符");
        System.out.println("  表格数据: " + result.getTables().size() + " 个");
        System.out.println("  图片OCR: " + result.getImages().stream().filter(i -> i.getOcrText() != null && !i.getOcrText().isBlank()).count() + " 个有效识别");

        return result;
    }

    // ==================== 文本提取 ====================

    /**
     * 安全文本提取（三层降级容错）
     * <p>
     * Round 1: 标准提取（按位置排序，保留段落结构）
     * Round 2: 降级提取（关闭格式增强和字体替换，减少系统字体依赖）
     * Round 3: 逐页提取（单页失败只跳过该页，不影响其他页）
     */
    private String extractTextSafely(PDDocument document) {
        // Round 1: 标准提取
        try {
            return extractText(document);
        } catch (IOException e) {
            System.err.println("[文本提取] 标准提取失败: " + e.getMessage());

            // Round 2: 降级提取（关闭字体替换和格式增强）
            try {
                System.err.println("[文本提取] 尝试降级提取（关闭字体依赖）...");
                return extractTextFallback(document);
            } catch (IOException e2) {
                System.err.println("[文本提取] 降级提取也失败: " + e2.getMessage());

                // Round 3: 逐页提取（终极兜底，隔离单页故障）
                System.err.println("[文本提取] 尝试逐页提取...");
                return extractTextPageByPage(document);
            }
        }
    }

    /**
     * 使用 PDFBox 提取完整文本（保持段落结构）
     */
    private String extractText(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setParagraphStart("  ");
        stripper.setParagraphEnd("\n");
        return stripper.getText(document);
    }

    /**
     * 降级文本提取：关闭格式增强和排序，减少对系统字体的依赖
     */
    private String extractTextFallback(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(false);
        stripper.setAddMoreFormatting(false);
        stripper.setSuppressDuplicateOverlappingText(true);
        return stripper.getText(document);
    }

    /**
     * 逐页提取文本：单页故障只跳过该页，确保其他页数据不丢失
     */
    private String extractTextPageByPage(PDDocument document) {
        StringBuilder result = new StringBuilder();
        int totalPages = document.getNumberOfPages();
        int successPages = 0;
        int failedPages = 0;

        for (int i = 0; i < totalPages; i++) {
            try {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(false);
                stripper.setAddMoreFormatting(false);
                stripper.setSuppressDuplicateOverlappingText(true);
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String pageText = stripper.getText(document);
                if (pageText != null && !pageText.isBlank()) {
                    result.append(pageText).append("\n");
                }
                successPages++;
            } catch (Exception e) {
                System.err.println("  [第" + (i + 1) + "页] 提取失败，已跳过: " + e.getMessage());
                failedPages++;
            }
        }

        System.out.println("  [逐页提取] 成功=" + successPages + "页, 失败=" + failedPages + "页");

        if (result.isEmpty()) {
            return "（文本提取失败：PDFBox 无法解析该文档的文字层。如为扫描版 PDF，建议走 OCR 流程。）";
        }
        return result.toString();
    }

    // ==================== 表格提取 ====================

    /**
     * 使用 tabula-java 提取所有页面中的表格
     */
    private List<ExtractedTable> extractTables(PDDocument document) {
        List<ExtractedTable> allTables = new ArrayList<>();

        try {
            ObjectExtractor oe = new ObjectExtractor(document);
            // 使用 Nurminen 检测算法（自动识别表格区域）
            SpreadsheetExtractionAlgorithm sea = new SpreadsheetExtractionAlgorithm();
            PageIterator pageIterator = oe.extract();

            int tableIndex = 0;
            while (pageIterator.hasNext()) {
                Page page = pageIterator.next();
                int pageNum = page.getPageNumber();

                // 检测表格区域
                List<Rectangle> tableAreas = detectTableAreas(page);

                if (tableAreas.isEmpty()) {
                    // 无检测到的区域，全页搜索表格
                    List<Table> tables = sea.extract(page);
                    for (Table table : tables) {
                        if (table.getRows().size() >= MIN_TABLE_ROWS) {
                            ExtractedTable et = convertToExtractedTable(table, pageNum, ++tableIndex);
                            allTables.add(et);
                        }
                    }
                } else {
                    // 按检测到的区域提取
                    for (Rectangle area : tableAreas) {
                        List<Table> tables = sea.extract(page.getArea(area.getTop(),
                                area.getLeft(), area.getBottom(), area.getRight()));
                        for (Table table : tables) {
                            if (table.getRows().size() >= MIN_TABLE_ROWS) {
                                ExtractedTable et = convertToExtractedTable(table, pageNum, ++tableIndex);
                                et.setTableArea(Map.of(
                                        "top", area.getTop(),
                                        "left", area.getLeft(),
                                        "bottom", area.getBottom(),
                                        "right", area.getRight()
                                ));
                                allTables.add(et);
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("[表格提取] 错误: " + e.getMessage());
        }

        return allTables;
    }

    /**
     * 检测页面中的表格区域
     */
    private List<Rectangle> detectTableAreas(Page page) {
        try {
            // 使用 NurminenDetectionAlgorithm 自动检测表格位置
            NurminenDetectionAlgorithm detector = new NurminenDetectionAlgorithm();
            return detector.detect(page);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 将 tabula Table 转换为标准 JSON 结构
     */
    private ExtractedTable convertToExtractedTable(Table table, int pageNum, int tableIndex) {
        ExtractedTable et = new ExtractedTable();
        et.setTableIndex(tableIndex);
        et.setPage(pageNum);
        et.setRowCount(table.getRows().size());

        List<List<RectangularTextContainer>> rows = table.getRows();

        // 提取表头（第一行）
        if (!rows.isEmpty()) {
            List<String> headers = new ArrayList<>();
            for (RectangularTextContainer cell : rows.get(0)) {
                headers.add(cleanCellText(cell.getText()));
            }
            et.setHeaders(headers);
        }

        // 提取数据行
        List<List<String>> dataRows = new ArrayList<>();
        int startRow = et.getHeaders() != null && !et.getHeaders().isEmpty() ? 1 : 0;
        for (int i = startRow; i < rows.size(); i++) {
            List<String> row = new ArrayList<>();
            for (RectangularTextContainer cell : rows.get(i)) {
                row.add(cleanCellText(cell.getText()));
            }
            dataRows.add(row);
        }
        et.setRows(dataRows);

        // 转换为 JSON 对象数组（key=表头, value=数据）
        if (et.getHeaders() != null && !et.getHeaders().isEmpty() && !dataRows.isEmpty()) {
            List<Map<String, String>> jsonRows = new ArrayList<>();
            for (List<String> dataRow : dataRows) {
                Map<String, String> jsonRow = new LinkedHashMap<>();
                for (int j = 0; j < et.getHeaders().size() && j < dataRow.size(); j++) {
                    jsonRow.put(et.getHeaders().get(j), dataRow.get(j));
                }
                jsonRows.add(jsonRow);
            }
            et.setJsonData(jsonRows);
        }

        return et;
    }

    private String cleanCellText(String text) {
        if (text == null) return "";
        return text.replace("\r", " ").replace("\n", " ").trim();
    }

    // ==================== 图片提取与OCR ====================

    /**
     * 提取 PDF 中所有嵌入的图片，并对每张图片执行 OCR
     */
    private List<ExtractedImage> extractImages(PDDocument document) {
        List<ExtractedImage> images = new ArrayList<>();
        File tempDir = ensureTempDir();
        Tesseract tesseract = createTesseract();

        int imageIndex = 0;
        for (int pageIdx = 0; pageIdx < document.getNumberOfPages(); pageIdx++) {
            PDPage page = document.getPage(pageIdx);
            try {
                PDResources resources = page.getResources();
                if (resources == null) continue;

                for (COSName cosName : resources.getXObjectNames()) {
                    PDXObject xObject = resources.getXObject(cosName);
                    if (!(xObject instanceof PDImageXObject)) continue;

                    PDImageXObject imageObj = (PDImageXObject) xObject;
                    imageIndex++;

                    ExtractedImage ei = new ExtractedImage();
                    ei.setImageIndex(imageIndex);
                    ei.setPage(pageIdx + 1);
                    ei.setWidth(imageObj.getWidth());
                    ei.setHeight(imageObj.getHeight());

                    try {
                        // 保存图片到临时文件
                        BufferedImage bufferedImage = imageObj.getImage();
                        String imageFileName = "page_" + (pageIdx + 1) + "_img_" + imageIndex + ".png";
                        File imageFile = new File(tempDir, imageFileName);
                        ImageIO.write(bufferedImage, "png", imageFile);
                        ei.setImagePath(imageFile.getAbsolutePath());

                        // OCR 识别图片中的文字
                        String ocrText = tesseract.doOCR(bufferedImage);
                        ei.setOcrText(ocrText != null ? ocrText.trim() : "");

                        System.out.println("  [图片 " + imageIndex + "] 第" + (pageIdx + 1) + "页, "
                                + imageObj.getWidth() + "x" + imageObj.getHeight()
                                + ", OCR文字: " + (ocrText != null ? ocrText.length() : 0) + " 字符");

                    } catch (IOException | TesseractException e) {
                        System.err.println("  [图片 " + imageIndex + "] 处理失败: " + e.getMessage());
                        ei.setOcrText("");
                    }

                    images.add(ei);
                }
            } catch (Exception e) {
                System.err.println("  [第" + (pageIdx + 1) + "页] 图片提取出错: " + e.getMessage());
            }
        }

        return images;
    }

    private File ensureTempDir() {
        File dir = new File(imageTempDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private Tesseract createTesseract() {
        Tesseract tesseract = new Tesseract();
        tesseract.setLanguage(ocrLanguage);

        // 如果配置了 tessdata 路径则设置
        if (tesseractDataPath != null && !tesseractDataPath.isBlank()) {
            tesseract.setDatapath(tesseractDataPath);
        }
        // 设置页面分割模式：自动检测
        tesseract.setPageSegMode(3);

        return tesseract;
    }

    // ==================== 数据结构 ====================

    /**
     * PDF 全面提取结果
     */
    public static class ExtractedPdfData {
        private String fileName;
        private String filePath;
        private int totalPages;
        private String fullText;                     // 纯文本内容
        private List<ExtractedTable> tables;          // 提取的表格列表
        private List<ExtractedImage> images;          // 提取的图片（含OCR）
        private String mergedText;                   // 合并后的完整文本

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public int getTotalPages() { return totalPages; }
        public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
        public String getFullText() { return fullText; }
        public void setFullText(String fullText) { this.fullText = fullText; }
        public List<ExtractedTable> getTables() { return tables; }
        public void setTables(List<ExtractedTable> tables) { this.tables = tables; }
        public List<ExtractedImage> getImages() { return images; }
        public void setImages(List<ExtractedImage> images) { this.images = images; }
        public String getMergedText() { return mergedText; }
        public void setMergedText(String mergedText) { this.mergedText = mergedText; }
    }

    /**
     * 表格提取结果（表格 → JSON）
     */
    public static class ExtractedTable {
        private int tableIndex;                       // 表格序号
        private int page;                             // 所在页码
        private int rowCount;                         // 行数
        private List<String> headers;                 // 表头
        private List<List<String>> rows;              // 数据行（List格式）
        private List<Map<String, String>> jsonData;   // 数据行（JSON格式 key=表头）
        private Map<String, Object> tableArea;        // 表格区域坐标

        public int getTableIndex() { return tableIndex; }
        public void setTableIndex(int tableIndex) { this.tableIndex = tableIndex; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
        public int getRowCount() { return rowCount; }
        public void setRowCount(int rowCount) { this.rowCount = rowCount; }
        public List<String> getHeaders() { return headers; }
        public void setHeaders(List<String> headers) { this.headers = headers; }
        public List<List<String>> getRows() { return rows; }
        public void setRows(List<List<String>> rows) { this.rows = rows; }
        public List<Map<String, String>> getJsonData() { return jsonData; }
        public void setJsonData(List<Map<String, String>> jsonData) { this.jsonData = jsonData; }
        public Map<String, Object> getTableArea() { return tableArea; }
        public void setTableArea(Map<String, Object> tableArea) { this.tableArea = tableArea; }
    }

    /**
     * 图片提取结果（图片 → OCR文字）
     */
    public static class ExtractedImage {
        private int imageIndex;                       // 图片序号
        private int page;                             // 所在页码
        private int width;                            // 图片宽度
        private int height;                           // 图片高度
        private String imagePath;                     // 临时文件路径
        private String ocrText;                       // OCR 识别的文字

        public int getImageIndex() { return imageIndex; }
        public void setImageIndex(int imageIndex) { this.imageIndex = imageIndex; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
        public int getWidth() { return width; }
        public void setWidth(int width) { this.width = width; }
        public int getHeight() { return height; }
        public void setHeight(int height) { this.height = height; }
        public String getImagePath() { return imagePath; }
        public void setImagePath(String imagePath) { this.imagePath = imagePath; }
        public String getOcrText() { return ocrText; }
        public void setOcrText(String ocrText) { this.ocrText = ocrText; }
    }
}
