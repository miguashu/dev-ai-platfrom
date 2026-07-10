package com.devai.devaiplatform.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 向量库管理服务
 * 支持内存模式和Elasticsearch持久化模式，通过 vector.store.type 配置切换
 */
@Service
public class VectorStoreService {

    @Value("${vector.store.type:memory}")
    private String storeType;

    @Value("${vector.store.persist-path:./agent_memory/vector_store.json}")
    private String persistPath;

    private final EmbeddingModel embeddingModel;

    @Autowired(required = false)
    private ElasticsearchVectorStoreService esVectorStoreService;

    // 内存模式专用
    private volatile InMemoryEmbeddingStore<TextSegment> memoryStore;
    private final AtomicInteger memorySegmentCount = new AtomicInteger(0);

    // JSON持久化：启动时加载，入库时写入
    private final List<Map<String, Object>> persistBuffer = new CopyOnWriteArrayList<>();

    public VectorStoreService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.memoryStore = new InMemoryEmbeddingStore<>();
    }

    /**
     * 启动时从JSON文件恢复向量数据
     * - 内存模式：加载到 InMemoryEmbeddingStore
     * - ES模式：迁移历史数据到 Elasticsearch（避免重启后数据丢失）
     */
    @PostConstruct
    public void loadFromDisk() {
        // 【诊断】打印 ES 模式检测结果
        System.out.println("[向量服务] 启动诊断: storeType='" + storeType
                + "', esVectorStoreService=" + (esVectorStoreService != null ? "已注入" : "null(未注入)")
                + ", isElasticsearchMode=" + isElasticsearchMode());
        if ("elasticsearch".equalsIgnoreCase(storeType) && esVectorStoreService == null) {
            System.err.println("[向量服务] ⚠️ 配置了 vector.store.type=elasticsearch 但 ES 服务未注入！");
            System.err.println("[向量服务] 可能原因: ElasticsearchVectorStoreService 的 @ConditionalOnProperty 未匹配，或 ES 连接失败");
            System.err.println("[向量服务] 请检查: 1) ES是否运行在配置地址  2) vector.store.type 配置是否正确  3) 启动日志中是否有ES相关错误");
        }

        Path path = Paths.get(persistPath);
        if (!Files.exists(path)) {
            System.out.println("[向量持久化] 未找到持久化文件，从空库启动: " + persistPath);
            return;
        }
        try {
            String json = Files.readString(path);
            if (json == null || json.isBlank()) {
                return;
            }
            List<Map<String, Object>> entries = JSON.parseObject(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            if (entries == null || entries.isEmpty()) {
                return;
            }

            // ES模式：将历史数据迁移写入ES（避免重启后"内存有数据但ES为空"的问题）
            if (isElasticsearchMode()) {
                migrateToElasticsearch(entries);
                return;
            }

            // 内存模式：加载到内存
            int loaded = 0;
            for (Map<String, Object> entry : entries) {
                try {
                    String text = (String) entry.get("text");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metaMap = (Map<String, Object>) entry.get("metadata");
                    @SuppressWarnings("unchecked")
                    List<Double> embList = (List<Double>) entry.get("embedding");

                    if (text == null || embList == null) continue;

                    float[] embedding = new float[embList.size()];
                    for (int i = 0; i < embList.size(); i++) {
                        embedding[i] = embList.get(i).floatValue();
                    }

                    TextSegment segment = TextSegment.from(text,
                            new dev.langchain4j.data.document.Metadata(metaMap != null ? metaMap : new HashMap<>()));
                    dev.langchain4j.data.embedding.Embedding emb = dev.langchain4j.data.embedding.Embedding.from(embedding);
                    memoryStore.add(emb, segment);
                    memorySegmentCount.incrementAndGet();
                    loaded++;
                } catch (Exception e) {
                    System.err.println("[向量持久化] 跳过一条损坏记录: " + e.getMessage());
                }
            }
            System.out.println("[向量持久化] 从文件恢复 " + loaded + " 条向量到内存: " + persistPath);
        } catch (Exception e) {
            System.err.println("[向量持久化] 加载失败，从空库启动: " + e.getMessage());
        }
    }

    /**
     * 【ES迁移】将JSON文件中的历史向量数据迁移写入Elasticsearch
     * 解决从内存模式切换到ES模式后"旧数据丢失"的问题
     */
    private void migrateToElasticsearch(List<Map<String, Object>> entries) {
        if (esVectorStoreService == null) {
            System.err.println("[向量持久化] ES迁移失败：ES服务未就绪，数据保留在JSON文件中");
            System.out.println("[向量持久化] 提示：ES就绪后重新启动即可自动迁移");
            return;
        }

        EmbeddingStore<TextSegment> esStore = esVectorStoreService.getStore();
        int migrated = 0;

        System.out.println("[向量持久化] 开始迁移 " + entries.size() + " 条向量数据到 Elasticsearch...");

        for (Map<String, Object> entry : entries) {
            try {
                String text = (String) entry.get("text");
                @SuppressWarnings("unchecked")
                Map<String, Object> metaMap = (Map<String, Object>) entry.get("metadata");
                @SuppressWarnings("unchecked")
                List<Double> embList = (List<Double>) entry.get("embedding");

                if (text == null || embList == null) continue;

                float[] embedding = new float[embList.size()];
                for (int i = 0; i < embList.size(); i++) {
                    embedding[i] = embList.get(i).floatValue();
                }

                TextSegment segment = TextSegment.from(text,
                        new dev.langchain4j.data.document.Metadata(metaMap != null ? metaMap : new HashMap<>()));
                dev.langchain4j.data.embedding.Embedding emb = dev.langchain4j.data.embedding.Embedding.from(embedding);
                esStore.add(emb, segment);
                esVectorStoreService.incrementCount();
                migrated++;

                if (migrated % 10 == 0) {
                    System.out.println("[向量持久化] ES迁移进度: " + migrated + "/" + entries.size());
                }
            } catch (Exception e) {
                System.err.println("[向量持久化] ES迁移跳过一条损坏记录: " + e.getMessage());
            }
        }

        System.out.println("[向量持久化] ✅ ES迁移完成！共 " + migrated + " 条向量已写入ES索引");
        System.out.println("[向量持久化] JSON文件保留在: " + persistPath + "（可手动删除）");
    }

    /**
     * 持久化单条向量到JSON文件（追加模式）
     */
    public void persistSegment(TextSegment segment, float[] embedding) {
        if (!"memory".equalsIgnoreCase(storeType)) return;

        Map<String, Object> entry = new HashMap<>();
        entry.put("text", segment.text());
        entry.put("metadata", new HashMap<>(segment.metadata().toMap()));
        List<Double> embList = new ArrayList<>(embedding.length);
        for (float v : embedding) embList.add((double) v);
        entry.put("embedding", embList);
        persistBuffer.add(entry);
    }

    /**
     * 将缓冲区批量刷写到磁盘
     */
    public void flushToDisk() {
        if (!"memory".equalsIgnoreCase(storeType) || persistBuffer.isEmpty()) return;

        Path path = Paths.get(persistPath);
        try {
            // 确保父目录存在
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // 读取已有数据合并
            List<Map<String, Object>> allEntries = new ArrayList<>();
            if (Files.exists(path)) {
                String existing = Files.readString(path);
                if (existing != null && !existing.isBlank()) {
                    List<Map<String, Object>> existingEntries = JSON.parseObject(existing,
                            new TypeReference<List<Map<String, Object>>>() {});
                    if (existingEntries != null) {
                        allEntries.addAll(existingEntries);
                    }
                }
            }

            // 追加新数据
            List<Map<String, Object>> toWrite = new ArrayList<>(persistBuffer);
            allEntries.addAll(toWrite);
            persistBuffer.removeAll(toWrite);

            // 写入JSON
            String json = JSON.toJSONString(allEntries);
            Files.writeString(path, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[向量持久化] 写入失败: " + e.getMessage());
        }
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
        boolean typeMatch = "elasticsearch".equalsIgnoreCase(storeType);
        boolean serviceReady = esVectorStoreService != null;
        if (!typeMatch) {
            // 只在首次打印，避免刷屏
            if (!esModeDiagPrinted) {
                System.err.println("[向量服务] ⚠️ storeType='" + storeType + "' 不匹配 'elasticsearch'，降级为内存模式");
                System.err.println("[向量服务] 请检查 application.properties 中 vector.store.type=elasticsearch 是否正确配置");
                esModeDiagPrinted = true;
            }
        }
        return typeMatch && serviceReady;
    }

    private volatile boolean esModeDiagPrinted = false;

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
            // 清空持久化文件
            persistBuffer.clear();
            try {
                Path path = Paths.get(persistPath);
                Files.deleteIfExists(path);
                System.out.println("✅ 持久化文件已删除: " + persistPath);
            } catch (IOException e) {
                System.err.println("[向量持久化] 删除文件失败: " + e.getMessage());
            }
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
     * 按文件名删除向量库中该文件的所有片段
     */
    public int deleteByFileName(String fileName) {
        if (isElasticsearchMode()) {
            return esVectorStoreService.deleteByFileName(fileName);
        } else {
            // 内存模式：不支持按文件名删除（InMemoryEmbeddingStore无此能力）
            System.err.println("[向量服务] 内存模式不支持按文件名删除，请切换到ES模式");
            return 0;
        }
    }

    /**
     * 获取向量库中所有不重复的文件名列表及其片段数
     */
    public List<Map<String, Object>> listFilesWithCounts() {
        if (isElasticsearchMode()) {
            return esVectorStoreService.listFilesWithCounts();
        } else {
            // 内存模式：从持久化文件中提取
            List<Map<String, Object>> result = new ArrayList<>();
            Map<String, Integer> fileCounts = new java.util.LinkedHashMap<>();
            for (Map<String, Object> entry : persistBuffer) {
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = (Map<String, Object>) entry.get("metadata");
                if (meta != null && meta.containsKey("file_name")) {
                    String fn = (String) meta.get("file_name");
                    fileCounts.merge(fn, 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> e : fileCounts.entrySet()) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("fileName", e.getKey());
                item.put("segmentCount", e.getValue());
                result.add(item);
            }
            return result;
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
