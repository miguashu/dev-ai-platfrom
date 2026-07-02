package com.devai.devaiplatform.service;


import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class DevAgentService {
    private final ChatLanguageModel chatModel;
    private final DevRagService ragService;
    private final VectorStoreService vectorStoreService;
    private final PersistentMemoryService memoryService;
    private final MemoryDistillationScheduler distillationScheduler;  // 【新增】蒸馏调度器
    private final IdeaErrorAnalyzerService ideaErrorAnalyzerService;  // 【新增】IDEA错误分析服务
    private final LocalFileOperationService fileOperationService;
    private final ProjectStructureGenerator projectStructureGenerator;
    private final ScriptExecutorService scriptExecutorService;

    public DevAgentService(ChatLanguageModel chatModel,
                           DevRagService ragService,
                           VectorStoreService vectorStoreService,
                           PersistentMemoryService memoryService,
                           MemoryDistillationScheduler distillationScheduler,
                           IdeaErrorAnalyzerService ideaErrorAnalyzerService,
                           LocalFileOperationService fileOperationService,
                           ProjectStructureGenerator projectStructureGenerator,
                           ScriptExecutorService scriptExecutorService
    ) {
        this.chatModel = chatModel;
        this.ragService = ragService;
        this.vectorStoreService = vectorStoreService;
        this.memoryService = memoryService;
        this.distillationScheduler = distillationScheduler;
        this.ideaErrorAnalyzerService = ideaErrorAnalyzerService;
        this.fileOperationService = fileOperationService;
        this.projectStructureGenerator = projectStructureGenerator;
        this.scriptExecutorService = scriptExecutorService;
    }

    /**
     * 确保Tool方法返回值不为null或空白，防止LangChain4j抛出IllegalArgumentException
     */
    private String safeReturn(String result) {
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
        System.out.println("[工具调用] 检索知识库: " + question);
        String relevantMemories = memoryService.getRelevantMemories(question);
        String enhancedQuestion = question + "\n\n历史参考:\n" + relevantMemories;
        return safeReturn(ragService.ragQuery(enhancedQuestion));
    }


    @Tool("输入Service业务代码，生成标准JUnit5单元测试，包含Mock配置和断言")
    public String genUnitTest(String serviceCode) {
        System.out.println("[工具调用] 生成单元测试");
        String prompt = String.format(PromptTemplate.UNIT_TEST_TEMPLATE, serviceCode);
        return safeGenerate(prompt);
    }

    @Tool("输入Controller代码，输出标准接口文档：地址、入参、返回体、业务说明、示例")
    public String genApiDoc(String controllerCode) {
        System.out.println("[工具调用] 生成接口文档");
        String prompt = String.format(PromptTemplate.API_DOC_TEMPLATE, controllerCode);
        return safeGenerate(prompt);
    }

    @Tool("将重要对话内容蒸馏为永久记忆，自动提取关键信息并评分。适用于：保存技术方案、记录故障处理经验、保存最佳实践")
    public String distillConversationToMemory(String conversation, String summary) {
        System.out.println("[工具调用] 数据蒸馏: " + summary);
        return safeReturn(memoryService.distillMemory(conversation, summary));
    }

    @Tool("检索历史记忆和經驗，查找类似问题的解决方案或相关技术文档")
    public String retrieveMemories(String query, String category) {
        System.out.println("[工具调用] 检索记忆: " + query);
        return safeReturn(memoryService.getRelevantMemories(query));
    }

    @Tool("获取记忆库统计信息，包括记忆数量、分类分布、重要性等")
    public String getMemoryStatistics() {
        System.out.println("[工具调用] 获取记忆统计");
        Map<String, Object> stats = memoryService.getMemoryStats();
        return "记忆库统计：\n" + stats.toString();
    }

    @Tool("输入报错日志，结合历史故障资料输出分步排查修复方案")
    public String analyzeErrorLog(String errorLog) {
        System.out.println("[工具调用] 分析错误日志");
        try {
            String historyCase = ragService.ragQuery("报错：" + errorLog.substring(0, Math.min(150, errorLog.length())));
            String prompt = String.format(PromptTemplate.ERROR_LOG_TEMPLATE, safeReturn(historyCase), errorLog);
            return safeGenerate(prompt);
        } catch (Exception e) {
            System.err.println("[错误] 分析错误日志失败: " + e.getMessage());
            return "调用AI服务失败，请检查网络或API配置。错误信息: " + e.getMessage();
        }
    }

    @Tool("根据需求描述生成符合规范的PRD需求文档，包含功能点、业务流程、数据模型")
    public String genPrdDoc(String requirementDesc) {
        System.out.println("[工具调用] 生成PRD文档");
        String prompt = String.format(PromptTemplate.PRD_DOC_TEMPLATE, requirementDesc);
        return safeGenerate(prompt);
    }
    /**
     * 【核心】智能Agent执行入口，自动识别任务类型并调度相应工具
     *
     * @param taskContent 用户任务描述（自然语言）
     * @return 任务执行结果
     */
    public String runDevTask(String taskContent) {
        System.out.println("\n========== Agent开始执行任务 ==========");
        System.out.println("任务内容: " + taskContent);
        System.out.println("======================================\n");

        // 【新增】检索相关历史记忆
        String relevantMemories = memoryService.getRelevantMemories(taskContent);
        System.out.println("检索到相关记忆:\n" + relevantMemories);

        DevAgent agent = AiServices.builder(DevAgent.class)
                .chatLanguageModel(chatModel)
                .tools(this)
                .systemMessageProvider(chatMemory -> AGENT_SYSTEM_PROMPT)
                .build();

        // 将记忆注入到上下文中
        String enhancedTask = taskContent + "\n\n历史参考:\n" + relevantMemories;

        String result = agent.handleTask(enhancedTask);

        System.out.println("\n========== Agent任务执行完成 ==========\n");
        
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

    @Tool("根据表结构SQL生成对应的Entity、Mapper、Service层代码")
    public String genCrudCode(String tableSql) {
        System.out.println("[工具调用] 生成CRUD代码");
        return safeGenerate(String.format(PromptTemplate.CRUD_CODE_TEMPLATE, tableSql));
    }

    @Tool("对代码进行CodeReview，检查规范性、潜在bug、性能问题、安全隐患")
    public String codeReview(String code) {
        System.out.println("[工具调用] 代码审查");
        return safeGenerate(String.format(PromptTemplate.CODE_REVIEW_TEMPLATE, code));
    }

    @Tool("分析SQL语句性能，识别慢查询问题，给出优化建议和索引方案")
    public String optimizeSql(String sqlQuery) {
        System.out.println("[工具调用] SQL性能优化分析");
        return safeGenerate(String.format(PromptTemplate.SQL_OPTIMIZE_TEMPLATE, sqlQuery));
    }

    @Tool("根据业务场景和查询条件，设计最优的数据库索引策略")
    public String designIndex(String tableSchema, String queryPatterns) {
        System.out.println("[工具调用] 索引设计");
        return safeGenerate(String.format(PromptTemplate.INDEX_DESIGN_TEMPLATE, tableSchema, queryPatterns));
    }

    @Tool("将复杂SQL重写为高性能版本，优化JOIN、子查询、聚合等操作")
    public String rewriteSql(String originalSql) {
        System.out.println("[工具调用] SQL重写优化");
        return safeGenerate(String.format(PromptTemplate.SQL_REWRITE_TEMPLATE, originalSql));
    }

    @Tool("生成SQL执行计划分析报告，解读EXPLAIN输出并给出优化建议")
    public String analyzeExplainPlan(String explainOutput) {
        System.out.println("[工具调用] 执行计划分析");
        return safeGenerate(String.format(PromptTemplate.EXPLAIN_ANALYSIS_TEMPLATE, explainOutput));
    }

    @Tool("根据数据库表结构设计规范的ER图，生成符合第三范式的表结构SQL")
    public String designTableSchema(String businessRequirement) {
        System.out.println("[工具调用] 数据库表结构设计");
        return safeGenerate(String.format(PromptTemplate.TABLE_SCHEMA_TEMPLATE, businessRequirement));
    }

    // ... existing code ...

    @Tool("根据业务需求描述生成完整的Java后端代码，包括Controller、Service、Entity等分层架构代码")
    public String generateBackendCode(String requirement) {
        System.out.println("[工具调用] 生成后端业务代码");
        return safeGenerate(String.format(PromptTemplate.BACKEND_CODE_TEMPLATE, requirement));
    }

    @Tool("根据前端需求生成Vue/React组件代码，包括模板、样式和交互逻辑")
    public String generateFrontendCode(String componentRequirement) {
        System.out.println("[工具调用] 生成前端组件代码");
        return safeGenerate(String.format(PromptTemplate.FRONTEND_CODE_TEMPLATE, componentRequirement));
    }

    @Tool("生成API接口调用代码，包括HTTP请求封装、错误处理、类型定义")
    public String generateApiCallCode(String apiDescription) {
        System.out.println("[工具调用] 生成API调用代码");
        return safeGenerate(String.format(PromptTemplate.API_CALL_CODE_TEMPLATE, apiDescription));
    }

    @Tool("根据业务规则生成数据验证代码，包括表单验证、业务规则校验")
    public String generateValidationCode(String validationRules) {
        System.out.println("[工具调用] 生成数据验证代码");
        return safeGenerate(String.format(PromptTemplate.VALIDATION_CODE_TEMPLATE, validationRules));
    }

    @Tool("生成数据库迁移脚本，包括建表语句、索引、外键约束")
    public String generateMigrationScript(String schemaRequirement) {
        System.out.println("[工具调用] 生成数据库迁移脚本");
        return safeGenerate(String.format(PromptTemplate.MIGRATION_SCRIPT_TEMPLATE, schemaRequirement));
    }

    @Tool("为指定代码生成单元测试，包括正常场景、边界条件、异常情况")
    public String generateUnitTest(String codeToTest) {
        System.out.println("[工具调用] 生成单元测试");
        return safeGenerate(String.format(PromptTemplate.GENERATE_UNIT_TEST_TEMPLATE, codeToTest));
    }

    @Tool("分析现有代码并给出重构建议和最佳实践")
    public String suggestCodeRefactoring(String existingCode) {
        System.out.println("[工具调用] 分析代码并提供重构建议");
        return safeGenerate(String.format(PromptTemplate.CODE_REFACTORING_TEMPLATE, existingCode));
    }

    @Tool("生成配置文件代码，包括Dockerfile、docker-compose、CI/CD配置等")
    public String generateConfigFile(String configType, String requirements) {
        System.out.println("[工具调用] 生成配置文件");
        return safeGenerate(String.format(PromptTemplate.CONFIG_FILE_TEMPLATE, configType, requirements));
    }

    @Tool("对输入的长文本、对话、技术记忆内容生成精简AI摘要，支持结合上下文提炼核心要点，输出简短总结")
    public String generateTextSummary(String fullContent, String extraHint) {
        System.out.println("[工具调用] AI文本摘要生成");
        return safeGenerate(String.format(PromptTemplate.TEXT_SUMMARY_TEMPLATE, extraHint, fullContent));
    }

    // ==================== 脚本执行工具 ====================

    @Tool("执行本地脚本进行文件操作。可用脚本: file_list.bat(列出目录), file_read.bat(读文件), file_create.bat(创建目录), file_copy.bat(复制文件), file_search.bat(搜索文件), file_tree.bat(目录树)。参数用空格分隔，路径含空格请用引号括起")
    public String executeScript(String scriptName, String arg1, String arg2) {
        System.out.println("[工具调用] 执行脚本: " + scriptName + " " + arg1 + " " + arg2);
        String result = scriptExecutorService.execute(scriptName, arg1, arg2);
        return safeReturn(result);
    }

    @Tool("列出所有可用的本地操作脚本及其用途")
    public String listScripts() {
        System.out.println("[工具调用] 列出可用脚本");
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
        System.out.println("[工具调用] 扫描IDEA编译错误");
        
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
        System.out.println("[工具调用] 分析运行时异常");
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
        System.out.println("[工具调用] 创建目录: " + dirPath);
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
        System.out.println("[工具调用] 创建文件: " + filePath);
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
        System.out.println("[工具调用] 读取文件: " + filePath);
        return fileOperationService.readFile(filePath);
    }

    /**
     * 【新增】列出目录内容
     * @param dirPath 目录路径
     * @return 目录内容列表
     */
    @Tool("列出指定目录下的文件和子目录。适用于：查看项目结构、查找文件等")
    public String listDirectory(String dirPath) {
        System.out.println("[工具调用] 列出目录: " + dirPath);
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
        System.out.println("[工具调用] 生成Spring Boot项目: " + projectName);
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
        System.out.println("[工具调用] 生成CRUD模块代码: " + entityName);
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
        System.out.println("[工具调用] 设计项目架构");
        String prompt = String.format(PromptTemplate.PROJECT_STRUCTURE_DESIGN_TEMPLATE, requirement);
        return safeGenerate(prompt);
    }


// ... existing code ...

//    @Tool("清空向量知识库并重新初始化")
//    public String resetKnowledgeBase() {
//        System.out.println("[工具调用] 重置知识库");
//        vectorStoreService.clearVectorStore();
//        return "✅ 知识库已清空，可以重新上传文档";
//    }
//
//    @Tool("获取当前系统状态统计信息（知识库片段数、缓存命中率等）")
//    public String getSystemStats() {
//        System.out.println("[工具调用] 获取系统状态");
//        Map<String, Object> stats = new HashMap<>();
//
//        // 向量库统计
//        VectorStoreService.VectorStoreStats vsStats = vectorStoreService.getStats();
//        stats.put("vectorStore", vsStats);
//
//
//
//        return "系统状态：\n" + stats.toString();
//    }

    // ==================== Agent执行入口 ====================



    /**
     * 批量任务处理（支持多个子任务）
     *
     * @param tasks 任务列表（JSON数组格式）
     * @return 每个任务的执行结果
     */
    public String runBatchTasks(String tasks) {
        System.out.println("\n========== Agent开始执行批量任务 ==========");

        DevAgent agent = AiServices.builder(DevAgent.class)
                .chatLanguageModel(chatModel)
                .tools(this)
                .systemMessageProvider(chatMemory -> AGENT_SYSTEM_PROMPT)
                .build();

        String prompt = "请依次执行以下任务，每个任务完成后输出结果：\n\n" + tasks;
        String result = agent.handleTask(prompt);

        System.out.println("\n========== 批量任务执行完成 ==========\n");
        return result;
    }

    /**
     * 带上下文的任务处理（支持多轮对话）
     *
     * @param taskContent 当前任务
     * @param context 历史对话上下文
     * @return 任务执行结果
     */
    public String runTaskWithContext(String taskContent, String context) {
        System.out.println("\n========== Agent执行带上下文的任务 ==========");

        DevAgent agent = AiServices.builder(DevAgent.class)
                .chatLanguageModel(chatModel)
                .tools(this)
                .systemMessageProvider(chatMemory -> AGENT_SYSTEM_PROMPT)
                .build();

        String prompt = "历史上下文：\n" + context + "\n\n当前任务：" + taskContent;
        String result = agent.handleTask(prompt);

        System.out.println("\n========== 任务执行完成 ==========\n");
        return result;
    }

    /**
     * 【新增】简单的问答接口，支持上下文传入（无对话ID管理，无持久化）
     * 适用于前端多轮对话场景，由前端维护对话历史
     *
     * @param question 当前问题
     * @param contextHistory 历史对话上下文（格式："用户: xxx\nAI: yyy\n..."）
     * @return AI回答
     */
    public String askWithContext(String question, String contextHistory) {
        System.out.println("\n========== 带上下文问答 ==========");
        System.out.println("当前问题: " + question);
        System.out.println("上下文长度: " + (contextHistory != null ? contextHistory.length() : 0) + " 字符");
        System.out.println("======================================\n");

        // 构建带上下文的prompt，使用模板
        String safeContext = (contextHistory != null && !contextHistory.trim().isEmpty()) ? contextHistory : "";
        String prompt = String.format(PromptTemplate.CHAT_WITH_CONTEXT_TEMPLATE, safeContext, question);

        // 添加记忆增强
        String relevantMemories = memoryService.getRelevantMemories(question);
        if (!relevantMemories.isEmpty()) {
            prompt = prompt + "\n\n" + relevantMemories;
        }

        try {
            String result = chatModel.generate(prompt);

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

    // ==================== Agent交互接口定义 ====================

    /**
     * Agent系统提示词 - 明确告知AI它拥有的工具能力
     */
    private static final String AGENT_SYSTEM_PROMPT = """
            你是一个强大的AI开发助手，拥有直接操作本地文件系统的能力。你可以：

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
            """;

    public interface DevAgent {
        String handleTask(String userTask);
    }
}
