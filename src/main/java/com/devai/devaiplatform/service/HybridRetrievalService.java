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

    @Autowired
    private RagTuningService ragTuningService;  // 【新增】RAG自动调参服务

    @Autowired(required = false)
    private AiKeywordExtractorService aiKeywordExtractorService;  // 【新增】AI关键词提取服务

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
                System.out.println("[混合检索] 非ES模式，降级为纯向量检索");
                return fallbackVectorSearch(userQuestion, RetrievalParams.analyze(userQuestion, ragTuningService.getTuningOffsets()));
            }

            // 0. 索引健康检查（仅首次调用时打印，避免刷屏）
            checkIndexHealth();

            // 【新增】1. 尝试文件名精确匹配优先
            String fileNameKeyword = extractFileNameKeyword(userQuestion);
            List<Content> exactFileMatch = tryExactFileNameMatch(userQuestion, fileNameKeyword);
            if (exactFileMatch != null && !exactFileMatch.isEmpty()) {
                long elapsed = System.currentTimeMillis() - startTime;
                System.out.println("[混合检索] ✅ 文件名精确匹配命中 " + exactFileMatch.size() + " 条, 耗时 " + elapsed + "ms");
                return exactFileMatch;
            }

            // 2. 动态参数分析（叠加调参偏移量）
            RetrievalParams params = RetrievalParams.analyze(userQuestion, ragTuningService.getTuningOffsets());
            System.out.println("[混合检索] 动态参数: " + params);

            // 2. 查询向量化
            dev.langchain4j.model.output.Response<Embedding> embeddingResponse = embeddingModel.embed(userQuestion);
            Embedding queryEmbedding = embeddingResponse.content();
            System.out.println("[混合检索] 查询向量维度: " + queryEmbedding.vector().length);

            // 3. 执行两路检索
            List<ScoredResult> bm25Results = searchBM25(userQuestion, params);
            List<ScoredResult> knnResults = searchKNN(queryEmbedding, params);

            System.out.println("[混合检索] BM25命中: " + bm25Results.size() + " 条, KNN命中: " + knnResults.size() + " 条");

            // 4. RRF 融合排序
            List<TextSegment> fused = fuseResultsRRF(bm25Results, knnResults, params);

            // 【新增】5. 文件名相关性过滤：当提取到文件名关键词时，过滤掉文件名不相关的结果
            if (fileNameKeyword != null && !fileNameKeyword.isBlank() && !fused.isEmpty()) {
                int beforeSize = fused.size();
                fused = filterByFileNameRelevance(fused, fileNameKeyword);
                System.out.println("[混合检索] 文件名相关性过滤: " + beforeSize + " → " + fused.size() + " 条 (关键词='" + fileNameKeyword + "')");
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("[混合检索] ✅ 融合后 " + fused.size() + " 条, 耗时 " + elapsed + "ms");

            // 6. 如果混合检索返回0条，自动降级为纯向量检索兜底
            if (fused.isEmpty()) {
                System.out.println("[混合检索] ⚠️ BM25+KNN均无结果，降级为纯向量检索兜底...");
                return fallbackVectorSearch(userQuestion, params);
            }

            return fused.stream()
                    .map(Content::from)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            System.err.println("[混合检索] 异常，降级为纯向量检索: " + e.getMessage());
            e.printStackTrace();
            return fallbackVectorSearch(userQuestion, RetrievalParams.analyze(userQuestion, ragTuningService.getTuningOffsets()));
        }
    }

    // ======================== 索引健康检查 ========================

    /** 是否已打印过索引信息（避免每次查询都打印） */
    private volatile boolean indexHealthChecked = false;

    /**
     * 检查 ES 索引状态：文档数 + mapping 字段名
     * 仅在首次调用时执行，后续跳过
     */
    private void checkIndexHealth() {
        if (indexHealthChecked || esVectorStoreService == null) return;
        try {
            RestClient client = esVectorStoreService.getRestClient();
            String indexName = esVectorStoreService.getIndexName();

            // 1. 检查索引文档数
            Request countReq = new Request("GET", "/" + indexName + "/_count");
            Response countResp = client.performRequest(countReq);
            String countBody = EntityUtils.toString(countResp.getEntity(), StandardCharsets.UTF_8);
            JSONObject countJson = JSON.parseObject(countBody);
            int docCount = countJson.getIntValue("count", -1);
            System.out.println("[索引检查] 索引 '" + indexName + "' 文档数: " + docCount);

            if (docCount == 0) {
                System.err.println("[索引检查] ⚠️ 索引为空！请先上传文档到知识库");
            }

            // 2. 检查 mapping 中的字段名
            Request mappingReq = new Request("GET", "/" + indexName + "/_mapping");
            Response mappingResp = client.performRequest(mappingReq);
            String mappingBody = EntityUtils.toString(mappingResp.getEntity(), StandardCharsets.UTF_8);

            // 提取 properties 中的字段名
            JSONObject mappingJson = JSON.parseObject(mappingBody);
            JSONObject indexMapping = mappingJson.getJSONObject(indexName);
            if (indexMapping != null) {
                JSONObject mappings = indexMapping.getJSONObject("mappings");
                if (mappings != null) {
                    JSONObject properties = mappings.getJSONObject("properties");
                    if (properties != null) {
                        System.out.println("[索引检查] Mapping字段: " + properties.keySet());
                        // 检查是否有 text 字段
                        if (!properties.containsKey("text") && !properties.containsKey("content")) {
                            System.err.println("[索引检查] ⚠️ 索引中无 'text' 或 'content' 字段！BM25检索可能失败");
                            System.err.println("[索引检查] 建议: 清空向量库后重新启动，让系统创建带HNSW的索引");
                        }
                        // 检查 vector 字段是否有 HNSW
                        JSONObject vectorField = properties.getJSONObject("vector");
                        if (vectorField != null) {
                            JSONObject indexOptions = vectorField.getJSONObject("index_options");
                            if (indexOptions != null) {
                                System.out.println("[索引检查] HNSW配置: type=" + indexOptions.getString("type")
                                        + ", m=" + indexOptions.getIntValue("m")
                                        + ", ef_construction=" + indexOptions.getIntValue("ef_construction"));
                            } else {
                                System.err.println("[索引检查] ⚠️ vector字段未配置HNSW索引！KNN检索可能降级为暴力搜索");
                            }
                        }
                    }
                }
            }

            indexHealthChecked = true;
        } catch (Exception e) {
            System.err.println("[索引检查] 健康检查失败: " + e.getMessage());
        }
    }

    // ======================== 文件名精确匹配（优先） ========================

    /**
     * 【新增】尝试从查询中提取文件名，进行精确匹配
     * 当用户查询包含明确文件名（如"李腾涛简历"）时，优先返回该文件的片段
     * 避免语义检索导致的跨文件误召回
     *
     * @param userQuestion 用户查询
     * @return 精确匹配的片段列表，无匹配时返回 null
     */
    private List<Content> tryExactFileNameMatch(String userQuestion, String fileNameKeyword) {
        if (userQuestion == null || userQuestion.isBlank()) return null;
        if (fileNameKeyword == null || fileNameKeyword.isBlank()) return null;

        try {
            RestClient client = esVectorStoreService.getRestClient();
            String indexName = esVectorStoreService.getIndexName();

            // 提取可能的文件名关键词（已在外部提取）
            System.out.println("[文件名匹配] 提取关键词: " + fileNameKeyword);

            // 【优化】使用 wildcard query 进行前缀匹配，避免部分匹配导致的误召回
            // 例如：查询"李腾涛简历"只匹配以"李腾涛简历"开头的文件名，不匹配"李腾涛个人简历"
            JSONObject requestBody = new JSONObject();
            JSONObject queryObj = new JSONObject();
            JSONObject wildcardQuery = new JSONObject();
            wildcardQuery.put("value", fileNameKeyword + "*");  // 前缀匹配
            wildcardQuery.put("case_insensitive", true);         // 忽略大小写
            queryObj.put("metadata.file_name.keyword", wildcardQuery);
            requestBody.put("query", new JSONObject().fluentPut("wildcard", queryObj));
            requestBody.put("size", 10); // 最多返回10条片段

            Request request = new Request("POST", "/" + indexName + "/_search");
            request.setJsonEntity(requestBody.toJSONString());

            Response response = client.performRequest(request);
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

            // 解析结果
            JSONObject respJson = JSON.parseObject(responseBody);
            JSONObject hits = respJson.getJSONObject("hits");
            if (hits == null) return null;

            JSONArray hitsArray = hits.getJSONArray("hits");
            if (hitsArray == null || hitsArray.isEmpty()) {
                System.out.println("[文件名匹配] 未找到精确匹配: " + fileNameKeyword);
                return null;
            }

            System.out.println("[文件名匹配] ✅ 精确命中 " + hitsArray.size() + " 条片段");

            List<Content> results = new ArrayList<>();
            for (int i = 0; i < hitsArray.size(); i++) {
                JSONObject hit = hitsArray.getJSONObject(i);
                JSONObject sourceObj = hit.getJSONObject("_source");
                if (sourceObj == null) continue;

                String text = sourceObj.getString("text");
                if (text == null || text.isBlank()) {
                    text = sourceObj.getString("content");
                }
                if (text == null || text.isBlank()) continue;

                Map<String, Object> metadataMap = new HashMap<>();
                JSONObject metadataObj = sourceObj.getJSONObject("metadata");
                if (metadataObj != null) {
                    for (String key : metadataObj.keySet()) {
                        Object val = metadataObj.get(key);
                        if (val != null) metadataMap.put(key, val.toString());
                    }
                }
                metadataMap.put("retrieval_source", "exact_file_match");
                metadataMap.put("retrieval_score", "1.0");

                TextSegment segment = TextSegment.from(text, new Metadata(metadataMap));
                results.add(Content.from(segment));
            }

            return results.isEmpty() ? null : results;

        } catch (Exception e) {
            System.err.println("[文件名匹配] 异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从用户查询中提取可能的文件名关键词
     * 
     * 【优化策略】优先使用AI提取，AI失败时降级为规则匹配
     * 
     * 支持格式：
     * - "李腾涛简历" → "李腾涛简历"
     * - "查看李腾涛的简历" → "李腾涛简历"
     * - "李腾涛.pdf" → "李腾涛.pdf"
     */
    private String extractFileNameKeyword(String query) {
        // 【优先级1】尝试AI提取（更智能、更准确）
        if (aiKeywordExtractorService != null) {
            try {
                String aiKeyword = aiKeywordExtractorService.extractFileNameKeyword(query);
                if (aiKeyword != null && !aiKeyword.isBlank()) {
                    System.out.println("[文件名提取] ✅ 使用AI提取: '" + query + "' → '" + aiKeyword + "'");
                    return aiKeyword;
                }
            } catch (Exception e) {
                System.err.println("[文件名提取] AI提取异常，降级为规则匹配: " + e.getMessage());
            }
        }
        
        // 【优先级2】降级为规则匹配（兜底方案）
        System.out.println("[文件名提取] ⚠️ AI不可用或提取失败，使用规则匹配");
        return extractFileNameKeywordByRule(query);
    }

    /**
     * 【备用】基于规则的文件名关键词提取（AI失败时的兜底方案）
     */
    private String extractFileNameKeywordByRule(String query) {
        String q = query.trim();

        // 如果查询本身就像文件名（含.pdf/.docx等扩展名），直接返回
        if (q.matches(".*\\.(pdf|docx?|txt|md)$")) {
            return q;
        }

        // 去除常见前缀动词
        String[] prefixes = {"查看", "查找", "搜索", "找一下", "帮我找", "打开", "读取", "获取", "分析", "总结"};
        for (String prefix : prefixes) {
            if (q.startsWith(prefix)) {
                q = q.substring(prefix.length()).trim();
                break;
            }
        }

        // 去除常见后缀词
        String[] suffixes = {"的内容", "的信息", "资料", "文档", "文件", "相关内容", "相关信息"};
        for (String suffix : suffixes) {
            if (q.endsWith(suffix)) {
                q = q.substring(0, q.length() - suffix.length()).trim();
                break;
            }
        }

        // 去除"的"字和空格
        q = q.replace("的", "").replace(" ", "").trim();

        // 【关键优化】如果处理后仍然包含多个名词（用空格或标点分隔），说明不够精确
        // 例如："李腾涛 简历" 应该进一步处理
        if (q.contains(" ") || q.contains("\t")) {
            // 取最长的连续非空白字符串作为关键词
            String[] parts = q.split("\\s+");
            String longest = "";
            for (String part : parts) {
                if (part.length() > longest.length()) {
                    longest = part;
                }
            }
            q = longest;
        }

        // 【关键优化】检查是否包含明确的文件标识词
        // 如果包含"简历"、"报告"、"文档"等词，确保这些词在末尾
        String[] fileIndicators = {"简历", "报告", "文档", "论文", "文章", "介绍"};
        for (String indicator : fileIndicators) {
            if (q.contains(indicator) && !q.endsWith(indicator)) {
                // 提取从开头到指示词的部分
                int idx = q.indexOf(indicator);
                q = q.substring(0, idx + indicator.length());
                break;
            }
        }

        // 如果处理后长度合理（2-50字符），作为文件名关键词
        if (q.length() >= 2 && q.length() <= 50) {
            System.out.println("[文件名提取] 原始查询: '" + query + "' → 提取关键词: '" + q + "'");
            return q;
        }

        System.out.println("[文件名提取] 原始查询: '" + query + "' → 无法提取有效关键词");
        return null;
    }

    /**
     * 【新增】基于文件名相关性的结果过滤
     * 
     * 当用户查询包含明确的文件名关键词时，对 RRF 融合后的结果进行过滤：
     * - 文件名包含关键词的片段 → 保留
     * - 文件名不包含关键词的片段 → 过滤掉
     * 
     * 这样可以避免语义检索导致的跨文件误召回（如查"李腾涛简历"却返回"王明简历"的内容）
     * 
     * @param segments RRF融合后的检索结果
     * @param keyword 提取的文件名关键词
     * @return 过滤后的结果列表
     */
    private List<TextSegment> filterByFileNameRelevance(List<TextSegment> segments, String keyword) {
        if (segments == null || segments.isEmpty() || keyword == null || keyword.isBlank()) {
            return segments;
        }

        String lowerKeyword = keyword.toLowerCase();
        
        List<TextSegment> relevant = new ArrayList<>();
        List<TextSegment> irrelevant = new ArrayList<>();

        for (TextSegment segment : segments) {
            String fileName = segment.metadata().getString("file_name");
            if (fileName == null || fileName.isBlank()) {
                // 无文件名信息的结果，保守保留
                relevant.add(segment);
                continue;
            }

            String lowerFileName = fileName.toLowerCase();
            // 检查文件名是否包含关键词中的核心词
            if (lowerFileName.contains(lowerKeyword) || containsCoreKeyword(lowerFileName, lowerKeyword)) {
                relevant.add(segment);
            } else {
                irrelevant.add(segment);
            }
        }

        if (irrelevant.isEmpty()) {
            // 所有结果都相关，无需过滤
            return segments;
        }

        if (relevant.isEmpty()) {
            // 过滤后没有相关结果，保守返回原始结果（避免空结果）
            System.out.println("[相关性过滤] ⚠️ 过滤后无相关结果，保留原始 " + segments.size() + " 条");
            return segments;
        }

        // 打印被过滤掉的文件名
        Set<String> filteredFiles = new LinkedHashSet<>();
        for (TextSegment seg : irrelevant) {
            String fn = seg.metadata().getString("file_name");
            if (fn != null) filteredFiles.add(fn);
        }
        System.out.println("[相关性过滤] ✅ 保留 " + relevant.size() + " 条, 过滤掉 " + irrelevant.size() + " 条不相关文件: " + filteredFiles);

        return relevant;
    }

    /**
     * 检查文件名是否包含关键词的核心部分
     * 例如：keyword="李腾涛简历", fileName="李腾涛个人简历_20260710.pdf"
     * 拆分关键词为["李腾涛", "简历"]，只要文件名包含其中主要实体词即认为相关
     */
    private boolean containsCoreKeyword(String lowerFileName, String lowerKeyword) {
        // 尝试拆分关键词（按常见分隔符或长度>4时取前半部分作为人名/实体名）
        // 例如 "李腾涛简历" → 核心实体是 "李腾涛"
        String[] indicators = {"简历", "报告", "文档", "论文", "文章", "介绍", "说明", "手册", "方案"};
        for (String indicator : indicators) {
            int idx = lowerKeyword.indexOf(indicator);
            if (idx > 0) {
                // 取指示词前面的部分作为核心实体名
                String coreEntity = lowerKeyword.substring(0, idx).trim();
                if (!coreEntity.isEmpty() && lowerFileName.contains(coreEntity)) {
                    return true;
                }
            }
        }
        return false;
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
            // fuzziness 仅在 MODERATE/COMPLEX 查询时启用，SIMPLE 查询用精确匹配
            if (!"0".equals(params.fuzziness)) {
                textField.put("fuzziness", params.fuzziness);
            }
            // minimum_should_match 仅对长查询生效，避免短查询因阈值过高返回0条
            if ("COMPLEX".equals(params.complexityLevel)) {
                textField.put("minimum_should_match", "70%");
            } else if ("MODERATE".equals(params.complexityLevel)) {
                textField.put("minimum_should_match", "50%");
            }
            // SIMPLE 查询不设置 minimum_should_match，让 ES 使用默认策略
            matchQuery.put("text", textField);
            queryObj.put("match", matchQuery);
            requestBody.put("query", queryObj);
            requestBody.put("size", params.topK);

            String dslBody = requestBody.toJSONString();
            System.out.println("[BM25] DSL查询体: " + dslBody);

            Request request = new Request("POST", "/" + indexName + "/_search");
            request.setJsonEntity(dslBody);

            Response response = client.performRequest(request);
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            System.out.println("[BM25] ES原始响应(前500字符): " +
                    responseBody.substring(0, Math.min(500, responseBody.length())));

            List<ScoredResult> results = parseESSearchResponse(responseBody, "bm25", params.minScore);
            System.out.println("[BM25] 解析后有效结果: " + results.size() + " 条 (minScore=" + params.minScore + ")");
            return results;
        } catch (Exception e) {
            System.err.println("[BM25] 检索失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
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

            String dslBody = requestBody.toJSONString();
            // 向量太长，只打印前200字符
            System.out.println("[KNN] DSL查询体(前200字符): " +
                    dslBody.substring(0, Math.min(200, dslBody.length())) + "...");

            Request request = new Request("POST", "/" + indexName + "/_search");
            request.setJsonEntity(dslBody);

            Response response = client.performRequest(request);
            String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            System.out.println("[KNN] ES原始响应(前500字符): " +
                    responseBody.substring(0, Math.min(500, responseBody.length())));

            List<ScoredResult> results = parseESSearchResponse(responseBody, "knn", params.minScore);
            System.out.println("[KNN] 解析后有效结果: " + results.size() + " 条 (minScore=" + params.minScore + ")");
            return results;
        } catch (Exception e) {
            System.err.println("[KNN] 检索失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    // ======================== ES 响应解析 ========================

    /**
     * 解析 ES _search 响应，提取文本和元数据
     * 兼容 BM25 和 KNN 两种查询的响应格式
     *
     * 注意：langchain4j-elasticsearch 存储的文档字段可能为:
     * - text: 文本内容（也可能在 _source 的顶层或其他嵌套位置）
     * - metadata: 元数据对象
     * - vector: 向量字段
     */
    private List<ScoredResult> parseESSearchResponse(String responseBody, String source, double minScore) {
        List<ScoredResult> results = new ArrayList<>();

        try {
            JSONObject response = JSON.parseObject(responseBody);

            // 检查是否有 ES 错误
            JSONObject error = response.getJSONObject("error");
            if (error != null) {
                System.err.println("[解析] " + source + " ES返回错误: " + error.toJSONString().substring(0, Math.min(300, error.toJSONString().length())));
                return results;
            }

            JSONObject hits = response.getJSONObject("hits");
            if (hits == null) {
                System.err.println("[解析] " + source + " 响应中无hits字段");
                return results;
            }

            // 打印 hits 总数（用于诊断）
            JSONObject totalObj = hits.getJSONObject("total");
            if (totalObj != null) {
                int totalHits = totalObj.getIntValue("value", 0);
                System.out.println("[解析] " + source + " ES总命中数: " + totalHits);
            }

            JSONArray hitsArray = hits.getJSONArray("hits");
            if (hitsArray == null) return results;

            int skippedByScore = 0;
            int skippedByNoText = 0;

            for (int i = 0; i < hitsArray.size(); i++) {
                JSONObject hit = hitsArray.getJSONObject(i);
                double score = hit.getDoubleValue("_score");

                if (score < minScore) {
                    skippedByScore++;
                    continue;
                }

                JSONObject sourceObj = hit.getJSONObject("_source");
                if (sourceObj == null) continue;

                // 尝试多种字段名获取文本（兼容不同版本的 langchain4j 映射）
                String text = sourceObj.getString("text");
                if (text == null || text.isBlank()) {
                    // langchain4j 某些版本可能用 "content" 字段
                    text = sourceObj.getString("content");
                }
                if (text == null || text.isBlank()) {
                    skippedByNoText++;
                    // 打印第一个无文本命中的 _source 结构用于诊断
                    if (skippedByNoText == 1) {
                        System.out.println("[解析] " + source + " 警告: 命中结果无text字段, _source keys=" + sourceObj.keySet());
                    }
                    continue;
                }

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

            if (skippedByScore > 0 || skippedByNoText > 0) {
                System.out.println("[解析] " + source + " 过滤统计: 分数过低跳过=" + skippedByScore
                        + ", 无文本跳过=" + skippedByNoText + ", 有效=" + results.size());
            }

        } catch (Exception e) {
            System.err.println("[解析] " + source + " 响应解析失败: " + e.getMessage());
            e.printStackTrace();
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
            // 重建后重置健康检查标记，下次检索时重新检查
            indexHealthChecked = false;
        }
    }

    /**
     * 重置索引健康检查缓存（清空向量库后调用）
     */
    public void resetHealthCheck() {
        indexHealthChecked = false;
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
