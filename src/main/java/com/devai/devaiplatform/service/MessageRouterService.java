package com.devai.devaiplatform.service;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 消息路由服务 - 通过正则+关键词快速判断消息应该走哪条链路
 * 
 * 路由策略：
 * 1. 简单问题（问候、闲聊、短问题）→ DIRECT_CHAT（直接LLM回答，不检索）
 * 2. 涉及项目内部知识/文档/代码 → RAG_RETRIEVAL（走向量库）
 * 3. 涉及最新技术/版本/外部信息 → WEB_SEARCH（联网搜索）
 * 4. 既涉及内部知识又涉及外部信息 → HYBRID（混合检索）
 */
@Service
public class MessageRouterService {

    // ==================== 简单问题正则（直接回答，不走检索） ====================

    /** 问候语：你好、hello、hi、早上好等 */
    private static final Pattern GREETING_PATTERN = Pattern.compile(
            "^(你好|您好|hello|hi|hey|早上好|下午好|晚上好|早安|晚安|嗨|哈喽|在吗|在不在|你是谁|你叫什么)[\\s!！。.？?]*$",
            Pattern.CASE_INSENSITIVE
    );

    /** 简单确认/否定：好的、是的、不是、谢谢等 */
    private static final Pattern SIMPLE_REPLY_PATTERN = Pattern.compile(
            "^(好的|嗯|是|是的|对|对的|不是|不对|谢谢|感谢|ok|okay|sure|yes|no|thanks|thank you|明白了|知道了|了解了|收到|明白|了解|可以|没问题|好吧|行|中)[\\s!！。.？?]*$",
            Pattern.CASE_INSENSITIVE
    );

    /** 极短问题（无实质内容的短文本，<=6个字符且不含技术关键词） */
    private static final Pattern ULTRA_SHORT_PATTERN = Pattern.compile(
            "^[\\u4e00-\\u9fa5a-zA-Z0-9\\s]{1,6}[?？。!！]*$"
    );

    /** 纯闲聊/情感类：开玩笑、无聊、开心等 */
    private static final Pattern CHITCHAT_PATTERN = Pattern.compile(
            "^(你会|你能|你是不是|你觉得|你开心|你无聊|开玩笑|哈哈哈|呵呵|嘿嘿|666|牛逼|厉害|厉害啊|不错|棒|加油|辛苦|拜拜|再见|bye)[\\s!！。.？?]*$",
            Pattern.CASE_INSENSITIVE
    );

    // ==================== 联网搜索关键词（需要最新信息） ====================

    /** 时效性关键词：暗示需要联网搜索最新信息 */
    private static final Pattern WEB_SEARCH_KEYWORD_PATTERN = Pattern.compile(
            "最新|最近|当前|今年|去年|昨天|今天|现在|版本|发布|更新|升级|新闻|动态|趋势|热点|" +
            "github|npm|maven|docker|kubernetes|k8s|spring boot \\d|java \\d+|jdk-?\\d+|" +
            "上网搜|帮我搜|查一下|搜一下|网上查|百度|google|" +
            "最新文档|官方文档|release|changelog|what's new|新特性",
            Pattern.CASE_INSENSITIVE
    );

    // ==================== 知识库检索关键词（需要项目内部知识） ====================

    /** 知识库关键词：暗示需要查询项目内部文档/代码/历史方案 */
    private static final Pattern RAG_KEYWORD_PATTERN = Pattern.compile(
            "项目|代码|文档|知识库|简历|备案|毕业|学历|" +
            "接口|api|controller|service|entity|repository|" +
            "数据库|表结构|sql|字段|索引|" +
            "配置|环境|部署|日志|报错|异常|堆栈|" +
            "需求|方案|设计|架构|模块|功能|" +
            "帮我找|查找|检索|查询|看看|分析一下|解释一下|什么意思|怎么实现|怎么写|" +
            "之前|上次|历史|以前|记得",
            Pattern.CASE_INSENSITIVE
    );

    /** 代码/技术深度关键词：暗示复杂技术问题 */
    private static final Pattern TECH_DEEP_PATTERN = Pattern.compile(
            "怎么实现|如何实现|原理|机制|底层|源码|源码分析|设计模式|最佳实践|" +
            "优化|性能|调优|排查|debug|分析|对比|区别|差异|" +
            "为什么|原因|怎么办|如何解决|解决方案",
            Pattern.CASE_INSENSITIVE
    );

    // ==================== 路由判定主入口 ====================

    /**
     * 分析用户消息，返回路由类型
     * 
     * @param userMessage 用户消息
     * @return 路由类型（DIRECT_CHAT / RAG_RETRIEVAL / WEB_SEARCH / HYBRID）
     */
    public MessageRouteType route(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return MessageRouteType.DIRECT_CHAT;
        }

        String trimmed = userMessage.trim();
        String lower = trimmed.toLowerCase();

        System.out.println("\n========== [消息路由] 分析开始 ==========");
        System.out.println("消息: " + truncate(trimmed, 100));

        // 第1层：简单问题快速拦截（正则精确匹配）
        if (isSimpleMessage(trimmed, lower)) {
            System.out.println("[消息路由] → DIRECT_CHAT（简单问题，直接回答）");
            System.out.println("========== [消息路由] 分析结束 ==========\n");
            return MessageRouteType.DIRECT_CHAT;
        }

        // 第2层：关键词分析 - 判断是否需要联网 / 是否需要知识库
        boolean needsWebSearch = needsWebSearch(lower);
        boolean needsRag = needsRag(lower);

        // 第3层：综合判定
        MessageRouteType result;
        if (needsWebSearch && needsRag) {
            result = MessageRouteType.HYBRID;
        } else if (needsWebSearch) {
            result = MessageRouteType.WEB_SEARCH;
        } else if (needsRag) {
            result = MessageRouteType.RAG_RETRIEVAL;
        } else {
            // 既没匹配联网关键词也没匹配知识库关键词
            // 根据消息长度和复杂度兜底判断
            if (trimmed.length() > 15 && TECH_DEEP_PATTERN.matcher(lower).find()) {
                // 较长且有一定技术深度的问题 → 走向量库
                result = MessageRouteType.RAG_RETRIEVAL;
            } else if (trimmed.length() <= 10) {
                // 很短的问题 → 直接回答
                result = MessageRouteType.DIRECT_CHAT;
            } else {
                // 中等长度、无明显特征 → 走向量库（宁可多检索，不遗漏信息）
                result = MessageRouteType.RAG_RETRIEVAL;
            }
        }

        System.out.println("[消息路由] → " + result.getDisplayName()
                + " (enableRag=" + result.isEnableRag()
                + ", enableWebSearch=" + result.isEnableWebSearch() + ")");
        System.out.println("========== [消息路由] 分析结束 ==========\n");

        return result;
    }

    // ==================== 内部判定方法 ====================

    /**
     * 判断是否为简单消息（直接回答，不走检索）
     */
    private boolean isSimpleMessage(String trimmed, String lower) {
        // 正则精确匹配：问候、确认、闲聊
        if (GREETING_PATTERN.matcher(lower).find()) return true;
        if (SIMPLE_REPLY_PATTERN.matcher(lower).find()) return true;
        if (CHITCHAT_PATTERN.matcher(lower).find()) return true;

        // 纯表情/符号
        if (trimmed.matches("^[\\s\\p{P}\\p{S}]+$")) return true;

        // 极短且不含技术关键词
        if (trimmed.length() <= 4 && !RAG_KEYWORD_PATTERN.matcher(lower).find()) return true;

        return false;
    }

    /**
     * 判断是否需要联网搜索
     */
    private boolean needsWebSearch(String lower) {
        return WEB_SEARCH_KEYWORD_PATTERN.matcher(lower).find();
    }

    /**
     * 判断是否需要知识库检索
     */
    private boolean needsRag(String lower) {
        return RAG_KEYWORD_PATTERN.matcher(lower).find();
    }

    /**
     * 截断字符串（日志用）
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return "null";
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }
}
