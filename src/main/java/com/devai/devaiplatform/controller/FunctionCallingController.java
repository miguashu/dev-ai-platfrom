package com.devai.devaiplatform.controller;

import com.devai.devaiplatform.common.Result;
import com.devai.devaiplatform.service.functionCalling.FunctionCallingService;
import com.devai.devaiplatform.service.functionCalling.ToolDefinition;
import dev.langchain4j.data.message.ChatMessage;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Function Calling REST API 控制器
 * <p>
 * 提供标准的 Function Calling 端点，支持：
 * 1. 聊天 + 自动工具调用（完整生命周期：AI->工具->AI）
 * 2. 工具列表查询
 * 3. 对话管理（历史消息维护）
 * <p>
 * 与 OpenAI Function Calling API 兼容的设计
 */
@RestController
@RequestMapping("/api/dev-ai/function-calling")
public class FunctionCallingController {

    private final FunctionCallingService functionCallingService;

    public FunctionCallingController(FunctionCallingService functionCallingService) {
        this.functionCallingService = functionCallingService;
    }

    /**
     * Function Calling 聊天入口
     * <p>
     * 请求示例：
     * {
     *   "message": "帮我查找用户管理模块的代码",
     *   "history": [{"role":"user","content":"..."}, {"role":"assistant","content":"..."}],
     *   "enableRag": true
     * }
     * <p>
     * 响应示例（有工具调用时）：
     * {
     *   "reply": "我正在查找用户管理模块的代码...",
     *   "hasToolCalls": true,
     *   "toolCalls": [
     *     {"toolName": "searchDevLib", "arguments": "{\"question\":\"用户管理模块代码\"}", "result": "...查找结果..."}
     *   ],
     *   "messages": [...] // 用于后续继续对话
     * }
     */
    @SuppressWarnings("deprecation")
    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(@RequestBody FunctionCallingRequest request) {
        System.out.println("\n========== [REST] Function Calling Chat ==========");
        System.out.println("消息: " + request.getMessage());
        System.out.println("历史消息数: " + (request.getHistory() != null ? request.getHistory().size() : 0));

        // 1. 将历史消息转为 ChatMessage 列表
        List<ChatMessage> history = new ArrayList<>();
        if (request.getHistory() != null) {
            for (HistoryMessage msg : request.getHistory()) {
                if ("user".equals(msg.getRole())) {
                    history.add(FunctionCallingService.createUserMessage(msg.getContent()));
                } else if ("assistant".equals(msg.getRole())) {
                    history.add(FunctionCallingService.createAiMessage(msg.getContent()));
                }
            }
        }

        // 2. 调用 Function Calling 服务
        FunctionCallingService.FunctionCallingResult fcResult = functionCallingService.chat(
                request.getMessage(),
                history,
                request.getToolNames(),
                request.isEnableRag()
        );

        // 3. 构建响应
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", fcResult.getReply());
        response.put("hasToolCalls", fcResult.isHasToolCalls());
        response.put("processingTimeMs", fcResult.getProcessingTimeMs());

        // 工具调用记录
        if (fcResult.getToolCalls() != null && !fcResult.getToolCalls().isEmpty()) {
            List<Map<String, Object>> toolCallResults = new ArrayList<>();
            for (var record : fcResult.getToolCalls()) {
                Map<String, Object> tc = new LinkedHashMap<>();
                tc.put("toolName", record.getToolName());
                tc.put("arguments", record.getArguments());
                tc.put("result", record.getResult());
                toolCallResults.add(tc);
            }
            response.put("toolCalls", toolCallResults);
        }

        // 构建可序列化的消息列表（供前端继续对话使用）
        if (fcResult.getConversationMessages() != null) {
            List<Map<String, Object>> messages = new ArrayList<>();
            for (ChatMessage msg : fcResult.getConversationMessages()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", msg.type().name());
                m.put("text", msg.text());
                messages.add(m);
            }
            // 只保留最后几轮（避免消息太长）
            int maxMsgs = 20;
            if (messages.size() > maxMsgs) {
                messages = messages.subList(messages.size() - maxMsgs, messages.size());
            }
            response.put("messages", messages);
        }

        System.out.println("========== [REST] Function Calling 完成 ==========\n");
        return Result.success(response);
    }

    /**
     * 简单问答模式（无历史管理）
     *
     * @param message  用户消息
     * @param enableRag 是否启用RAG
     * @return AI回复
     */
    @PostMapping("/ask")
    public Result<Map<String, Object>> ask(
            @RequestParam String message,
            @RequestParam(required = false, defaultValue = "false") boolean enableRag) {

        FunctionCallingService.FunctionCallingResult result = functionCallingService.chat(
                message, null, null, enableRag
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reply", result.getReply());
        response.put("hasToolCalls", result.isHasToolCalls());

        if (result.getToolCalls() != null && !result.getToolCalls().isEmpty()) {
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (var record : result.getToolCalls()) {
                Map<String, Object> tc = new LinkedHashMap<>();
                tc.put("toolName", record.getToolName());
                tc.put("arguments", record.getArguments());
                tc.put("result", record.getResult());
                toolCalls.add(tc);
            }
            response.put("toolCalls", toolCalls);
        }

        return Result.success(response);
    }

    /**
     * 获取所有已注册的可用工具列表（OpenAI-compatible 格式）
     * 返回工具的定义信息，包括名称、描述、参数Schema
     */
    @GetMapping("/tools")
    public Result<List<Map<String, Object>>> listTools() {
        Set<String> toolNames = functionCallingService.getRegisteredTools();

        List<Map<String, Object>> tools = new ArrayList<>();
        for (String name : toolNames) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", name);
            tool.put("description", getToolDescription(name));
            tools.add(tool);
        }

        return Result.success(tools);
    }

    /**
     * 获取内置工具的详细定义（含参数Schema）
     */
    @GetMapping("/tools/detail")
    public Result<List<Map<String, Object>>> listToolDetails() {
        Set<String> toolNames = functionCallingService.getRegisteredTools();

        List<Map<String, Object>> tools = new ArrayList<>();
        for (String name : toolNames) {
            Map<String, Object> functionDef = new LinkedHashMap<>();
            functionDef.put("name", name);
            functionDef.put("description", getToolDescription(name));

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", getToolParameters(name));
            parameters.put("required", getToolRequiredParams(name));

            functionDef.put("parameters", parameters);

            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", functionDef);

            tools.add(tool);
        }

        return Result.success(tools);
    }

    /**
     * 获取系统状态（当前活跃工具数量等）
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("activeTools", functionCallingService.getRegisteredTools().size());
        status.put("toolNames", functionCallingService.getRegisteredTools());
        return Result.success(status);
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取工具描述（内置，映射）
     */
    private String getToolDescription(String toolName) {
        Map<String, String> descriptions = new LinkedHashMap<>();
        descriptions.put("searchDevLib", "查询项目内部代码、需求文档、历史方案资料");
        descriptions.put("genUnitTest", "生成标准JUnit5单元测试");
        descriptions.put("genApiDoc", "生成API接口文档");
        descriptions.put("distillConversationToMemory", "将重要对话内容蒸馏为永久记忆");
        descriptions.put("retrieveMemories", "检索历史记忆和经验");
        descriptions.put("getMemoryStatistics", "获取记忆库统计信息");
        descriptions.put("analyzeErrorLog", "分析报错日志并输出分步排查修复方案");
        descriptions.put("genPrdDoc", "生成PRD需求文档");
        descriptions.put("genCrudCode", "根据表结构SQL生成CRUD代码");
        descriptions.put("codeReview", "对代码进行CodeReview");
        descriptions.put("optimizeSql", "分析SQL语句性能并给出优化建议");
        descriptions.put("designIndex", "设计数据库索引策略");
        descriptions.put("rewriteSql", "将SQL重写为高性能版本");
        descriptions.put("analyzeExplainPlan", "分析SQL执行计划");
        descriptions.put("designTableSchema", "设计数据库表结构");
        descriptions.put("generateBackendCode", "生成后端业务代码");
        descriptions.put("generateFrontendCode", "生成前端组件代码");
        descriptions.put("generateApiCallCode", "生成API调用代码");
        descriptions.put("generateValidationCode", "生成数据验证代码");
        descriptions.put("generateMigrationScript", "生成数据库迁移脚本");
        descriptions.put("generateUnitTest", "为指定代码生成单元测试");
        descriptions.put("suggestCodeRefactoring", "分析代码并给出重构建议");
        descriptions.put("generateConfigFile", "生成配置文件");
        descriptions.put("generateTextSummary", "生成AI文本摘要");
        descriptions.put("executeScript", "执行本地脚本进行文件操作");
        descriptions.put("listScripts", "列出所有可用的本地操作脚本");
        descriptions.put("scanAndFixIdeaErrors", "扫描IDEA编译错误并生成修复方案");
        descriptions.put("analyzeRuntimeError", "分析运行时异常堆栈");
        descriptions.put("createDirectory", "在本地文件系统创建目录");
        descriptions.put("createFile", "在本地文件系统创建文件");
        descriptions.put("readFile", "读取本地文件内容");
        descriptions.put("listDirectory", "列出目录内容");
        descriptions.put("generateSpringBootProject", "生成Spring Boot项目结构");
        descriptions.put("generateCrudModuleCode", "生成CRUD模块代码");
        descriptions.put("designProjectArchitecture", "设计项目架构方案");
        return descriptions.getOrDefault(toolName, "无描述");
    }

    /**
     * 获取工具参数Schema
     */
    private Map<String, Object> getToolParameters(String toolName) {
        // 通过反射从 DevAgentService 获取方法参数信息
        for (java.lang.reflect.Method method : 
                com.devai.devaiplatform.service.DevAgentService.class.getDeclaredMethods()) {
            if (method.getName().equals(toolName)) {
                Map<String, Object> params = new LinkedHashMap<>();
                for (java.lang.reflect.Parameter param : method.getParameters()) {
                    Map<String, Object> paramSchema = new LinkedHashMap<>();
                    paramSchema.put("type", "string");
                    paramSchema.put("description", param.getName());
                    params.put(param.getName(), paramSchema);
                }
                return params;
            }
        }
        return Collections.emptyMap();
    }

    /**
     * 获取工具必需参数列表
     */
    private List<String> getToolRequiredParams(String toolName) {
        for (java.lang.reflect.Method method : 
                com.devai.devaiplatform.service.DevAgentService.class.getDeclaredMethods()) {
            if (method.getName().equals(toolName)) {
                List<String> required = new ArrayList<>();
                for (java.lang.reflect.Parameter param : method.getParameters()) {
                    required.add(param.getName());
                }
                return required;
            }
        }
        return Collections.emptyList();
    }

    // ==================== 请求DTO ====================

    /**
     * Function Calling 请求体
     */
    public static class FunctionCallingRequest {
        /**
         * 用户消息
         */
        private String message;

        /**
         * 历史消息列表
         */
        private List<HistoryMessage> history;

        /**
         * 本次启用的工具列表（null表示使用所有可用工具）
         */
        private List<String> toolNames;

        /**
         * 是否启用RAG记忆检索
         */
        private boolean enableRag;

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public List<HistoryMessage> getHistory() { return history; }
        public void setHistory(List<HistoryMessage> history) { this.history = history; }
        public List<String> getToolNames() { return toolNames; }
        public void setToolNames(List<String> toolNames) { this.toolNames = toolNames; }
        public boolean isEnableRag() { return enableRag; }
        public void setEnableRag(boolean enableRag) { this.enableRag = enableRag; }
    }

    /**
     * 历史消息
     */
    public static class HistoryMessage {
        private String role;
        private String content;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
}
