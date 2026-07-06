package com.devai.devaiplatform.controller;

import com.devai.devaiplatform.common.Result;
import com.devai.devaiplatform.config.agent.AgentConfig;
import com.devai.devaiplatform.config.agent.AgentConfigService;
import com.devai.devaiplatform.service.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private final ChatHistoryService chatHistoryService;  // 【新增】聊天历史服务
    private final AgentOrchestrator orchestrator;  // 多Agent编排器
    private final AgentConfigService agentConfigService;  // 【新增】Agent配置服务
    private final MessageRouterService messageRouterService;  // 【新增】消息路由服务

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

    /** 文件名时间戳格式 */
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 文件存储目录 */
    private static final String UPLOAD_TEMP_DIR = "./uploads/temp";


    public DevAiController(DevRagService ragService,
                           DevAgentService agentService,
                           OcrService ocrService,
                           MemoryDistillationScheduler distillationScheduler,
                           IntentAnalyzerService intentAnalyzerService,
                           TaskDispatcherService taskDispatcherService,
                           IdeaErrorAnalyzerService ideaErrorAnalyzerService,
                           ChatHistoryService chatHistoryService,
                           AgentConfigService agentConfigService,
                           AgentOrchestrator orchestrator,
                           MessageRouterService messageRouterService) {
        this.ragService = ragService;
        this.agentService = agentService;
        this.ocrService = ocrService;
        this.memoryService = new PersistentMemoryService();
        this.distillationScheduler = distillationScheduler;
        this.intentAnalyzerService = intentAnalyzerService;
        this.taskDispatcherService = taskDispatcherService;
        this.ideaErrorAnalyzerService = ideaErrorAnalyzerService;
        this.chatHistoryService = chatHistoryService;
        this.agentConfigService = agentConfigService;
        this.orchestrator = orchestrator;
        this.messageRouterService = messageRouterService;
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
     * 【SSE流式】Agent任务执行，实时推送进度给前端
     * 用EventSource监听: /api/dev-ai/agent/stream?task=xxx
     */
    @GetMapping("/agent/stream")
    public SseEmitter agentRunStream(@RequestParam String task) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10分钟超时

        DevAgentService.setProgressCallback((type, message) -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(type)
                        .data(message));
            } catch (Exception e) {
                // 客户端断开连接，忽略
            }
        });

        // 异步执行任务，避免阻塞SSE连接
        new Thread(() -> {
            try {
                String result = agentService.runDevTask(task);
                emitter.send(SseEmitter.event()
                        .name("result")
                        .data(result));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("执行失败: " + e.getMessage()));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            } finally {
                DevAgentService.clearProgressCallback();
            }
        }).start();

        return emitter;
    }

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
    /**
     * 【SSE流式】多轮对话带上下文的任务处理，实时推送进度给前端
     * 使用 SseEmitter 正确处理异步SSE，避免void+PrintWriter在异步线程中响应被关闭的问题
     */
    @PostMapping(value = "/agent/context-run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runTaskWithContext(@RequestBody Map<String, String> body) {
        // 超时设为5分钟
        SseEmitter emitter = new SseEmitter(300_000L);
        // 跟踪emitter是否已完成（客户端断开/超时/出错都会导致emitter提前完成）
        AtomicBoolean emitterDone = new AtomicBoolean(false);
        emitter.onCompletion(() -> emitterDone.set(true));
        emitter.onTimeout(() -> emitterDone.set(true));
        emitter.onError(e -> emitterDone.set(true));

        String taskContent = body.getOrDefault("taskContent", "");
        String context = body.getOrDefault("context", "");
        boolean enableRag = Boolean.parseBoolean(body.getOrDefault("enableRag", "false"));
        boolean enableWebSearch = Boolean.parseBoolean(body.getOrDefault("enableWebSearch", "false"));

        // ========== 【智能路由】自动判断是否需要走向量库/联网搜索 ==========
        MessageRouteType routeType = messageRouterService.route(taskContent);
        System.out.println("[智能路由] 消息路由结果: " + routeType.getDisplayName()
                + " → enableRag=" + routeType.isEnableRag() + ", enableWebSearch=" + routeType.isEnableWebSearch());

        String prompt = "历史上下文：\n" + context + "\n\n当前任务：" + taskContent;

        // 安全发送：检查emitter是否已完成
        java.util.function.BiConsumer<String, String> safeSend = (eventType, data) -> {
            if (!emitterDone.get()) {
                try {
                    emitter.send(SseEmitter.event().name(eventType).data(data));
                } catch (Exception e) {
                    emitterDone.set(true); // 发送失败说明emitter已不可用
                }
            }
        };

        // 在新线程中设置ThreadLocal回调 + 执行任务
        new Thread(() -> {
            try {
                // 在当前线程设置进度回调（ThreadLocal是线程隔离的）
                DevAgentService.setProgressCallback((type, message) -> safeSend.accept(type, message));

                String result;
                if (routeType == MessageRouteType.DIRECT_CHAT) {
                    // 【DIRECT_CHAT】简单问题直接调LLM，跳过编排器和工具链
                    result = orchestrator.directChat(taskContent);
                } else {
                    // 【复杂问题】走完整的多Agent编排流程
                    final boolean ragEnabled = routeType.isEnableRag();
                    final boolean webSearchEnabled = routeType.isEnableWebSearch();
                    result = orchestrator.orchestrate(prompt, ragEnabled, webSearchEnabled);
                }
                safeSend.accept("result", result);
                if (!emitterDone.get()) {
                    emitter.complete();
                }
            } catch (Exception e) {
                System.err.println("[SSE] 执行失败: " + e.getMessage());
                e.printStackTrace();
                safeSend.accept("error", "执行失败: " + e.getMessage());
                if (!emitterDone.get()) {
                    try { emitter.complete(); } catch (Exception ignored) {}
                }
            } finally {
                DevAgentService.clearProgressCallback();
            }
        }).start();

        return emitter;
    }

    /**
     * 【新增】简单的问答接口，支持上下文传入（无对话ID管理，无持久化）
     * 前端需要自行维护对话历史，按格式传入
     *
     * @param question 当前问题
     * @param contextHistory 历史对话上下文，格式："用户: xxx\nAI: yyy\n用户: aaa\nAI: bbb"
     * @param enableRag 是否启用RAG检索（默认false）
     * @param enableWebSearch 是否启用联网搜索（默认false）
     * @return AI回答
     */
    @PostMapping("/chat/ask")
    public Result<String> askWithContext(@RequestParam String question,
                                         @RequestParam(required = false) String contextHistory,
                                         @RequestParam(required = false, defaultValue = "false") boolean enableRag,
                                         @RequestParam(required = false, defaultValue = "false") boolean enableWebSearch) {
        // 【智能路由】自动判定路由，覆盖前端手动开关
        MessageRouteType routeType = messageRouterService.route(question);
        boolean finalRag = routeType.isEnableRag();
        boolean finalWebSearch = routeType.isEnableWebSearch();
        System.out.println("[chat/ask 智能路由] " + routeType.getDisplayName()
                + " → rag=" + finalRag + ", webSearch=" + finalWebSearch);

        // DIRECT_CHAT 简单问题直接回答，不走检索
        if (routeType == MessageRouteType.DIRECT_CHAT) {
            return Result.success(agentService.askWithContext(question, contextHistory, false, false));
        }
        return Result.success(agentService.askWithContext(question, contextHistory, finalRag, finalWebSearch));
    }

    /* 8. SQL性能优化分析
     * @param sql 需要优化的SQL语句
     * @return 优化建议和分析报告
     */
    @PostMapping("/sql/optimize")
    public Result<String> optimizeSql(@RequestParam String sql) {
        return Result.success(agentService.generateContent("sql_optimize", sql));
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
        return Result.success(agentService.generateContent("sql_design", tableSchema + "\n查询模式:" + queryPatterns));
    }

    /**
     * 10. SQL重写优化
     * @param sql 原始SQL
     * @return 优化后的高性能SQL
     */
    @PostMapping("/sql/rewrite")
    public Result<String> rewriteSql(@RequestParam String sql) {
        return Result.success(agentService.generateContent("sql_rewrite", sql));
    }

    /**
     * 11. 执行计划分析
     * @param explainOutput EXPLAIN命令输出
     * @return 详细分析报告
     */
    @PostMapping("/sql/explain-analysis")
    public Result<String> analyzeExplainPlan(@RequestParam String explainOutput) {
        return Result.success(agentService.generateContent("explain_analysis", explainOutput));
    }

    /**
     * 12. 数据库表结构设计
     * @param requirement 业务需求描述
     * @return 完整的表结构SQL
     */
    @PostMapping("/sql/design-schema")
    public Result<String> designTableSchema(@RequestParam String requirement) {
        return Result.success(agentService.generateContent("table_design", requirement));
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
     * 22b. OCR批量处理上传的PDF文件（前端选择文件夹后直接上传文件）
     * 接收多文件上传 → 保存到临时目录 → OCR处理 → 清理
     * @param files 上传的PDF文件列表
     * @return OCR处理结果列表
     */
    @PostMapping("/ocr/process-files")
    public Result<List<OcrService.OcrResult>> batchOcrProcessFiles(@RequestParam("files") List<MultipartFile> files) {
        System.out.println("\n[OCR批量上传] 收到 " + files.size() + " 个文件");

        if (files.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        // 创建临时目录（用于OCR处理）
        Path tempDir = null;
        // 文件名映射：原始安全名 → 唯一文件名（带时间戳，向量库与磁盘一致）
        Map<String, String> fileNameMapping = new LinkedHashMap<>();
        try {
            tempDir = Files.createTempDirectory("ocr_batch_");
            System.out.println("[OCR批量上传] 临时目录: " + tempDir);

            // 确保 uploads/temp/ 目录存在
            Path uploadDir = Paths.get(UPLOAD_TEMP_DIR);
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }
            // 生成批次时间戳（同一批次所有文件共享，确保一致性）
            String batchTs = LocalDateTime.now().format(TS_FORMATTER);

            // 保存所有上传的文件到临时目录 + 同时保存到 uploads/temp/
            int savedCount = 0;
            for (MultipartFile file : files) {
                String originalFilename = file.getOriginalFilename();
                if (originalFilename == null || originalFilename.isBlank()) {
                    continue;
                }
                // 只处理PDF文件
                String lower = originalFilename.toLowerCase();
                if (!lower.endsWith(".pdf")) {
                    System.out.println("[OCR批量上传] 跳过非PDF文件: " + originalFilename);
                    continue;
                }

                // 安全文件名处理
                String safeName = sanitizeFilename(originalFilename);
                // 给文件名追加时间戳
                String uniqueName = appendTimestamp(safeName, batchTs);

                // 保存到 OCR 临时目录（用安全名，OCR处理用）
                Path tempTarget = tempDir.resolve(safeName);
                file.transferTo(tempTarget.toFile());

                // 同时保存到 uploads/temp/（用唯一名，供后续预览下载）
                Path uploadTarget = uploadDir.resolve(uniqueName);
                Files.copy(tempTarget, uploadTarget);

                fileNameMapping.put(safeName, uniqueName);
                savedCount++;
                System.out.println("[OCR批量上传] 已保存: " + safeName + " → 源文件: " + uniqueName);
            }

            if (savedCount == 0) {
                System.out.println("[OCR批量上传] 没有有效的PDF文件");
                return Result.success(Collections.emptyList());
            }

            System.out.println("[OCR批量上传] 共保存 " + savedCount + " 个PDF（源文件已同步到 uploads/temp/），开始OCR处理...");

            // 调用现有的批量处理逻辑
            List<OcrService.OcrResult> results = ocrService.batchProcessFolder(tempDir.toString());

            // 【关键】将OCR结果写入向量库（传入文件名映射，确保向量库 file_name 与磁盘一致）
            int ingested = ragService.ingestOcrResults(results, fileNameMapping);
            System.out.println("[OCR批量上传] 向量库入库: " + ingested + "/" + results.size() + " 个文档");

            return Result.success(results);

        } catch (IOException e) {
            System.err.println("[OCR批量上传] 处理失败: " + e.getMessage());
            e.printStackTrace();
            return Result.error("OCR批量处理失败: " + e.getMessage());
        } finally {
            // 清理临时目录
            if (tempDir != null) {
                try {
                    File[] files2 = tempDir.toFile().listFiles();
                    if (files2 != null) {
                        for (File f : files2) {
                            f.delete();
                        }
                    }
                    Files.deleteIfExists(tempDir);
                    System.out.println("[OCR批量上传] 临时目录已清理");
                } catch (IOException e) {
                    System.err.println("[OCR批量上传] 清理临时目录失败: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 给文件名追加时间戳
     * 如 "简历.pdf" + "20260704153022" → "简历_20260704153022.pdf"
     */
    private String appendTimestamp(String fileName, String timestamp) {
        String baseName = fileName;
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }
        return baseName + "_" + timestamp + extension;
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

    // ==================== 文件预览与下载 ====================

    /**
     * 26. 文件预览/下载（支持PDF等文件）
     * 优先查找 uploads/temp/，其次查找 templates/ 目录
     * @param name 文件名（URL编码），如 教育部学历证书电子注册备案表_李文昊-2.pdf
     */
    @GetMapping("/file/preview")
    public void filePreview(@RequestParam String name, HttpServletResponse response) {
        try {
            // URL解码
            String decodedName = URLDecoder.decode(name, StandardCharsets.UTF_8);
            // 安全校验：防路径遍历
            if (decodedName.contains("..") || decodedName.contains("/") || decodedName.contains("\\")) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "非法文件名");
                return;
            }

            // 多路径查找文件
            Path filePath = null;
            String[] searchDirs = {
                "./uploads/temp",
                "./templates",
                "./src/main/resources/templates"
            };
            for (String dir : searchDirs) {
                Path candidate = Paths.get(dir, decodedName);
                if (Files.exists(candidate)) {
                    filePath = candidate;
                    break;
                }
            }

            if (filePath == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "文件不存在: " + decodedName);
                return;
            }

            // 设置Content-Type
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                String lower = decodedName.toLowerCase();
                if (lower.endsWith(".pdf")) contentType = "application/pdf";
                else if (lower.endsWith(".png")) contentType = "image/png";
                else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) contentType = "image/jpeg";
                else contentType = "application/octet-stream";
            }
            response.setContentType(contentType);

            // inline预览（浏览器内打开），不做附件下载
            String encodedFilename = URLEncoder.encode(decodedName, StandardCharsets.UTF_8).replace("+", "%20");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "inline; filename*=UTF-8''" + encodedFilename);
            response.setContentLengthLong(Files.size(filePath));

            // 流式输出
            try (FileInputStream fis = new FileInputStream(filePath.toFile());
                 OutputStream os = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                os.flush();
            }
        } catch (Exception e) {
            try {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "文件预览失败: " + e.getMessage());
            } catch (IOException ex) {
                // ignore
            }
        }
    }

    // ==================== 聊天历史会话管理 ====================

    /**
     * 获取所有历史会话列表（摘要，按时间倒序）
     */
    @GetMapping("/history/list")
    public Result<List<ChatHistoryService.ChatSession>> listHistorySessions() {
        return Result.success(chatHistoryService.listSessions());
    }

    /**
     * 获取单个历史会话的完整内容（含消息列表）
     */
    @GetMapping("/history/get")
    public Result<ChatHistoryService.ChatSession> getHistorySession(@RequestParam String sessionId) {
        ChatHistoryService.ChatSession session = chatHistoryService.getSession(sessionId);
        if (session == null) {
            return Result.notFound("会话不存在");
        }
        return Result.success(session);
    }

    /**
     * 保存/更新聊天会话
     */
    @PostMapping("/history/save")
    public Result<ChatHistoryService.ChatSession> saveHistorySession(@RequestBody ChatHistoryService.ChatSession session) {
        return Result.success(chatHistoryService.saveSession(session));
    }

    /**
     * 删除单个历史会话
     */
    @DeleteMapping("/history/delete")
    public Result<String> deleteHistorySession(@RequestParam String sessionId) {
        boolean deleted = chatHistoryService.deleteSession(sessionId);
        return deleted ? Result.success("已删除") : Result.notFound("会话不存在");
    }

    /**
     * 清空所有历史会话
     */
    @DeleteMapping("/history/clear")
    public Result<String> clearAllHistory() {
        int count = chatHistoryService.clearAllSessions();
        return Result.success("已清空 " + count + " 个会话");
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

    // ==================== Agent配置管理面板API ====================

    /**
     * 获取Agent完整配置（用于前端管理面板展示）
     */
    @GetMapping("/agent/config")
    public Result<AgentConfig> getAgentConfig() {
        return Result.success(agentConfigService.getConfig());
    }

    /**
     * 获取Agent配置摘要（自愈状态、升级策略等关键信息）
     */
    @GetMapping("/agent/config/summary")
    public Result<Map<String, Object>> getAgentConfigSummary() {
        AgentConfig config = agentConfigService.getConfig();
        Map<String, Object> summary = new LinkedHashMap<>();

        if (config.getAgentMetadata() != null) {
            summary.put("agentName", config.getAgentMetadata().getAgentName());
            summary.put("agentVersion", config.getAgentMetadata().getAgentVersion());
            summary.put("description", config.getAgentMetadata().getDescription());
        }

        if (config.getSelfHealingMechanism() != null) {
            summary.put("selfHealingEnabled", config.getSelfHealingMechanism().isEnabled());
            if (config.getSelfHealingMechanism().getHealthCheck() != null) {
                summary.put("healthCheckFrequencySec",
                        config.getSelfHealingMechanism().getHealthCheck().getFrequencySeconds());
                summary.put("healthMetrics",
                        config.getSelfHealingMechanism().getHealthCheck().getMetrics());
            }
            if (config.getSelfHealingMechanism().getRemediationStrategies() != null) {
                summary.put("remediationStrategyCount",
                        config.getSelfHealingMechanism().getRemediationStrategies().size());
            }
        }

        if (config.getSelfUpgradeMechanism() != null) {
            summary.put("upgradeEnabled", config.getSelfUpgradeMechanism().isEnabled());
            if (config.getSelfUpgradeMechanism().getUpgradePolicy() != null) {
                summary.put("upgradePolicyType",
                        config.getSelfUpgradeMechanism().getUpgradePolicy().getType());
                summary.put("approvalRequired",
                        config.getSelfUpgradeMechanism().getUpgradePolicy().isApprovalRequired());
            }
        }

        if (config.getCoreCapabilities() != null) {
            if (config.getCoreCapabilities().getPerception() != null) {
                summary.put("sensorCount",
                        config.getCoreCapabilities().getPerception().getSensors() != null
                                ? config.getCoreCapabilities().getPerception().getSensors().size() : 0);
            }
            if (config.getCoreCapabilities().getAction() != null) {
                summary.put("toolCount",
                        config.getCoreCapabilities().getAction().getActionTools() != null
                                ? config.getCoreCapabilities().getAction().getActionTools().size() : 0);
            }
        }

        return Result.success(summary);
    }

    @Scheduled(cron = "0 0 2 * * ?") // 每天凌晨2点
    public void scheduledCleanup() {
        memoryService.cleanupLowValueMemories();
    }

}
