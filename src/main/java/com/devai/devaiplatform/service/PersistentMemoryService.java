package com.devai.devaiplatform.service;



import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;


import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 永久记忆服务（Persistent Memory Service）
 * 负责Agent的长期记忆存储、检索和管理
 *
 * 核心功能：
 * 1. 永久记忆持久化到本地文件
 * 2. 智能记忆检索（基于关键词匹配）
 * 3. 记忆重要性评分
 * 4. 记忆去重和合并
 */
@Service
public class PersistentMemoryService {

    // 记忆存储文件路径
    private static final String MEMORY_FILE_PATH = "./agent_memory/persistent_memory.json";

    // 内存中的记忆存储（运行时快速访问）
    private final Map<String, MemoryEntry> memoryStore = new ConcurrentHashMap<>();

    // JSON序列化器（配置支持 Java 8 时间类型）
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());


    // 归档文件路径（用于备份）
    private static final String ARCHIVE_FILE_PATH = "./agent_memory/archived_memory.json";



    // 【新增】配置参数
    private static final int MAX_ACTIVE_MEMORIES = 1000;  // 最大活跃记忆数
    private static final int MIN_IMPORTANCE_TO_KEEP = 4;   // 最低保留重要性
    private static final int DAYS_BEFORE_ARCHIVE = 90;
    private static final int MIN_IMPORTANCE_THRESHOLD = 6;  // 【新增】返回记忆的最低重要性阈值
    private static final int RECENT_DAYS_THRESHOLD = 30;    // 【新增】记忆有效天数阈值
    private static final int MAX_CONTEXT_MEMORIES = 3;      // 【新增】上下文最多返回的记忆条数

    /**
     * 记忆条目
     */
    public static class MemoryEntry {
        private String id;              // 记忆唯一ID
        private String category;        // 分类（technical/business/problem/solution）
        private String title;           // 标题/摘要
        private String content;         // 详细内容
        private List<String> keywords;  // 关键词列表
        private int importance;         // 重要性评分（1-10）
        private LocalDateTime createdAt; // 创建时间
        private LocalDateTime lastAccessedAt; // 最后访问时间
        private int accessCount;        // 访问次数

        public MemoryEntry() {}

        public MemoryEntry(String id, String category, String title, String content, List<String> keywords, int importance) {
            this.id = id;
            this.category = category;
            this.title = title;
            this.content = content;
            this.keywords = keywords;
            this.importance = importance;
            this.createdAt = LocalDateTime.now();
            this.lastAccessedAt = LocalDateTime.now();
            this.accessCount = 0;
        }

        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public List<String> getKeywords() { return keywords; }
        public void setKeywords(List<String> keywords) { this.keywords = keywords; }

        public int getImportance() { return importance; }
        public void setImportance(int importance) { this.importance = importance; }

        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

        public LocalDateTime getLastAccessedAt() { return lastAccessedAt; }
        public void setLastAccessedAt(LocalDateTime lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }

        public int getAccessCount() { return accessCount; }
        public void setAccessCount(int accessCount) { this.accessCount = accessCount; }

        public void incrementAccessCount() {
            this.accessCount++;
            this.lastAccessedAt = LocalDateTime.now();
        }
    }

    @PostConstruct
    public void init() {
        loadMemoriesFromFile();
    }

    /**
     * 添加新记忆
     */
    public String addMemory(String category, String title, String content, List<String> keywords, int importance) {
        String id = UUID.randomUUID().toString().substring(0, 8);

        MemoryEntry entry = new MemoryEntry(id, category, title, content, keywords, importance);
        memoryStore.put(id, entry);

        System.out.println("[记忆服务] 新增记忆: " + title + " (ID: " + id + ")");

        // 异步保存到文件
        saveMemoriesToFile();

        return id;
    }

    /**
     * 根据关键词检索记忆
     */
    public List<MemoryEntry> searchMemories(String query, String category, int limit) {
        System.out.println("[记忆服务] 检索记忆: " + query + (category != null ? " [分类: " + category + "]" : ""));

        List<MemoryEntry> results = memoryStore.values().stream()
            .filter(entry -> {
                // 分类过滤
                if (category != null && !entry.getCategory().equals(category)) {
                    return false;
                }

                // 关键词匹配
                boolean keywordMatch = entry.getKeywords().stream()
                    .anyMatch(kw -> query.toLowerCase().contains(kw.toLowerCase()) ||
                                   kw.toLowerCase().contains(query.toLowerCase()));

                // 标题或内容匹配
                boolean titleMatch = entry.getTitle().toLowerCase().contains(query.toLowerCase());
                boolean contentMatch = entry.getContent().toLowerCase().contains(query.toLowerCase());

                return keywordMatch || titleMatch || contentMatch;
            })
            // 按重要性和访问次数排序
            .sorted((a, b) -> {
                int scoreA = a.getImportance() * 10 + a.getAccessCount();
                int scoreB = b.getImportance() * 10 + b.getAccessCount();
                return Integer.compare(scoreB, scoreA);
            })
            .limit(limit)
            .collect(Collectors.toList());

        // 更新访问统计
        results.forEach(entry -> {
            entry.incrementAccessCount();
            System.out.println("  - 命中记忆: " + entry.getTitle() + " (重要性: " + entry.getImportance() + ", 访问: " + entry.getAccessCount() + "次)");
        });

        saveMemoriesToFile();

        return results;
    }


    /**
     * 获取相关记忆（用于RAG增强）- 带筛选
     */
    public String getRelevantMemories(String query) {
        List<MemoryEntry> memories = searchMemories(query, null, MAX_CONTEXT_MEMORIES * 2);

        if (memories.isEmpty()) {
            return "";
        }

        // 【新增】筛选高质量记忆
        List<MemoryEntry> filteredMemories = memories.stream()
            .filter(m -> m.getImportance() >= MIN_IMPORTANCE_THRESHOLD)  // 重要性 >= 6
            .filter(m -> {
                // 30天内访问过的，或者访问次数 >= 3 的
                if (m.getLastAccessedAt() == null) return true;
                long daysSinceAccess = java.time.Duration.between(
                    m.getLastAccessedAt(), LocalDateTime.now()).toDays();
                return daysSinceAccess <= RECENT_DAYS_THRESHOLD || m.getAccessCount() >= 3;
            })
            .limit(MAX_CONTEXT_MEMORIES)  // 最多返回3条
            .collect(Collectors.toList());

        if (filteredMemories.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 相关历史记忆\n\n");

        for (int i = 0; i < filteredMemories.size(); i++) {
            MemoryEntry m = filteredMemories.get(i);
            sb.append("### ").append(i + 1).append(". ").append(m.getTitle()).append("\n");
            sb.append("- **分类**: ").append(m.getCategory()).append("\n");
            sb.append("- **重要性**: ").append(m.getImportance()).append("/10\n");
            sb.append("- **内容**: ").append(m.getContent()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 蒸馏记忆（从对话中提取关键信息）
     */
    public String distillMemory(String conversation, String summary) {
        System.out.println("[记忆服务] 开始数据蒸馏...");

        // 自动提取关键词
        List<String> keywords = extractKeywords(conversation + " " + summary);

        // 判断记忆类型
        String category = categorizeMemory(conversation);

        // 计算重要性评分
        int importance = calculateImportance(conversation, keywords);

        // 只保存高价值记忆（重要性 >= 6）
        if (importance < 6) {
            System.out.println("[记忆服务] 记忆重要性较低 (" + importance + ")，跳过保存");
            return "记忆已分析，但重要性较低，未保存";
        }

        // 生成标题
        String title = summary.length() > 50 ? summary.substring(0, 50) + "..." : summary;

        // 添加记忆
        String id = addMemory(category, title, summary, keywords, importance);

        return "✅ 记忆已蒸馏并保存 (ID: " + id + ", 重要性: " + importance + "/10)";
    }

    /**
     * 自动提取关键词
     */
    private List<String> extractKeywords(String text) {
        Set<String> keywordSet = new HashSet<>();

        // 技术术语模式
        String[] techPatterns = {
            "Spring", "Bean", "Controller", "Service", "Repository",
            "SQL", "JOIN", "INDEX", "API", "REST", "JPA",
            "JUnit", "Mock", "Test", "Exception", "Error"
        };

        String lowerText = text.toLowerCase();
        for (String pattern : techPatterns) {
            if (lowerText.contains(pattern.toLowerCase())) {
                keywordSet.add(pattern.toLowerCase());
            }
        }

        // 提取3个以上字符的单词
        String[] words = text.split("\\W+");
        for (String word : words) {
            if (word.length() >= 3 && Character.isLetter(word.charAt(0))) {
                keywordSet.add(word.toLowerCase());
            }
        }

        return new ArrayList<>(keywordSet).subList(0, Math.min(10, keywordSet.size()));
    }

    /**
     * 自动分类记忆
     */
    private String categorizeMemory(String conversation) {
        String lower = conversation.toLowerCase();

        if (lower.contains("error") || lower.contains("报错") || lower.contains("异常")) {
            return "problem";
        }
        if (lower.contains("sql") || lower.contains("数据库") || lower.contains("查询")) {
            return "database";
        }
        if (lower.contains("代码") || lower.contains("class") || lower.contains("方法")) {
            return "technical";
        }
        if (lower.contains("需求") || lower.contains("功能") || lower.contains("业务")) {
            return "business";
        }

        return "general";
    }

    /**
     * 计算重要性评分
     */
    private int calculateImportance(String conversation, List<String> keywords) {
        int score = 5; // 基础分

        // 关键词数量
        if (keywords.size() > 5) score += 2;
        else if (keywords.size() > 3) score += 1;

        // 包含代码或技术方案
        if (conversation.contains("") || conversation.contains("@Service") || conversation.contains("CREATE TABLE")) { score += 2; }
        // 包含错误排查
        if (conversation.toLowerCase().contains("error") ||
                conversation.toLowerCase().contains("exception")) {
            score += 1;
        }

        return Math.min(10, score);
    }

    /**
     * 保存记忆到文件
     */
    private void saveMemoriesToFile() {
        try {
            File dir = new File(MEMORY_FILE_PATH).getParentFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }

            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(MEMORY_FILE_PATH), memoryStore);

            System.out.println("[记忆服务] 已保存 " + memoryStore.size() + " 条记忆到文件");
        } catch (IOException e) {
            System.err.println("[记忆服务] 保存记忆失败: " + e.getMessage());
        }
    }

    /**
     * 从文件加载记忆，JSON损坏时自动备份并重置
     */
    @SuppressWarnings("unchecked")
    private void loadMemoriesFromFile() {
        File file = new File(MEMORY_FILE_PATH);
        if (!file.exists()) {
            System.out.println("[记忆服务] 记忆文件不存在，初始化空记忆库");
            return;
        }

        try {
            Map<String, Map<String, Object>> loaded = objectMapper.readValue(file, Map.class);

            for (Map.Entry<String, Map<String, Object>> entry : loaded.entrySet()) {
                Map<String, Object> data = entry.getValue();

                MemoryEntry mem = new MemoryEntry();
                mem.setId((String) data.get("id"));
                mem.setCategory((String) data.get("category"));
                mem.setTitle((String) data.get("title"));
                mem.setContent((String) data.get("content"));
                mem.setKeywords((List<String>) data.get("keywords"));
                mem.setImportance((Integer) data.get("importance"));
                mem.setAccessCount((Integer) data.getOrDefault("accessCount", 0));

                // 【修复】安全解析日期时间，支持多种格式
                if (data.get("createdAt") != null) {
                    mem.setCreatedAt(parseDateTime((String) data.get("createdAt")));
                }
                if (data.get("lastAccessedAt") != null) {
                    mem.setLastAccessedAt(parseDateTime((String) data.get("lastAccessedAt")));
                }

                memoryStore.put(entry.getKey(), mem);
            }

            System.out.println("[记忆服务] 已加载 " + memoryStore.size() + " 条记忆");
        } catch (Exception e) {
            System.err.println("[记忆服务] 加载记忆失败: " + e.getMessage());
            // JSON损坏时备份并重置
            try {
                File backup = new File(MEMORY_FILE_PATH + ".broken." + System.currentTimeMillis());
                file.renameTo(backup);
                System.out.println("[记忆服务] 已备份损坏的记忆文件到: " + backup.getName());
            } catch (Exception ex) {
                System.err.println("[记忆服务] 备份损坏文件失败: " + ex.getMessage());
            }
            memoryStore.clear();
            saveMemoriesToFile();
            System.out.println("[记忆服务] 已重置为空记忆库");
        }
    }

    /**
     * 解析日期时间字符串，支持多种格式
     * @param dateTimeStr 日期时间字符串
     * @return LocalDateTime 对象
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            return LocalDateTime.now();
        }

        try {
            // 尝试解析 ISO 8601 格式（带 Z 或时区偏移）
            if (dateTimeStr.contains("Z") || dateTimeStr.contains("+") || dateTimeStr.contains("-0")) {
                // 移除毫秒部分（如果存在）
                String normalized = dateTimeStr.replace("Z", "+00:00");
                // 使用 OffsetDateTime 解析带时区的格式
                java.time.OffsetDateTime offsetDateTime = java.time.OffsetDateTime.parse(normalized);
                return offsetDateTime.toLocalDateTime();
            } else {
                // 标准 LocalDateTime 格式
                return LocalDateTime.parse(dateTimeStr);
            }
        } catch (Exception e) {
            System.err.println("[记忆服务] 日期解析失败: " + dateTimeStr + ", 错误: " + e.getMessage());
            // 解析失败时返回当前时间
            return LocalDateTime.now();
        }
    }

    /**
     * 获取记忆统计信息
     */
    public Map<String, Object> getMemoryStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalMemories", memoryStore.size());

        // 按分类统计
        Map<String, Long> byCategory = memoryStore.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::getCategory, Collectors.counting()));
        stats.put("byCategory", byCategory);

        // 平均重要性
        double avgImportance = memoryStore.values().stream()
                .mapToInt(MemoryEntry::getImportance)
                .average()
                .orElse(0);
        stats.put("avgImportance", Math.round(avgImportance * 10) / 10.0);

        // 总访问次数
        int totalAccesses = memoryStore.values().stream()
                .mapToInt(MemoryEntry::getAccessCount)
                .sum();
        stats.put("totalAccesses", totalAccesses);

        return stats;
    }
    /**
     * 【新增】智能清理低价值记忆
     * 定期执行，移除低质量或过时的记忆
     */
    public String cleanupLowValueMemories() {
        System.out.println("[记忆服务] 开始清理低价值记忆...");

        int beforeCount = memoryStore.size();

        List<String> toRemove = memoryStore.entrySet().stream()
                .filter(entry -> {
                    MemoryEntry mem = entry.getValue();

                    // 条件1：重要性很低且很少访问
                    boolean lowValue = mem.getImportance() < MIN_IMPORTANCE_TO_KEEP &&
                            mem.getAccessCount() < 2;

                    // 条件2：很久未访问（超过90天）
                    boolean oldAndUnused = false;
                    if (mem.getLastAccessedAt() != null) {
                        long daysSinceAccess = java.time.Duration.between(
                                mem.getLastAccessedAt(), LocalDateTime.now()).toDays();
                        oldAndUnused = daysSinceAccess > DAYS_BEFORE_ARCHIVE &&
                                mem.getAccessCount() < 3;
                    }

                    return lowValue || oldAndUnused;
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 移除低价值记忆
        toRemove.forEach(key -> {
            MemoryEntry removed = memoryStore.remove(key);
            System.out.println("  - 移除记忆: " + removed.getTitle() +
                    " (重要性: " + removed.getImportance() +
                    ", 访问: " + removed.getAccessCount() + "次)");
        });

        // 如果仍然超过限制，按重要性排序删除
        if (memoryStore.size() > MAX_ACTIVE_MEMORIES) {
            List<Map.Entry<String, MemoryEntry>> sorted = memoryStore.entrySet().stream()
                    .sorted((a, b) -> {
                        int scoreA = a.getValue().getImportance() * 10 + a.getValue().getAccessCount();
                        int scoreB = b.getValue().getImportance() * 10 + b.getValue().getAccessCount();
                        return Integer.compare(scoreA, scoreB); // 升序，先删低的
                    })
                    .collect(Collectors.toList());

            int toDelete = memoryStore.size() - MAX_ACTIVE_MEMORIES;
            for (int i = 0; i < toDelete; i++) {
                memoryStore.remove(sorted.get(i).getKey());
            }
        }

        int afterCount = memoryStore.size();
        int removedCount = beforeCount - afterCount;

        saveMemoriesToFile();

        String result = String.format("✅ 清理完成：删除 %d 条低价值记忆，剩余 %d 条", removedCount, afterCount);
        System.out.println(result);
        return result;
    }

    /**
     * 【新增】归档旧记忆到备份文件
     * 将不常用的记忆移动到归档文件，保持主记忆库精简
     */
    public String archiveOldMemories() {
        System.out.println("[记忆服务] 开始归档旧记忆...");

        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(DAYS_BEFORE_ARCHIVE);

        // 找出需要归档的记忆（90天未访问且访问次数少）
        List<MemoryEntry> toArchive = memoryStore.values().stream()
                .filter(mem -> {
                    if (mem.getLastAccessedAt() == null) return true;
                    return mem.getLastAccessedAt().isBefore(cutoffDate) &&
                            mem.getAccessCount() < 5;
                })
                .collect(Collectors.toList());

        if (toArchive.isEmpty()) {
            return "没有需要归档的记忆";
        }

        // 加载现有归档（如果有）
        Map<String, MemoryEntry> archivedMemories = loadArchivedMemories();

        // 添加到归档
        toArchive.forEach(mem -> {
            archivedMemories.put(mem.getId(), mem);
            memoryStore.remove(mem.getId()); // 从主库移除
        });

        // 保存归档
        try {
            File dir = new File(ARCHIVE_FILE_PATH).getParentFile();
            if (!dir.exists()) dir.mkdirs();

            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(new File(ARCHIVE_FILE_PATH), archivedMemories);

            saveMemoriesToFile();

            String result = String.format("✅ 已归档 %d 条记忆到 %s", toArchive.size(), ARCHIVE_FILE_PATH);
            System.out.println(result);
            return result;
        } catch (IOException e) {
            System.err.println("[记忆服务] 归档失败: " + e.getMessage());
            return "归档失败: " + e.getMessage();
        }
    }

    /**
     * 【新增】从归档文件中检索记忆
     * 当主记忆库没有找到时，可以搜索归档
     */
    public List<MemoryEntry> searchArchivedMemories(String query, int limit) {
        Map<String, MemoryEntry> archived = loadArchivedMemories();

        if (archived.isEmpty()) {
            return Collections.emptyList();
        }

        return archived.values().stream()
                .filter(entry -> {
                    boolean keywordMatch = entry.getKeywords().stream()
                            .anyMatch(kw -> query.toLowerCase().contains(kw.toLowerCase()));
                    boolean titleMatch = entry.getTitle().toLowerCase().contains(query.toLowerCase());
                    return keywordMatch || titleMatch;
                })
                .sorted((a, b) -> Integer.compare(b.getImportance(), a.getImportance()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 加载归档记忆
     */
    @SuppressWarnings("unchecked")
    private Map<String, MemoryEntry> loadArchivedMemories() {
        File file = new File(ARCHIVE_FILE_PATH);
        if (!file.exists()) {
            return new HashMap<>();
        }

        try {
            return objectMapper.readValue(file, Map.class);
        } catch (IOException e) {
            System.err.println("[记忆服务] 加载归档失败: " + e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 【新增】获取记忆库健康报告
     */
    public Map<String, Object> getMemoryHealthReport() {
        Map<String, Object> report = new HashMap<>();

        int totalMemories = memoryStore.size();
        report.put("totalMemories", totalMemories);

        // 活跃度统计
        long activeCount = memoryStore.values().stream()
                .filter(m -> m.getAccessCount() > 5)
                .count();
        report.put("activeMemories", activeCount);
        report.put("activeRate", Math.round((double) activeCount / totalMemories * 1000) / 10.0 + "%");

        // 平均重要性
        double avgImportance = memoryStore.values().stream()
                .mapToInt(MemoryEntry::getImportance)
                .average()
                .orElse(0);
        report.put("avgImportance", Math.round(avgImportance * 10) / 10.0);

        // 需要清理的低价值记忆数量
        long lowValueCount = memoryStore.values().stream()
                .filter(m -> m.getImportance() < MIN_IMPORTANCE_TO_KEEP && m.getAccessCount() < 2)
                .count();
        report.put("lowValueMemories", lowValueCount);

        // 建议操作
        List<String> suggestions = new ArrayList<>();
        if (totalMemories > MAX_ACTIVE_MEMORIES) {
            suggestions.add("记忆库超过 " + MAX_ACTIVE_MEMORIES + " 条，建议执行清理");
        }
        if (lowValueCount > 50) {
            suggestions.add("发现 " + lowValueCount + " 条低价值记忆，建议清理");
        }
        report.put("suggestions", suggestions);

        return report;
    }

    /**
     * 清空所有记忆
     */
    public void clearAllMemories() {
        memoryStore.clear();
        saveMemoriesToFile();
        System.out.println("[记忆服务] 已清空所有记忆");
    }
}