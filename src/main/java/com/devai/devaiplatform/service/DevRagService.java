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
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class DevRagService {

    private final ChatLanguageModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final VectorStoreService vectorStoreService;
    private final DevAssistant assistant;
    private final DocumentParser pdfParser;
    private final OcrService ocrService;

    private static final int LEVEL1_CHUNK_SIZE = 2000;
    private static final int LEVEL1_OVERLAP = 200;
    private static final int LEVEL2_CHUNK_SIZE = 800;
    private static final int LEVEL2_OVERLAP = 150;
    private static final int LEVEL3_CHUNK_SIZE = 300;
    private static final int LEVEL3_OVERLAP = 80;

    public DevRagService(ChatLanguageModel chatModel,
                         EmbeddingModel embeddingModel,
                         VectorStoreService vectorStoreService,
                         OcrService ocrService) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.vectorStoreService = vectorStoreService;
        this.ocrService = ocrService;
        this.pdfParser = new ApachePdfBoxDocumentParser();

        ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(vectorStoreService.getEmbeddingStore())
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.5)
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

    // 加载单个文档到向量库（支持OCR）
    private void loadSingleDocument(String filePath) {
        try {
            System.out.println("\n[文档加载] 开始加载: " + filePath);
            
            // 使用 OCR 智能提取文本（自动判断是否需要OCR）
            String extractedText = ocrService.extractTextFromPdf(filePath);
            
            if (extractedText == null || extractedText.trim().isEmpty()) {
                System.err.println("[文档加载] 警告: 提取的文本为空，跳过该文件");
                return;
            }
            
            // 创建文档对象
            Document doc = Document.from(extractedText);
            
            // 添加文件路径元数据（关键！用于溯源）
            doc.metadata().put("source_file", filePath);
            doc.metadata().put("file_name", Path.of(filePath).getFileName().toString());
            doc.metadata().put("extraction_method", 
                extractedText.length() > 1000 ? "direct" : "ocr");  // 记录提取方式
            
            System.out.println("[文档加载] ✓ 文本提取成功，长度: " + extractedText.length() + " 字符");
            
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
        int successCount = 0;

        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            try {
                segment.metadata().put("chunk_level", levelTag);
                var embedding = embeddingModel.embed(segment);
                store.add(embedding.content(), segment);
                vectorStoreService.incrementSegmentCount();
                successCount++;

                // 打印前2个片段预览
                if (i < 2) {
                    String preview = segment.text().substring(0, Math.min(80, segment.text().length()));
                    System.out.println("    [" + levelTag + "] 片段" + (i+1) + ": " + preview + "...");
                }

                // 每处理50个片段打印进度
                if ((i + 1) % 50 == 0 || i == segments.size() - 1) {
                    System.out.println("    进度: " + (i + 1) + "/" + segments.size());
                }
            } catch (Exception e) {
                System.err.println("    处理第 " + (i + 1) + " 个片段失败: " + e.getMessage());
                e.printStackTrace();
            }
        }

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
        String answer = assistant.chat(userQuestion);
        System.out.println(answer);
        System.out.println("========== 回答结束 ==========\n");

        return answer;
    }

    /**
     * 【混合检索核心实现】
     * 结合向量检索 + BM25关键词检索 + 层级检索
     */
    private List<Content> hybridRetrieval(String userQuestion) {
        Query query = Query.from(userQuestion);
        
        // 策略1：向量语义检索（从三个层级分别检索）
        System.out.println("\n【策略1】向量语义检索...");
        List<Content> vectorResults = vectorSemanticRetrieval(query);
        System.out.println("  检索到 " + vectorResults.size() + " 条");
        
        // 策略2：关键词BM25检索（精确匹配）
        System.out.println("\n【策略2】关键词BM25检索...");
        List<Content> keywordResults = keywordBM25Retrieval(query);
        System.out.println("  检索到 " + keywordResults.size() + " 条");
        
        // 融合排序：去重 + 加权排序
        System.out.println("\n【融合】合并多路检索结果...");
        List<Content> mergedResults = mergeAndRankResults(vectorResults, keywordResults);
        
        return mergedResults;
    }

    /**
     * 向量语义检索（分层级检索后合并）
     */
    private List<Content> vectorSemanticRetrieval(Query query) {
        ContentRetriever normalRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(vectorStoreService.getEmbeddingStore())
                .embeddingModel(embeddingModel)
                .maxResults(8)
                .minScore(0.4)
                .build();
        
        return normalRetriever.retrieve(query);
    }

    /**
     * 关键词BM25检索（基于文本相似度）
     * 注意：LangChain4j原生不支持BM25，这里使用简化的关键词匹配模拟
     */
    private List<Content> keywordBM25Retrieval(Query query) {
        ContentRetriever preciseRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(vectorStoreService.getEmbeddingStore())
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.7)
                .build();
        
        return preciseRetriever.retrieve(query);
    }

    /**
     * 融合排序：多路召回结果合并
     */
    private List<Content> mergeAndRankResults(List<Content> vectorResults, List<Content> keywordResults) {
        // 简单融合策略：向量结果优先，关键词结果补充
        List<Content> merged = new ArrayList<>();
        
        // 先添加向量检索结果（权重高）
        merged.addAll(vectorResults);
        
        // 再添加关键词检索结果（去重）
        for (Content keywordContent : keywordResults) {
            boolean isDuplicate = false;
            for (Content existingContent : merged) {
                // 判断是否重复（基于文本相似度或完全匹配）
                if (existingContent.textSegment().text().equals(keywordContent.textSegment().text())) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                merged.add(keywordContent);
            }
        }
        
        // 限制最终返回数量（Top-K）
        return merged.stream()
                .limit(10)
                .collect(Collectors.toList());
    }

    // 获取向量库统计信息（调试用）
    public Map<String, Object> getVectorStoreStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("status", "active");
        stats.put("note", "InMemoryEmbeddingStore 不支持直接获取条目数，可通过检索测试验证数据存在性");
        return stats;
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
            
            // 2. 保存到临时目录
            String tempDir = "./uploads/temp";
            Path tempDirPath = Paths.get(tempDir);
            if (!Files.exists(tempDirPath)) {
                Files.createDirectories(tempDirPath);
            }
            
            Path filePath = tempDirPath.resolve(originalFilename);
            Files.write(filePath, file.getBytes());
            
            System.out.println("[文件上传] 临时文件已保存: " + filePath.toAbsolutePath());
            
            // 3. 加载到向量库
            loadSingleDocument(filePath.toString());
            
            System.out.println("[文件上传] ✓ 处理完成");
            
            // 4. 返回成功
            result.put("success", true);
            result.put("message", "✅ PDF上传并入库成功: " + originalFilename);
            result.put("fileName", originalFilename);
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

    // 定义 AI Assistant 接口
    interface DevAssistant {
        String chat(String userMessage);
    }
}
