package com.devai.devaiplatform.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Elasticsearch向量存储服务
 * 当vector.store.type=elasticsearch时启用，数据持久化到ES
 */
@Service
@ConditionalOnProperty(name = "vector.store.type", havingValue = "elasticsearch")
public class ElasticsearchVectorStoreService {

    @Value("${elasticsearch.url:http://127.0.0.1:9200}")
    private String esUrl;

    @Value("${elasticsearch.index-name:dev-ai-vectors}")
    private String indexName;

    @Value("${elasticsearch.username:}")
    private String username;

    @Value("${elasticsearch.password:}")
    private String password;

    @Value("${retrieval.hnsw.m:32}")
    private int hnswM;

    @Value("${retrieval.hnsw.ef-construction:200}")
    private int hnswEfConstruction;

    @Value("${retrieval.hnsw.dims:768}")
    private int hnswDims;

    private volatile EmbeddingStore<TextSegment> store;
    private volatile RestClient restClient;
    private final AtomicInteger segmentCount = new AtomicInteger(0);

    /**
     * 懒加载ES存储实例
     */
    public EmbeddingStore<TextSegment> getStore() {
        if (store == null) {
            synchronized (this) {
                if (store == null) {
                    store = buildStore();
                }
            }
        }
        return store;
    }

    private EmbeddingStore<TextSegment> buildStore() {
        RestClient restClient = buildRestClient();
        System.out.println("[ES向量库] 初始化连接: " + esUrl + ", 索引: " + indexName);
        System.out.println("[ES向量库] 索引将由首次 add() 自动创建（含KNN映射）");

        return ElasticsearchEmbeddingStore.builder()
                .restClient(restClient)
                .indexName(indexName)
                .build();
    }

    private RestClient buildRestClient() {
        var builder = RestClient.builder(HttpHost.create(esUrl));
        if (username != null && !username.isBlank() && password != null && !password.isBlank()) {
            List<Header> headers = new ArrayList<>();
            String auth = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
            headers.add(new BasicHeader("Authorization", "Basic " + auth));
            builder.setDefaultHeaders(headers.toArray(new Header[0]));
        }
        return builder.build();
    }

    /**
     * 获取底层 RestClient（供 HybridRetrievalService 发送自定义 DSL 查询）
     */
    public RestClient getRestClient() {
        if (restClient == null) {
            synchronized (this) {
                if (restClient == null) {
                    restClient = buildRestClient();
                }
            }
        }
        return restClient;
    }

    public String getIndexName() {
        return indexName;
    }

    public String getEsUrl() {
        return esUrl;
    }

    /**
     * 确保 ES 索引存在并配置 HNSW 优化映射
     * - 新索引：直接创建带 HNSW 参数的 mapping
     * - 已有索引：检测是否有 HNSW，没有则尝试重建
     *
     * @param forceRecreate 是否强制删除重建（会丢失数据！）
     * @return true=索引已就绪
     */
    public boolean ensureHnswIndex(boolean forceRecreate) {
        try {
            RestClient client = getRestClient();

            // 1. 检查索引是否存在
            Request headReq = new Request("HEAD", "/" + indexName);
            Response headResp = client.performRequest(headReq);
            boolean exists = (headResp.getStatusLine().getStatusCode() == 200);

            if (exists && !forceRecreate) {
                // 索引已存在，检查 HNSW 配置
                if (checkHnswEnabled(client)) {
                    System.out.println("[ES-HNSW] ✅ 索引已存在且 HNSW 已启用: " + indexName);
                    return true;
                } else {
                    System.out.println("[ES-HNSW] ⚠️ 索引已存在但未启用 HNSW，建议清空向量库重建以启用 HNSW 加速");
                    return true; // 仍可用，只是没有 HNSW 优化
                }
            }

            // 2. 需要创建新索引（或强制重建）
            if (exists && forceRecreate) {
                System.out.println("[ES-HNSW] 🔄 强制删除旧索引: " + indexName);
                Request delReq = new Request("DELETE", "/" + indexName);
                client.performRequest(delReq);
                Thread.sleep(500); // 等待 ES 清理
            }

            // 3. 创建带 HNSW 优化的索引 mapping
            String mapping = buildHnswMapping();
            Request createReq = new Request("PUT", "/" + indexName);
            createReq.setJsonEntity(mapping);
            Response createResp = client.performRequest(createReq);

            if (createResp.getStatusLine().getStatusCode() == 200) {
                System.out.println("[ES-HNSW] ✅ 索引创建成功 (m=" + hnswM + ", ef_construction=" + hnswEfConstruction + "): " + indexName);
                return true;
            } else {
                String body = EntityUtils.toString(createResp.getEntity(), StandardCharsets.UTF_8);
                System.err.println("[ES-HNSW] ❌ 索引创建失败: " + body);
                return false;
            }
        } catch (Exception e) {
            System.err.println("[ES-HNSW] 索引管理异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查索引是否已配置 HNSW
     */
    private boolean checkHnswEnabled(RestClient client) {
        try {
            Request req = new Request("GET", "/" + indexName + "/_mapping");
            Response resp = client.performRequest(req);
            String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            return body.contains("\"index\"") && body.contains("\"hnsw\"");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构建 HNSW 优化的 ES 索引 mapping
     * - embedding: dense_vector 字段，启用 HNSW 索引
     * - text: text 字段，用于 BM25 关键词检索
     * - metadata: object 字段，存储文档元数据
     */
    private String buildHnswMapping() {
        return """
            {
              "settings": {
                "index.number_of_shards": 1,
                "index.number_of_replicas": 0,
                "refresh_interval": "5s"
              },
              "mappings": {
                "properties": {
                  "vector": {
                    "type": "dense_vector",
                    "dims": %d,
                    "index": true,
                    "similarity": "cosine",
                    "index_options": {
                      "type": "hnsw",
                      "m": %d,
                      "ef_construction": %d
                    }
                  },
                  "text": {
                    "type": "text",
                    "analyzer": "standard"
                  },
                  "metadata": {
                    "type": "object"
                  }
                }
              }
            }
            """.formatted(hnswDims, hnswM, hnswEfConstruction);
    }

    public void clearStore() {
        System.out.println("[ES向量库] 清空索引: " + indexName);
        store = buildStore();
        segmentCount.set(0);
    }

    public void incrementCount() {
        segmentCount.incrementAndGet();
    }

    public void addCount(int n) {
        segmentCount.addAndGet(n);
    }

    public int getSegmentCount() {
        return segmentCount.get();
    }
}
