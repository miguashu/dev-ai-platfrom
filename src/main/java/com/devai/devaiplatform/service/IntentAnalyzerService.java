package com.devai.devaiplatform.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 意图分析服务 - 使用AI理解用户消息，识别意图并拆分子任务
 * 核心职责：
 * 1. 接收用户原始消息
 * 2. 调用AI分析消息，理解真实需求
 * 3. 识别意图类型（从TaskIntent枚举中匹配）
 * 4. 判断是否为复合任务，如果是则拆分为多个子任务
 * 5. 提取关键内容（代码、SQL、需求描述等）
 */
@Service
public class IntentAnalyzerService {

    private final ChatLanguageModel chatModel;
    private final PersistentMemoryService memoryService;
    private final IntentAgent intentAgent;

    public IntentAnalyzerService(ChatLanguageModel chatModel,
                                 PersistentMemoryService memoryService) {
        this.chatModel = chatModel;
        this.memoryService = memoryService;

        this.intentAgent = AiServices.builder(IntentAgent.class)
                .chatLanguageModel(chatModel)
                .tools(new IntentTools())
                .systemMessageProvider(chatMemory -> INTENT_SYSTEM_PROMPT)
                .build();
    }


    /**
     * 分析用户消息，返回意图分析结果
     *
     * @param userMessage 用户原始消息
     * @return 分析结果（包含意图、置信度、子任务等）
     */
    public TaskAnalysisResult analyze(String userMessage) {
        long startTime = System.currentTimeMillis();

        System.out.println("\n========== 意图分析开始 ==========");
        System.out.println("用户消息: " + truncate(userMessage, 200));

        // 1. 构建可用意图列表描述
        String intentList = buildIntentListDescription();

        // 2. 调用AI进行意图分析
        String analysisPrompt = String.format(PromptTemplate.INTENT_ANALYSIS_TEMPLATE, userMessage, intentList);
        String aiResponse = callAI(analysisPrompt);

        System.out.println("AI分析结果: " + truncate(aiResponse, 500));

        // 3. 解析AI返回的JSON
        TaskAnalysisResult result = parseAnalysisResult(userMessage, aiResponse);

        // 4. 检索相关历史记忆
        String memories = memoryService.getRelevantMemories(userMessage);
        result.setRelevantMemories(memories);

        // 5. 记录分析耗时
        result.setAnalysisTimeMs(System.currentTimeMillis() - startTime);

        System.out.println("分析完成: " + result.getSummary());
        System.out.println("耗时: " + result.getAnalysisTimeMs() + "ms");
        System.out.println("========== 意图分析结束 ==========\n");

        return result;
    }

    /**
     * 快速意图识别（不检索记忆，适用于简单场景）
     */
    public TaskAnalysisResult quickAnalyze(String userMessage) {
        long startTime = System.currentTimeMillis();

        String intentList = buildIntentListDescription();
        String analysisPrompt = String.format(PromptTemplate.INTENT_ANALYSIS_TEMPLATE, userMessage, intentList);
        String aiResponse = callAI(analysisPrompt);

        TaskAnalysisResult result = parseAnalysisResult(userMessage, aiResponse);
        result.setAnalysisTimeMs(System.currentTimeMillis() - startTime);

        return result;
    }

    /**
     * 构建可用意图列表的描述文本
     */
    private String buildIntentListDescription() {
        return Arrays.stream(TaskIntent.values())
                .filter(intent -> intent != TaskIntent.UNKNOWN && intent != TaskIntent.MULTI_TASK)
                .map(intent -> String.format("- %s: %s", intent.name(), intent.getDescription()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 调用AI模型（智能体模式）
     */
    private String callAI(String prompt) {
        try {
            return intentAgent.analyzeIntent(prompt);
        } catch (Exception e) {
            System.err.println("[意图分析] AI调用失败: " + e.getMessage());
            return "{\"understoodRequirement\":\"AI分析失败\",\"primaryIntent\":\"GENERAL_CHAT\",\"confidence\":0.5,\"isMultiTask\":false,\"extractedContent\":\"\",\"subTasks\":[]}";
        }
    }

    // ... existing code ...




    /**
     * 解析AI返回的分析结果
     * 从JSON字符串中提取意图信息
     */
    private TaskAnalysisResult parseAnalysisResult(String userMessage, String aiResponse) {
        TaskAnalysisResult result = new TaskAnalysisResult();
        result.setOriginalMessage(userMessage);

        try {
            // 清理AI返回的内容（去除markdown代码块标记）
            String json = cleanJsonResponse(aiResponse);

            // 简单JSON解析（避免引入额外依赖）
            String primaryIntent = extractJsonField(json, "primaryIntent");
            String confidenceStr = extractJsonField(json, "confidence");
            String isMultiTaskStr = extractJsonField(json, "isMultiTask");
            String understoodRequirement = extractJsonField(json, "understoodRequirement");
            String extractedContent = extractJsonField(json, "extractedContent");

            // 设置主意图
            TaskIntent intent = TaskIntent.fromName(primaryIntent);
            result.setPrimaryIntent(intent);

            // 设置置信度
            double confidence = 0.5;
            try {
                confidence = Double.parseDouble(confidenceStr);
            } catch (NumberFormatException ignored) {
            }
            result.setConfidence(confidence);

            // 设置是否复合任务
            boolean isMultiTask = "true".equalsIgnoreCase(isMultiTaskStr);
            result.setMultiTask(isMultiTask);

            // 设置需求理解和提取内容
            result.setUnderstoodRequirement(understoodRequirement);
            result.setExtractedContent(extractedContent);

            // 解析子任务
            if (isMultiTask) {
                parseSubTasks(json, result);
            }

        } catch (Exception e) {
            System.err.println("[意图分析] 解析结果失败: " + e.getMessage());
            // 解析失败，默认走通用对话
            result.setPrimaryIntent(TaskIntent.GENERAL_CHAT);
            result.setConfidence(0.3);
            result.setUnderstoodRequirement("无法解析AI返回结果，使用默认对话模式");
            result.setExtractedContent(userMessage);
        }

        return result;
    }

    /**
     * 解析子任务列表
     */
    private void parseSubTasks(String json, TaskAnalysisResult result) {
        // 查找subTasks数组
        int subTasksStart = json.indexOf("\"subTasks\"");
        if (subTasksStart == -1) return;

        int arrayStart = json.indexOf("[", subTasksStart);
        int arrayEnd = findMatchingBracket(json, arrayStart, '[', ']');
        if (arrayStart == -1 || arrayEnd == -1) return;

        String subTasksJson = json.substring(arrayStart + 1, arrayEnd);

        // 简单拆分每个子任务对象
        int depth = 0;
        int objStart = -1;
        int index = 1;

        for (int i = 0; i < subTasksJson.length(); i++) {
            char c = subTasksJson.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart != -1) {
                    String subTaskJson = subTasksJson.substring(objStart, i + 1);
                    TaskAnalysisResult.SubTask subTask = parseSubTask(subTaskJson, index++);
                    result.addSubTask(subTask);
                    objStart = -1;
                }
            }
        }
    }

    /**
     * 解析单个子任务
     */
    private TaskAnalysisResult.SubTask parseSubTask(String subTaskJson, int index) {
        String intentName = extractJsonField(subTaskJson, "intent");
        String description = extractJsonField(subTaskJson, "description");
        String content = extractJsonField(subTaskJson, "content");

        TaskIntent intent = TaskIntent.fromName(intentName);
        return new TaskAnalysisResult.SubTask(index, intent, description, content);
    }

    /**
     * 清理AI返回的JSON（去除markdown代码块标记）
     */
    private String cleanJsonResponse(String response) {
        if (response == null) return "{}";

        String cleaned = response.trim();

        // 去除 ```json 和 ``` 标记
        if (cleaned.contains("```json")) {
            int start = cleaned.indexOf("```json") + 7;
            int end = cleaned.lastIndexOf("```");
            if (end > start) {
                cleaned = cleaned.substring(start, end).trim();
            }
        } else if (cleaned.contains("```")) {
            int start = cleaned.indexOf("```") + 3;
            int end = cleaned.lastIndexOf("```");
            if (end > start) {
                cleaned = cleaned.substring(start, end).trim();
            }
        }

        return cleaned;
    }

    /**
     * 从JSON字符串中提取字段值（简单实现，避免引入JSON库）
     */
    private String extractJsonField(String json, String fieldName) {
        if (json == null || fieldName == null) return "";

        String pattern = "\"" + fieldName + "\"";
        int fieldStart = json.indexOf(pattern);
        if (fieldStart == -1) return "";

        int colonPos = json.indexOf(":", fieldStart + pattern.length());
        if (colonPos == -1) return "";

        // 跳过冒号后的空白
        int valueStart = colonPos + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= json.length()) return "";

        char firstChar = json.charAt(valueStart);

        // 处理字符串值
        if (firstChar == '"') {
            int valueEnd = valueStart + 1;
            while (valueEnd < json.length()) {
                if (json.charAt(valueEnd) == '"' && json.charAt(valueEnd - 1) != '\\') {
                    break;
                }
                valueEnd++;
            }
            return json.substring(valueStart + 1, valueEnd);
        }

        // 处理数字/布尔值
        int valueEnd = valueStart;
        while (valueEnd < json.length() && !Character.isWhitespace(json.charAt(valueEnd))
                && json.charAt(valueEnd) != ',' && json.charAt(valueEnd) != '}'
                && json.charAt(valueEnd) != ']') {
            valueEnd++;
        }
        return json.substring(valueStart, valueEnd);
    }

    /**
     * 查找匹配的括号
     */
    private int findMatchingBracket(String str, int openPos, char open, char close) {
        if (openPos == -1) return -1;
        int depth = 1;
        for (int i = openPos + 1; i < str.length(); i++) {
            if (str.charAt(i) == open) depth++;
            else if (str.charAt(i) == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * 截断字符串（用于日志输出）
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return "null";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "...";
    }

    private static final String INTENT_SYSTEM_PROMPT = """
            你是一个专业的意图分析引擎，负责精准识别用户消息的真实意图。

            【核心职责】
            1. 分析用户消息，识别其核心意图
            2. 判断是否为复合任务，如果是则拆分为多个子任务
            3. 提取关键内容（代码、SQL、需求描述等）
            4. 评估置信度（0-1）

            【网页搜索识别规则】
            当用户消息明显涉及以下场景时，primaryIntent 应设为 WEB_SEARCH：
            - 询问"最新"、"最近"、"当前最新版本"等时效性信息
            - 询问不熟悉的第三方框架、库、工具的用法
            - 要求对比不同技术方案/产品（需要联网查证）
            - 询问行业动态、技术趋势、新闻资讯
            - 询问开源项目的最新进展、GitHub star数等实时数据
            - 消息中明确提到"搜索"、"查一下"、"上网搜"、"网上查"

            【输出规则】
            - 必须严格返回JSON格式，不要包含任何额外文字
            - primaryIntent 必须从给定的意图列表中选择
            - confidence 为 0.0 到 1.0 之间的浮点数
            - 如果是复合任务，isMultiTask 为 true，并在 subTasks 中列出每个子任务
            """;

    public interface IntentAgent {
        String analyzeIntent(String userMessage);
    }

    public class IntentTools {
        @dev.langchain4j.agent.tool.Tool("检索历史记忆，辅助判断用户意图是否与历史问题相关")
        public String searchMemory(String query) {
            return memoryService.getRelevantMemories(query);
        }
    }
}
