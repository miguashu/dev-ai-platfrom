package com.devai.devaiplatform.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 聊天会话表 — 记录每次对话的会话信息
 */
@Entity
@Table(name = "chat_session")
public class ChatSession {

    @Id
    @Column(length = 64)
    private String sessionId;

    /** 会话标题（自动截取首条消息前50字） */
    @Column(length = 100)
    private String title;

    /** 会话类型: chat / agent / smart_dispatch */
    @Column(length = 32)
    private String sessionType;

    /** 消息总数 */
    private Integer messageCount;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最后更新时间 */
    private LocalDateTime updateTime;

    // ===== Getters & Setters =====

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSessionType() { return sessionType; }
    public void setSessionType(String sessionType) { this.sessionType = sessionType; }

    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
