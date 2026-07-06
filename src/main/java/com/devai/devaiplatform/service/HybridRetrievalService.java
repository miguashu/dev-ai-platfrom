package com.devai.devaiplatform.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索服务：BM25 + HNSW KNN 协同提速
 *
 * 核心优化：
 * 1. 真 BM25：通过 ES DSL match_query 走倒排索引，而非模拟
 * 2. HNSW 优化：KNN 查询利用 HNSW 索引加速，降低向量检索耗时
 * 3. 动态参数：根据查询复杂度自动调整 topK / minScore / ef_search
 * 4. RRF 融合：Reciprocal Rank Fusion 加权排序，兼顾语义和关键词
 */
@Service
public class HybridRetrievalService {

    @Autowired
    private VectorStoreService vectorStoreService;

    @Autowired(required = false)
    private ElasticsearchVectorStoreService esVectorStoreService;

    @Autowired
    private EmbeddingModel embeddingModel;

    /** RRF 常数 k（标准值 60，平衡排名权重） */
    private static final int RRF_K = 60;

    // ======================== 对外主入口 ========================

    /**
     * 混合检索主入口
     * 根据是否 ES 模式自动选择策略：
     * - ES 模式：BM25 + KNN + RRF 融合（完整多路检索）
     * - 内存模式：降级为纯向量检索
     *
     * @param userQuestion 用户查询文本
     * @return 融合排序后的检索结果
     */
    public List<Content> hybridSearch(String userQuestion) {
        long startTime = System.currentTimeMillis();

        try {
            if (!vectorStoreService.isElasticsearchMode()) {
                // 内存模式降级
                return fallbackVectorSearch(userQuestion, RetrievalParams.analyze(userQuestion));
            }

            // 1. 动态参数分析
            RetrievalParams params = RetrievalParams.analyze(userQuestion);
            System.out.println("[混合检索] 动态参数: " + params);

            // 2. 查询向量化
            Embedding queryEmbedding = embeddingModel.embed(userQuestion).content();

            // 3. 并行执行两路检索
            List<ScoredResult> bm25Results = searchBM25(userQuestion, params);
            List<ScoredResult> knnResults = searchKNN(queryEmbedding, params);

            System.out.println("[混合检索] BM25命中: " + bm25Results.size() + " 条, KNN命中: " + knnResults.size() + " 条");

            // 4. RRF 融合排序
            List<TextSegment> fused = fuseResultsRRF(bm25Results, knnResults, params);

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("[混合检索] ✅ 融合后 " + fused.size() + " 条, 耗时 " + elapsed + "ms");

            return fused.stream()
                    .map(Content::from)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            System.err.println("[混合检索] 异常，降级为纯向量检索: " + e.getMessage());
            return fallbackVectorSearch(userQuestion, RetrievalParams.analyze(userQuestion));
        }
    }

    // ======================== BM25 关键词检索 ========================

    /**
     * 真正的 BM25 检索（通过 ES DSL match_query 走倒排索引）
     * 这是核心优化点：替代原来假的 BM25（实际还是向量检索）
     */
    private List<ScoredResult> searchBM25(String queryText, RetrievalParams params) {
        try {
            RestClient client = esVectorStoreService.getRestClient();
            String indexName = esVectorStoreService.getIndexName();

            // 构建 BM25 DSL 查询
            JSONObject requestBody = new JSONObject();
            JSONObject queryObj = new JSONObject();
            JSONObject matchQuery = new JSONObject();
            JSONObject textField = new JSONObject();
            textField.put("query", queryText);
            textField.put("fuzziness", params.fuzziness);
            textField.put("minimum_should_match", "70%");
            matchQuery.put("text", textField);
            queryObj.put("match", matchQuery);
            requestBody.put("query", queryObj);
            requestBody.put("size", params.topK);

            Request request = new Request("POST", "/" + indexName + "/_search");
            request.setJsonEntity(requestBody.toJSONString());

            Response response = client.performRequest(request);
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

            return parseESSearchResponse(responseBody, "bm25", params.minScore);
        } catch (Exception e) {
            System.err.println("[BM25] 检索失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ======================== KNN 向量检索 ========================

    /**
     * KNN 向量检索（通过 ES DSL knn query 利用 HNSW 索引加速）
     * 直接控制 ef_search 参数，实现精度-速度动态平衡
     */
    private List<ScoredResult> searchKNN(Embedding queryEmbedding, RetrievalParams params) {
        try {
            RestClient client = esVectorStoreService.getRestClient();
            String indexName = esVectorStoreService.getIndexName();

            // 构建 KNN DSL 查询
            JSONObject requestBody = new JSONObject();
            JSONObject knnObj = new JSONObject();
            knnObj.put("field", "vector");
            knnObj.put("query_vector", queryEmbedding.vector());
            knnObj.put("k", params.topK);
            knnObj.put("num_candidates", params.numCandidates);
            requestBody.put("knn", knnObj);
            requestBody.put("size", params.topK);

            Request request = new Request("POST", "/" + indexName + "/_search");
            request.setJsonEntity(requestBody.toJSONString());

            Response response = client.performRequest(request);
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

            return parseESSearchResponse(responseBody, "knn", params.minScore);
        } catch (Exception e) {
            System.err.println("[KNN] 检索失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ======================== ES 响应解析 ========================

    /**
     * 解析 ES _search 响应，提取文本和元数据
     * 兼容 BM25 和 KNN 两种查询的响应格式
     */
    private List<ScoredResult> parseESSearchResponse(String responseBody, String source, double minScore) {
        List<ScoredResult> results = new ArrayList<>();

        try {
            JSONObject response = JSON.parseObject(responseBody);
            JSONObject hits = response.getJSONObject("hits");
            if (hits == null) return results;

            JSONArray hitsArray = hits.getJSONArray("hits");
            if (hitsArray == null) return results;

            for (int i = 0; i < hitsArray.size(); i++) {
                JSONObject hit = hitsArray.getJSONObject(i);
                double score = hit.getDoubleValue("_score");

                if (score < minScore) continue;

                JSONObject sourceObj = hit.getJSONObject("_source");
                if (sourceObj == null) continue;

                String text = sourceObj.getString("text");
                if (text == null || text.isBlank()) continue;

                // 解析元数据
                Map<String, Object> metadataMap = new HashMap<>();
                JSONObject metadataObj = sourceObj.getJSONObject("metadata");
                if (metadataObj != null) {
                    for (String key : metadataObj.keySet()) {
                        Object val = metadataObj.get(key);
                        if (val != null) {
                            metadataMap.put(key, val.toString());
                        }
                    }
                }
                metadataMap.put("retrieval_source", source);
                metadataMap.put("retrieval_score", String.valueOf(score));

                TextSegment segment = TextSegment.from(text, new Metadata(metadataMap));
                results.add(new ScoredResult(segment, score));
            }
        } catch (Exception e) {
            System.err.println("[解析] " + source + " 响应解析失败: " + e.getMessage());
        }

        return results;
    }

    // ======================== RRF 融合排序 ========================

    /**
     * Reciprocal Rank Fusion (RRF) 融合算法
     *
     * 公式: score(d) = Σ boost_i / (k + rank_i(d))
     * - k=60 (常数，平滑排名差异)
     * - rank 从 0 开始
     * - boost 为各路检索的权重系数
     *
     * 优势：不需要分数归一化，只依赖排名，对不同评分体系天然兼容
     */
    private List<TextSegment> fuseResultsRRF(List<ScoredResult> bm25Results,
                                              List<ScoredResult> knnResults,
                                              RetrievalParams params) {
        System.out.println("[RRF] 融合: BM25=" + bm25Results.size() + "条(boost=" + params.bm25Boost
                + "), KNN=" + knnResults.size() + "条(boost=" + params.knnBoost + "), k=" + RRF_K);

        // 用 LinkedHashMap 保持插入顺序，key=text（去重标识）
        Map<String, double[]> rrfScores = new LinkedHashMap<>();
        Map<String, TextSegment> segmentMap = new HashMap<>();

        // BM25 路贡献
        for (int i = 0; i < bm25Results.size(); i++) {
            String key = bm25Results.get(i).segment.text();
            double rrfScore = params.bm25Boost / (RRF_K + i);
            rrfScores.computeIfAbsent(key, k -> new double[1])[0] += rrfScore;
            segmentMap.putIfAbsent(key, bm25Results.get(i).segment);
        }

        // KNN 路贡献
        for (int i = 0; i < knnResults.size(); i++) {
            String key = knnResults.get(i).segment.text();
            double rrfScore = params.knnBoost / (RRF_K + i);
            rrfScores.computeIfAbsent(key, k -> new double[1])[0] += rrfScore;
            segmentMap.putIfAbsent(key, knnResults.get(i).segment);
        }

        // 按 RRF 分数降序排列，取 Top-K
        return rrfScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .limit(params.topK)
                .map(entry -> segmentMap.get(entry.getKey()))
                .collect(Collectors.toList());
    }

    // ======================== 降级方案 ========================

    /**
     * 内存模式降级：纯向量检索（无 BM25）
     */
    private List<Content> fallbackVectorSearch(String userQuestion, RetrievalParams params) {
        try {
            ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                    .embeddingStore(vectorStoreService.getEmbeddingStore())
                    .embeddingModel(embeddingModel)
                    .maxResults(params.topK)
                    .minScore(params.minScore)
                    .build();
            return retriever.retrieve(Query.from(userQuestion));
        } catch (Exception e) {
            System.err.println("[降级检索] 失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ======================== 索引管理 ========================

    /**
     * 初始化 HNSW 索引（应用启动或清空向量库后调用）
     * @param forceRecreate 是否强制重建（会清空数据！）
     */
    public void ensureHnswIndex(boolean forceRecreate) {
        if (vectorStoreService.isElasticsearchMode() && esVectorStoreService != null) {
            esVectorStoreService.ensureHnswIndex(forceRecreate);
        }
    }

    // ======================== 内部数据结构 ========================

    /**
     * 带分数的检索结果（内部使用）
     */
    private static class ScoredResult {
        final TextSegment segment;
        final double score;

        ScoredResult(TextSegment segment, double score) {
            this.segment = segment;
            this.score = score;
        }
    }
}
