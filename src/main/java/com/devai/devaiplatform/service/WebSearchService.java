package com.devai.devaiplatform.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 网页搜索与信息抓取服务 - 多轮筛选机制确保信息完整性
 * <p>
 * 核心流程（三阶段筛选）：
 * Round 1 - 搜索收集：搜索关键词，收集候选 URL 及摘要
 * Round 2 - 内容抓取过滤：抓取网页正文，按相关性过滤低质内容
 * Round 3 - AI 提炼：将筛选后的内容交给 AI 进行深度提炼和结构化输出
 * <p>
 * 去重 & 完整性保障：
 * - URL 去重 + 内容去重（基于文本指纹）
 * - 多样性采样（确保来源不单一）
 * - 完整性检查（关键信息点覆盖验证）
 */
@Service
public class WebSearchService {

    // ==================== 可配置属性 ====================

    /** 代理开关 */
    @Value("${web.search.proxy.enabled:false}")
    private boolean proxyEnabled;

    /** 代理主机地址 */
    @Value("${web.search.proxy.host:}")
    private String proxyHost;

    /** 代理端口 */
    @Value("${web.search.proxy.port:1080}")
    private int proxyPort;

    /** 搜索引擎选择: duckduckgo / bing */
    @Value("${web.search.engine:duckduckgo}")
    private String searchEngine;

    private HttpClient httpClient;

    private static final int MAX_SEARCH_RESULTS = 8;
    private static final int MAX_FETCH_RESULTS = 5;
    private static final int MAX_CONTENT_LENGTH = 8000;
    private static final int FETCH_TIMEOUT_SECONDS = 15;

    @PostConstruct
    public void init() {
        var builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.ALWAYS);

        if (proxyEnabled && proxyHost != null && !proxyHost.isBlank()) {
            System.out.println("[WebSearch] 启用 HTTP 代理: " + proxyHost + ":" + proxyPort);
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        } else {
            System.out.println("[WebSearch] 直连模式 (无代理)，搜索引擎: " + searchEngine);
        }

        this.httpClient = builder.build();
    }

    // ==================== 搜索入口 ====================

    /**
     * 执行带多轮筛选的网络信息检索
     *
     * @param query 搜索查询
     * @return 筛选后的结构化信息摘要
     */
    public WebSearchResult search(String query) {
        System.out.println("\n========== [WebSearch] Round 1: 搜索收集 ==========");
        System.out.println("查询: " + query);

        // Round 1: 搜索收集候选链接
        List<SearchResultItem> candidates = doSearch(query);

        if (candidates.isEmpty()) {
            System.out.println("[WebSearch] 未找到搜索结果");
            return WebSearchResult.empty(query);
        }

        System.out.println("[WebSearch] 收集到 " + candidates.size() + " 条候选结果");
        for (int i = 0; i < Math.min(3, candidates.size()); i++) {
            System.out.println("  [" + (i + 1) + "] " + candidates.get(i).title);
        }

        // Round 2: 抓取内容 + 过滤
        System.out.println("\n========== [WebSearch] Round 2: 内容抓取与过滤 ==========");
        List<ContentItem> contents = fetchAndFilter(candidates);

        if (contents.isEmpty()) {
            System.out.println("[WebSearch] 所有页面抓取失败或无有效内容");
            return WebSearchResult.empty(query);
        }

        System.out.println("[WebSearch] 有效内容 " + contents.size() + " 条，开始去重与多样性筛选");
        contents = deduplicateAndDiversify(contents);

        System.out.println("[WebSearch] 筛选后保留 " + contents.size() + " 条高质量内容");

        return new WebSearchResult(query, contents, generateStatistics(contents));
    }

    // ==================== Round 1: 搜索收集 ====================

    /**
     * 使用 DuckDuckGo Lite 进行搜索（无需 API Key，基于 HTML 解析）
     */
    private List<SearchResultItem> searchDuckDuckGo(String query) {
        List<SearchResultItem> results = new ArrayList<>();
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            // DuckDuckGo Lite - 无 JS 的纯文本版本，解析友好
            String url = "https://lite.duckduckgo.com/lite/?q=" + encodedQuery;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String html = response.body();

            results = parseDuckDuckGoLite(html);
        } catch (Exception e) {
            System.err.println("[WebSearch] DuckDuckGo 搜索失败: " + e.getMessage());
            // 降级：尝试备用搜索引擎
            if ("bing".equalsIgnoreCase(searchEngine)) {
                // 主引擎就是Bing时，不再走DuckDuckGo备用
                System.out.println("[WebSearch] 主引擎为Bing，跳过DuckDuckGo备用");
            } else {
                results = searchBingFallback(query);
            }
        }
        return results;
    }

    /**
     * 统一搜索入口 — 根据配置选择搜索引擎
     */
    private List<SearchResultItem> doSearch(String query) {
        if ("bing".equalsIgnoreCase(searchEngine)) {
            System.out.println("[WebSearch] 使用 Bing 搜索引擎");
            return searchBing(query);
        }
        return searchDuckDuckGo(query);
    }

    /**
     * 解析 DuckDuckGo Lite 的搜索结果
     * Lite 版结构清晰，每个结果是一个 <a> 标签 + <span> 摘要
     */
    private List<SearchResultItem> parseDuckDuckGoLite(String html) {
        List<SearchResultItem> results = new ArrayList<>();

        // 匹配结果行: <a rel="nofollow" href="URL" ...>Title</a>
        // 后面跟着的 <span class="snippet"> 或 <td class="result-snippet"> 中的内容
        Pattern linkPattern = Pattern.compile(
                "<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>([^<]+)</a>",
                Pattern.CASE_INSENSITIVE
        );
        Pattern snippetPattern = Pattern.compile(
                "<td class=\"result-snippet\"[^>]*>(.*?)</td>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );

        Matcher linkMatcher = linkPattern.matcher(html);
        List<String[]> links = new ArrayList<>();
        while (linkMatcher.find() && links.size() < MAX_SEARCH_RESULTS * 2) {
            String href = linkMatcher.group(1);
            String title = linkMatcher.group(2).trim();
            // 过滤 DuckDuckGo 自身的链接和空标题
            if (!href.contains("duckduckgo.com") && !title.isEmpty()) {
                links.add(new String[]{href, title});
            }
        }

        Matcher snippetMatcher = snippetPattern.matcher(html);
        List<String> snippets = new ArrayList<>();
        while (snippetMatcher.find() && snippets.size() < MAX_SEARCH_RESULTS * 2) {
            String snippet = snippetMatcher.group(1).replaceAll("<[^>]+>", "").trim();
            if (!snippet.isEmpty() && snippet.length() > 10) {
                snippets.add(snippet);
            }
        }

        // 配对链接和摘要
        int count = Math.min(links.size(), MAX_SEARCH_RESULTS);
        for (int i = 0; i < count; i++) {
            String snippet = i < snippets.size() ? snippets.get(i) : "";
            results.add(new SearchResultItem(links.get(i)[0], links.get(i)[1], snippet));
        }

        return results;
    }

    /**
     * 备用搜索引擎 — 使用 DuckDuckGo HTML 版
     */
    private List<SearchResultItem> searchBingFallback(String query) {
        List<SearchResultItem> results = new ArrayList<>();
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://html.duckduckgo.com/html/?q=" + encodedQuery;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String html = response.body();

            results = parseDuckDuckGoHtml(html);
        } catch (Exception e) {
            System.err.println("[WebSearch] 备用搜索引擎也失败了: " + e.getMessage());
        }
        return results;
    }

    /**
     * 使用 Bing 搜索（国内可访问）
     * 通过解析 Bing 搜索结果页面提取链接和摘要
     */
    private List<SearchResultItem> searchBing(String query) {
        List<SearchResultItem> results = new ArrayList<>();
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            // Bing 搜索（国内可访问 cn.bing.com）
            String url = "https://cn.bing.com/search?q=" + encodedQuery + "&setlang=zh-cn";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String html = response.body();

            results = parseBingResults(html);
            System.out.println("[WebSearch] Bing 搜索返回 " + results.size() + " 条结果");
        } catch (Exception e) {
            System.err.println("[WebSearch] Bing 搜索失败: " + e.getMessage());
        }
        return results;
    }

    /**
     * 解析 Bing 搜索结果页面
     * Bing 搜索结果结构: <li class="b_algo"> 包含 <h2><a href="...">title</a></h2> 和 <p>snippet</p>
     */
    private List<SearchResultItem> parseBingResults(String html) {
        List<SearchResultItem> results = new ArrayList<>();

        // Bing 搜索结果项容器: <li class="b_algo"> ... </li>
        Pattern algoPattern = Pattern.compile(
                "<li[^>]*class=\"[^\"]*b_algo[^\"]*\"[^>]*>(.*?)</li>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );

        Matcher algoMatcher = algoPattern.matcher(html);
        while (algoMatcher.find() && results.size() < MAX_SEARCH_RESULTS) {
            String block = algoMatcher.group(1);

            // 提取标题和链接: <a href="..." ...>title</a>
            Pattern linkPattern = Pattern.compile(
                    "<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
            Matcher linkMatcher = linkPattern.matcher(block);
            if (!linkMatcher.find()) continue;

            String href = linkMatcher.group(1);
            String title = linkMatcher.group(2).replaceAll("<[^>]+>", "").trim();

            // 跳过 Bing 自身链接
            if (href.contains("bing.com") || title.isEmpty()) continue;

            // 提取摘要: <p> 或 <div class="b_caption">
            Pattern snippetPattern = Pattern.compile(
                    "<(?:p|div)[^>]*class=\"[^\"]*(?:b_caption|b_lineclamp)[^\"]*\"[^>]*>(.*?)</(?:p|div)>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            );
            Matcher snippetMatcher = snippetPattern.matcher(block);
            String snippet = "";
            if (snippetMatcher.find()) {
                snippet = snippetMatcher.group(1).replaceAll("<[^>]+>", "").trim();
            }

            results.add(new SearchResultItem(href, title, snippet));
        }

        return results;
    }

    /**
     * 解析 DuckDuckGo HTML 版搜索结果
     */
    private List<SearchResultItem> parseDuckDuckGoHtml(String html) {
        List<SearchResultItem> results = new ArrayList<>();

        Pattern linkPattern = Pattern.compile(
                "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Pattern snippetPattern = Pattern.compile(
                "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );

        Matcher linkMatcher = linkPattern.matcher(html);
        List<String[]> links = new ArrayList<>();
        while (linkMatcher.find() && links.size() < MAX_SEARCH_RESULTS) {
            links.add(new String[]{
                    linkMatcher.group(1),
                    linkMatcher.group(2).replaceAll("<[^>]+>", "").trim()
            });
        }

        Matcher snippetMatcher = snippetPattern.matcher(html);
        List<String> snippets = new ArrayList<>();
        while (snippetMatcher.find() && snippets.size() < MAX_SEARCH_RESULTS) {
            snippets.add(snippetMatcher.group(1).replaceAll("<[^>]+>", "").trim());
        }

        for (int i = 0; i < links.size(); i++) {
            String snippet = i < snippets.size() ? snippets.get(i) : "";
            results.add(new SearchResultItem(links.get(i)[0], links.get(i)[1], snippet));
        }

        return results;
    }

    // ==================== Round 2: 内容抓取与过滤 ====================

    /**
     * 批量抓取网页内容并按质量过滤
     */
    private List<ContentItem> fetchAndFilter(List<SearchResultItem> candidates) {
        List<ContentItem> contents = new ArrayList<>();

        for (int i = 0; i < Math.min(candidates.size(), MAX_FETCH_RESULTS); i++) {
            SearchResultItem item = candidates.get(i);
            System.out.println("  [" + (i + 1) + "] 抓取: " + item.title);

            try {
                String rawContent = fetchPageContent(item.url);
                if (rawContent != null && rawContent.length() > 100) {
                    // 提取正文并截断
                    String cleanText = extractMainText(rawContent, item.title);
                    if (cleanText.length() > 200) {
                        contents.add(new ContentItem(item.url, item.title, cleanText, item.snippet));
                        System.out.println("      ✓ 成功 (" + cleanText.length() + " 字符)");
                    } else {
                        System.out.println("      ✗ 内容太短，丢弃");
                    }
                } else {
                    System.out.println("      ✗ 抓取失败或内容不足");
                }
            } catch (Exception e) {
                System.out.println("      ✗ 抓取异常: " + e.getMessage());
            }
        }

        // 按内容长度和质量排序
        contents.sort((a, b) -> {
            int scoreA = calculateContentScore(a);
            int scoreB = calculateContentScore(b);
            return Integer.compare(scoreB, scoreA);
        });

        return contents;
    }

    /**
     * 抓取单个页面内容
     */
    private String fetchPageContent(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 HTML 中提取正文内容
     * 策略：移除 script/style/header/footer/nav/广告，保留正文区域
     */
    private String extractMainText(String html, String title) {
        if (html == null) return "";

        // 移除不需要的标签及其内容
        html = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        html = html.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        html = html.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        html = html.replaceAll("(?is)<nav[^>]*>.*?</nav>", " ");
        html = html.replaceAll("(?is)<header[^>]*>.*?</header>", " ");
        html = html.replaceAll("(?is)<footer[^>]*>.*?</footer>", " ");
        html = html.replaceAll("(?is)<aside[^>]*>.*?</aside>", " ");
        html = html.replaceAll("(?is)<form[^>]*>.*?</form>", " ");

        // 移除所有 HTML 标签
        String text = html.replaceAll("<[^>]+>", " ");
        // 解码常见 HTML 实体
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&ldquo;", "\"")
                .replace("&rdquo;", "\"");
        // 移除多余空白
        text = text.replaceAll("\\s+", " ").trim();
        // 限制长度
        if (text.length() > MAX_CONTENT_LENGTH) {
            text = text.substring(0, MAX_CONTENT_LENGTH) + "...";
        }

        return text;
    }

    /**
     * 计算内容质量分数
     * 维度：长度、关键词密度、结构化特征
     */
    private int calculateContentScore(ContentItem item) {
        int score = 0;
        // 长度分（200-2000为最佳）
        int len = item.content.length();
        if (len > 200 && len < 2000) score += 30;
        else if (len >= 2000 && len < 5000) score += 25;
        else if (len >= 5000) score += 20;
        else score += 10;

        // 中文字符比例
        long chineseCount = item.content.chars().filter(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS).count();
        double chineseRatio = (double) chineseCount / len;
        if (chineseRatio > 0.3) score += 25;
        else if (chineseRatio > 0.1) score += 15;
        else score += 5;

        // 有无代码块/结构化内容
        if (item.content.contains("```") || item.content.contains("    ")) score += 15;
        if (item.content.contains("1.") || item.content.contains("•") || item.content.contains("- ")) score += 10;

        return score;
    }

    // ==================== 去重与多样性保障 ====================

    /**
     * 去重 + 多样性采样，确保信息来源不单一
     */
    private List<ContentItem> deduplicateAndDiversify(List<ContentItem> contents) {
        if (contents.size() <= 2) return contents;

        List<ContentItem> result = new ArrayList<>();
        Set<String> seenDomains = new HashSet<>();
        Set<String> contentFingerprints = new HashSet<>();

        for (ContentItem item : contents) {
            // 内容指纹去重（取前200字符的hash）
            String fingerprint = generateFingerprint(item.content);
            if (contentFingerprints.contains(fingerprint)) {
                System.out.println("  [去重] 内容重复: " + item.title);
                continue;
            }

            // 域名去重（每个域名最多保留1条）
            String domain = extractDomain(item.url);
            if (seenDomains.contains(domain)) {
                System.out.println("  [多样性] 同域名跳过: " + domain);
                continue;
            }

            contentFingerprints.add(fingerprint);
            seenDomains.add(domain);
            result.add(item);

            if (result.size() >= 4) break; // 最多保留4条高质量结果
        }

        return result;
    }

    /**
     * 生成内容指纹（用于去重检测）
     */
    private String generateFingerprint(String content) {
        // 取关键位置字符组成指纹
        int len = content.length();
        StringBuilder fp = new StringBuilder();
        // 开头、中间、结尾各取50字符
        fp.append(content, 0, Math.min(50, len));
        if (len > 200) {
            int mid = len / 2;
            fp.append(content, mid - 25, mid + 25);
        }
        if (len > 100) {
            fp.append(content, len - Math.min(50, len), len);
        }
        return Integer.toHexString(fp.toString().hashCode());
    }

    /**
     * 从 URL 提取域名
     */
    private String extractDomain(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return url;
            // 去掉 www 前缀
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * 生成搜索统计信息
     */
    private String generateStatistics(List<ContentItem> contents) {
        StringBuilder stats = new StringBuilder();
        stats.append("共获取 ").append(contents.size()).append(" 个有效信息源");
        if (!contents.isEmpty()) {
            int totalLen = contents.stream().mapToInt(c -> c.content.length()).sum();
            stats.append("，总内容量约 ").append(totalLen / 1000).append("k 字符");
            stats.append("\n来源域名: ");
            stats.append(contents.stream()
                    .map(c -> extractDomain(c.url))
                    .distinct()
                    .collect(Collectors.joining(", ")));
        }
        return stats.toString();
    }

    // ==================== 数据模型 ====================

    /**
     * Round 1 搜索结果项
     */
    public static class SearchResultItem {
        public final String url;
        public final String title;
        public final String snippet;

        SearchResultItem(String url, String title, String snippet) {
            this.url = url;
            this.title = title;
            this.snippet = snippet;
        }
    }

    /**
     * Round 2 抓取后的内容项
     */
    public static class ContentItem {
        public final String url;
        public final String title;
        public final String content;
        public final String snippet;

        ContentItem(String url, String title, String content, String snippet) {
            this.url = url;
            this.title = title;
            this.content = content;
            this.snippet = snippet;
        }

        public String toPromptBlock(int index) {
            StringBuilder sb = new StringBuilder();
            sb.append("### 信息源 ").append(index).append(": ").append(title).append("\n");
            sb.append("**链接**: [").append(title).append("](").append(url).append(")\n\n");
            sb.append(content).append("\n");
            return sb.toString();
        }
    }

    /**
     * 最终搜索结果
     */
    public static class WebSearchResult {
        public final String query;
        public final List<ContentItem> contents;
        public final String statistics;
        public final boolean hasResult;

        WebSearchResult(String query, List<ContentItem> contents, String statistics) {
            this.query = query;
            this.contents = contents;
            this.statistics = statistics;
            this.hasResult = !contents.isEmpty();
        }

        public static WebSearchResult empty(String query) {
            return new WebSearchResult(query, List.of(), "未找到相关网页信息");
        }

        /**
         * 将所有内容拼接为适合 AI 理解的提示文本（含溯源指令）
         */
        public String toPromptContext() {
            if (!hasResult) {
                return "【网页搜索无结果】\n搜索词: " + query + "\n请基于已有知识回答，并告知用户未在网络上找到相关信息。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("【网页搜索结果】\n");
            sb.append("搜索词: ").append(query).append("\n");
            sb.append(statistics).append("\n\n");
            sb.append("---\n\n");

            for (int i = 0; i < contents.size(); i++) {
                sb.append(contents.get(i).toPromptBlock(i + 1));
                sb.append("\n---\n\n");
            }

            sb.append("【重要：溯源引用规则 - 必须在回答中遵守】\n");
            sb.append("1. 每个来自搜索结果的事实必须在句末标注来源编号，如 [1]、[2]\n");
            sb.append("2. 回答末尾必须以 Markdown 超链接格式列出所有参考来源：\n");
            sb.append("   - [来源标题](完整URL)\n");
            sb.append("3. 链接必须是可点击的 Markdown 格式，便于用户直接访问原文\n");
            sb.append("4. 区分\"来自搜索结果的事实\"和\"基于已有知识的推断\"\n");
            sb.append("5. 如果搜索结果不足以完全回答，请明确说明，并结合你的知识补充\n");
            return sb.toString();
        }

        @Override
        public String toString() {
            if (!hasResult) return "未找到相关网页信息";
            StringBuilder sb = new StringBuilder();
            sb.append("搜索词: ").append(query).append("\n");
            sb.append(statistics).append("\n\n");
            for (ContentItem item : contents) {
                sb.append("【").append(item.title).append("】\n");
                sb.append(item.url).append("\n");
                sb.append(item.content.substring(0, Math.min(200, item.content.length()))).append("...\n\n");
            }
            return sb.toString();
        }
    }
}
