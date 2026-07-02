package com.devai.devaiplatform.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 向量库管理服务
 * 支持内存模式和Elasticsearch持久化模式，通过 vector.store.type 配置切换
 */
@Service
public class VectorStoreService {

    @Value("${vector.store.type:memory}")
    private String storeType;

    private final EmbeddingModel embeddingModel;

    @Autowired(required = false)
    private ElasticsearchVectorStoreService esVectorStoreService;

    // 内存模式专用
    private volatile InMemoryEmbeddingStore<TextSegment> memoryStore;
    private final AtomicInteger memorySegmentCount = new AtomicInteger(0);

    public VectorStoreService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.memoryStore = new InMemoryEmbeddingStore<>();
    }

    /**
     * 获取当前激活的向量存储实例
     */
    public EmbeddingStore<TextSegment> getEmbeddingStore() {
        if (isElasticsearchMode()) {
            return esVectorStoreService.getStore();
        }
        return memoryStore;
    }

    /**
     * 获取内存存储（仅内存模式使用）
     */
    public InMemoryEmbeddingStore<TextSegment> getMemoryStore() {
        return memoryStore;
    }

    /**
     * 是否为Elasticsearch模式
     */
    public boolean isElasticsearchMode() {
        return "elasticsearch".equalsIgnoreCase(storeType) && esVectorStoreService != null;
    }

    /**
     * 清空向量库
     */
    public void clearVectorStore() {
        System.out.println("\n========== 开始清空向量库 ==========");
        if (isElasticsearchMode()) {
            esVectorStoreService.clearStore();
        } else {
            this.memoryStore = new InMemoryEmbeddingStore<>();
            this.memorySegmentCount.set(0);
        }
        System.out.println("✅ 向量库已清空！(模式: " + storeType + ")");
        System.out.println("========== 清空完成 ==========\n");
    }

    /**
     * 获取向量库统计信息
     */
    public VectorStoreStats getStats() {
        int count = isElasticsearchMode() ? esVectorStoreService.getSegmentCount() : memorySegmentCount.get();
        return new VectorStoreStats(count, "active", true, storeType);
    }

    public void incrementSegmentCount() {
        if (isElasticsearchMode()) {
            esVectorStoreService.incrementCount();
        } else {
            memorySegmentCount.incrementAndGet();
        }
    }

    public void addSegmentCount(int count) {
        if (isElasticsearchMode()) {
            esVectorStoreService.addCount(count);
        } else {
            memorySegmentCount.addAndGet(count);
        }
    }

    /**
     * 创建内容检索器
     */
    public ContentRetriever createContentRetriever(int maxResults, double minScore) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(getEmbeddingStore())
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
    }

    /**
     * 向量库统计信息 DTO
     */
    public static class VectorStoreStats {
        private final int totalSegments;
        private final String status;
        private final boolean initialized;
        private final String storeType;

        public VectorStoreStats(int totalSegments, String status, boolean initialized, String storeType) {
            this.totalSegments = totalSegments;
            this.status = status;
            this.initialized = initialized;
            this.storeType = storeType;
        }

        public int getTotalSegments() { return totalSegments; }
        public String getStatus() { return status; }
        public boolean isInitialized() { return initialized; }
        public String getStoreType() { return storeType; }
    }
}
