package com.devai.devaiplatform.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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

    // ========== Elasticsearch 向量存储配置 ==========
    @Value("${vector.store.type:memory}")
    private String vectorStoreType;

    @Value("${elasticsearch.url:http://127.0.0.1:9200}")
    private String esUrl;

    @Value("${elasticsearch.index-name:dev-ai-vectors}")
    private String esIndexName;

    @Value("${elasticsearch.username:}")
    private String esUsername;

    @Value("${elasticsearch.password:}")
    private String esPassword;

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
     * 【默认】内存向量存储 Bean
     * 当 vector.store.type 不为 elasticsearch 时使用
     */
    @Bean
    @Primary
    public InMemoryEmbeddingStore<TextSegment> embeddingStore() {
        System.out.println("[AI配置] 向量存储模式: 内存 (InMemoryEmbeddingStore)");
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * 【ES】Elasticsearch 向量存储 Bean
     * 仅在 vector.store.type=elasticsearch 时启用
     */
    @Bean
    @ConditionalOnProperty(name = "vector.store.type", havingValue = "elasticsearch")
    public EmbeddingStore<TextSegment> elasticsearchEmbeddingStore() {
        // 检查 ES 连接配置是否完整
        if (esUrl == null || esUrl.isBlank()) {
            System.err.println("[AI配置] ⚠️ ES向量存储启用但未配置 elasticsearch.url！");
        }
        if (esIndexName == null || esIndexName.isBlank()) {
            System.err.println("[AI配置] ⚠️ ES向量存储启用但未配置 elasticsearch.index-name！");
        }

        RestClient restClient = buildEsRestClient();
        System.out.println("[AI配置] 向量存储模式: Elasticsearch (url=" + esUrl + ", index=" + esIndexName + ")");
        System.out.println("[AI配置] ES索引将在首次写入时自动创建（含KNN向量映射）");

        return ElasticsearchEmbeddingStore.builder()
                .restClient(restClient)
                .indexName(esIndexName)
                .build();
    }

    /**
     * 构建 ES RestClient（含可选 Basic Auth）
     */
    private RestClient buildEsRestClient() {
        var builder = RestClient.builder(HttpHost.create(esUrl));
        if (esUsername != null && !esUsername.isBlank()
                && esPassword != null && !esPassword.isBlank()) {
            String auth = Base64.getEncoder()
                    .encodeToString((esUsername + ":" + esPassword).getBytes());
            List<Header> headers = new ArrayList<>();
            headers.add(new BasicHeader("Authorization", "Basic " + auth));
            builder.setDefaultHeaders(headers.toArray(new Header[0]));
            System.out.println("[AI配置] ES已启用Basic认证");
        }
        return builder.build();
    }
}
