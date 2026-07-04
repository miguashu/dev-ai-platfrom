package com.devai.devaiplatform.service;

/**
 * AI Assistant 接口，用于 LangChain4j AiServices 代理
 */
public interface DevAssistant {
    String chat(String userMessage);
}
