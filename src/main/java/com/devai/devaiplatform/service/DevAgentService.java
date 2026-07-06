package com.devai.devaiplatform.service;


import com.devai.devaiplatform.config.agent.AgentConfig;
import com.devai.devaiplatform.config.agent.AgentConfigService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DevAgentService {
    private final ChatLanguageModel chatModel;
    private final AgentConfigService agentConfigService;
    private final DevRagService ragService;
    private final VectorStoreService vectorStoreService;
    private final PersistentMemoryService memoryService;
    private final MemoryDistillationScheduler distillationScheduler;  // 【新增】蒸馏调度器
    private final IdeaErrorAnalyzerService ideaErrorAnalyzerService;  // 【新增】IDEA错误分析服务
    private final LocalFileOperationService fileOperationService;
    private final ProjectStructureGenerator projectStructureGenerator;
    private final ScriptExecutorService scriptExecutorService;
    private final WebSearchService webSearchService;

    /**
     * 单次Agent任务的最大连续工具调用次数，防止AI陷入死循环
     */
    private static final int MAX_TOOL_CALLS = 50;

    /**
     * 当前Agent任务已执行的工具调用计数
     */
    private final AtomicInteger toolCallCount = new AtomicInteger(0);

    /**
     * ThreadLocal进度回调，用于SSE实时推送工具调用进度到前端
     */
    private static final ThreadLocal<TaskProgressCallback> progressCallback = new ThreadLocal<>();

    /**
     * 设置当前任务的进度回调
     */
    public static void setProgressCallback(TaskProgressCallback callback) {
        progressCallback.set(callback);
    }

    /**
     * 清除当前任务的进度回调
     */
    public static void clearProgressCallback() {
        progressCallback.remove();
    }

    /** 公开的进度报告方法，供AgentOrchestrator调用 */
    void reportProgressPublic(String type, String message) {
        reportProgress(type, message);
    }

    /**
     * 向当前任务的进度回调发送进度通知
     */
    private void reportProgress(String type, String message) {
        TaskProgressCallback cb = progressCallback.get();
        if (cb != null) {
            cb.onProgress(type, message);
        }
        // 同时输出到控制台用于调试
        System.out.println("[" + type + "] " + message);
    }

    /**
     * 是否已触发工具调用上限，触发后后续工具调用直接返回停止提示
     */
    private volatile boolean toolLimitReached = false;

    /**
     * 工具调用已达上限时的返回消息（返回给AI，让AI自行总结已有结果）
     */
    private static final String TOOL_LIMIT_MESSAGE = "【系统提示】工具调用次数已达" + MAX_TOOL_CALLS + "次上限。请立即基于已有结果进行总结回答，不要继续调用任何工具。你可以告诉用户：点击下方'继续优化'按钮将自动分配新一轮配额继续执行。";

    /**
     * 检查是否已达工具调用上限
     */
    private void checkToolLimit() {
        if (toolLimitReached) {
            return; // 已触发上限，不再递增计数
        }
        int count = toolCallCount.incrementAndGet();
        if (count > MAX_TOOL_CALLS) {
            toolLimitReached = true;
            reportProgress("warn", "已达50次上限");
        }
    }

    /**
     * 检查工具调用是否已被限制，若是则返回停止消息
     */
    private String checkToolLimitAndStop() {
        checkToolLimit();
        return toolLimitReached ? TOOL_LIMIT_MESSAGE : null;
    }

    /**
     * Agent任务开始前重置工具调用计数器
     */
    private void resetToolCounter() {
        toolCallCount.set(0);
        toolLimitReached = false;
    }

    public DevAgentService(ChatLanguageModel chatModel,
                           DevRagService ragService,
                           VectorStoreService vectorStoreService,
                           PersistentMemoryService memoryService,
                           MemoryDistillationScheduler distillationScheduler,
                           IdeaErrorAnalyzerService ideaErrorAnalyzerService,
                           LocalFileOperationService fileOperationService,
                           ProjectStructureGenerator projectStructureGenerator,
                           ScriptExecutorService scriptExecutorService,
                           WebSearchService webSearchService,
                           AgentConfigService agentConfigService
    ) {
        this.chatModel = chatModel;
        this.agentConfigService = agentConfigService;
        this.ragService = ragService;
        this.vectorStoreService = vectorStoreService;
        this.memoryService = memoryService;
        this.distillationScheduler = distillationScheduler;
        this.ideaErrorAnalyzerService = ideaErrorAnalyzerService;
        this.fileOperationService = fileOperationService;
        this.projectStructureGenerator = projectStructureGenerator;
        this.scriptExecutorService = scriptExecutorService;
        this.webSearchService = webSearchService;
    }

    /**
     * 确保Tool方法返回值不为null或空白，防止LangChain4j抛出IllegalArgumentException
     */
    private String safeReturn(String result) {
        String stopMsg = checkToolLimitAndStop();
        if (stopMsg != null) {
            return stopMsg;
        }
        if (result == null || result.isBlank()) {
            return "（工具执行完成，无具体输出）";
        }
        return result;
    }

    /**
     * 安全调用AI模型，确保返回值不为空
     */
    private String safeGenerate(String prompt) {
        try {
            String result = chatModel.generate(prompt);
            return safeReturn(result);
        } catch (Exception e) {
            System.err.println("[错误] 调用AI模型失败: " + e.getMessage());
            return "调用AI服务失败，请检查网络或API配置。错误信息: " + e.getMessage();
        }
    }

    // ==================== 工具定义 ====================

    // ==================== 工具定义 ====================

    @Tool("查询项目内部代码、需求文档、历史方案资料。适用于：查找代码示例、理解业务逻辑、查看历史技术方案")
    public String searchDevLib(String question) {
        reportProgress("tool", "检索知识库: " + question);
        String relevantMemories = memoryService.getRelevantMemories(question);
        String enhancedQuestion = question + "\n\n历史参考:\n" + relevantMemories;
        return safeReturn(ragService.ragQuery(enhancedQuestion));
    }

    @Tool("根据用户需求的类型和详细描述，生成对应的技术内容。taskType可选值：unit_test(单元测试)、api_doc(接口文档)、prd_doc(需求文档)、backend_code(后端代码)、frontend_code(前端代码)、api_call_code(API调用代码)、validation_code(数据验证代码)、migration_script(数据库迁移脚本)、code_review(代码审查)、refactoring(代码重构建议)、config_file(配置文件)、text_summary(文本摘要)、sql_optimize(SQL优化建议)、sql_design(索引设计)、sql_rewrite(SQL重写)、explain_analysis(执行计划分析)、table_design(表结构设计)、crud_sql(表结构SQL生成CRUD)。适用于所有需要AI生成技术内容的场景")
    public String generateContent(String taskType, String requirement) {
        reportProgress("tool", "生成内容: " + taskType);
        String template = PromptTemplate.getTemplate(taskType);
        if (template == null) {
            return "未知的任务类型: " + taskType + "，可用类型：unit_test, api_doc, prd_doc, backend_code 等";
        }
        return safeGenerate(String.format(template, requirement));
    }

    @Tool("将重要对话内容蒸馏为永久记忆，自动提取关键信息并评分。适用于：保存技术方案、记录故障处理经验、保存最佳实践")
    public String distillConversationToMemory(String conversation, String summary) {
        reportProgress("tool", "数据蒸馏: " + summary);
        return safeReturn(memoryService.distillMemory(conversation, summary));
    }

    @Tool("检索历史记忆和經驗，查找类似问题的解决方案或相关技术文档")
    public String retrieveMemories(String query, String category) {
        reportProgress("tool", "检索记忆: " + query);
        return safeReturn(memoryService.getRelevantMemories(query));
    }

    @Tool("获取记忆库统计信息，包括记忆数量、分类分布、重要性等")
    public String getMemoryStatistics() {
        reportProgress("tool", "获取记忆统计");
        String stopMsg = checkToolLimitAndStop();
        if (stopMsg != null) return stopMsg;
        Map<String, Object> stats = memoryService.getMemoryStats();
        return "记忆库统计：\n" + stats.toString();
    }

    @Tool("输入报错日志，结合历史故障资料输出分步排查修复方案")
    public String analyzeErrorLog(String errorLog) {
        reportProgress("tool", "分析错误日志");
        try {
            String historyCase = ragService.ragQuery("报错：" + errorLog.substring(0, Math.min(150, errorLog.length())));
            String prompt = String.format(PromptTemplate.ERROR_LOG_TEMPLATE, safeReturn(historyCase), errorLog);
            return safeGenerate(prompt);
        } catch (Exception e) {
            System.err.println("[错误] 分析错误日志失败: " + e.getMessage());
            return "调用AI服务失败，请检查网络或API配置。错误信息: " + e.getMessage();
        }
    }
    /**
     * 【核心】智能Agent执行入口，自动识别任务类型并调度相应工具
     *
     * @param taskContent 用户任务描述（自然语言）
     * @return 任务执行结果
     */
    public String runDevTask(String taskContent) {
        reportProgress("start", "任务内容: " + taskContent);

        // 【新增】检索相关历史记忆
        String relevantMemories = memoryService.getRelevantMemories(taskContent);
        reportProgress("info", "检索到 " + (relevantMemories != null && !relevantMemories.isEmpty() ? "历史记忆" : "0条记忆"));

        DevAgent agent = AiServices.builder(DevAgent.class)
                .chatLanguageModel(chatModel)
                .tools(this)
                .systemMessageProvider(chatMemory -> AGENT_SYSTEM_PROMPT)
                .build();

        // 将记忆注入到上下文中
        String enhancedTask = taskContent + "\n\n历史参考:\n" + relevantMemories;

        resetToolCounter();
        String result = agent.handleTask(enhancedTask);

        reportProgress("done", "Agent任务执行完成");
        
        // 【新增】自动记录对话用于后续批量蒸馏
        distillationScheduler.recordConversation(taskContent, result, "agent_task");
        
        return result;
    }

    /**
     * 【新增】数据蒸馏任务 - 从对话中提取永久记忆
     *
     * @param fullConversation 完整对话内容
     * @param keyTakeaway 关键要点摘要
     * @return 蒸馏结果
     */
    public String runMemoryDistillation(String fullConversation, String keyTakeaway) {
        System.out.println("\n========== 开始数据蒸馏 ==========");
        System.out.println("摘要: " + keyTakeaway);
        System.out.println("======================================\n");

        String result = memoryService.distillMemory(fullConversation, keyTakeaway);

        System.out.println("\n========== 蒸馏完成 ==========\n");
        return result;
    }

    // ==================== 脚本执行工具 ====================

    @Tool("执行本地脚本进行文件操作。可用脚本: file_list.bat(列出目录), file_read.bat(读文件), file_create.bat(创建目录), file_copy.bat(复制文件), file_search.bat(搜索文件), file_tree.bat(目录树)。参数用空格分隔，路径含空格请用引号括起")
    public String executeScript(String scriptName, String arg1, String arg2) {
        reportProgress("tool", "执行脚本: " + scriptName + " " + arg1 + " " + arg2);
        String result = scriptExecutorService.execute(scriptName, arg1, arg2);
        return safeReturn(result);
    }

    @Tool("列出所有可用的本地操作脚本及其用途")
    public String listScripts() {
        reportProgress("tool", "列出可用脚本");
        String stopMsg = checkToolLimitAndStop();
        if (stopMsg != null) return stopMsg;
        return "可用脚本列表:\n" +
               "  file_list.bat <目录>      - 列出目录内容\n" +
               "  file_read.bat <文件>      - 读取文件内容\n" +
               "  file_create.bat <目录>    - 创建目录\n" +
               "  file_copy.bat <源> <目标> - 复制文件\n" +
               "  file_search.bat <目录> <关键字> - 搜索文件\n" +
               "  file_tree.bat [目录]      - 显示目录树\n" +
               "\n当前已安装脚本: " + scriptExecutorService.listAvailableScripts();
    }

    // ==================== IDEA错误分析修复工具 ====================

    /**
     * 【新增】扫描IDEA编译错误并生成修复方案
     * @param projectPath 项目路径（可选，为空时使用默认路径）
     * @return AI修复建议
     */
    @Tool("扫描IntelliJ IDEA项目的编译错误，自动分析错误原因并生成修复方案。适用于：项目编译失败、批量修复错误、代码审查")
    public String scanAndFixIdeaErrors(String projectPath) {
        reportProgress("tool", "扫描IDEA编译错误");
        String stopMsg = checkToolLimitAndStop();
        if (stopMsg != null) return stopMsg;
        // 1. 执行编译扫描
        IdeaErrorAnalyzerService.CompileResult result = ideaErrorAnalyzerService.scanCompileErrors(projectPath);
        
        if (result.success) {
            return "✅ 编译成功，无错误！";
        }
        
        // 2. 构建错误上下文
        String errorContext = ideaErrorAnalyzerService.buildFixContext(result);
        
        // 3. 调用AI分析修复
        String prompt = String.format(PromptTemplate.IDEA_COMPILE_ERROR_FIX_TEMPLATE, errorContext);
        return safeGenerate(prompt);
    }

    /**
     * 【新增】分析运行时异常堆栈并生成修复方案
     * @param stackTrace 异常堆栈信息
     * @return AI修复建议
     */
    @Tool("分析Java运行时异常堆栈，定位问题根因并生成修复方案。适用于：NullPointerException、ClassCastException等运行时错误排查")
    public String analyzeRuntimeError(String stackTrace) {
        reportProgress("tool", "分析运行时异常");
        String prompt = String.format(PromptTemplate.IDEA_RUNTIME_ERROR_FIX_TEMPLATE, stackTrace, "（无相关源码）");
        return safeGenerate(prompt);
    }

    // ==================== 本地文件操作工具 ====================

    /**
     * 【新增】创建目录
     * @param dirPath 目录路径
     * @return 创建结果
     */
    @Tool("在本地文件系统创建目录，支持多级目录自动创建。适用于：创建项目目录、模块目录等")
    public String createDirectory(String dirPath) {
        reportProgress("tool", "创建目录: " + dirPath);
        String stopMsg = checkToolLimitAndStop();
        if (stopMsg != null) return stopMsg;
        LocalFileOperationService.FileOperationResult result = fileOperationService.createDirectory(dirPath);
        return result.success ? "✅ " + result.message : "❌ " + result.message;
    }

    /**
     * 【新增】创建文件
     * @param filePath 文件路径
     * @param content 文件内容
     * @return 创建结果
     */
    @Tool("在本地文件系统创建文件，自动创建父目录。适用于：创建代码文件、配置文件、文档文件等")
    public String createFile(String filePath, String content) {
        reportProgress("tool", "创建文件: " + filePath);
        String stopMsg = checkToolLimitAndStop();
        if (stopMsg != null) return stopMsg;
        LocalFileOperationService.FileOperationResult result = fileOperationService.createFile(filePath, content);
        return result.success ? "✅ " + result.message : "❌ " + result.message;
    }

    /**
     * 【新增】读取文件内容
     * @param filePath 文件路径
     * @return 文件内容
     */
    @Tool("读取本地文件内容。适用于：查看代码文件、配置文件、日志文件等")
    public String readFile(String filePath) {
        reportProgress("tool", "读取文件: " + filePath);
        String stopMsg = checkToolLimitAndStop();
        if (stopMsg != null) return stopMsg;
        return fileOperationService.readFile(filePath);
    }

    /**
     * 【新增】列出目录内容
     * @param dirPath 目录路径
     * @return 目录内容列表
     */
    @Tool("列出指定目录下的文件和子目录。适用于：查看项目结构、查找文件等")
    public String listDirectory(String dirPath) {
        reportProgress("tool", "列出目录: " + dirPath);
        String stopMsg = checkToolLimitAndStop();
        if (stopMsg != null) return stopMsg;
        var items = fileOperationService.listDirectory(dirPath, false);
        if (items.isEmpty()) {
            return "目录为空或不存在";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("目录内容:\n");
        for (var item : items) {
            sb.append(item.type.equals("DIR") ? "📁 " : "📄 ")
              .append(item.path.substring(item.path.lastIndexOf(java.io.File.separator) + 1))
              .append("\n");
        }
        return sb.toString();
    }

    /**
     * 【新增】生成Spring Boot项目结构
     * @param projectPath 项目根路径
     * @param projectName 项目名称
     * @param packageName 包名
     * @return 生成结果
     */
    @Tool("生成完整的Spring Boot项目结构，包括标准目录布局、pom.xml、主启动类、配置文件等。适用于：快速搭建新项目")
    public String generateSpringBootProject(String projectPath, String projectName, String packageName) {
        reportProgress("tool", "生成Spring Boot项目: " + projectName);
        String stopMsg = checkToolLimitAndStop();
        if (stopMsg != null) return stopMsg;
        ProjectStructureGenerator.GenerateResult result =
                projectStructureGenerator.generateSpringBootProject(projectPath, projectName, packageName);
        
        if (result.success) {
            return "✅ 项目生成成功！\n项目路径: " + result.projectPath + 
                   "\n生成文件数: " + result.items.size();
        } else {
            return "❌ 项目生成失败: " + result.message;
        }
    }

    /**
     * 【新增】生成CRUD模块代码
     * @param projectPath 项目路径
     * @param packageName 包名
     * @param entityName 实体名称
     * @return 生成结果
     */
    @Tool("为指定实体生成完整的CRUD代码，包括Entity、Repository、Service、Controller、DTO等。适用于：快速生成业务模块代码")
    public String generateCrudModuleCode(String projectPath, String packageName, String entityName) {
        reportProgress("tool", "生成CRUD模块代码: " + entityName);
        String stopMsg = checkToolLimitAndStop();
        if (stopMsg != null) return stopMsg;
        ProjectStructureGenerator.GenerateResult result = 
                projectStructureGenerator.generateLayeredCode(projectPath, packageName, entityName, entityName);
        
        if (result.success) {
            return "✅ " + entityName + " 模块代码生成成功！\n生成文件数: " + result.items.size();
        } else {
            return "❌ 代码生成失败: " + result.message;
        }
    }

    /**
     * 【新增】设计项目架构方案
     * @param requirement 需求描述
     * @return AI架构设计方案
     */
    @Tool("根据需求设计项目架构方案，包括技术选型、目录结构、模块划分、代码骨架等。适用于：新项目架构设计、架构评审")
    public String designProjectArchitecture(String requirement) {
        reportProgress("tool", "设计项目架构");
        String prompt = String.format(PromptTemplate.PROJECT_STRUCTURE_DESIGN_TEMPLATE, requirement);
        return safeGenerate(prompt);
    }

    // ==================== 网页搜索与信息检索工具 ====================

    /**
     * 【新增】网络信息搜索 - 多轮筛选确保信息完整
     * 适用于：查询最新技术文档、API 用法、开源项目、技术方案对比等
     */
    @Tool("在网络中搜索相关技术信息，经过多轮筛选去重后返回高质量内容。适用于：查询最新技术动态、API文档、开源方案、技术对比、行业资讯等需要实时网络资料的场景")
    public String searchWeb(String query) {
        reportProgress("tool", "网络搜索: " + query);
        String stopMsg = checkToolLimitAndStop();
        if (stopMsg != null) return stopMsg;
        try {
            WebSearchService.WebSearchResult result = webSearchService.search(query);
            return result.toPromptContext();
        } catch (Exception e) {
            System.err.println("[工具调用] 网络搜索失败: " + e.getMessage());
            return "网络搜索失败: " + e.getMessage() + "。请基于已有知识回答用户问题。";
        }
    }


    // ==================== Agent执行入口 ====================



    /**
     * 批量任务处理（支持多个子任务）
     *
     * @param tasks 任务列表（JSON数组格式）
     * @return 每个任务的执行结果
     */
    public String runBatchTasks(String tasks) {
        reportProgress("start", "开始执行批量任务");

        DevAgent agent = AiServices.builder(DevAgent.class)
                .chatLanguageModel(chatModel)
                .tools(this)
                .systemMessageProvider(chatMemory -> AGENT_SYSTEM_PROMPT)
                .build();

        String prompt = "请依次执行以下任务，每个任务完成后输出结果：\n\n" + tasks;
        resetToolCounter();
        String result = agent.handleTask(prompt);

        reportProgress("done", "批量任务执行完成");
        return result;
    }

    /**
     * 带上下文的任务处理（支持多轮对话）
     *
     * @return 任务执行结果
     */
    public String runTaskWithContext(TaskAnalysisResult analysis) {
        reportProgress("start", "意图: " + analysis.getPrimaryIntent().getDisplayName());

        DevAgent agent = AiServices.builder(DevAgent.class)
                .chatLanguageModel(chatModel)
                .tools(this)
                .systemMessageProvider(chatMemory -> AGENT_SYSTEM_PROMPT)
                .build();
       // 将 analysis 对象序列化为任务描述字符串
        String taskDescription = buildTaskDescription(analysis);
        System.out.println("任务描述字符串 = " + taskDescription);
        resetToolCounter();
        String result = agent.handleTask(taskDescription);

        reportProgress("done", "任务执行完成");
        return result;
    }

    /**
     * 将 TaskAnalysisResult 对象转换为可传递给 Agent 的文本描述
     */
    private String buildTaskDescription(TaskAnalysisResult analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("【任务分析结果】\n");
        sb.append("- 原始消息: ").append(analysis.getOriginalMessage()).append("\n");
        sb.append("- 识别意图: ").append(analysis.getPrimaryIntent().getDisplayName()).append("\n");
        sb.append("- 置信度: ").append(String.format("%.1f%%", analysis.getConfidence() * 100)).append("\n");
        if (analysis.getUnderstoodRequirement() != null && !analysis.getUnderstoodRequirement().isBlank()) {
            sb.append("- 需求理解: ").append(analysis.getUnderstoodRequirement()).append("\n");
        }
        if (analysis.getExtractedContent() != null && !analysis.getExtractedContent().isBlank()) {
            sb.append("- 提取内容: ").append(analysis.getExtractedContent()).append("\n");
        }
        if (analysis.getRelevantMemories() != null && !analysis.getRelevantMemories().isBlank()) {
            sb.append("- 关联记忆: ").append(analysis.getRelevantMemories()).append("\n");
        }
        if (analysis.isMultiTask() && !analysis.getSubTasks().isEmpty()) {
            sb.append("- 复合任务, 包含 ").append(analysis.getSubTasks().size()).append(" 个子任务:\n");
            for (var sub : analysis.getSubTasks()) {
                sb.append("  ").append(sub.toString()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 【新增】简单的问答接口，支持上下文传入（无对话ID管理，无持久化）
     * 适用于前端多轮对话场景，由前端维护对话历史
     *
     * @param question 当前问题
     * @param contextHistory 历史对话上下文（格式："用户: xxx\nAI: yyy\n..."）
     * @param enableRag 是否启用RAG检索（默认false）
     * @param enableWebSearch 是否启用联网搜索（默认false）
     * @return AI回答
     */
    public String askWithContext(String question, String contextHistory, boolean enableRag, boolean enableWebSearch) {
        reportProgress("start", "问题: " + question);

        // 构建带上下文的prompt，使用模板
        String safeContext = (contextHistory != null && !contextHistory.trim().isEmpty()) ? contextHistory : "";
        String prompt = String.format(PromptTemplate.CHAT_WITH_CONTEXT_TEMPLATE, safeContext, question);

        String citations = "";  // 引用标注

        // 1. 联网搜索（优先级最高，获取实时信息）
        if (enableWebSearch) {
            try {
                WebSearchService.WebSearchResult webResult = webSearchService.search(question);
                if (webResult.hasResult) {
                    String webContext = webResult.toPromptContext();
                    prompt = prompt + "\n\n" + webContext;
                    System.out.println("[联网搜索] 已注入搜索结果，长度: " + webContext.length() + " 字符");
                } else {
                    System.out.println("[联网搜索] 未找到相关网页信息");
                }
            } catch (Exception e) {
                System.err.println("[联网搜索] 搜索失败: " + e.getMessage());
            }
        }

        // 2. RAG向量库检索
        if (enableRag) {
            String ragContext = ragService.retrieveRelevantContent(question);
            if (!ragContext.isEmpty()) {
                prompt = prompt + "\n\n" + ragContext;
                System.out.println("[RAG] 已注入向量库检索结果，长度: " + ragContext.length() + " 字符");
                citations = buildRagCitations(ragContext);
            } else {
                System.out.println("[RAG] 向量库中未检索到相关内容");
            }

            // 3. 检索永久记忆
            String relevantMemories = memoryService.getRelevantMemories(question);
            if (!relevantMemories.isEmpty()) {
                prompt = prompt + "\n\n【历史记忆】\n" + relevantMemories;
                System.out.println("[记忆] 已注入相关记忆");
            }
        }

        try {
            String result = chatModel.generate(prompt);

            // 【引用标注】在答案末尾附加文件来源
            if (!citations.isEmpty()) {
                result = result + citations;
            }

            System.out.println("\n========== 问答完成 ==========\n");

            // 记录对话用于后续蒸馏
            String fullConversation = (contextHistory != null ? contextHistory : "") + "\n用户: " + question + "\nAI: " + result;
            distillationScheduler.recordConversation(fullConversation, result, "chat");

            return result;
        } catch (Exception e) {
            System.err.println("[错误] 调用AI服务失败: " + e.getMessage());
            return "抱歉，处理您的请求时出现问题。请稍后重试。错误: " + e.getMessage();
        }
    }

    /**
     * 从RAG检索上下文中提取文件名，生成引用标注
     */
    private String buildRagCitations(String ragContext) {
        if (ragContext == null || ragContext.isEmpty()) return "";

        java.util.LinkedHashSet<String> files = new java.util.LinkedHashSet<>();
        // 匹配 "--- 来源: xxx ---" 格式
        String[] lines = ragContext.split("\n");
        for (String line : lines) {
            if (line.startsWith("--- 来源: ") && line.endsWith(" ---")) {
                String fileName = line.substring(7, line.length() - 4).trim();
                if (!fileName.isEmpty() && !"未知来源".equals(fileName)) {
                    files.add(fileName);
                }
            }
        }

        if (files.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n");
        sb.append("📚 **参考文件**：\n");
        int idx = 1;
        for (String file : files) {
            String encodedName = file.replace(" ", "%20");
            sb.append("- [").append(idx).append(". ").append(file)
              .append("](/api/dev-ai/file/preview?name=").append(encodedName).append(")\n");
            idx++;
        }
        return sb.toString();
    }

    // ==================== Agent交互接口定义 ====================

    /**
     * Agent系统提示词 - 明确告知AI它拥有的工具能力
     */
    /**
     * 获取当前Agent配置（用于管理面板展示）
     */
    public AgentConfig getAgentConfig() {
        return agentConfigService.getConfig();
    }

    private static final String AGENT_SYSTEM_PROMPT = """
            你是一个强大的AI开发助手，拥有直接操作本地文件系统、检索知识库和搜索网络信息的能力。你可以：

            【重要】你拥有以下能力，当用户需要时，你必须直接调用工具执行，而不是说"我无法做到"：

            1. **创建文件和目录** - 你可以直接在用户本地磁盘创建任意文件和目录
            2. **读取文件** - 你可以读取用户本地任意文件内容
            3. **列出目录** - 你可以查看用户本地目录结构
            4. **生成完整项目** - 你可以一键生成Spring Boot项目结构
            5. **生成CRUD模块** - 你可以为指定实体生成完整的Entity/Repository/Service/Controller/DTO代码
            6. **设计架构方案** - 你可以根据需求设计项目架构
            7. **扫描编译错误** - 你可以扫描IDEA编译错误并给出修复方案
            8. **分析运行时异常** - 你可以分析异常堆栈并定位问题
            9. **执行本地脚本** - 你可以调用executeScript工具执行预定义的bat脚本进行文件操作
            10. **网络信息搜索** - 你可以通过searchWeb工具搜索网络上的最新技术文档、API用法、开源方案等

            【网络搜索说明】
            - 当用户询问最新技术、不熟悉的框架/工具、需要实时信息的场景，请主动调用searchWeb
            - searchWeb已内置多轮筛选和去重机制，返回的是高质量、去重后的搜索结果
            - 搜索结果会包含来源URL和内容摘要，请在回答中标注信息来源
            - 如果搜索结果不足以完全回答，结合你的知识补充说明

            【溯源引用规则 - 数据有据可查】
            当你的回答使用了知识库文件或网络搜索结果时，必须遵守以下规则：
            1. 正文中明确提及引用的文件名，例如"根据《xxx.pdf》..."或"参考知识库中《xxx》..."
            2. 如果使用了searchWeb工具返回的网络搜索结果：
               - 正文中用 [1]、[2] 标注每个来自搜索结果的事实
               - 回答末尾用 Markdown 可点击链接列出所有参考来源
            3. 如果使用了searchDevLib工具返回的知识库内容：
               - 正文中提及引用的文件名
               - 系统会自动在回答末尾附加 📚 **参考文件** 链接
            4. 明确区分哪些内容来自知识库/搜索结果，哪些来自你的已有知识

            【脚本执行】
            当需要批量文件操作或系统级操作时，优先使用executeScript工具调用对应脚本：
            - file_list.bat <目录>      → 列出目录
            - file_read.bat <文件>      → 读取文件
            - file_create.bat <目录>    → 创建目录
            - file_copy.bat <源> <目标> → 复制文件
            - file_search.bat <目录> <关键字> → 搜索文件
            - file_tree.bat [目录]      → 显示目录树

            【核心规则】
            - 当用户要求"创建项目"、"搭建架构"、"生成文件"时，直接调用工具执行
            - 你确实可以访问文件系统，工具已经为你准备好了
            - 如果用户没有指定路径，请主动询问用户期望的创建路径
            - 执行完成后，告诉用户文件创建在了哪里

            【工具调用限制 - 重要】
            - 【最关键】生成代码、单元测试、接口文档、PRD文档等内容时，**请直接在回答中输出**，无需调用任何工具。你有足够的知识直接生成这些内容。
            - 调用工具只用于：检索知识库(searchDevLib)、文件操作(createFile/readFile/createDirectory/listDirectory)、网络搜索(searchWeb)、扫描错误(scanAndFixIdeaErrors)、执行脚本(executeScript)
            - 每次调用工具后，评估是否已获取足够信息。如果是，直接输出最终答案
            - 一次请求中，连续工具调用不应超过 5 次
            - 每个工具只能调用一次，不要重复调用同一个工具
            """;

    public interface DevAgent {
        String handleTask(String userTask);
    }
}
