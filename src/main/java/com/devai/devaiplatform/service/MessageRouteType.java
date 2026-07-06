package com.devai.devaiplatform.service;

/**
 * 消息路由结果枚举 - 决定消息走哪条处理链路
 * 由 MessageRouterService 通过正则+关键词快速判定
 */
public enum MessageRouteType {

    /** 简单问题：直接LLM回答，不走向量库也不联网 */
    DIRECT_CHAT("直接对话", false, false),

    /** 复杂问题-知识库：走向量库检索（RAG） */
    RAG_RETRIEVAL("知识库检索", true, false),

    /** 复杂问题-联网：走网络搜索 */
    WEB_SEARCH("联网搜索", false, true),

    /** 复杂问题-混合：同时走向量库+联网搜索 */
    HYBRID("混合检索", true, true);

    private final String displayName;
    private final boolean enableRag;
    private final boolean enableWebSearch;

    MessageRouteType(String displayName, boolean enableRag, boolean enableWebSearch) {
        this.displayName = displayName;
        this.enableRag = enableRag;
        this.enableWebSearch = enableWebSearch;
    }

    public String getDisplayName() { return displayName; }
    public boolean isEnableRag() { return enableRag; }
    public boolean isEnableWebSearch() { return enableWebSearch; }
}
