package com.devai.devaiplatform.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 多智能体编排器 - 根据任务类型动态选择需要的Agent执行，结果聚合
 */
@Service
public class AgentOrchestrator {
    private final ChatLanguageModel chatModel;
    private final DevAgentService baseService;
    private final DevRagService ragService;
    private final PersistentMemoryService memoryService;
    private final LocalFileOperationService fileOperationService;
    private final WebSearchService webSearchService;
    private final ProjectStructureGenerator projectStructureGenerator;
    private final IdeaErrorAnalyzerService ideaErrorAnalyzerService;
    private final ScriptExecutorService scriptExecutorService;

    private final AtomicInteger toolCallCount = new AtomicInteger(0);
    private volatile boolean toolLimitReached = false;
    private static final int MAX_TOOL_CALLS = 50;
    private static final String TOOL_LIMIT_MSG = "已达50次上限，请基于已有结果总结回答。";

    /** Agent类型枚举 */
    enum AgentType { RESEARCH, CODE, FILE, PROJECT }

    public AgentOrchestrator(ChatLanguageModel chatModel,
                              DevAgentService baseService,
                              DevRagService ragService,
                              PersistentMemoryService memoryService,
                              LocalFileOperationService fileOperationService,
                              WebSearchService webSearchService,
                              ProjectStructureGenerator projectStructureGenerator,
                              IdeaErrorAnalyzerService ideaErrorAnalyzerService,
                              ScriptExecutorService scriptExecutorService) {
        this.chatModel = chatModel;
        this.baseService = baseService;
        this.ragService = ragService;
        this.memoryService = memoryService;
        this.fileOperationService = fileOperationService;
        this.webSearchService = webSearchService;
        this.projectStructureGenerator = projectStructureGenerator;
        this.ideaErrorAnalyzerService = ideaErrorAnalyzerService;
        this.scriptExecutorService = scriptExecutorService;
    }

    private void reportProgress(String type, String msg) {
        baseService.reportProgressPublic(type, msg);
    }

    private void resetCounter() { toolCallCount.set(0); toolLimitReached = false; }

    private String checkLimit() {
        if (toolLimitReached) return TOOL_LIMIT_MSG;
        if (toolCallCount.incrementAndGet() > MAX_TOOL_CALLS) {
            toolLimitReached = true;
            reportProgress("warn", "已达50次上限");
            return TOOL_LIMIT_MSG;
        }
        return null;
    }

    private String safeReturn(String result) {
        String limit = checkLimit();
        if (limit != null) return limit;
        return result == null || result.isBlank() ? "（无输出）" : result;
    }

    // ==================== 搜索研究Agent ====================
    interface ResearchAgent {
        String handleTask(String userTask);
    }

    class ResearchTools {
        @Tool("查询项目内部代码、需求文档、历史方案。适用于查找代码示例、理解业务逻辑、查看历史技术方案")
        public String searchDevLib(String question) {
            reportProgress("tool", "📚 检索知识库: " + question);
            String memories = memoryService.getRelevantMemories(question);
            return safeReturn(ragService.ragQuery(question + "\n历史参考:\n" + memories));
        }

        @Tool("在网络中搜索最新技术信息，返回高质量内容。适用于查询最新技术动态、API文档、开源方案等")
        public String searchWeb(String query) {
            reportProgress("tool", "🌐 网络搜索: " + query);
            try {
                var result = webSearchService.search(query);
                return safeReturn(result.toPromptContext());
            } catch (Exception e) {
                return "搜索失败: " + e.getMessage();
            }
        }

        @Tool("检索历史记忆，查找类似问题方案")
        public String retrieveMemories(String query) {
            reportProgress("tool", "🧠 检索记忆: " + query);
            return safeReturn(memoryService.getRelevantMemories(query));
        }
    }

    private static final String RESEARCH_SYS_PROMPT = """
        你是研究分析专家，任务是搜索和收集信息。你只有3个工具：
        - searchDevLib：搜索内部知识库
        - searchWeb：搜索网络
        - retrieveMemories：检索历史记忆
        收集完信息后，输出简洁的信息摘要，不要调用任何其他工具。
        限制：工具调用不超过 3 次。
        """;

    // ==================== 文件操作Agent ====================
    interface FileAgent {
        String handleTask(String userTask);
    }

    class FileTools {
        @Tool("在本地文件系统创建目录，支持多级自动创建")
        public String createDirectory(String dirPath) {
            reportProgress("tool", "📁 创建目录: " + dirPath);
            var result = fileOperationService.createDirectory(dirPath);
            return safeReturn(result.success ? "✅ " + result.message : "❌ " + result.message);
        }

        @Tool("在本地文件系统创建文件，自动创建父目录")
        public String createFile(String filePath, String content) {
            reportProgress("tool", "📝 创建文件: " + filePath);
            var result = fileOperationService.createFile(filePath, content);
            return safeReturn(result.success ? "✅ " + result.message : "❌ " + result.message);
        }

        @Tool("读取本地文件内容")
        public String readFile(String filePath) {
            reportProgress("tool", "📖 读取文件: " + filePath);
            return safeReturn(fileOperationService.readFile(filePath));
        }

        @Tool("列出指定目录下的文件和子目录")
        public String listDirectory(String dirPath) {
            reportProgress("tool", "📋 列出目录: " + dirPath);
            var items = fileOperationService.listDirectory(dirPath, false);
            if (items.isEmpty()) return "目录为空或不存在";
            StringBuilder sb = new StringBuilder("目录内容:\n");
            for (var item : items) {
                sb.append(item.type.equals("DIR") ? "📁 " : "📄 ")
                  .append(item.path.substring(item.path.lastIndexOf(java.io.File.separator) + 1)).append("\n");
            }
            return safeReturn(sb.toString());
        }

        @Tool("执行本地脚本进行文件操作。可用: file_list.bat/read.bat/create.bat/copy.bat/search.bat/tree.bat")
        public String executeScript(String scriptName, String arg1, String arg2) {
            reportProgress("tool", "⚙️ 执行脚本: " + scriptName);
            return safeReturn(scriptExecutorService.execute(scriptName, arg1, arg2));
        }
    }

    private static final String FILE_SYS_PROMPT = """
        你是文件操作专家。拥有创建目录、创建文件、读取文件、列出目录、执行脚本的能力。
        收到任务后直接调用对应工具执行，完成后告知结果。不要调用其他无关工具。
        限制：工具调用不超过 5 次。
        """;

    // ==================== 代码生成Agent ====================
    interface CodeAgent {
        String handleTask(String userTask);
    }

    class CodeTools {
        private final Set<String> calledTaskTypes = new HashSet<>(); // 跟踪已调用的任务类型
        
        @Tool("生成技术内容：unit_test/api_doc/prd_doc/backend_code/frontend_code/api_call_code/validation_code/migration_script/code_review/refactoring/config_file/text_summary/sql_optimize/sql_design/sql_rewrite/explain_analysis/table_design/crud_sql")
        public String generateContent(String taskType, String requirement) {
            // 【防重复】检查是否已经调用过相同类型的任务
            if (calledTaskTypes.contains(taskType)) {
                reportProgress("warn", "⚠️ 警告: " + taskType + " 已生成过，避免重复调用");
                return "❌ 错误: 任务类型 '" + taskType + "' 已经调用过，请不要重复生成相同类型的代码。如需生成其他模块，请更换 taskType 或直接输出代码内容。";
            }
            
            calledTaskTypes.add(taskType);
            reportProgress("tool", "️ 生成: " + taskType);
            String template = PromptTemplate.getTemplate(taskType);
            if (template == null) return "未知类型: " + taskType;
            return safeReturn(chatModel.generate(String.format(template, requirement)));
        }
    }

    private static final String CODE_SYS_PROMPT = """
        你是代码生成专家。可以通过 generateContent 工具生成各种技术内容。
        
        ## 重要规则
        1. **不要重复生成相同类型的代码**：如果用户要求生成多个模块，请在一次调用中说明所有需求
        2. **generateContent 工具限制**：每个任务最多调用 3 次，超过将返回错误
        3. **输出格式**：直接输出代码即可，无需通过工具生成（除非明确要求创建文件）
        4. **模块化思维**：如果需要生成多个实体/模块的代码，请分步骤说明，但不要反复调用同一工具
        
        ## 可用任务类型
        - backend_code: 后端完整代码（Controller/Service/Entity/Repository/DTO）
        - frontend_code: 前端组件代码
        - unit_test: 单元测试
        - api_doc: 接口文档
        - crud_sql: CRUD SQL语句
        - config_file: 配置文件
        - code_review: 代码审查
        - refactoring: 重构建议
        
        收到需求后，优先直接输出代码内容。只有在需要实际创建文件时才使用文件操作工具。
        限制：工具调用不超过 3 次。
        """;

    // ==================== 项目生成Agent ====================
    interface ProjectAgent {
        String handleTask(String userTask);
    }

    class ProjectTools {
        @Tool("生成完整的Spring Boot项目结构，包括标准目录布局、pom.xml、启动类等")
        public String generateSpringBootProject(String projectPath, String projectName, String packageName) {
            reportProgress("tool", "🏗️ 生成Spring Boot项目: " + projectName);
            var result = projectStructureGenerator.generateSpringBootProject(projectPath, projectName, packageName);
            return safeReturn(result.success ? "✅ 项目生成成功! 路径: " + result.projectPath + " 文件数: " + result.items.size() : "❌ " + result.message);
        }

        @Tool("为指定实体生成完整CRUD代码：Entity/Repository/Service/Controller/DTO")
        public String generateCrudModuleCode(String projectPath, String packageName, String entityName) {
            reportProgress("tool", "⚡ 生成CRUD: " + entityName);
            var result = projectStructureGenerator.generateLayeredCode(projectPath, packageName, entityName, entityName);
            return safeReturn(result.success ? "✅ " + entityName + " CRUD生成成功! 文件数: " + result.items.size() : "❌ " + result.message);
        }
    }

    private static final String PROJECT_SYS_PROMPT = """
        你是项目架构专家。拥有生成Spring Boot项目和CRUD模块的能力。
        
        ## 重要规则
        1. **路径参数说明**：
           - `projectPath` 应该是项目的**父目录**（不包含项目名），例如：`E:\\projects`
           - `projectName` 是项目名称，例如：`myapp`
           - 系统会自动拼接为：`E:\\projects/myapp`
        
        2. **不要重复调用同一工具**：每个任务最多调用一次 generateSpringBootProject 或 generateCrudModuleCode
        
        3. **调用顺序**：先生成项目结构（generateSpringBootProject），再生成CRUD代码（generateCrudModuleCode）
        
        4. **CRUD代码路径**：调用 generateCrudModuleCode 时，projectPath 应该是**完整的项目路径**（包含项目名），例如：`E:\\projects/myapp`
        
        收到需求后直接调用对应工具生成项目结构，完成后告知结果。
        限制：工具调用不超过 3 次。
        """;

    // ==================== 任务分析：动态选择Agent ====================

    /**
     * 分析任务内容，决定需要调用哪些Agent
     * 使用关键词规则 + AI辅助判断，避免每次都跑全部Agent
     */
    private Set<AgentType> analyzeRequiredAgents(String taskContent, boolean enableRag, boolean enableWebSearch) {
        Set<AgentType> required = new LinkedHashSet<>();
        String lower = taskContent.toLowerCase();

        // 1. 研究Agent：用户勾选了RAG/联网搜索，或任务涉及查询/搜索/分析
        if (enableRag || enableWebSearch) {
            required.add(AgentType.RESEARCH);
        } else if (matchesAny(lower, "查询", "搜索", "查找", "检索", "分析", "是什么", "怎么回事",
                "为什么", "帮我找", "看看", "了解", "调研", "对比")) {
            required.add(AgentType.RESEARCH);
        }

        // 2. 项目Agent：涉及创建项目/生成项目结构
        if (matchesAny(lower, "创建项目", "生成项目", "新建项目", "搭建项目", "初始化项目",
                "spring boot项目", "springboot项目", "生成spring", "搭建框架")) {
            required.add(AgentType.PROJECT);
        }

        // 3. 文件Agent：涉及文件/目录操作
        if (matchesAny(lower, "创建文件", "创建目录", "读取文件", "列出目录", "删除文件",
                "写文件", "新建文件夹", "文件操作", "目录结构", "执行脚本")) {
            required.add(AgentType.FILE);
        }

        // 4. 代码Agent：涉及代码生成/编写/重构/SQL等
        if (matchesAny(lower, "生成代码", "编写代码", "写代码", "实现", "编码",
                "单元测试", "接口文档", "文档", "sql", "重构", "优化",
                "crud", "controller", "service", "entity", "代码审查",
                "review", "前端代码", "后端代码", "api", "配置")) {
            required.add(AgentType.CODE);
        }

        // 如果没有任何Agent被选中，默认使用代码Agent（兜底）
        if (required.isEmpty()) {
            required.add(AgentType.CODE);
        }

        return required;
    }

    /** 检查文本是否匹配任一关键词 */
    private boolean matchesAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    // ==================== 编排器 ====================

    record AgentResult(String agent, String result) {}

    public String orchestrate(String taskContent, boolean enableRag, boolean enableWebSearch) {
        resetCounter();
        String shortTask = taskContent.length() > 50 ? taskContent.substring(0, 50) + "..." : taskContent;
        reportProgress("start", "任务分析中: " + shortTask);

        // 1. 动态分析需要哪些Agent
        Set<AgentType> requiredAgents = analyzeRequiredAgents(taskContent, enableRag, enableWebSearch);
        reportProgress("info", "已选择Agent: " + requiredAgents.stream()
                .map(a -> switch(a) {
                    case RESEARCH -> "研究"; case CODE -> "代码";
                    case FILE -> "文件"; case PROJECT -> "项目";
                }).collect(Collectors.joining(" + ")));

        List<AgentResult> results = new ArrayList<>();
        int step = 1;

        // 2. 研究阶段（仅当需要时）
        if (requiredAgents.contains(AgentType.RESEARCH)) {
            reportProgress("info", "【第" + step + "步】研究Agent搜索信息...");
            var researchAgent = AiServices.builder(ResearchAgent.class)
                    .chatLanguageModel(chatModel)
                    .tools(new ResearchTools())
                    .systemMessageProvider(m -> RESEARCH_SYS_PROMPT)
                    .build();
            String researchResult = researchAgent.handleTask("查询与以下任务相关的知识库和历史信息：" + taskContent);
            results.add(new AgentResult("研究", researchResult));
            reportProgress("info", "研究Agent完成");
            step++;
        }

        // 3. 代码生成阶段（仅当需要时）
        if (requiredAgents.contains(AgentType.CODE)) {
            reportProgress("info", "【第" + step + "步】代码Agent处理...");
            var codeAgent = AiServices.builder(CodeAgent.class)
                    .chatLanguageModel(chatModel)
                    .tools(new CodeTools())
                    .systemMessageProvider(m -> CODE_SYS_PROMPT)
                    .build();
            String prevContext = results.isEmpty() ? "无" : results.stream()
                    .map(r -> r.agent() + ": " + r.result()).collect(Collectors.joining("\n"));
            String codeResult = codeAgent.handleTask("任务: " + taskContent + "\n前序结果: " + prevContext);
            results.add(new AgentResult("代码", codeResult));
            reportProgress("info", "代码Agent完成");
            step++;
        }

        // 4. 文件操作阶段（仅当需要时）
        if (requiredAgents.contains(AgentType.FILE)) {
            reportProgress("info", "【第" + step + "步】文件Agent执行操作...");
            var fileAgent = AiServices.builder(FileAgent.class)
                    .chatLanguageModel(chatModel)
                    .tools(new FileTools())
                    .systemMessageProvider(m -> FILE_SYS_PROMPT)
                    .build();
            String prevContext = results.stream()
                    .map(r -> r.agent() + ": " + r.result()).collect(Collectors.joining("\n"));
            String fileResult = fileAgent.handleTask("需要实际文件操作的任务：\n" + taskContent + "\n前面Agent的结果：\n" + prevContext);
            results.add(new AgentResult("文件", fileResult));
            reportProgress("info", "文件Agent完成");
            step++;
        }

        // 5. 项目结构生成阶段（仅当需要时）
        if (requiredAgents.contains(AgentType.PROJECT)) {
            reportProgress("info", "【第" + step + "步】项目Agent生成结构...");
            var projectAgent = AiServices.builder(ProjectAgent.class)
                    .chatLanguageModel(chatModel)
                    .tools(new ProjectTools())
                    .systemMessageProvider(m -> PROJECT_SYS_PROMPT)
                    .build();
            String prevContext = results.stream()
                    .map(r -> r.agent() + ": " + r.result()).collect(Collectors.joining("\n"));
            String projectResult = projectAgent.handleTask("根据任务生成项目结构：\n" + taskContent + "\n前面结果:\n" + prevContext);
            results.add(new AgentResult("项目", projectResult));
            reportProgress("info", "项目Agent完成");
        }

        // 6. 整理最终结果
        StringBuilder finalResult = new StringBuilder();
        if (results.size() == 1) {
            // 只有一个Agent，直接返回其结果（简洁）
            finalResult.append(results.get(0).result());
        } else {
            finalResult.append("## 多Agent协同执行结果\n\n");
            for (AgentResult r : results) {
                if (r.result != null && !r.result.isBlank() && !r.result.contains("无输出")) {
                    finalResult.append("### ").append(r.agent).append("Agent\n").append(r.result).append("\n\n");
                }
            }
        }

        reportProgress("done", "多Agent协同完成");
        return finalResult.toString().trim();
    }
}
