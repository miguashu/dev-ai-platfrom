package com.devai.devaiplatform.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 永久记忆表 — 蒸馏后的知识记忆
 */
@Entity
@Table(name = "persistent_memory", indexes = {
    @Index(name = "idx_memory_category", columnList = "category"),
    @Index(name = "idx_memory_importance", columnList = "importance")
})
public class PersistentMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 记忆标题/摘要 */
    @Column(length = 500)
    private String title;

    /** 记忆内容（完整文本） */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 分类: tech_solution / bug_fix / best_practice / general */
    @Column(length = 32)
    private String category;

    /** 重要性评分 0-100 */
    private Integer importance;

    /** 来源会话ID（可选） */
    @Column(name = "source_session_id", length = 64)
    private String sourceSessionId;

    /** 关联关键词（JSON数组字符串） */
    @Column(length = 1000)
    private String keywords;

    /** 引用次数 */
    @Column(name = "reference_count")
    private Integer referenceCount;

    /** 创建时间 */
    @Column(name = "create_time")
    private LocalDateTime createTime;

    /** 最后访问时间 */
    @Column(name = "last_access_time")
    private LocalDateTime lastAccessTime;

    // ===== Getters & Setters =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Integer getImportance() { return importance; }
    public void setImportance(Integer importance) { this.importance = importance; }

    public String getSourceSessionId() { return sourceSessionId; }
    public void setSourceSessionId(String sourceSessionId) { this.sourceSessionId = sourceSessionId; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public Integer getReferenceCount() { return referenceCount; }
    public void setReferenceCount(Integer referenceCount) { this.referenceCount = referenceCount; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getLastAccessTime() { return lastAccessTime; }
    public void setLastAccessTime(LocalDateTime lastAccessTime) { this.lastAccessTime = lastAccessTime; }
}
