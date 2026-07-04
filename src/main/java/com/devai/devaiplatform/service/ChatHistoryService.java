package com.devai.devaiplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 聊天历史服务 - 持久化存储每次对话会话
 * 支持：保存/加载/列表/删除历史会话
 * 存储路径：./agent_memory/chat_history/
 */
@Service
public class ChatHistoryService {

    private static final String HISTORY_DIR = "./agent_memory/chat_history/";
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @PostConstruct
    public void init() {
        File dir = new File(HISTORY_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * 会话数据模型
     */
    public static class ChatSession {
        private String id;
        private String title;
        private List<Message> messages;
        private String createdAt;
        private String updatedAt;
        private int messageCount;

        public ChatSession() {
            this.messages = new ArrayList<>();
        }

        // Getters & Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public List<Message> getMessages() { return messages; }
        public void setMessages(List<Message> messages) {
            this.messages = messages;
            this.messageCount = messages != null ? messages.size() : 0;
        }

        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

        public int getMessageCount() { return messageCount; }
        public void setMessageCount(int messageCount) { this.messageCount = messageCount; }
    }

    /**
     * 单条消息
     */
    public static class Message {
        private String role;    // "user" 或 "ai"
        private String content; // 消息内容（可含HTML）
        private String time;    // 显示时间

        public Message() {}

        public Message(String role, String content, String time) {
            this.role = role;
            this.content = content;
            this.time = time;
        }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public String getTime() { return time; }
        public void setTime(String time) { this.time = time; }
    }

    /**
     * 保存/更新会话（新建或覆盖）
     */
    public ChatSession saveSession(ChatSession session) {
        if (session.getId() == null || session.getId().isEmpty()) {
            session.setId(UUID.randomUUID().toString().substring(0, 12));
        }
        if (session.getCreatedAt() == null) {
            session.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        session.setUpdatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        session.setMessageCount(session.getMessages() != null ? session.getMessages().size() : 0);

        // 自动生成标题（取第一条用户消息的前30个字符）
        if (session.getTitle() == null || session.getTitle().isEmpty()) {
            session.setTitle(generateTitle(session));
        }

        try {
            File file = getSessionFile(session.getId());
            objectMapper.writeValue(file, session);
            System.out.println("[历史会话] 已保存会话: " + session.getTitle() + " (ID: " + session.getId() + ")");
        } catch (IOException e) {
            System.err.println("[历史会话] 保存失败: " + e.getMessage());
        }

        return session;
    }

    /**
     * 获取单个会话
     */
    public ChatSession getSession(String sessionId) {
        File file = getSessionFile(sessionId);
        if (!file.exists()) {
            return null;
        }
        try {
            return objectMapper.readValue(file, ChatSession.class);
        } catch (IOException e) {
            System.err.println("[历史会话] 读取失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取所有会话列表（按更新时间倒序）
     */
    public List<ChatSession> listSessions() {
        File dir = new File(HISTORY_DIR);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));

        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        List<ChatSession> sessions = new ArrayList<>();
        for (File file : files) {
            try {
                ChatSession session = objectMapper.readValue(file, ChatSession.class);
                // 只返回摘要信息，不返回完整消息列表
                ChatSession summary = new ChatSession();
                summary.setId(session.getId());
                summary.setTitle(session.getTitle());
                summary.setCreatedAt(session.getCreatedAt());
                summary.setUpdatedAt(session.getUpdatedAt());
                summary.setMessageCount(session.getMessageCount());
                sessions.add(summary);
            } catch (IOException e) {
                System.err.println("[历史会话] 读取文件失败: " + file.getName() + " - " + e.getMessage());
            }
        }

        // 按更新时间倒序
        sessions.sort((a, b) -> {
            String ta = a.getUpdatedAt() != null ? a.getUpdatedAt() : "";
            String tb = b.getUpdatedAt() != null ? b.getUpdatedAt() : "";
            return tb.compareTo(ta);
        });

        return sessions;
    }

    /**
     * 删除会话
     */
    public boolean deleteSession(String sessionId) {
        File file = getSessionFile(sessionId);
        if (file.exists()) {
            boolean deleted = file.delete();
            System.out.println("[历史会话] 删除会话: " + sessionId + " - " + (deleted ? "成功" : "失败"));
            return deleted;
        }
        return false;
    }

    /**
     * 清空所有历史会话
     */
    public int clearAllSessions() {
        File dir = new File(HISTORY_DIR);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return 0;

        int count = 0;
        for (File file : files) {
            if (file.delete()) count++;
        }
        System.out.println("[历史会话] 已清空 " + count + " 个会话");
        return count;
    }

    /**
     * 自动生成会话标题
     */
    private String generateTitle(ChatSession session) {
        if (session.getMessages() == null || session.getMessages().isEmpty()) {
            return "新对话";
        }
        // 找第一条用户消息
        for (Message msg : session.getMessages()) {
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                String clean = msg.getContent().replaceAll("<[^>]*>", "").trim();
                if (clean.length() > 30) {
                    return clean.substring(0, 30) + "...";
                }
                return clean;
            }
        }
        return "新对话";
    }

    private File getSessionFile(String sessionId) {
        // 防止路径注入
        String safeId = sessionId.replaceAll("[^a-zA-Z0-9\\-]", "");
        return new File(HISTORY_DIR + safeId + ".json");
    }
}
