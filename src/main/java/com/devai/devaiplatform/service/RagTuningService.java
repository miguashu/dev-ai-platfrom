package com.devai.devaiplatform.service;

import com.devai.devaiplatform.entity.RagFeedback;
import com.devai.devaiplatform.repository.RagFeedbackRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * RAG 自动调参服务
 * 
 * 核心机制：
 * 1. 收集用户反馈（评分 + 问题类型）
 * 2. 统计分析近期低分反馈的模式
 * 3. 自动计算参数偏移量（topK/minScore/bm25Boost/knnBoost）
 * 4. 持久化到磁盘文件，混合检索时自动加载
 * 5. 高分反馈逐步回退偏移量，避免过度调整
 * 
 * 形成完整闭环：用户反馈 → AI分析 → 自动调参 → 下次检索生效 → 用户再反馈
 */
@Service
public class RagTuningService {

    @Autowired
    private RagFeedbackRepository ragFeedbackRepository;

    /** 调参配置持久化文件 */
    private static final String TUNING_FILE = "./agent_memory/rag_tuning.json";

    /** 当前生效的调参偏移量（内存缓存） */
    private volatile TuningOffsets currentOffsets = null;

    // ==================== 对外接口 ====================

    /**
     * 获取当前生效的调参偏移量
     * 供 RetrievalParams.analyze() 调用，叠加到默认参数上
     */
    public TuningOffsets getTuningOffsets() {
        if (currentOffsets == null) {
            currentOffsets = loadFromDisk();
        }
        return currentOffsets;
    }

    /**
     * 反馈提交后触发自动调参
     * 分析最近20条反馈，重新计算最优偏移量
     */
    public Map<String, Object> autoTuneAfterFeedback() {
        List<RagFeedback> recentFeedbacks = ragFeedbackRepository.findAllByOrderByCreateTimeDesc();
        
        // 至少需要3条反馈才开始调参
        if (recentFeedbacks.size() < 3) {
            System.out.println("[RAG调参] 反馈不足3条，暂不调参");
            return buildTuningResult(false, "反馈数据不足（需≥3条），保持默认参数");
        }

        // 取最近20条进行分析
        int analyzeCount = Math.min(20, recentFeedbacks.size());
        List<RagFeedback> sample = recentFeedbacks.subList(0, analyzeCount);

        TuningOffsets newOffsets = calculateOffsets(sample);
        
        // 保存并更新缓存
        saveToDisk(newOffsets);
        this.currentOffsets = newOffsets;

        // 标记已处理的反馈为"优化已应用"
        for (RagFeedback rf : sample) {
            if (rf.getOptimizationApplied() == null || !rf.getOptimizationApplied()) {
                rf.setOptimizationApplied(true);
                ragFeedbackRepository.save(rf);
            }
        }

        String summary = buildTuningSummary(newOffsets, sample);
        System.out.println("[RAG调参] ✅ 自动调参完成: " + summary);

        return buildTuningResult(true, summary);
    }

    /**
     * 获取当前调参状态（供前端展示）
     */
    public Map<String, Object> getTuningStatus() {
        TuningOffsets offsets = getTuningOffsets();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("active", offsets != null && offsets.hasAdjustment());
        status.put("topKDelta", offsets != null ? offsets.topKDelta : 0);
        status.put("minScoreDelta", offsets != null ? offsets.minScoreDelta : 0.0);
        status.put("bm25BoostDelta", offsets != null ? offsets.bm25BoostDelta : 0.0);
        status.put("knnBoostDelta", offsets != null ? offsets.knnBoostDelta : 0.0);
        status.put("lastUpdated", offsets != null ? offsets.lastUpdated : "从未调参");
        status.put("feedbackCount", offsets != null ? offsets.feedbackAnalyzed : 0);
        status.put("avgScore", offsets != null ? offsets.avgScore : 0.0);
        return status;
    }

    // ==================== 核心算法 ====================

    /**
     * 根据反馈样本计算参数偏移量
     * 
     * 策略：
     * - 统计各问题类型的占比和平均评分
     * - incomplete 多 → topK↑, minScore↓
     * - irrelevant 多 → minScore↑, knnBoost↑（更依赖语义精确度）
     * - inaccurate 多 → bm25Boost↑（更依赖关键词精确匹配）
     * - 平均分高 → 逐步回退偏移量（回归默认值）
     */
    private TuningOffsets calculateOffsets(List<RagFeedback> feedbacks) {
        TuningOffsets offsets = new TuningOffsets();

        // 统计
        int totalScore = 0;
        int irrelevantCount = 0;
        int incompleteCount = 0;
        int inaccurateCount = 0;
        int otherCount = 0;

        for (RagFeedback rf : feedbacks) {
            totalScore += (rf.getScore() != null ? rf.getScore() : 3);
            String type = rf.getIssueType();
            if ("irrelevant".equals(type)) irrelevantCount++;
            else if ("incomplete".equals(type)) incompleteCount++;
            else if ("inaccurate".equals(type)) inaccurateCount++;
            else otherCount++;
        }

        double avgScore = (double) totalScore / feedbacks.size();
        offsets.avgScore = Math.round(avgScore * 100.0) / 100.0;
        offsets.feedbackAnalyzed = feedbacks.size();

        // === 如果平均分 >= 4，说明效果好，逐步回退偏移量 ===
        if (avgScore >= 4.0) {
            offsets.topKDelta = 0;
            offsets.minScoreDelta = 0.0;
            offsets.bm25BoostDelta = 0.0;
            offsets.knnBoostDelta = 0.0;
            return offsets;
        }

        // === 根据问题类型分布计算偏移 ===
        int totalIssues = irrelevantCount + incompleteCount + inaccurateCount + otherCount;
        if (totalIssues == 0) {
            // 没有明确问题类型，按平均分微调
            if (avgScore < 3.0) {
                offsets.topKDelta = 3;      // 多召回一些
                offsets.minScoreDelta = -0.05; // 放宽阈值
            }
            return offsets;
        }

        double irrelevantRatio = (double) irrelevantCount / totalIssues;
        double incompleteRatio = (double) incompleteCount / totalIssues;
        double inaccurateRatio = (double) inaccurateCount / totalIssues;

        // --- topK 调整 ---
        // incomplete 多 → 增大 topK；irrelevant 多 → 减小 topK（减少噪声）
        if (incompleteRatio > 0.4) {
            offsets.topKDelta = 5;   // 大幅增大
        } else if (incompleteRatio > 0.2) {
            offsets.topKDelta = 3;   // 适度增大
        } else if (irrelevantRatio > 0.4) {
            offsets.topKDelta = -2;  // 减少噪声
        }

        // --- minScore 调整 ---
        // incomplete 多 → 降低阈值；irrelevant/inaccurate 多 → 提高阈值
        if (incompleteRatio > 0.3) {
            offsets.minScoreDelta = -0.1;
        } else if (irrelevantRatio > 0.3 || inaccurateRatio > 0.3) {
            offsets.minScoreDelta = 0.1;
        }

        // --- BM25 权重调整 ---
        // inaccurate 多 → 增强关键词匹配
        if (inaccurateRatio > 0.3) {
            offsets.bm25BoostDelta = 0.3;
        } else if (irrelevantRatio > 0.4) {
            offsets.bm25BoostDelta = 0.2; // 也适当增强关键词
        }

        // --- KNN 权重调整 ---
        // irrelevant 多 → 增强语义精确度
        if (irrelevantRatio > 0.3) {
            offsets.knnBoostDelta = 0.2;
        }

        // === 安全边界限制 ===
        offsets.topKDelta = clamp(offsets.topKDelta, -3, 10);
        offsets.minScoreDelta = clamp(offsets.minScoreDelta, -0.2, 0.3);
        offsets.bm25BoostDelta = clamp(offsets.bm25BoostDelta, -0.5, 1.0);
        offsets.knnBoostDelta = clamp(offsets.knnBoostDelta, -0.5, 1.0);

        return offsets;
    }

    // ==================== 持久化 ====================

    private void saveToDisk(TuningOffsets offsets) {
        try {
            Path dir = Paths.get("./agent_memory");
            if (!Files.exists(dir)) Files.createDirectories(dir);

            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"topKDelta\": ").append(offsets.topKDelta).append(",\n");
            json.append("  \"minScoreDelta\": ").append(offsets.minScoreDelta).append(",\n");
            json.append("  \"bm25BoostDelta\": ").append(offsets.bm25BoostDelta).append(",\n");
            json.append("  \"knnBoostDelta\": ").append(offsets.knnBoostDelta).append(",\n");
            json.append("  \"avgScore\": ").append(offsets.avgScore).append(",\n");
            json.append("  \"feedbackAnalyzed\": ").append(offsets.feedbackAnalyzed).append(",\n");
            json.append("  \"lastUpdated\": \"").append(LocalDateTime.now()).append("\"\n");
            json.append("}");

            Files.writeString(Paths.get(TUNING_FILE), json.toString());
            System.out.println("[RAG调参] 配置已保存到 " + TUNING_FILE);
        } catch (Exception e) {
            System.err.println("[RAG调参] 保存失败: " + e.getMessage());
        }
    }

    private TuningOffsets loadFromDisk() {
        try {
            Path path = Paths.get(TUNING_FILE);
            if (!Files.exists(path)) {
                return new TuningOffsets(); // 返回零偏移
            }
            String content = Files.readString(path);
            // 简单JSON解析
            TuningOffsets offsets = new TuningOffsets();
            offsets.topKDelta = extractInt(content, "topKDelta");
            offsets.minScoreDelta = extractDouble(content, "minScoreDelta");
            offsets.bm25BoostDelta = extractDouble(content, "bm25BoostDelta");
            offsets.knnBoostDelta = extractDouble(content, "knnBoostDelta");
            offsets.avgScore = extractDouble(content, "avgScore");
            offsets.feedbackAnalyzed = extractInt(content, "feedbackAnalyzed");
            offsets.lastUpdated = extractString(content, "lastUpdated");
            System.out.println("[RAG调参] 已从磁盘加载调参配置: topKΔ=" + offsets.topKDelta 
                    + ", minScoreΔ=" + offsets.minScoreDelta);
            return offsets;
        } catch (Exception e) {
            System.err.println("[RAG调参] 加载失败: " + e.getMessage());
            return new TuningOffsets();
        }
    }

    // ==================== 辅助方法 ====================

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int extractInt(String json, String key) {
        try {
            int start = json.indexOf("\"" + key + "\"") + key.length() + 3;
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            return Integer.parseInt(json.substring(start, end).trim());
        } catch (Exception e) { return 0; }
    }

    private double extractDouble(String json, String key) {
        try {
            int start = json.indexOf("\"" + key + "\"") + key.length() + 3;
            int end = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            return Double.parseDouble(json.substring(start, end).trim());
        } catch (Exception e) { return 0.0; }
    }

    private String extractString(String json, String key) {
        try {
            int start = json.indexOf("\"" + key + "\"") + key.length() + 4;
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        } catch (Exception e) { return ""; }
    }

    private String buildTuningSummary(TuningOffsets offsets, List<RagFeedback> sample) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("分析了%d条反馈(均分%.1f)。", offsets.feedbackAnalyzed, offsets.avgScore));
        if (!offsets.hasAdjustment()) {
            sb.append("效果良好，参数保持不变。");
        } else {
            sb.append("已自动调整: ");
            if (offsets.topKDelta != 0) sb.append(String.format("topK%+d ", offsets.topKDelta));
            if (offsets.minScoreDelta != 0) sb.append(String.format("minScore%+.2f ", offsets.minScoreDelta));
            if (offsets.bm25BoostDelta != 0) sb.append(String.format("bm25%+.1f ", offsets.bm25BoostDelta));
            if (offsets.knnBoostDelta != 0) sb.append(String.format("knn%+.1f ", offsets.knnBoostDelta));
            sb.append("。下次检索将自动生效。");
        }
        return sb.toString();
    }

    private Map<String, Object> buildTuningResult(boolean tuned, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tuned", tuned);
        result.put("message", message);
        result.put("currentParams", getTuningStatus());
        return result;
    }

    // ==================== 调参偏移量数据结构 ====================

    /**
     * 参数偏移量：叠加在 RetrievalParams 默认值之上
     */
    public static class TuningOffsets {
        public int topKDelta = 0;           // topK 偏移量
        public double minScoreDelta = 0.0;  // minScore 偏移量
        public double bm25BoostDelta = 0.0; // BM25权重偏移
        public double knnBoostDelta = 0.0;  // KNN权重偏移
        public double avgScore = 0.0;       // 分析时的平均评分
        public int feedbackAnalyzed = 0;    // 分析的反馈数量
        public String lastUpdated = "从未调参"; // 最后更新时间

        /** 是否有任何非零调整 */
        public boolean hasAdjustment() {
            return topKDelta != 0 || minScoreDelta != 0 
                    || bm25BoostDelta != 0 || knnBoostDelta != 0;
        }
    }
}
