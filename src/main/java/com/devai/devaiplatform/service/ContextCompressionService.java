package com.devai.devaiplatform.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上下文压缩服务（Context Compression Service）
 * 负责智能压缩历史对话上下文，提取有价值信息进行保留
 * 
 * 核心功能：
 * 1. 监控对话长度，达到阈值时触发压缩
 * 2. 使用AI提取关键信息和决策点
 * 3. 生成压缩后的上下文摘要
 * 4. 保留重要技术细节和业务逻辑
 */
@Service
public class ContextCompressionService {

    private final ChatLanguageModel chatModel;
    
    // 配置参数
    private static final int MAX_CONTEXT_MESSAGES = 20;           // 最大消息数阈值
    private static final int MAX_CONTEXT_CHARS = 15000;           // 最大字符数阈值
    private static final int COMPRESSED_SUMMARY_MAX_LENGTH = 2000; // 压缩摘要最大长度
    
    // 存储每个会话的压缩状态
    private final Map<String, CompressionState> compressionStates = new ConcurrentHashMap<>();

    public ContextCompressionService(ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 压缩状态类
     */
    public static class CompressionState {
        private String sessionId;
        private int originalMessageCount;
        private int compressedMessageCount;
        private long lastCompressionTime;
        private String compressedSummary;

        public CompressionState(String sessionId) {
            this.sessionId = sessionId;
            this.originalMessageCount = 0;
            this.compressedMessageCount = 0;
            this.lastCompressionTime = System.currentTimeMillis();
            this.compressedSummary = "";
        }

        // Getters and Setters
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        
        public int getOriginalMessageCount() { return originalMessageCount; }
        public void setOriginalMessageCount(int originalMessageCount) { this.originalMessageCount = originalMessageCount; }
        
        public int getCompressedMessageCount() { return compressedMessageCount; }
        public void setCompressedMessageCount(int compressedMessageCount) { this.compressedMessageCount = compressedMessageCount; }
        
        public long getLastCompressionTime() { return lastCompressionTime; }
        public void setLastCompressionTime(long lastCompressionTime) { this.lastCompressionTime = lastCompressionTime; }
        
        public String getCompressedSummary() { return compressedSummary; }
        public void setCompressedSummary(String compressedSummary) { this.compressedSummary = compressedSummary; }
    }

    /**
     * 检查是否需要压缩上下文
     * @param messages 当前对话消息列表
     * @return true 如果需要压缩
     */
    public boolean shouldCompress(List<ChatHistoryService.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }

        // 检查消息数量
        if (messages.size() >= MAX_CONTEXT_MESSAGES) {
            System.out.println("[上下文压缩] 消息数量达到阈值: " + messages.size() + "/" + MAX_CONTEXT_MESSAGES);
            return true;
        }

        // 检查总字符数
        int totalChars = messages.stream()
                .mapToInt(msg -> msg.getContent() != null ? msg.getContent().length() : 0)
                .sum();
        
        if (totalChars >= MAX_CONTEXT_CHARS) {
            System.out.println("[上下文压缩] 字符数达到阈值: " + totalChars + "/" + MAX_CONTEXT_CHARS);
            return true;
        }

        return false;
    }

    /**
     * 压缩上下文（核心方法）
     * @param sessionId 会话ID
     * @param messages 原始消息列表
     * @return 压缩后的摘要
     */
    public String compressContext(String sessionId, List<ChatHistoryService.Message> messages) {
        System.out.println("[上下文压缩] 开始压缩会话: " + sessionId + ", 消息数: " + messages.size());

        try {
            // 1. 构建待压缩的对话内容
            String conversationContent = buildConversationContent(messages);
            
            // 2. 使用AI提取关键信息并生成摘要
            String compressedSummary = generateCompressedSummary(conversationContent);
            
            // 3. 更新压缩状态
            CompressionState state = compressionStates.computeIfAbsent(sessionId, CompressionState::new);
            state.setOriginalMessageCount(messages.size());
            state.setCompressedMessageCount(1); // 压缩后变成1条摘要
            state.setLastCompressionTime(System.currentTimeMillis());
            state.setCompressedSummary(compressedSummary);

            System.out.println("[上下文压缩] 压缩完成，原消息数: " + messages.size() + 
                             ", 摘要长度: " + compressedSummary.length());
            
            return compressedSummary;

        } catch (Exception e) {
            System.err.println("[上下文压缩] 压缩失败: " + e.getMessage());
            e.printStackTrace();
            return "上下文压缩失败，请手动清理历史消息";
        }
    }

    /**
     * 构建对话内容字符串
     */
    private String buildConversationContent(List<ChatHistoryService.Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            ChatHistoryService.Message msg = messages.get(i);
            String role = "user".equals(msg.getRole()) ? "用户" : "AI助手";
            sb.append("【").append(role).append("】\n");
            sb.append(msg.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 使用AI生成压缩摘要
     */
    private String generateCompressedSummary(String conversationContent) throws Exception {
        String prompt = String.format("""
            你是一个专业的对话上下文压缩专家。请分析以下对话内容，提取最有价值的信息进行压缩。
            
            ## 压缩要求：
            1. **保留关键信息**：技术方案、代码示例、错误解决方案、重要决策
            2. **去除冗余内容**：问候语、重复确认、无关闲聊
            3. **结构化输出**：按主题分类整理
            4. **保持完整性**：确保压缩后的信息仍然有用
            
            ## 输出格式：
            ```
            ### 📋 对话摘要
            
            #### 🔧 技术问题与解决方案
            - [问题1]: [解决方案]
            - [问题2]: [解决方案]
            
            #### 💻 代码与技术要点
            - [技术点1]: [详细说明]
            - [技术点2]: [详细说明]
            
            #### 🎯 关键决策与结论
            - [决策1]: [原因和结果]
            - [决策2]: [原因和结果]
            
            #### ⚠️ 待处理事项
            - [事项1]: [状态]
            - [事项2]: [状态]
            ```
            
            ## 原始对话内容：
            %s
            
            请生成压缩摘要（不超过%d字）：
            """, conversationContent, COMPRESSED_SUMMARY_MAX_LENGTH);

        String summary = chatModel.generate(prompt);
        
        // 确保摘要不为空
        if (summary == null || summary.isBlank()) {
            return "对话已压缩，但未能生成有效摘要";
        }
        
        return summary;
    }

    /**
     * 获取压缩后的上下文（用于后续对话）
     * @param sessionId 会话ID
     * @param recentMessages 最近的消息（压缩后新增的）
     * @return 组合后的上下文
     */
    public String getCompressedContext(String sessionId, List<ChatHistoryService.Message> recentMessages) {
        CompressionState state = compressionStates.get(sessionId);
        
        StringBuilder context = new StringBuilder();
        
        // 添加压缩的历史摘要
        if (state != null && !state.getCompressedSummary().isEmpty()) {
            context.append("## 📜 历史对话摘要（已压缩）\n\n");
            context.append(state.getCompressedSummary()).append("\n\n");
            context.append("---\n\n");
        }
        
        // 添加最近的未压缩消息
        if (recentMessages != null && !recentMessages.isEmpty()) {
            context.append("## 💬 最近对话\n\n");
            for (ChatHistoryService.Message msg : recentMessages) {
                String role = "user".equals(msg.getRole()) ? "用户" : "AI助手";
                context.append("【").append(role).append("】\n");
                context.append(msg.getContent()).append("\n\n");
            }
        }
        
        return context.toString();
    }

    /**
     * 获取压缩统计信息
     */
    public Map<String, Object> getCompressionStats(String sessionId) {
        CompressionState state = compressionStates.get(sessionId);
        Map<String, Object> stats = new ConcurrentHashMap<>();
        
        if (state != null) {
            stats.put("sessionId", sessionId);
            stats.put("originalMessageCount", state.getOriginalMessageCount());
            stats.put("compressedMessageCount", state.getCompressedMessageCount());
            stats.put("compressionRatio", 
                String.format("%.1f%%", 
                    (1.0 - (double)state.getCompressedMessageCount() / state.getOriginalMessageCount()) * 100));
            stats.put("lastCompressionTime", state.getLastCompressionTime());
            stats.put("hasCompressedSummary", !state.getCompressedSummary().isEmpty());
        } else {
            stats.put("message", "该会话尚未进行上下文压缩");
        }
        
        return stats;
    }

    /**
     * 手动触发压缩
     */
    public String triggerManualCompression(String sessionId, List<ChatHistoryService.Message> messages) {
        System.out.println("[上下文压缩] 手动触发压缩: " + sessionId);
        return compressContext(sessionId, messages);
    }

    /**
     * 清除压缩状态
     */
    public void clearCompressionState(String sessionId) {
        compressionStates.remove(sessionId);
        System.out.println("[上下文压缩] 已清除会话压缩状态: " + sessionId);
    }
}
