package com.devai.devaiplatform.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class DevRagService {

    private final ChatLanguageModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final VectorStoreService vectorStoreService;
    private final HybridRetrievalService hybridRetrievalService;
    private final DevAssistant assistant;
    private final DocumentParser pdfParser;
    private final OcrService ocrService;
    private final PdfDataExtractor pdfDataExtractor;  // 【新增】PDF全面提取器

    private static final int LEVEL1_CHUNK_SIZE = 2000;
    private static final int LEVEL1_OVERLAP = 200;
    private static final int LEVEL2_CHUNK_SIZE = 800;
    private static final int LEVEL2_OVERLAP = 150;
    private static final int LEVEL3_CHUNK_SIZE = 300;
    private static final int LEVEL3_OVERLAP = 80;

    /** 文件名时间戳格式: yyyyMMddHHmmss */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 文件存储目录 */
    private static final String UPLOAD_TEMP_DIR = "./uploads/temp";

    public DevRagService(ChatLanguageModel chatModel,
                         EmbeddingModel embeddingModel,
                         VectorStoreService vectorStoreService,
                         HybridRetrievalService hybridRetrievalService,
                         OcrService ocrService,
                         PdfDataExtractor pdfDataExtractor) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.vectorStoreService = vectorStoreService;
        this.hybridRetrievalService = hybridRetrievalService;
        this.ocrService = ocrService;
        this.pdfDataExtractor = pdfDataExtractor;
        this.pdfParser = new ApachePdfBoxDocumentParser();

        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(vectorStoreService.getEmbeddingStore())
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.0)
                .build();

        this.assistant = AiServices.builder(DevAssistant.class)
                .chatLanguageModel(chatModel)
                .contentRetriever(contentRetriever)
                .build();
    }

    // 批量加载文件夹下PDF文档入库向量库
    public void loadDocsToVectorLib(String folderPath) {
        java.nio.file.Path path = Path.of(folderPath);

        // 判断是文件还是目录
        if (java.nio.file.Files.isRegularFile(path)) {
            // 单个文件
            loadSingleDocument(path.toString());
        } else if (java.nio.file.Files.isDirectory(path)) {
            // 目录：加载所有文档
            List<Document> docs = FileSystemDocumentLoader.loadDocuments(path, pdfParser);
            for (Document doc : docs) {
                processDocumentWithHierarchicalSplitting(doc);
            }
        } else {
            throw new IllegalArgumentException("路径不存在: " + folderPath);
        }
    }

    // 加载单个文档到向量库（使用 PdfDataExtractor 全面提取：文本+表格+图片OCR）
    private void loadSingleDocument(String filePath) {
        try {
            System.out.println("\n[文档加载] 开始全面提取: " + filePath);
            
            // 【核心改进】使用 PdfDataExtractor 全面提取 PDF 数据
            PdfDataExtractor.ExtractedPdfData extractedData = pdfDataExtractor.extractAll(filePath);
            
            // 获取合并后的完整文本（文本 + 表格JSON + 图片OCR文字）
            String mergedText = extractedData.getMergedText();
            
            if (mergedText == null || mergedText.trim().isEmpty()) {
                System.err.println("[文档加载] 警告: 提取的文本为空，跳过该文件");
                return;
            }
            
            // 创建文档对象
            Document doc = Document.from(mergedText);
            
            // 添加丰富的元数据（关键！用于溯源和检索增强）
            doc.metadata().put("source_file", filePath);
            doc.metadata().put("file_name", Path.of(filePath).getFileName().toString());
            doc.metadata().put("total_pages", String.valueOf(extractedData.getTotalPages()));
            doc.metadata().put("table_count", String.valueOf(extractedData.getTables().size()));
            doc.metadata().put("image_count", String.valueOf(extractedData.getImages().size()));
            
            // 单独存储表格JSON（结构化数据，便于后续精确查询）
            if (!extractedData.getTables().isEmpty()) {
                doc.metadata().put("tables_json", 
                    com.alibaba.fastjson2.JSON.toJSONString(extractedData.getTables()));
            }
            
            System.out.println("[文档加载] ✓ 全面提取成功");
            System.out.println("  文本长度: " + mergedText.length() + " 字符");
            System.out.println("  表格数量: " + extractedData.getTables().size());
            System.out.println("  图片数量: " + extractedData.getImages().size());
            
            // 处理文档（层级分片+向量化）
            processDocumentWithHierarchicalSplitting(doc);
            
        } catch (Exception e) {
            System.err.println("加载文档失败: " + filePath);
            e.printStackTrace();
            throw new RuntimeException("加载文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 【新增】层级分片 + 滑动窗口处理
     * 将文档分为三个层级，每个层级有不同的粒度
     */
    private void processDocumentWithHierarchicalSplitting(Document doc) {
        String sourceFile = doc.metadata().getString("source_file");
        if (sourceFile == null || sourceFile.isEmpty()) {
            sourceFile = "未知文件";
        }

        String fileName = doc.metadata().getString("file_name");
        if (fileName == null || fileName.isEmpty()) {
            fileName = sourceFile;
        }

        System.out.println("\n========== 开始处理文件: " + fileName + " ==========");

        // 数据清洗
        System.out.println("正在执行数据清洗...");
        String cleanedText = cleanDocumentText(doc.text());
        doc = Document.from(cleanedText, doc.metadata());
        System.out.println("数据清洗完成，清洗后长度: " + doc.text().length() + " 字符");

        // 【新增】三级分片策略
        AtomicInteger totalSegments = new AtomicInteger(0);

        // Level 1: 大段落分片（用于理解文档结构和主题）
        System.out.println("\n【Level 1】大段落分片 (" + LEVEL1_CHUNK_SIZE + "字符, 重叠" + LEVEL1_OVERLAP + ")");
        DocumentSplitter level1Splitter = DocumentSplitters.recursive(LEVEL1_CHUNK_SIZE, LEVEL1_OVERLAP);
        List<TextSegment> level1Segments = level1Splitter.split(doc);
        System.out.println("  生成 " + level1Segments.size() + " 个大段落");

        // 入库 Level 1 片段
        int level1Count = ingestSegments(level1Segments, "L1_SUMMARY");
        totalSegments.addAndGet(level1Count);

        // Level 2: 中等段落分片（常规检索用）
        System.out.println("\n【Level 2】中等段落分片 (" + LEVEL2_CHUNK_SIZE + "字符, 重叠" + LEVEL2_OVERLAP + ")");
        DocumentSplitter level2Splitter = DocumentSplitters.recursive(LEVEL2_CHUNK_SIZE, LEVEL2_OVERLAP);
        List<TextSegment> level2Segments = level2Splitter.split(doc);
        System.out.println("  生成 " + level2Segments.size() + " 个中等段落");

        // 入库 Level 2 片段
        int level2Count = ingestSegments(level2Segments, "L2_NORMAL");
        totalSegments.addAndGet(level2Count);

        // Level 3: 小片段分片（精确匹配用）
        System.out.println("\n【Level 3】小片段分片 (" + LEVEL3_CHUNK_SIZE + "字符, 重叠" + LEVEL3_OVERLAP + ")");
        DocumentSplitter level3Splitter = DocumentSplitters.recursive(LEVEL3_CHUNK_SIZE, LEVEL3_OVERLAP);
        List<TextSegment> level3Segments = level3Splitter.split(doc);
        System.out.println("  生成 " + level3Segments.size() + " 个小片段");

        // 入库 Level 3 片段
        int level3Count = ingestSegments(level3Segments, "L3_PRECISE");
        totalSegments.addAndGet(level3Count);

        System.out.println("\n========== 分片统计 ==========");
        System.out.println("  Level 1 (大段落): " + level1Count + " 个");
        System.out.println("  Level 2 (中等):   " + level2Count + " 个");
        System.out.println("  Level 3 (小片段): " + level3Count + " 个");
        System.out.println("  总计:           " + totalSegments.get() + " 个片段");
        System.out.println("【" + fileName + "】入库完成！==========\n");
    }

    /**
     * 【新增】批量入库文本片段
     * @param segments 待入库的片段列表
     * @param levelTag 层级标签（L1_SUMMARY / L2_NORMAL / L3_PRECISE）
     * @return 成功入库的数量
     */
    private int ingestSegments(List<TextSegment> segments, String levelTag) {
        EmbeddingStore<TextSegment> store = vectorStoreService.getEmbeddingStore();
        boolean isEs = vectorStoreService.isElasticsearchMode();
        String storeLabel = isEs ? "Elasticsearch" : "内存";

        System.out.println("[入库] 向量存储类型: " + storeLabel + " | 待写入片段: " + segments.size());

        if (isEs) {
            // ES模式：写一条测试数据确保索引已创建
            try {
                TextSegment probe = segments.get(0);
                var probeEmb = embeddingModel.embed(probe);
                store.add(probeEmb.content(), probe);
                System.out.println("[入库] ✅ ES索引连通性验证通过，开始批量写入...");
            } catch (Exception e) {
                System.err.println("[入库] ❌ ES写入失败（请检查ES是否运行、Ollama是否启动）: " + e.getMessage());
                e.printStackTrace();
                return 0;
            }
        }

        int successCount = 0;

        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            try {
                segment.metadata().put("chunk_level", levelTag);
                var embedding = embeddingModel.embed(segment);
                store.add(embedding.content(), segment);
                vectorStoreService.incrementSegmentCount();
                // 持久化到本地JSON（内存模式；ES模式自动跳过）
                vectorStoreService.persistSegment(segment, embedding.content().vector());
                successCount++;

                // 打印前2个片段预览
                if (i < 2) {
                    String preview = segment.text().substring(0, Math.min(80, segment.text().length()));
                    System.out.println("    [" + levelTag + "] 片段" + (i+1) + ": " + preview + "...");
                }

                // 每处理50个片段打印进度，并刷写磁盘
                if ((i + 1) % 50 == 0 || i == segments.size() - 1) {
                    vectorStoreService.flushToDisk();
                    System.out.println("    进度: " + (i + 1) + "/" + segments.size()
                            + " | 成功: " + successCount + " | 存储: " + storeLabel);
                }
            } catch (Exception e) {
                System.err.println("    处理第 " + (i + 1) + " 个片段失败 [" + storeLabel + "]: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                e.printStackTrace();
                // 第一个片段就失败 → 大概率是存储或Embedding问题，直接终止避免刷屏
                if (successCount == 0 && i == 0) {
                    System.err.println("[入库] ❌ 首条写入即失败，终止入库（请检查: Ollama是否启动? ES是否运行?）");
                    return 0;
                }
            }
        }

        System.out.println("[入库] ✅ " + levelTag + " 写入完成: " + successCount + "/" + segments.size() + " → " + storeLabel);
        return successCount;
    }

    /**
     * 数据清洗：清理文档中的噪声内容
     * @param originalText 原始文本
     * @return 清洗后的文本
     */
    private String cleanDocumentText(String originalText) {
        if (originalText == null || originalText.isEmpty()) {
            return originalText;
        }

        String cleaned = originalText;

        // 1. 去除多余的空格和制表符（保留单个空格）
        cleaned = cleaned.replaceAll("[ \\t]+", " ");

        // 2. 去除空行或只包含空白字符的行
        cleaned = cleaned.replaceAll("(?m)^\\s*\\n", "");

        // 3. 去除特殊控制字符（保留换行符、制表符等常用字符）
        cleaned = cleaned.replaceAll("[\\x00-\\x08\\x0B-\\x0C\\x0E-\\x1F]", "");

        // 4. 去除连续的换行符（最多保留2个换行符）
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");

        // 5. 去除首尾空白
        cleaned = cleaned.trim();

        // 6. 去除常见的PDF解析噪声（如页眉页脚标记）
        // 例如："Page 1 of 10"、"- 1 -" 等
        cleaned = cleaned.replaceAll("(?m)^\\s*(Page|page|PAGE)\\s+\\d+\\s+(of|Of|OF)\\s+\\d+\\s*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*-\\s*\\d+\\s*-\\s*$", "");

        // 7. 去除过短的行（少于3个字符且不是标点符号结尾，可能是噪声）
        cleaned = cleaned.replaceAll("(?m)^.{1,2}$", "");

        // 8. 再次去除因清理产生的空行
        cleaned = cleaned.replaceAll("(?m)^\\s*\\n", "").trim();

        System.out.println("数据清洗统计 - 原始长度: " + originalText.length() +
                ", 清洗后长度: " + cleaned.length() +
                ", 减少: " + (originalText.length() - cleaned.length()) + " 字符");

        return cleaned;
    }

    // RAG问答 - 混合检索模式
    public String ragQuery(String userQuestion) {
        System.out.println("\n========== 用户提问: " + userQuestion + " ==========");

        // 【混合检索】结合多种检索策略
        List<Content> allContents = hybridRetrieval(userQuestion);
        
        System.out.println("\n【混合检索结果】共检索到 " + allContents.size() + " 条相关内容:\n");

        for (int i = 0; i < allContents.size(); i++) {
            Content content = allContents.get(i);
            TextSegment segment = content.textSegment();
            String fileName = segment.metadata().getString("file_name");
            if (fileName == null || fileName.isEmpty()) {
                fileName = "未知文件";
            }

            // 显示分片层级和检索来源
            String chunkLevel = segment.metadata().getString("chunk_level");
            if (chunkLevel == null || chunkLevel.isEmpty()) {
                chunkLevel = "未知";
            }

            String textPreview = segment.text().substring(0, Math.min(150, segment.text().length()));

            System.out.println("[" + (i+1) + "] 来源: 《" + fileName + "》 [层级: " + chunkLevel + "]");
            System.out.println("    内容: " + textPreview + "...");
        }

        System.out.println("\n========== AI 生成答案 ==========");
        String answer;
        try {
            answer = assistant.chat(userQuestion);
        } catch (Exception e) {
            System.out.println("[RAG问答] AI回答异常（索引可能未创建，请先上传文档）: " + e.getMessage());
            answer = "知识库尚未初始化，请先上传文档到知识库后再提问。";
        }

        // 【引用标注】在答案末尾附加文件来源
        String citationBlock = buildCitationBlock(allContents);
        if (!citationBlock.isEmpty()) {
            answer = answer + citationBlock;
        }

        System.out.println(answer);
        System.out.println("========== 回答结束 ==========\n");

        return answer;
    }

    /**
     * 【引用标注】从检索结果中提取文件来源，生成可点击的Markdown引用块
     */
    public String buildCitationBlock(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }

        // 去重收集文件来源
        Set<String> uniqueFiles = new LinkedHashSet<>();
        for (Content content : contents) {
            TextSegment segment = content.textSegment();
            String fileName = segment.metadata().getString("file_name");
            if (fileName != null && !fileName.isBlank() && !"未知文件".equals(fileName)) {
                uniqueFiles.add(fileName);
            }
        }

        if (uniqueFiles.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n");
        sb.append("📚 **参考文件**：\n");
        int idx = 1;
        for (String file : uniqueFiles) {
            // 生成文件预览链接（可点击下载/查看）
            String encodedName = file.replace(" ", "%20");
            sb.append("- [").append(idx).append(". ").append(file)
              .append("](/api/dev-ai/file/preview?name=").append(encodedName).append(")\n");
            idx++;
        }
        return sb.toString();
    }
    /**
     * 【新增】检索向量库中的相关内容文本（不生成AI答案）
     * 供其他服务调用，将检索结果作为上下文注入 prompt
     * @param question 用户问题
     * @return 检索到的相关内容文本，格式化为可注入prompt的字符串；无结果时返回空字符串
     */
    public String retrieveRelevantContent(String question) {
        List<Content> allContents = hybridRetrieval(question);
        if (allContents.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【知识库相关内容】\n");
        for (int i = 0; i < allContents.size(); i++) {
            Content content = allContents.get(i);
            TextSegment segment = content.textSegment();
            String fileName = segment.metadata().getString("file_name");
            if (fileName == null || fileName.isEmpty()) {
                fileName = "未知来源";
            }
            sb.append("--- 来源: ").append(fileName).append(" ---\n");
            sb.append(segment.text()).append("\n\n");
        }
        return sb.toString();
    }
    /**
     * 【混合检索核心】委托给 HybridRetrievalService
     * BM25 + HNSW KNN + RRF 融合排序 + 动态参数控制
     */
    private List<Content> hybridRetrieval(String userQuestion) {
        return hybridRetrievalService.hybridSearch(userQuestion);
    }

    /**
     * 将OCR批量处理结果写入向量库，同时将源文件保存到 uploads/temp/
     * @param ocrResults OCR批量处理的结果列表
     * @param fileNameMapping 原始文件名 → 唯一文件名 的映射（用于向量库中 file_name 元数据）
     * @return 成功入库的文档数
     */
    public int ingestOcrResults(List<com.devai.devaiplatform.service.OcrService.OcrResult> ocrResults,
                                Map<String, String> fileNameMapping) {
        if (ocrResults == null || ocrResults.isEmpty()) {
            System.out.println("[OCR入库] 无OCR结果，跳过");
            return 0;
        }

        int ingestedCount = 0;
        System.out.println("\n[OCR入库] 开始将 " + ocrResults.size() + " 个OCR结果写入向量库...");

        for (com.devai.devaiplatform.service.OcrService.OcrResult ocrResult : ocrResults) {
            if (!ocrResult.isSuccess()) {
                System.out.println("[OCR入库] 跳过失败项: " + ocrResult.getFileName());
                continue;
            }

            String text = ocrResult.getText();
            if (text == null || text.trim().isEmpty()) {
                System.out.println("[OCR入库] 跳过空文本: " + ocrResult.getFileName());
                continue;
            }

            try {
                String ocrFileName = ocrResult.getFileName();
                // 优先使用映射中的唯一文件名，确保与 uploads/temp/ 中保存的源文件名一致
                String tsFileName;
                if (fileNameMapping != null && fileNameMapping.containsKey(ocrFileName)) {
                    tsFileName = fileNameMapping.get(ocrFileName);
                } else {
                    tsFileName = appendTimestampToFileName(ocrFileName);
                }

                Document doc = Document.from(text);
                doc.metadata().put("file_name", tsFileName);
                doc.metadata().put("source", "ocr_batch_upload");
                doc.metadata().put("ocr_original_name", ocrFileName);
                processDocumentWithHierarchicalSplitting(doc);
                ingestedCount++;
            } catch (Exception e) {
                System.err.println("[OCR入库] 入库失败: " + ocrResult.getFileName() + " - " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("[OCR入库] ✅ 完成，成功入库 " + ingestedCount + "/" + ocrResults.size() + " 个文档");
        return ingestedCount;
    }

    /**
     * 将OCR批量处理结果写入向量库（无文件名映射的简化版，内部自动生成时间戳）
     * @param ocrResults OCR批量处理的结果列表
     * @return 成功入库的文档数
     */
    public int ingestOcrResults(List<com.devai.devaiplatform.service.OcrService.OcrResult> ocrResults) {
        return ingestOcrResults(ocrResults, null);
    }

    // 获取向量库统计信息（调试用）
    public Map<String, Object> getVectorStoreStats() {
        Map<String, Object> stats = new HashMap<>();
        VectorStoreService.VectorStoreStats vsStats = vectorStoreService.getStats();
        stats.put("status", vsStats.getStatus());
        stats.put("storeType", vsStats.getStoreType());
        stats.put("totalSegments", vsStats.getTotalSegments());
        stats.put("initialized", vsStats.isInitialized());
        stats.put("persistPath", "./agent_memory/vector_store.json");
        if (vsStats.getTotalSegments() == 0) {
            stats.put("warning", "向量库为空，请先上传文档到知识库！");
        }
        return stats;
    }

    /**
     * 解析唯一文件名：如果 uploads/temp/ 下已存在同名文件，自动追加时间戳
     * @param originalFilename 原始文件名（如 "简历.pdf"）
     * @return 唯一文件名（如 "简历_20260704153022.pdf"）
     */
    private String resolveUniqueFileName(String originalFilename) {
        Path dir = Paths.get(UPLOAD_TEMP_DIR);
        Path candidate = dir.resolve(originalFilename);

        if (!Files.exists(candidate)) {
            return originalFilename;
        }

        // 同名文件已存在 → 追加时间戳
        String baseName = originalFilename;
        String extension = "";
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = originalFilename.substring(0, dotIndex);
            extension = originalFilename.substring(dotIndex);
        }

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        String uniqueName = baseName + "_" + timestamp + extension;

        // 极低概率的时间戳冲突兜底（同一秒内多次上传同名文件）
        int counter = 1;
        while (Files.exists(dir.resolve(uniqueName))) {
            uniqueName = baseName + "_" + timestamp + "_" + counter + extension;
            counter++;
        }

        System.out.println("[文件去重] 文件名冲突 → 自动重命名: " + originalFilename + " → " + uniqueName);
        return uniqueName;
    }

    /**
     * 给文件名追加时间戳（用于OCR批量入库等场景）
     * 如 "简历.pdf" → "简历_20260704153022.pdf"
     */
    private String appendTimestampToFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return fileName;
        String baseName = fileName;
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        return baseName + "_" + timestamp + extension;
    }

    /**
     * 【新增】上传单个PDF文件到知识库
     * @param file Spring MVC上传的PDF文件
     * @return 上传结果（包含成功状态、消息、统计信息）
     */
    public Map<String, Object> uploadSinglePdf(MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 1. 验证文件
            if (file == null || file.isEmpty()) {
                result.put("success", false);
                result.put("message", "文件为空");
                return result;
            }
            
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
                result.put("success", false);
                result.put("message", "只支持PDF格式文件");
                return result;
            }
            
            System.out.println("\n[文件上传] 开始处理: " + originalFilename);
            System.out.println("[文件上传] 文件大小: " + file.getSize() + " bytes");
            
            // 2. 去重检测 → 生成唯一文件名（如已存在则追加时间戳）
            String uniqueFilename = resolveUniqueFileName(originalFilename);
            boolean wasRenamed = !uniqueFilename.equals(originalFilename);
            
            // 3. 保存到临时目录（使用唯一文件名）
            Path tempDirPath = Paths.get(UPLOAD_TEMP_DIR);
            if (!Files.exists(tempDirPath)) {
                Files.createDirectories(tempDirPath);
            }
            
            Path filePath = tempDirPath.resolve(uniqueFilename);
            Files.write(filePath, file.getBytes());
            
            System.out.println("[文件上传] 已保存: " + filePath.toAbsolutePath());
            
            // 4. 加载到向量库（metadata 中的 file_name 自动使用唯一文件名）
            loadSingleDocument(filePath.toString());
            
            System.out.println("[文件上传] ✓ 处理完成");
            
            // 5. 返回成功
            String msg = "✅ PDF上传并入库成功: " + uniqueFilename;
            if (wasRenamed) {
                msg += "（原始文件名: " + originalFilename + "，因同名已追加时间戳）";
            }
            result.put("success", true);
            result.put("message", msg);
            result.put("fileName", uniqueFilename);
            result.put("originalFileName", originalFilename);
            result.put("wasRenamed", wasRenamed);
            result.put("fileSize", file.getSize());
            
            // 添加统计信息
            result.put("stats", getVectorStoreStats());
            
            return result;
            
        } catch (IOException e) {
            System.err.println("[文件上传] IO错误: " + e.getMessage());
            e.printStackTrace();
            result.put("success", false);
            result.put("message", "❌ 文件上传失败: " + e.getMessage());
            return result;
        } catch (Exception e) {
            System.err.println("[文件上传] 处理错误: " + e.getMessage());
            e.printStackTrace();
            result.put("success", false);
            result.put("message", "❌ PDF处理失败: " + e.getMessage());
            return result;
        }
    }

}
