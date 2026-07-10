package com.devai.devaiplatform.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * RAG检索反馈表 — 记录用户对RAG结果的评分和问题反馈
 * 用于AI自动优化检索策略，实现精细化召回
 */
@Entity
@Table(name = "rag_feedback", indexes = {
    @Index(name = "idx_feedback_query", columnList = "query_text(100)"),
    @Index(name = "idx_feedback_score", columnList = "score"),
    @Index(name = "idx_feedback_time", columnList = "create_time")
})
public class RagFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户原始查询 */
    @Column(name = "query_text", length = 1000)
    private String queryText;

    /** 用户评分: 1-5星 */
    @Column(nullable = false)
    private Integer score;

    /** 问题类型: irrelevant(不相关) / incomplete(不完整) / inaccurate(不准确) / other(其他) */
    @Column(name = "issue_type", length = 32)
    private String issueType;

    /** 用户指出的具体问题描述 */
    @Column(name = "issue_detail", length = 2000)
    private String issueDetail;

    /** 检索到的文件来源列表(JSON数组) */
    @Column(name = "source_files", length = 2000)
    private String sourceFiles;

    /** 检索片段数量 */
    @Column(name = "segment_count")
    private Integer segmentCount;

    /** AI根据反馈生成的优化建议 */
    @Column(name = "ai_suggestion", length = 2000)
    private String aiSuggestion;

    /** 优化是否已应用 */
    @Column(name = "optimization_applied")
    private Boolean optimizationApplied;

    /** 创建时间 */
    @Column(name = "create_time")
    private LocalDateTime createTime;

    // ===== Getters & Setters =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public String getIssueType() { return issueType; }
    public void setIssueType(String issueType) { this.issueType = issueType; }

    public String getIssueDetail() { return issueDetail; }
    public void setIssueDetail(String issueDetail) { this.issueDetail = issueDetail; }

    public String getSourceFiles() { return sourceFiles; }
    public void setSourceFiles(String sourceFiles) { this.sourceFiles = sourceFiles; }

    public Integer getSegmentCount() { return segmentCount; }
    public void setSegmentCount(Integer segmentCount) { this.segmentCount = segmentCount; }

    public String getAiSuggestion() { return aiSuggestion; }
    public void setAiSuggestion(String aiSuggestion) { this.aiSuggestion = aiSuggestion; }

    public Boolean getOptimizationApplied() { return optimizationApplied; }
    public void setOptimizationApplied(Boolean optimizationApplied) { this.optimizationApplied = optimizationApplied; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
