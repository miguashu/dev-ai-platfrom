package com.devai.devaiplatform.controller;

import com.devai.devaiplatform.common.Result;
import com.devai.devaiplatform.service.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/dev-ai")
public class DevAiController {
    private final DevRagService ragService;
    private final DevAgentService agentService;
    private final PersistentMemoryService memoryService;
    private final OcrService ocrService;  // 【新增】OCR服务
    private final MemoryDistillationScheduler distillationScheduler;  // 【新增】蒸馏调度器
    private final IntentAnalyzerService intentAnalyzerService;  // 【新增】意图分析服务
    private final TaskDispatcherService taskDispatcherService;  // 【新增】任务分发服务
    private final IdeaErrorAnalyzerService ideaErrorAnalyzerService;  // 【新增】IDEA错误分析服务

    // 【新增】文件上传安全配置
    @Value("${file.upload.max-size:52428800}") // 默认50MB
    private long maxFileSize;

    // 允许的文件类型（MIME类型）
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
        "application/pdf",
        "application/x-pdf"
    );

    // 允许的文件扩展名
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf");

    // 文件名安全校验正则（只允许字母数字、中文、下划线、连字符、点号）
    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("^[\\w\\u4e00-\\u9fa5\\-_. ]+$");

    // 危险文件名模式（路径遍历、系统文件等）
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(".*(\\.\\.|/|\\\\|%|\\$|\0).*");


    public DevAiController(DevRagService ragService,
                           DevAgentService agentService,
                           OcrService ocrService,
                           MemoryDistillationScheduler distillationScheduler,
                           IntentAnalyzerService intentAnalyzerService,
                           TaskDispatcherService taskDispatcherService,
                           IdeaErrorAnalyzerService ideaErrorAnalyzerService) {
        this.ragService = ragService;
        this.agentService = agentService;
        this.ocrService = ocrService;  // 【新增】初始化
        this.memoryService = new PersistentMemoryService();
        this.distillationScheduler = distillationScheduler;  // 【新增】初始化
        this.intentAnalyzerService = intentAnalyzerService;  // 【新增】初始化
        this.taskDispatcherService = taskDispatcherService;  // 【新增】初始化
        this.ideaErrorAnalyzerService = ideaErrorAnalyzerService;  // 【新增】初始化
    }

    // ==================== 智能路由分发 ====================

    /**
     * 【智能路由】接收用户消息 -> AI分析意图 -> 拆分子任务 -> 路由到对应Agent执行
     * 核心入口：自动理解用户需求，智能分发到合适的处理Agent
     *
     * @param message 用户消息（自然语言）
     * @return 执行结果
     */
    @PostMapping("/smart/dispatch")
    public Result<Map<String, Object>> smartDispatch(@RequestParam String message) {
        System.out.println("\n========== 智能路由收到请求 ==========");
        System.out.println("消息: " + message);

        // 1. AI分析意图（理解、拆分、提取）
        TaskAnalysisResult analysis = intentAnalyzerService.analyze(message);

        // 2. 根据意图路由并执行
        String result = taskDispatcherService.dispatch(analysis);

        // 3. 组装返回结果
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("analysis", buildAnalysisSummary(analysis));

        return Result.success(response);
    }

    /**
     * 【仅分析】只进行意图分析，不执行任务（用于调试/预览）
     *
     * @param message 用户消息
     * @return 意图分析结果
     */
    @PostMapping("/smart/analyze-only")
    public Result<Map<String, Object>> analyzeOnly(@RequestParam String message) {
        TaskAnalysisResult analysis = intentAnalyzerService.analyze(message);
        return Result.success(buildAnalysisSummary(analysis));
    }

    /**
     * 获取支持的路由意图列表
     */
    @GetMapping("/smart/supported-intents")
    public Result<String> getSupportedIntents() {
        return Result.success(taskDispatcherService.getSupportedIntents());
    }

    // ==================== IDEA错误分析修复 ====================

    /**
     * 【新增】扫描IDEA编译错误并获取AI修复建议
     * 执行Maven编译 -> 解析错误 -> 读取源码上下文 -> AI分析修复
     *
     * @param projectPath 项目路径（可选，为空时使用当前项目路径）
     * @return AI修复建议
     */
    @PostMapping("/idea/scan-and-fix")
    public Result<Map<String, Object>> scanAndFixErrors(
            @RequestParam(required = false) String projectPath) {
        System.out.println("\n========== IDEA错误扫描开始 ==========");
        System.out.println("项目路径: " + (projectPath != null ? projectPath : "默认路径"));

        Map<String, Object> response = new LinkedHashMap<>();

        // 1. 执行编译扫描
        IdeaErrorAnalyzerService.CompileResult compileResult = ideaErrorAnalyzerService.scanCompileErrors(projectPath);

        response.put("compileSuccess", compileResult.success);
        response.put("errorCount", compileResult.errors.size());
        response.put("summary", compileResult.getSummary());

        if (compileResult.success) {
            response.put("fixSuggestion", "✅ 编译成功，无错误！");
            return Result.success(response);
        }

        // 2. 构建错误上下文并调用AI分析
        String errorContext = ideaErrorAnalyzerService.buildFixContext(compileResult);
        String fixSuggestion = agentService.scanAndFixIdeaErrors(projectPath);

        response.put("fixSuggestion", fixSuggestion);
        response.put("errorDetails", compileResult.errors.stream()
                .map(e -> Map.of(
                        "file", e.filePath != null ? e.filePath : "",
                        "line", e.line,
                        "column", e.column,
                        "message", e.message != null ? e.message : "",
                        "type", e.errorType
                ))
                .toList());

        return Result.success(response);
    }

    /**
     * 【新增】仅获取编译错误列表（不经过AI分析，用于前端展示错误列表）
     *
     * @param projectPath 项目路径（可选）
     * @return 编译错误列表
     */
    @PostMapping("/idea/scan-errors")
    public Result<Map<String, Object>> scanErrorsOnly(
            @RequestParam(required = false) String projectPath) {
        IdeaErrorAnalyzerService.CompileResult result = ideaErrorAnalyzerService.scanCompileErrors(projectPath);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.success);
        response.put("errorCount", result.errors.size());
        response.put("summary", result.getSummary());

        if (!result.success) {
            response.put("errors", result.errors.stream()
                    .map(e -> Map.of(
                            "file", e.filePath != null ? e.filePath : "",
                            "line", e.line,
                            "column", e.column,
                            "message", e.message != null ? e.message : "",
                            "type", e.errorType
                    ))
                    .toList());
        }

        return Result.success(response);
    }

    /**
     * 【新增】分析运行时异常堆栈
     *
     * @param stackTrace 异常堆栈信息
     * @return AI分析修复建议
     */
    @PostMapping("/idea/analyze-runtime-error")
    public Result<String> analyzeRuntimeError(@RequestParam String stackTrace) {
        return Result.success(agentService.analyzeRuntimeError(stackTrace));
    }

    // ==================== 本地文件操作 ====================

    /**
     * 【新增】创建目录
     *
     * @param dirPath 目录路径
     * @return 创建结果
     */
    @PostMapping("/file/create-directory")
    public Result<String> createDirectory(@RequestParam String dirPath) {
        return Result.success(agentService.createDirectory(dirPath));
    }

    /**
     * 【新增】创建文件
     *
     * @param filePath 文件路径
     * @param content  文件内容
     * @return 创建结果
     */
    @PostMapping("/file/create-file")
    public Result<String> createFile(@RequestParam String filePath, 
                                     @RequestParam(required = false) String content) {
        return Result.success(agentService.createFile(filePath, content));
    }

    /**
     * 【新增】读取文件内容
     *
     * @param filePath 文件路径
     * @return 文件内容
     */
    @PostMapping("/file/read")
    public Result<String> readFile(@RequestParam String filePath) {
        return Result.success(agentService.readFile(filePath));
    }

    /**
     * 【新增】列出目录内容
     *
     * @param dirPath 目录路径
     * @return 目录内容列表
     */
    @PostMapping("/file/list-directory")
    public Result<String> listDirectory(@RequestParam String dirPath) {
        return Result.success(agentService.listDirectory(dirPath));
    }

    // ==================== 项目架构生成 ====================

    /**
     * 【新增】生成Spring Boot项目结构
     *
     * @param projectPath 项目根路径
     * @param projectName 项目名称
     * @param packageName 包名（如 com.example.demo）
     * @return 生成结果
     */
    @PostMapping("/project/generate-springboot")
    public Result<String> generateSpringBootProject(
            @RequestParam String projectPath,
            @RequestParam String projectName,
            @RequestParam String packageName) {
        return Result.success(agentService.generateSpringBootProject(projectPath, projectName, packageName));
    }

    /**
     * 【新增】生成CRUD模块代码
     *
     * @param projectPath 项目路径
     * @param packageName 包名
     * @param entityName  实体名称
     * @return 生成结果
     */
    @PostMapping("/project/generate-crud-module")
    public Result<String> generateCrudModuleCode(
            @RequestParam String projectPath,
            @RequestParam String packageName,
            @RequestParam String entityName) {
        return Result.success(agentService.generateCrudModuleCode(projectPath, packageName, entityName));
    }

    /**
     * 【新增】设计项目架构方案（AI分析）
     *
     * @param requirement 需求描述
     * @return AI架构设计方案
     */
    @PostMapping("/project/design-architecture")
    public Result<String> designProjectArchitecture(@RequestParam String requirement) {
        return Result.success(agentService.designProjectArchitecture(requirement));
    }

    /**
     * 构建意图分析摘要（返回给前端）
     */
    private Map<String, Object> buildAnalysisSummary(TaskAnalysisResult analysis) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("primaryIntent", analysis.getPrimaryIntent().name());
        summary.put("intentDisplayName", analysis.getPrimaryIntent().getDisplayName());
        summary.put("confidence", analysis.getConfidence());
        summary.put("isMultiTask", analysis.isMultiTask());
        summary.put("understoodRequirement", analysis.getUnderstoodRequirement());
        summary.put("extractedContentLength", analysis.getExtractedContent() != null ? analysis.getExtractedContent().length() : 0);
        summary.put("analysisTimeMs", analysis.getAnalysisTimeMs());

        if (analysis.isMultiTask() && !analysis.getSubTasks().isEmpty()) {
            List<Map<String, Object>> subTaskSummaries = new java.util.ArrayList<>();
            for (TaskAnalysisResult.SubTask subTask : analysis.getSubTasks()) {
                Map<String, Object> st = new LinkedHashMap<>();
                st.put("index", subTask.getIndex());
                st.put("intent", subTask.getIntent().name());
                st.put("intentDisplayName", subTask.getIntent().getDisplayName());
                st.put("description", subTask.getDescription());
                st.put("contentLength", subTask.getContent() != null ? subTask.getContent().length() : 0);
                subTaskSummaries.add(st);
            }
            summary.put("subTasks", subTaskSummaries);
        }

        return summary;
    }

    // ==================== 知识库管理 ====================

    /**
     * 1. 手动把文件夹内PDF文档入库知识库
     */
    @PostMapping("/lib/upload-doc")
    public Result<String> uploadDocLib(@RequestParam String folderPath) {
        ragService.loadDocsToVectorLib(folderPath);
        return Result.success("✅ 文档向量库增量更新完成");
    }

    /**
     * 2. 基础RAG问答接口（编码/需求查阅）
     */
    @PostMapping("/rag/query")
    public Result<String> ragQuery(@RequestParam String question) {
        return Result.success(ragService.ragQuery(question));
    }

    // ==================== Agent智能任务执行 ====================

    /**
     * 3. Agent综合任务入口，覆盖编码、需求、测试、文档、运维全链路
     * 示例：
     * - "帮我查找用户管理模块的代码"
     * - "为UserService生成单元测试"
     * - "分析这个报错日志"
     * - "生成订单表的CRUD代码"
     */
    @PostMapping("/agent/run")
    public Result<String> agentRunTask(@RequestParam String task) {
        return Result.success(agentService.runDevTask(task));
    }

    /**
     * 4. 批量任务处理
     * 示例：tasks=[
     *   {"task": "查找用户管理代码"},
     *   {"task": "生成单元测试"}
     * ]
     */
    @PostMapping("/agent/batch-run")
    public Result<String> agentBatchRun(@RequestBody String tasks) {
        return Result.success(agentService.runBatchTasks(tasks));
    }

    /**
     * 多轮对话带上下文的任务处理（支持多轮对话，Agent调度工具）
     */
    @PostMapping("/agent/context-run")
    public Result<String> runTaskWithContext(@RequestParam String taskContent, @RequestParam String context) {
        return Result.success(agentService.runTaskWithContext(taskContent, context));
    }

    /**
     * 【新增】简单的问答接口，支持上下文传入（无对话ID管理，无持久化）
     * 前端需要自行维护对话历史，按格式传入
     *
     * @param question 当前问题
     * @param contextHistory 历史对话上下文，格式："用户: xxx\nAI: yyy\n用户: aaa\nAI: bbb"
     * @return AI回答
     */
    @PostMapping("/chat/ask")
    public Result<String> askWithContext(@RequestParam String question,
                                         @RequestParam(required = false) String contextHistory) {
        return Result.success(agentService.askWithContext(question, contextHistory));
    }

    /* 8. SQL性能优化分析
     * @param sql 需要优化的SQL语句
     * @return 优化建议和分析报告
     */
    @PostMapping("/sql/optimize")
    public Result<String> optimizeSql(@RequestParam String sql) {
        return Result.success(agentService.optimizeSql(sql));
    }

    /**
     * 9. 索引设计建议
     * @param tableSchema 表结构SQL
     * @param queryPatterns 查询模式描述
     * @return 索引设计方案
     */
    @PostMapping("/sql/index-design")
    public Result<String> designIndex(
            @RequestParam String tableSchema,
            @RequestParam String queryPatterns) {
        return Result.success(agentService.designIndex(tableSchema, queryPatterns));
    }

    /**
     * 10. SQL重写优化
     * @param sql 原始SQL
     * @return 优化后的高性能SQL
     */
    @PostMapping("/sql/rewrite")
    public Result<String> rewriteSql(@RequestParam String sql) {
        return Result.success(agentService.rewriteSql(sql));
    }

    /**
     * 11. 执行计划分析
     * @param explainOutput EXPLAIN命令输出
     * @return 详细分析报告
     */
    @PostMapping("/sql/explain-analysis")
    public Result<String> analyzeExplainPlan(@RequestParam String explainOutput) {
        return Result.success(agentService.analyzeExplainPlan(explainOutput));
    }

    /**
     * 12. 数据库表结构设计
     * @param requirement 业务需求描述
     * @return 完整的表结构SQL
     */
    @PostMapping("/sql/design-schema")
    public Result<String> designTableSchema(@RequestParam String requirement) {
        return Result.success(agentService.designTableSchema(requirement));
    }

    // ... existing code ...

    // ==================== 永久记忆管理 ====================

    /**
     * 13. 数据蒸馏 - 将对话保存为永久记忆
     * @return 蒸馏结果
     */
    @PostMapping("/memory/distill")
    public Result<String> distillMemory(@RequestBody DistillRequest request) {
        return Result.success(agentService.runMemoryDistillation(request.conversation, request.summary));
    }

    /**
     * 14. 检索永久记忆
     * @param query 查询关键词
     * @param category 分类（可选）
     * @return 相关记忆
     */
    @GetMapping("/memory/search")
    public Result<Object> searchMemories(@RequestParam String query,
                                 @RequestParam(required = false) String category) {
        return Result.success(memoryService.searchMemories(query, category, 10));
    }

    /**
     * 15. 获取记忆统计
     */
    @GetMapping("/memory/stats")
    public Result<Map<String, Object>> getMemoryStats() {
        return Result.success(memoryService.getMemoryStats());
    }

    /**
     * 16. 获取记忆健康报告
     */
    @GetMapping("/memory/health")
    public Result<Object> getMemoryHealth() {
        return Result.success(memoryService.getMemoryHealthReport());
    }

    /**
     * 17. 清理低价值记忆
     */
    @PostMapping("/memory/cleanup")
    public Result<String> cleanupMemories() {
        return Result.success(memoryService.cleanupLowValueMemories());
    }

    /**
     * 18. 归档旧记忆
     */
    @PostMapping("/memory/archive")
    public Result<String> archiveMemories() {
        return Result.success(memoryService.archiveOldMemories());
    }

    /**
     * 19. 清空所有记忆
     */
    @PostMapping("/memory/clear")
    public Result<String> clearMemories() {
        memoryService.clearAllMemories();
        return Result.success("已清空所有永久记忆");
    }

    /**
     * 20. 手动触发批量蒸馏（用于测试）
     */
    @PostMapping("/memory/distill-now")
    public Result<String> triggerDistillNow() {
        return Result.success(distillationScheduler.triggerDistillationNow());
    }

    /**
     * 24. 上传单个PDF文件到知识库（带安全校验）
     * @param file PDF文件
     * @return 上传结果
     */
    @PostMapping("/lib/upload-file")
    public Result<Map<String, Object>> uploadPdfFile(@RequestParam("file") MultipartFile file) {
        // 1. 文件大小校验
        if (file == null || file.isEmpty()) {
            return Result.badRequest("上传文件不能为空");
        }

        if (file.getSize() > maxFileSize) {
            return Result.badRequest("文件大小超过限制，最大允许 " + (maxFileSize / 1024 / 1024) + "MB");
        }

        // 2. 文件名安全校验（防注入）
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.trim().isEmpty()) {
            return Result.badRequest("文件名不能为空");
        }

        // 检测路径遍历攻击
        if (DANGEROUS_PATTERN.matcher(originalFilename).matches()) {
            return Result.badRequest("文件名包含非法字符，可能存在安全风险");
        }

        // 清理文件名（去除特殊字符）
        String sanitizedFilename = sanitizeFilename(originalFilename);
        if (sanitizedFilename.isEmpty()) {
            return Result.badRequest("文件名校验失败");
        }

        // 3. 文件类型白名单校验
        // 3.1 校验扩展名
        String extension = getFileExtension(sanitizedFilename);
        if (!ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
            return Result.badRequest("不支持的文件类型，仅允许上传: " + ALLOWED_EXTENSIONS);
        }

        // 3.2 校验MIME类型
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            return Result.badRequest("文件MIME类型不匹配，实际类型: " + contentType);
        }

        // 4. 安全校验通过后，使用清理后的文件名重新包装文件
        MultipartFile safeFile = new SanitizedMultipartFile(file, sanitizedFilename);

        return Result.success(ragService.uploadSinglePdf(safeFile));
    }

    /**
     * 清理文件名（防注入）
     */
    private String sanitizeFilename(String filename) {
        if (filename == null) {
            return "";
        }

        // 获取文件名（不含路径）
        String name = filename;
        int lastSeparator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        if (lastSeparator != -1) {
            name = filename.substring(lastSeparator + 1);
        }

        // 去除危险字符
        name = name.replaceAll("[^\\w\\u4e00-\\u9fa5\\-_. ]", "_");

        // 限制文件名长度（保留扩展名）
        int maxNameLength = 200;
        String extension = getFileExtension(name);
        String baseName = name.substring(0, name.length() - (extension.isEmpty() ? 0 : extension.length() + 1));

        if (baseName.length() > maxNameLength) {
            baseName = baseName.substring(0, maxNameLength);
        }

        return extension.isEmpty() ? baseName : baseName + "." + extension;
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        if (filename == null || filename.lastIndexOf('.') == -1) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * 25. 获取待蒸馏对话数量
     */
    @GetMapping("/memory/pending-count")
    public Result<Integer> getPendingDistillCount() {
        return Result.success(distillationScheduler.getPendingCount());
    }

    /**
     * 22. OCR批量处理文件夹下的PDF（支持图片PDF）
     * @param folderPath PDF文件夹路径
     * @return OCR处理结果列表
     */
    @PostMapping("/ocr/process-folder")
    public Result<List<OcrService.OcrResult>> batchOcrProcess(@RequestParam String folderPath) {
        return Result.success(ocrService.batchProcessFolder(folderPath));
    }

    /**
     * 23. OCR识别单个图片文件
     * @param imagePath 图片路径
     * @return 识别的文本
     */
    @GetMapping("/ocr/recognize-image")
    public Result<String> recognizeImage(@RequestParam String imagePath) {
        return Result.success(ocrService.recognizeTextFromImageFile(imagePath));
    }

    /**
     * 请求DTO
     */
    public static class DistillRequest {
        public String conversation;
        public String summary;

    }

    /**
     * 【新增】安全的MultipartFile包装类
     * 使用清理后的文件名，保留原始文件内容
     */
    public static class SanitizedMultipartFile implements MultipartFile {
        private final MultipartFile originalFile;
        private final String sanitizedFilename;

        public SanitizedMultipartFile(MultipartFile originalFile, String sanitizedFilename) {
            this.originalFile = originalFile;
            this.sanitizedFilename = sanitizedFilename;
        }

        @Override
        public String getName() {
            return originalFile.getName();
        }

        @Override
        public String getOriginalFilename() {
            return sanitizedFilename;
        }

        @Override
        public String getContentType() {
            return originalFile.getContentType();
        }

        @Override
        public boolean isEmpty() {
            return originalFile.isEmpty();
        }

        @Override
        public long getSize() {
            return originalFile.getSize();
        }

        @Override
        public byte[] getBytes() throws IOException {
            return originalFile.getBytes();
        }

        @Override
        public java.io.InputStream getInputStream() throws IOException {
            return originalFile.getInputStream();
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            originalFile.transferTo(dest);
        }
    }

    @Scheduled(cron = "0 0 2 * * ?") // 每天凌晨2点
    public void scheduledCleanup() {
        memoryService.cleanupLowValueMemories();
    }

}
