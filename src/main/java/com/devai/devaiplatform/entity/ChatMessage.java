package com.devai.devaiplatform.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 聊天消息表 — 记录每条对话消息
 */
@Entity
@Table(name = "chat_message")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话ID */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    /** 消息角色: user / assistant */
    @Column(length = 16)
    private String role;

    /** 消息内容（完整文本） */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 消息序号（在会话中的顺序） */
    @Column(name = "seq_index")
    private Integer seqIndex;

    /** 创建时间 */
    @Column(name = "create_time")
    private LocalDateTime createTime;

    // ===== Getters & Setters =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getSeqIndex() { return seqIndex; }
    public void setSeqIndex(Integer seqIndex) { this.seqIndex = seqIndex; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
