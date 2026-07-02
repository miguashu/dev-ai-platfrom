package com.devai.devaiplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 记忆蒸馏调度器（Memory Distillation Scheduler）
 * 负责自动化的记忆蒸馏和清理任务
 *
 * 核心功能：
 * 1. 记录每次对话（临时存储）
 * 2. 每天凌晨2点批量蒸馏高价值对话
 * 3. 使用AI生成高质量摘要
 * 4. 智能评分和分类
 */
@Service
public class MemoryDistillationScheduler {

    private final PersistentMemoryService memoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 待蒸馏的对话队列（内存中）
    private static final List<ConversationRecord> pendingConversations = Collections.synchronizedList(new ArrayList<>());

    // 配置文件路径
    private static final String PENDING_FILE_PATH = "./agent_memory/pending_conversations.json";

    public MemoryDistillationScheduler(PersistentMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * 初始化时加载未处理的对话
     */
    @PostConstruct
    public void init() {
        loadPendingConversations();
        System.out.println("[蒸馏调度器] 已启动，当前待处理对话: " + pendingConversations.size());
    }

    /**
     * 记录对话（在每次问答后调用）
     * @param question 用户问题
     * @param answer AI回答
     * @param taskType 任务类型（可选）
     */
    public void recordConversation(String question, String answer, String taskType) {
        ConversationRecord record = new ConversationRecord();
        record.setQuestion(question);
        record.setAnswer(answer);
        record.setTaskType(taskType != null ? taskType : "general");
        record.setTimestamp(LocalDateTime.now().toString()); // 【修复】使用字符串存储时间

        pendingConversations.add(record);

        // 异步保存到文件（防止丢失）
        savePendingConversations();

        System.out.println("[对话记录] 已记录，当前待处理: " + pendingConversations.size() + " 条");
    }

    /**
     * 【核心】每天凌晨2点执行批量蒸馏
     * cron表达式：秒 分 时 日 月 周
     * 0 0 2 * * ? = 每天凌晨2点
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledDistillation() {
        System.out.println("\n========== 开始定时记忆蒸馏 ==========");
        System.out.println("当前时间: " + LocalDateTime.now());

        if (pendingConversations.isEmpty()) {
            System.out.println("[定时蒸馏] 没有待处理的对话");
            return;
        }

        int totalProcessed = pendingConversations.size();
        int successCount = 0;
        int skippedCount = 0;

        System.out.println("待处理对话: " + totalProcessed + " 条\n");

        for (int i = 0; i < pendingConversations.size(); i++) {
            ConversationRecord record = pendingConversations.get(i);

            try {
                System.out.println("[" + (i + 1) + "/" + totalProcessed + "] 处理: " +
                    record.getQuestion().substring(0, Math.min(50, record.getQuestion().length())));

                // 【新增】先检索相关历史记忆作为上下文
                String relevantMemories = getRelevantContext(record);
                if (!relevantMemories.isEmpty()) {
                    System.out.println("  → 找到相关历史记忆: " + countMemories(relevantMemories) + " 条");
                }

                // 判断是否需要蒸馏
                if (!shouldDistill(record)) {
                    System.out.println("  → 跳过（低价值内容）");
                    skippedCount++;
                    continue;
                }

                // 计算重要性评分（考虑历史记忆）
                int importance = calculateImportanceWithContext(record, relevantMemories);

                // 只保存高价值内容（重要性 >= 6）
                if (importance < 6) {
                    System.out.println("  → 跳过（重要性: " + importance + "/10）");
                    skippedCount++;
                    continue;
                }

                // 生成高质量摘要（结合历史记忆）
                String summary = generateHighQualitySummaryWithContext(record, relevantMemories);

                // 分类
                String category = categorize(record);

                // 提取关键词
                List<String> keywords = extractKeywords(record);

                // 保存到永久记忆
                String memoryId = memoryService.addMemory(
                    category,
                    summary,
                    record.getQuestion() + "\n\nAI回答:\n" + record.getAnswer(),
                    keywords,
                    importance
                );

                successCount++;
                System.out.println("  ✓ 已保存 (ID: " + memoryId + ", 重要性: " + importance + "/10)\n");

            } catch (Exception e) {
                System.err.println("  ✗ 处理失败: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // 清空已处理的对话
        pendingConversations.clear();
        savePendingConversations();

        System.out.println("\n========== 定时蒸馏完成 ==========");
        System.out.println("总计处理: " + totalProcessed + " 条");
        System.out.println("成功保存: " + successCount + " 条");
        System.out.println("跳过过滤: " + skippedCount + " 条");
        System.out.println("====================================\n");
    }

    /**
     * 判断是否应该蒸馏这条对话
     */
    private boolean shouldDistill(ConversationRecord record) {
        String content = (record.getQuestion() + " " + record.getAnswer()).toLowerCase();
        // 缩短最低长度阈值
        if (record.getAnswer().length() < 80) {
            return false;
        }
        // 简单问候过滤
        String[] trivialPatterns = {"你好", "谢谢", "再见", "好的", "明白了"};
        for (String pattern : trivialPatterns) {
            if (content.equals(pattern)) return false;
        }
        // 关键词门槛降低为1个
        String[] techKeywords = {"代码", "sql", "配置", "优化", "异常", "错误", "方案", "实现", "数据库"};
        int keywordCount = 0;
        for (String keyword : techKeywords) {
            if (content.contains(keyword)) keywordCount++;
        }
        return keywordCount >= 1;
    }
    /**
     * 计算重要性评分（1-10分）
     */
    private int calculateImportance(ConversationRecord record) {
        int score = 5; // 基础分

        String content = record.getQuestion() + " " + record.getAnswer();
        String lowerContent = content.toLowerCase();

        // 包含代码示例 (+2)
        if (content.contains("```") || content.contains("@Service") ||
            content.contains("@Controller") || content.contains("CREATE TABLE")) {
            score += 2;
        }

        // 包含SQL优化 (+1)
        if (lowerContent.contains("sql") || lowerContent.contains("index") ||
            lowerContent.contains("explain")) {
            score += 1;
        }

        // 包含错误处理 (+1)
        if (lowerContent.contains("error") || lowerContent.contains("exception") ||
            lowerContent.contains("报错") || lowerContent.contains("异常")) {
            score += 1;
        }

        // 包含最佳实践 (+1)
        if (lowerContent.contains("优化") || lowerContent.contains("最佳实践") ||
            lowerContent.contains("建议") || lowerContent.contains("方案")) {
            score += 1;
        }

        // 答案很长且详细 (+1)
        if (record.getAnswer().length() > 1000) {
            score += 1;
        }

        return Math.min(10, score);
    }

    /**
     * 生成高质量摘要（简单版本）
     * TODO: 可以集成LLM生成更智能的摘要
     */
    private String generateHighQualitySummary(ConversationRecord record) {
        // 策略1：提取问题前60字符作为标题
        String question = record.getQuestion();
        if (question.length() <= 60) {
            return question;
        }

        // 如果问题太长，尝试找到关键部分
        int firstQuestionMark = question.indexOf("?");
        if (firstQuestionMark > 10 && firstQuestionMark < 60) {
            return question.substring(0, firstQuestionMark + 1);
        }

        return question.substring(0, 60) + "...";
    }

    /**
     * 分类对话内容
     */
    private String categorize(ConversationRecord record) {
        String content = (record.getQuestion() + " " + record.getAnswer()).toLowerCase();

        if (content.contains("sql") || content.contains("数据库") || content.contains("查询")) {
            return "database";
        }
        if (content.contains("代码") || content.contains("class") || content.contains("@service")) {
            return "technical";
        }
        if (content.contains("错误") || content.contains("异常") || content.contains("error")) {
            return "problem";
        }
        if (content.contains("需求") || content.contains("功能") || content.contains("业务")) {
            return "business";
        }
        if (content.contains("测试") || content.contains("junit") || content.contains("mock")) {
            return "testing";
        }

        return "general";
    }

    /**
     * 提取关键词
     */
    private List<String> extractKeywords(ConversationRecord record) {
        Set<String> keywords = new HashSet<>();
        String content = (record.getQuestion() + " " + record.getAnswer()).toLowerCase();

        // 技术术语模式匹配
        String[] patterns = {
            "spring", "sql", "api", "service", "controller", "entity",
            "repository", "jpa", "mybatis", "mysql", "postgresql",
            "redis", "docker", "kubernetes", "微服务"
        };

        for (String pattern : patterns) {
            if (content.contains(pattern)) {
                keywords.add(pattern);
            }
        }

        // 从问题中提取重要单词
        String[] words = record.getQuestion().split("\\W+");
        for (String word : words) {
            if (word.length() >= 4 && Character.isLetter(word.charAt(0))) {
                // 排除常见停用词
                if (!isStopWord(word)) {
                    keywords.add(word.toLowerCase());
                }
            }
        }

        return new ArrayList<>(keywords).subList(0, Math.min(10, keywords.size()));
    }

    /**
     * 判断是否为停用词
     */
    private boolean isStopWord(String word) {
        Set<String> stopWords = Set.of(
            "what", "how", "when", "where", "why", "which",
            "这个", "那个", "如何", "怎么", "什么", "为什么"
        );
        return stopWords.contains(word.toLowerCase());
    }

    /**
     * 【新增】获取相关历史记忆作为上下文
     */
    private String getRelevantContext(ConversationRecord record) {
        try {
            // 改为问题+答案合并检索，不粗暴截断
            String fullText = record.getQuestion() + " " + record.getAnswer();
            // 只限制最大长度，不直接切前100字
            String query = fullText.length() > 300 ? fullText.substring(0, 300) : fullText;
            List<PersistentMemoryService.MemoryEntry> memories = memoryService.searchMemories(query, null, 8);
            if (memories == null || memories.isEmpty()) {
                return "";
            }
            StringBuilder context = new StringBuilder();
            for (int i = 0; i < memories.size(); i++) {
                PersistentMemoryService.MemoryEntry memory = memories.get(i);
                context.append("\n--- 历史记忆 ").append(i + 1).append(" ---\n");
                context.append("分类: ").append(memory.getCategory()).append("\n");
                context.append("标题: ").append(memory.getTitle()).append("\n");
                context.append("内容摘要: ").append(memory.getContent()).append("\n");
                context.append("重要性: ").append(memory.getImportance()).append("/10\n");
            }
            return context.toString();
        } catch (Exception e) {
            System.err.println("[蒸馏调度器] 获取上下文失败: " + e.getMessage());
            return "";
        }
    }
    /**
     * 【新增】统计记忆数量
     */
    private int countMemories(String context) {
        if (context == null || context.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = context.indexOf("--- 历史记忆", index)) != -1) {
            count++;
            index += 14;
        }
        return count;
    }

    /**
     * 【新增】结合上下文计算重要性评分
     */
    private int calculateImportanceWithContext(ConversationRecord record, String relevantMemories) {
        // 先计算基础重要性
        int baseScore = calculateImportance(record);

        // 如果没有相关记忆，返回基础分数
        if (relevantMemories.isEmpty()) {
            return baseScore;
        }

        // 有相关记忆时，提升重要性（因为说明这是持续关注的主题）
        int memoryCount = countMemories(relevantMemories);
        int bonus = Math.min(3, memoryCount); // 最多+3分

        int finalScore = baseScore + bonus;
        System.out.println("  → 基础评分: " + baseScore + ", 上下文加成: +" + bonus +
                         " (相关记忆: " + memoryCount + " 条), 最终评分: " + finalScore);

        return Math.min(10, finalScore);
    }

    /**
     * 【新增】结合上下文生成高质量摘要
     */
    private String generateHighQualitySummaryWithContext(ConversationRecord record, String relevantMemories) {
        // 先生成基础摘要
        String baseSummary = generateHighQualitySummary(record);

        // 如果没有相关记忆，返回基础摘要
        if (relevantMemories.isEmpty()) {
            return baseSummary;
        }

        // 提取相关记忆中的关键信息
        StringBuilder enhancedSummary = new StringBuilder();
        enhancedSummary.append(baseSummary);

        // 如果有相关记忆，在摘要中补充背景
        int memoryCount = countMemories(relevantMemories);
        if (memoryCount > 0) {
            enhancedSummary.append(" [相关背景: ").append(memoryCount).append(" 条历史记忆]");
        }

        return enhancedSummary.toString();
    }

    /**
     * 保存待处理对话到文件（防止重启丢失）
     */
    private void savePendingConversations() {
        try {
            File dir = new File(PENDING_FILE_PATH).getParentFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }

            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(PENDING_FILE_PATH), pendingConversations);
        } catch (IOException e) {
            System.err.println("[蒸馏调度器] 保存待处理对话失败: " + e.getMessage());
        }
    }

    /**
     * 从文件加载待处理对话
     */
    @SuppressWarnings("unchecked")
    private void loadPendingConversations() {
        File file = new File(PENDING_FILE_PATH);
        if (!file.exists()) {
            System.out.println("[蒸馏调度器] 未找到待处理对话文件，将创建新文件");
            return;
        }

        try {
            // 检查文件大小，如果为0则跳过
            if (file.length() == 0) {
                System.out.println("[蒸馏调度器] 待处理对话文件为空，将重新创建");
                return;
            }

            List<Map<String, Object>> loaded = objectMapper.readValue(file, List.class);

            if (loaded == null || loaded.isEmpty()) {
                System.out.println("[蒸馏调度器] 待处理对话文件为空列表");
                return;
            }

            int successCount = 0;
            int failedCount = 0;

            for (Map<String, Object> data : loaded) {
                try {
                    // 验证必要字段是否存在
                    if (!data.containsKey("question") || !data.containsKey("answer")) {
                        System.out.println("[蒸馏调度器] 跳过无效记录（缺少必要字段）");
                        failedCount++;
                        continue;
                    }

                    ConversationRecord record = new ConversationRecord();
                    record.setQuestion((String) data.get("question"));
                    record.setAnswer((String) data.get("answer"));
                    record.setTaskType((String) data.getOrDefault("taskType", "general"));
                    // 【修复】直接设置字符串时间，不需要解析
                    record.setTimestamp((String) data.getOrDefault("timestamp", LocalDateTime.now().toString()));

                    pendingConversations.add(record);
                    successCount++;
                } catch (Exception e) {
                    System.err.println("[蒸馏调度器] 加载单条记录失败: " + e.getMessage());
                    failedCount++;
                }
            }

            System.out.println("[蒸馏调度器] 恢复 " + successCount + " 条待处理对话" +
                             (failedCount > 0 ? " (失败 " + failedCount + " 条)" : ""));

        } catch (Exception e) {
            // JSON格式错误时，备份损坏的文件并创建新的空文件
            System.err.println("[蒸馏调度器] 加载待处理对话失败: " + e.getMessage());
            System.err.println("[蒸馏调度器] 正在备份损坏的文件...");

            try {
                // 备份损坏的文件
                String backupPath = PENDING_FILE_PATH + ".backup." + System.currentTimeMillis();
                File backupFile = new File(backupPath);
                if (file.renameTo(backupFile)) {
                    System.out.println("[蒸馏调度器] 已备份损坏文件到: " + backupPath);
                }

                // 创建新的空文件
                savePendingConversations();
                System.out.println("[蒸馏调度器] 已创建新的待处理对话文件");
            } catch (Exception backupEx) {
                System.err.println("[蒸馏调度器] 备份文件失败: " + backupEx.getMessage());
            }
        }
    }

    /**
     * 手动触发一次蒸馏（用于测试）
     */
    public String triggerDistillationNow() {
        System.out.println("[蒸馏调度器] 手动触发蒸馏...");
        scheduledDistillation();
        return "已触发批量蒸馏，请查看控制台日志";
    }

    /**
     * 获取待处理对话数量
     */
    public int getPendingCount() {
        return pendingConversations.size();
    }

    /**
     * 对话记录类
     */
    public static class ConversationRecord {
        private String question;
        private String answer;
        private String taskType;
        private String timestamp;  // 【修复】使用字符串存储时间，避免Jackson序列化问题

        public ConversationRecord() {}

        // Getters and Setters
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }

        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }

        public String getTaskType() { return taskType; }
        public void setTaskType(String taskType) { this.taskType = taskType; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        /**
         * 【新增】获取LocalDateTime对象（方便使用时转换）
         * 【重要】使用@JsonIgnore跳过此方法，避免Jackson序列化错误
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public LocalDateTime getTimestampAsDateTime() {
            if (timestamp == null || timestamp.isEmpty()) {
                return LocalDateTime.now();
            }
            try {
                return LocalDateTime.parse(timestamp);
            } catch (Exception e) {
                return LocalDateTime.now();
            }
        }
    }
}
