package com.devai.devaiplatform.service.functionCalling;

import com.devai.devaiplatform.service.DevAgentService;
import com.devai.devaiplatform.service.PersistentMemoryService;
import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 核心 Function Calling 服务
 * <p>
 * 职责：
 * 1. 统一的工具/函数注册中心（支持内置工具+动态注册）
 * 2. 使用 LangChain4j 的 ToolSpecification 构建 OpenAI-compatible 工具定义
 * 3. 管理 Function Calling 生命周期：AI调用 -> 函数执行 -> 结果回传 -> AI继续
 * 4. 会话管理：支持多轮对话中的函数调用上下文维护
 * <p>
 * 工作流程：
 * - 用户发送消息 + 可用工具列表
 * - AI 判断是否要调用某个工具
 * - 如果需要，返回 {function_call: name, arguments}
 * - 外部执行函数后，将结果回传
 * - AI 使用函数结果继续生成回复
 */
@SuppressWarnings({"deprecation", "removal"})
@Service
public class FunctionCallingService {

    private final ChatLanguageModel chatModel;
    private final DevAgentService agentService;
    private final PersistentMemoryService memoryService;

    /**
     * 内置工具注册表 <工具名, 执行器>
     */
    private final Map<String, ToolExecutor> builtinTools = new ConcurrentHashMap<>();

    /**
     * Function Calling 系统提示词
     */
    private static final String SYSTEM_PROMPT = """
            你是一个强大的AI开发助手，拥有调用各种工具的能力。
            你可以根据用户的问题，选择合适的工具来完成任务。
            每次工具调用后，你会收到执行结果，并基于结果继续回答用户。
            """;

    public FunctionCallingService(ChatLanguageModel chatModel,
                                  DevAgentService agentService,
                                  PersistentMemoryService memoryService) {
        this.chatModel = chatModel;
        this.agentService = agentService;
        this.memoryService = memoryService;
        registerBuiltinTools();
    }

    // ==================== 工具注册 ====================

    /**
     * 注册内置工具（从 DevAgentService 的 @Tool 方法自动注册）
     */
    private void registerBuiltinTools() {
        // 扫描 DevAgentService 中所有带 @Tool 注解的方法
        Method[] methods = DevAgentService.class.getDeclaredMethods();
        for (Method method : methods) {
            dev.langchain4j.agent.tool.Tool toolAnnotation =
                    method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
            if (toolAnnotation == null) continue;

            String toolName = method.getName();
            String toolDesc = String.join("; ", toolAnnotation.value());
            List<String> paramNames = new ArrayList<>();
            List<String> paramTypes = new ArrayList<>();
            List<String> paramDescs = new ArrayList<>();

            for (Parameter param : method.getParameters()) {
                paramNames.add(param.getName());
                paramTypes.add(param.getType().getSimpleName().toLowerCase());
                paramDescs.add(param.getName() + " (" + param.getType().getSimpleName() + ")");
            }

            builtinTools.put(toolName, (args) -> {
                try {
                    // 将 Map 参数转换为方法调用参数
                    Object[] methodArgs = new Object[paramNames.size()];
                    for (int i = 0; i < paramNames.size(); i++) {
                        Object val = args.get(paramNames.get(i));
                        if (val == null) {
                            methodArgs[i] = "";
                        } else {
                            methodArgs[i] = val.toString();
                        }
                    }
                    return (String) method.invoke(agentService, methodArgs);
                } catch (Exception e) {
                    System.err.println("[FunctionCalling] 工具执行失败: " + toolName + " - " + e.getMessage());
                    return "工具执行失败: " + e.getMessage();
                }
            });

            System.out.println("[FunctionCalling] 注册内置工具: " + toolName + " -> " + toolDesc);
        }
        System.out.println("[FunctionCalling] 共注册 " + builtinTools.size() + " 个内置工具");
    }

    /**
     * 注册一个自定义工具
     *
     * @param name       工具名称
     * @param description 工具描述
     * @param executor   工具执行器
     */
    public void registerTool(String name, String description, ToolExecutor executor) {
        builtinTools.put(name, executor);
        System.out.println("[FunctionCalling] 注册自定义工具: " + name);
    }

    /**
     * 移除已注册的工具
     */
    public void unregisterTool(String name) {
        builtinTools.remove(name);
        System.out.println("[FunctionCalling] 移除工具: " + name);
    }

    /**
     * 获取所有已注册的工具名称列表
     */
    public Set<String> getRegisteredTools() {
        return builtinTools.keySet();
    }

    // ==================== Function Calling 核心逻辑 ====================

    /**
     * Function Calling 一次完整的交互
     * 包含：AI思考 -> 可能调用工具 -> 工具执行 -> AI继续
     *
     * @param message     用户消息
     * @param history     历史消息列表
     * @param toolNames   本次可用的工具名列表（null表示使用所有内置工具）
     * @param enableRag   是否启用记忆/知识检索
     * @return 函数调用交互结果
     */
    public FunctionCallingResult chat(String message,
                                      List<ChatMessage> history,
                                      List<String> toolNames,
                                      boolean enableRag) {
        long startTime = System.currentTimeMillis();
        System.out.println("\n========== Function Calling 开始 ==========");
        System.out.println("用户消息: " + message);
        System.out.println("可用工具数: " + (toolNames != null ? toolNames.size() : builtinTools.size()));

        // 1. 构建消息列表
        List<ChatMessage> messages = new ArrayList<>();

        // 系统提示词
        messages.add(new SystemMessage(SYSTEM_PROMPT));

        // 历史消息
        if (history != null) {
            messages.addAll(history);
        }

        // 如果启用RAG，注入相关记忆
        String memories = "";
        if (enableRag) {
            memories = memoryService.getRelevantMemories(message);
            if (memories != null && !memories.isBlank()) {
                message = message + "\n\n【相关历史参考】\n" + memories;
            }
        }

        // 用户消息
        messages.add(new UserMessage(message));

        // 2. 构建可用 ToolSpecifications
        List<ToolSpecification> toolSpecs = buildToolSpecifications(toolNames);

        FunctionCallingResult result = new FunctionCallingResult();
        result.setUserMessage(message);
        List<FunctionCallingResult.ToolCallRecord> toolCalls = new ArrayList<>();

        // 3. 调用 AI 模型
        try {
            Response<AiMessage> response;
            if (toolSpecs.isEmpty()) {
                // 没有可用工具，走普通对话
                response = chatModel.generate(messages);
            } else {
                response = chatModel.generate(messages, toolSpecs);
            }

            AiMessage aiMessage = response.content();
            String reply = aiMessage.text();
            boolean hasToolCalls = aiMessage.hasToolExecutionRequests();

            if (hasToolCalls) {
                // 3a. AI 请求调用工具
                var executions = aiMessage.toolExecutionRequests();
                System.out.println("[FunctionCalling] AI 请求调用 " + executions.size() + " 个工具");

                for (var execution : executions) {
                    String toolName = execution.name();
                    String arguments = execution.arguments();
                    System.out.println("[FunctionCalling] 调用工具: " + toolName + " 参数: " + arguments);

                    // 执行工具
                    ToolExecutor executor = builtinTools.get(toolName);
                    String execResult;
                    if (executor != null) {
                        execResult = executor.execute(parseArguments(arguments));
                    } else {
                        execResult = "错误: 未找到工具 '" + toolName + "'";
                    }

                    // 记录工具调用
                    FunctionCallingResult.ToolCallRecord record = new FunctionCallingResult.ToolCallRecord();
                    record.setToolName(toolName);
                    record.setArguments(arguments);
                    record.setResult(execResult);
                    toolCalls.add(record);

                    // 将工具结果加入消息列表
                    messages.add(aiMessage);
                    messages.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(
                            execution.id(), toolName, execResult
                    ));
                }

                // 3b. 将工具结果发回给 AI，获取最终回复
                System.out.println("[FunctionCalling] 将工具结果回传 AI 生成最终回复");
                Response<AiMessage> finalResponse = chatModel.generate(messages);
                reply = finalResponse.content().text();
            }

            result.setReply(reply != null ? reply : "");
            result.setToolCalls(toolCalls);
            result.setHasToolCalls(hasToolCalls);
            result.setConversationMessages(messages);

        } catch (Exception e) {
            System.err.println("[FunctionCalling] 调用失败: " + e.getMessage());
            e.printStackTrace();
            result.setReply("抱歉，处理您的请求时出现错误: " + e.getMessage());
            result.setHasToolCalls(false);
        }

        result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
        System.out.println("[FunctionCalling] 处理完成，耗时: " + result.getProcessingTimeMs() + "ms");
        System.out.println("========== Function Calling 结束 ==========\n");

        return result;
    }

    /**
     * 构建 ToolSpecification 列表
     */
    @SuppressWarnings({"deprecation", "removal"})
    private List<ToolSpecification> buildToolSpecifications(List<String> toolNames) {
        // 筛选要使用的工具
        Set<String> activeTools;
        if (toolNames == null || toolNames.isEmpty()) {
            activeTools = builtinTools.keySet();
        } else {
            activeTools = new HashSet<>(toolNames);
            activeTools.retainAll(builtinTools.keySet());
        }

        if (activeTools.isEmpty()) {
            return Collections.emptyList();
        }

        // 扫描 DevAgentService 的 @Tool 方法构建 ToolSpecification
        List<ToolSpecification> specs = new ArrayList<>();
        Method[] methods = DevAgentService.class.getDeclaredMethods();

        for (Method method : methods) {
            dev.langchain4j.agent.tool.Tool toolAnnotation =
                    method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
            if (toolAnnotation == null) continue;

            String toolName = method.getName();
            if (!activeTools.contains(toolName)) continue;

            ToolSpecification.Builder specBuilder = ToolSpecification.builder()
                    .name(toolName)
                    .description(String.join("; ", toolAnnotation.value()));

            // 构建参数
            for (Parameter param : method.getParameters()) {
                String paramName = param.getName();
                String paramDesc = paramName + " (" + param.getType().getSimpleName() + ")";
                specBuilder.addParameter(paramName, 
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description(paramDesc));
            }

            specs.add(specBuilder.build());
        }

        return specs;
    }

    /**
     * 解析 JSON 参数字符串为 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            // 使用 fastjson2 解析
            return (Map<String, Object>) com.alibaba.fastjson2.JSON.parse(arguments);
        } catch (Exception e) {
            System.err.println("[FunctionCalling] 解析参数失败: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ==================== 会话管理 ====================

    /**
     * 创建一条用户消息
     */
    public static ChatMessage createUserMessage(String text) {
        return new UserMessage(text);
    }

    /**
     * 创建一条AI消息
     */
    public static ChatMessage createAiMessage(String text) {
        return AiMessage.from(text);
    }

    /**
     * 从 JSON 消息列表构建 ChatMessage 列表
     * 用于前端维护对话历史后回传
     *
     * @param messages JSON格式的消息列表
     *                 [{"role":"user","content":"..."}, {"role":"assistant","content":"..."}]
     * @return ChatMessage 列表
     */
    public List<ChatMessage> fromJsonMessages(String messages) {
        if (messages == null || messages.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> msgList =
                    (List<Map<String, Object>>) com.alibaba.fastjson2.JSON.parse(messages);
            List<ChatMessage> result = new ArrayList<>();
            for (Map<String, Object> msg : msgList) {
                String role = (String) msg.get("role");
                String content = (String) msg.get("content");
                if ("user".equals(role)) {
                    result.add(new UserMessage(content));
                } else if ("assistant".equals(role)) {
                    result.add(AiMessage.from(content));
                }
            }
            return result;
        } catch (Exception e) {
            System.err.println("[FunctionCalling] 解析消息列表失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ==================== 内部接口和结果类 ====================

    /**
     * 工具执行器接口
     */
    @FunctionalInterface
    public interface ToolExecutor {
        /**
         * 执行工具
         *
         * @param args 参数字典
         * @return 执行结果字符串
         */
        String execute(Map<String, Object> args);
    }

    /**
     * Function Calling 交互结果
     */
    public static class FunctionCallingResult {
        /**
         * 用户原始消息
         */
        private String userMessage;

        /**
         * AI回复文本
         */
        private String reply;

        /**
         * 是否有工具调用
         */
        private boolean hasToolCalls;

        /**
         * 本次调用的工具记录
         */
        private List<ToolCallRecord> toolCalls;

        /**
         * 完整对话消息列表（可用于后续继续对话）
         */
        private List<ChatMessage> conversationMessages;

        /**
         * 处理耗时
         */
        private long processingTimeMs;

        public String getUserMessage() { return userMessage; }
        public void setUserMessage(String userMessage) { this.userMessage = userMessage; }
        public String getReply() { return reply; }
        public void setReply(String reply) { this.reply = reply; }
        public boolean isHasToolCalls() { return hasToolCalls; }
        public void setHasToolCalls(boolean hasToolCalls) { this.hasToolCalls = hasToolCalls; }
        public List<ToolCallRecord> getToolCalls() { return toolCalls; }
        public void setToolCalls(List<ToolCallRecord> toolCalls) { this.toolCalls = toolCalls; }
        public List<ChatMessage> getConversationMessages() { return conversationMessages; }
        public void setConversationMessages(List<ChatMessage> conversationMessages) { this.conversationMessages = conversationMessages; }
        public long getProcessingTimeMs() { return processingTimeMs; }
        public void setProcessingTimeMs(long processingTimeMs) { this.processingTimeMs = processingTimeMs; }

        /**
         * 工具调用记录
         */
        public static class ToolCallRecord {
            private String toolName;
            private String arguments;
            private String result;

            public String getToolName() { return toolName; }
            public void setToolName(String toolName) { this.toolName = toolName; }
            public String getArguments() { return arguments; }
            public void setArguments(String arguments) { this.arguments = arguments; }
            public String getResult() { return result; }
            public void setResult(String result) { this.result = result; }
        }
    }
}
