package com.devai.devaiplatform.config;

import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class AiConfig {

    // ========== DeepSeek 云端大模型配置 ==========
    @Value("${deepseek.api-key}")
    private String deepseekApiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;

    @Value("${deepseek.model-name:deepseek-v4-pro}")
    private String deepseekModelName;

    @Value("${deepseek.max-tokens:8192}")
    private Integer maxTokens;

    @Value("${deepseek.timeout-seconds:300}")
    private Integer timeoutSeconds;

    // ========== 本地 Ollama Embedding 模型配置 ==========
    @Value("${ollama.base-url:http://127.0.0.1:11434}")
    private String ollamaUrl;

    @Value("${ollama.embedding-model.name:nomic-embed-text}")
    private String embedModelName;

    // ========== 本地 Ollama 聊天模型配置（降级备用） ==========
    @Value("${ollama.chat-model.enabled:true}")
    private boolean ollamaChatEnabled;

    @Value("${ollama.chat-model.name:qwen2.5:7b}")
    private String ollamaChatModelName;

    @Value("${ollama.chat-model.timeout-seconds:600}")
    private Integer ollamaChatTimeout;

    @Value("${ollama.chat-model.temperature:0.7}")
    private Double ollamaChatTemperature;

    @Value("${ollama.chat-model.max-tokens:8192}")
    private Integer ollamaChatMaxTokens;

    // ========== 降级策略配置 ==========
    @Value("${llm.fallback.enabled:true}")
    private boolean fallbackEnabled;

    @Value("${llm.fallback.health-check-interval-seconds:60}")
    private Long fallbackHealthCheckInterval;

    /**
     * 【云端】DeepSeek 大语言模型 Bean（主模型）
     */
    @Bean
    public ChatLanguageModel deepseekChatModel() {
        return OpenAiChatModel.builder()
                .apiKey(deepseekApiKey)
                .baseUrl(deepseekBaseUrl)
                .modelName(deepseekModelName)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .temperature(0.7)
                .maxTokens(maxTokens)
                .build();
    }

    /**
     * 【本地】Ollama 聊天模型 Bean（降级备用）
     */
    @Bean
    public ChatLanguageModel ollamaChatModel() {
        return OllamaChatModel.builder()
                .baseUrl(ollamaUrl)
                .modelName(ollamaChatModelName)
                .timeout(Duration.ofSeconds(ollamaChatTimeout))
                .temperature(ollamaChatTemperature)
                .build();
    }

    /**
     * 【核心】带自动降级的 ChatLanguageModel Bean
     * 优先使用云端 DeepSeek，无网络时自动切换到本地 Ollama
     * 云端恢复后自动切回
     */
    @Bean
    @Primary
    public ChatLanguageModel chatLanguageModel() {
        ChatLanguageModel primary = deepseekChatModel();

        if (!fallbackEnabled || !ollamaChatEnabled) {
            System.out.println("[AI配置] 降级模式已关闭，仅使用云端模型: " + deepseekModelName);
            return primary;
        }

        ChatLanguageModel fallback = ollamaChatModel();
        System.out.println("[AI配置] 启用自动降级: 云端=" + deepseekModelName + " -> 本地=" + ollamaChatModelName);
        System.out.println("[AI配置] 健康检查间隔: " + fallbackHealthCheckInterval + "秒");

        return new FallbackChatLanguageModel(
                primary,
                fallback,
                deepseekModelName,
                ollamaChatModelName,
                deepseekBaseUrl,  // 用API地址做HTTP健康探测
                fallbackHealthCheckInterval
        );
    }

    /**
     * 【本地】Ollama Embedding 模型 Bean
     * 用于文本向量化（RAG检索）
     * 注意：DeepSeek 不提供 Embedding 服务，继续使用本地模型
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl(ollamaUrl)
                .modelName(embedModelName)
                .timeout(Duration.ofSeconds(1800))
                .maxRetries(3)
                .build();
    }

    /**
     * 内存向量存储
     */
    @Bean
    public InMemoryEmbeddingStore embeddingStore() {
        return new InMemoryEmbeddingStore();
    }

    /**
     * 文档切片器
     */
    @Bean
    public dev.langchain4j.data.document.DocumentSplitter documentSplitter() {
        return DocumentSplitters.recursive(800, 150);
    }
}
