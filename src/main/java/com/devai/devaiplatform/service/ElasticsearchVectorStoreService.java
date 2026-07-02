package com.devai.devaiplatform.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Elasticsearch向量存储服务
 * 当vector.store.type=elasticsearch时启用，数据持久化到ES
 */
@Service
public class ElasticsearchVectorStoreService {

    @Value("${elasticsearch.url:http://127.0.0.1:9200}")
    private String esUrl;

    @Value("${elasticsearch.index-name:dev-ai-vectors}")
    private String indexName;

    @Value("${elasticsearch.username:}")
    private String username;

    @Value("${elasticsearch.password:}")
    private String password;

    private volatile EmbeddingStore<TextSegment> store;
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
