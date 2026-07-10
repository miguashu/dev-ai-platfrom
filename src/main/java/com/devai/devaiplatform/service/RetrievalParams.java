package com.devai.devaiplatform.service;

/**
 * 混合检索动态参数配置
 * 根据查询复杂度自动调整检索策略，直接降低检索耗时
 *
 * 核心思路：简单查询少花时间，复杂查询多花资源
 * 【新增】支持叠加 RagTuningService 的自动调参偏移量
 */
public class RetrievalParams {

    /** 检索结果数量上限 (Top-K) */
    public int topK;

    /** 最低相关性分数阈值 (低于此值的结果直接丢弃) */
    public double minScore;

    /** BM25 关键词检索权重 (RRF融合时使用) */
    public double bm25Boost;

    /** KNN 向量检索权重 (RRF融合时使用) */
    public double knnBoost;

    /** HNSW ef_search 参数 (越大越精确但越慢，推荐 50~200) */
    public int efSearch;

    /** KNN 候选集大小 (通常为 topK 的倍数) */
    public int numCandidates;

    /** BM25 模糊匹配容错 (0=精确, 1=允许1字符差异, 2=允许2字符差异) */
    public String fuzziness;

    /** 查询复杂度等级: SIMPLE / MODERATE / COMPLEX */
    public String complexityLevel;

    /**
     * 根据查询文本动态分析并生成检索参数
     * 核心降耗时逻辑：简单查询用最小资源，复杂查询才投入更多
     */
    public static RetrievalParams analyze(String query) {
        return analyze(query, null);
    }

    /**
     * 带调参偏移量的分析
     * @param query 用户查询
     * @param offsets 调参偏移量（可为null）
     */
    public static RetrievalParams analyze(String query, RagTuningService.TuningOffsets offsets) {
        RetrievalParams params = new RetrievalParams();

        if (query == null || query.isBlank()) {
            params.setSimple();
            params.complexityLevel = "EMPTY";
            params.applyOffsets(offsets);
            return params;
        }

        // 查询复杂度评估：长度 + 句子数
        int queryLen = query.trim().length();
        int sentenceCount = countSentences(query);

        if (queryLen <= 20 && sentenceCount <= 1) {
            params.setSimple();
            params.complexityLevel = "SIMPLE";
        } else if (queryLen <= 80 && sentenceCount <= 3) {
            params.setModerate();
            params.complexityLevel = "MODERATE";
        } else {
            params.setComplex();
            params.complexityLevel = "COMPLEX";
        }

        // 【核心】叠加调参偏移量
        params.applyOffsets(offsets);

        return params;
    }

    /**
     * 应用调参偏移量到当前参数上
     * 安全边界检查确保参数不会超出合理范围
     */
    private void applyOffsets(RagTuningService.TuningOffsets offsets) {
        if (offsets == null || !offsets.hasAdjustment()) {
            return;
        }

        this.topK = clampInt(this.topK + offsets.topKDelta, 3, 30);
        this.minScore = clampDouble(this.minScore + offsets.minScoreDelta, 0.0, 0.8);
        this.bm25Boost = clampDouble(this.bm25Boost + offsets.bm25BoostDelta, 0.1, 3.0);
        this.knnBoost = clampDouble(this.knnBoost + offsets.knnBoostDelta, 0.1, 3.0);
    }

    /** 简单查询配置：最小资源，最快响应 */
    private void setSimple() {
        this.topK = 5;
        this.minScore = 0.25;
        this.bm25Boost = 1.0;
        this.knnBoost = 1.0;
        this.efSearch = 50;
        this.numCandidates = 20;
        this.fuzziness = "0";
    }

    /** 中等查询配置：平衡精度和速度 */
    private void setModerate() {
        this.topK = 8;
        this.minScore = 0.1;
        this.bm25Boost = 1.2;
        this.knnBoost = 1.0;
        this.efSearch = 100;
        this.numCandidates = 50;
        this.fuzziness = "1";
    }

    /** 复杂查询配置：最大召回，允许更多耗时 */
    private void setComplex() {
        this.topK = 12;
        this.minScore = 0.0;
        this.bm25Boost = 1.5;
        this.knnBoost = 1.0;
        this.efSearch = 200;
        this.numCandidates = 100;
        this.fuzziness = "1";
    }

    /**
     * 统计句子数（中英文混合）
     */
    private static int countSentences(String text) {
        int count = 0;
        for (char c : text.toCharArray()) {
            if (c == '。' || c == '.' || c == '？' || c == '?' || c == '！' || c == '!' || c == '\n') {
                count++;
            }
        }
        return Math.max(count, 1);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public String toString() {
        return String.format("RetrievalParams{level=%s, topK=%d, minScore=%.2f, bm25Boost=%.1f, knnBoost=%.1f, ef=%d, candidates=%d, fuzz=%s}",
                complexityLevel, topK, minScore, bm25Boost, knnBoost, efSearch, numCandidates, fuzziness);
    }
}
