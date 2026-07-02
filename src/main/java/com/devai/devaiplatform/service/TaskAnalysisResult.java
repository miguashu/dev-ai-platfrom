package com.devai.devaiplatform.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务分析结果 - 包含意图识别、子任务拆分、参数提取等信息
 * 由 IntentAnalyzerService 生成，供 TaskDispatcherService 路由使用
 */
public class TaskAnalysisResult {

    /**
     * 原始用户消息
     */
    private String originalMessage;

    /**
     * 识别出的主意图
     */
    private TaskIntent primaryIntent;

    /**
     * 意图置信度 (0.0 ~ 1.0)
     */
    private double confidence;

    /**
     * 是否为复合任务（包含多个子任务）
     */
    private boolean multiTask;

    /**
     * 子任务列表（复合任务时使用）
     */
    private List<SubTask> subTasks = new ArrayList<>();

    /**
     * 从用户消息中提取的关键参数
     * 例如：代码片段、SQL语句、需求描述等
     */
    private String extractedContent;

    /**
     * AI对用户需求理解的摘要
     */
    private String understoodRequirement;

    /**
     * 相关历史记忆（已检索）
     */
    private String relevantMemories;

    /**
     * 分析耗时（毫秒）
     */
    private long analysisTimeMs;

    public TaskAnalysisResult() {
    }

    public TaskAnalysisResult(String originalMessage, TaskIntent primaryIntent, double confidence) {
        this.originalMessage = originalMessage;
        this.primaryIntent = primaryIntent;
        this.confidence = confidence;
    }

    // ==================== Getters & Setters ====================

    public String getOriginalMessage() {
        return originalMessage;
    }

    public void setOriginalMessage(String originalMessage) {
        this.originalMessage = originalMessage;
    }

    public TaskIntent getPrimaryIntent() {
        return primaryIntent;
    }

    public void setPrimaryIntent(TaskIntent primaryIntent) {
        this.primaryIntent = primaryIntent;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public boolean isMultiTask() {
        return multiTask;
    }

    public void setMultiTask(boolean multiTask) {
        this.multiTask = multiTask;
    }

    public List<SubTask> getSubTasks() {
        return subTasks;
    }

    public void setSubTasks(List<SubTask> subTasks) {
        this.subTasks = subTasks;
    }

    public void addSubTask(SubTask subTask) {
        this.subTasks.add(subTask);
    }

    public String getExtractedContent() {
        return extractedContent;
    }

    public void setExtractedContent(String extractedContent) {
        this.extractedContent = extractedContent;
    }

    public String getUnderstoodRequirement() {
        return understoodRequirement;
    }

    public void setUnderstoodRequirement(String understoodRequirement) {
        this.understoodRequirement = understoodRequirement;
    }

    public String getRelevantMemories() {
        return relevantMemories;
    }

    public void setRelevantMemories(String relevantMemories) {
        this.relevantMemories = relevantMemories;
    }

    public long getAnalysisTimeMs() {
        return analysisTimeMs;
    }

    public void setAnalysisTimeMs(long analysisTimeMs) {
        this.analysisTimeMs = analysisTimeMs;
    }

    /**
     * 获取分析摘要（用于日志和调试）
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("意图: ").append(primaryIntent.getDisplayName());
        sb.append(" (置信度: ").append(String.format("%.1f%%", confidence * 100)).append(")");
        if (multiTask) {
            sb.append(" [复合任务, ").append(subTasks.size()).append("个子任务]");
        }
        if (understoodRequirement != null && !understoodRequirement.isEmpty()) {
            sb.append("\n需求理解: ").append(understoodRequirement);
        }
        return sb.toString();
    }

    // ==================== 子任务内部类 ====================

    /**
     * 子任务 - 复合任务拆分后的每个子项
     */
    public static class SubTask {
        /**
         * 子任务序号
         */
        private int index;

        /**
         * 子任务意图
         */
        private TaskIntent intent;

        /**
         * 子任务描述
         */
        private String description;

        /**
         * 子任务提取的内容/参数
         */
        private String content;

        /**
         * 子任务执行结果
         */
        private String result;

        /**
         * 是否执行成功
         */
        private boolean success;

        public SubTask() {
        }

        public SubTask(int index, TaskIntent intent, String description, String content) {
            this.index = index;
            this.intent = intent;
            this.description = description;
            this.content = content;
        }

        // Getters & Setters
        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
        public TaskIntent getIntent() { return intent; }
        public void setIntent(TaskIntent intent) { this.intent = intent; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        @Override
        public String toString() {
            return String.format("[%d] %s: %s", index, intent.getDisplayName(), description);
        }
    }
}
