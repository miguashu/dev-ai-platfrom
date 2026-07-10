package com.devai.devaiplatform.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * AI驱动的查询关键词提取服务
 * 
 * 使用AI模型从用户自然语言查询中智能提取核心关键词，
 * 适用于文件检索、知识库问答、语义搜索等所有场景。
 */
@Service
public class AiKeywordExtractorService {

    @Autowired
    private ChatLanguageModel chatModel;

    /**
     * 从用户查询中提取核心关键词
     * 
     * @param userQuery 用户原始查询，如"帮我分析一下李腾涛的简历内容"、"Spring Boot如何配置数据源"
     * @return 提取的核心关键词，如"李腾涛简历"、"Spring Boot 数据源配置"；无法提取时返回null
     */
    public String extractFileNameKeyword(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return null;
        }

        try {
            // 构建AI提示词
            String prompt = buildExtractionPrompt(userQuery);
            
            // 调用AI模型
            String response = chatModel.generate(prompt);
            
            // 解析AI响应
            String keyword = parseAiResponse(response);
            
            System.out.println("[AI关键词提取] 原始查询: '" + userQuery + "' → AI提取: '" + keyword + "'");
            
            return keyword;
            
        } catch (Exception e) {
            System.err.println("[AI关键词提取] 异常: " + e.getMessage());
            // AI失败时降级为null，让上层使用规则匹配兜底
            return null;
        }
    }

    /**
     * 构建关键词提取的AI提示词（通用版）
     */
    private String buildExtractionPrompt(String userQuery) {
        return """
            你是一个智能关键词提取专家。请从用户的查询中提取最核心的检索关键词。
            
            ## 任务说明
            用户会用自然语言提问或描述需求，你需要从中提取出最适合用于检索的核心关键词组合。
            这些关键词将用于在知识库、文档库、代码库中进行精准检索。
            
            ## 提取原则
            1. **保留核心实体**：人名、项目名、技术名词、产品名、文档标题等
            2. **保留关键动作/状态**：配置、部署、报错、优化、异常等有检索价值的词
            3. **去除无意义词汇**：
               - 动词前缀：帮我、请、麻烦、想要、需要、看看、查看、查找
               - 助词/介词：的、了、吗、呢、把、被、从、向
               - 泛化后缀：内容、信息、资料、东西、问题、方法、方式
               - 疑问词：怎么、如何、什么、哪些、为什么
            4. **保持语义完整性**：提取的关键词组合应该能独立表达查询意图
            5. **简洁精炼**：通常2-10个词，避免过长
            6. **不要添加任何解释、标点或额外文字**
            
            ## 多场景示例
            
            ### 文件/文档检索
            输入："帮我查看一下李腾涛的简历"
            输出：李腾涛简历
            
            输入："找一下项目需求说明书"
            输出：项目需求说明书
            
            输入："搜索王明的技术报告文档"
            输出：王明技术报告
            
            ### 技术问题查询
            输入："Spring Boot怎么配置数据源啊"
            输出：Spring Boot 数据源配置
            
            输入："MyBatis Plus分页插件使用方法"
            输出：MyBatis Plus 分页插件
            
            输入："Redis缓存穿透怎么解决"
            输出：Redis 缓存穿透 解决方案
            
            ### 代码/功能查询
            输入："用户登录接口的实现代码"
            输出：用户登录接口 实现
            
            输入："订单模块的业务逻辑在哪里"
            输出：订单模块 业务逻辑
            
            ### 故障排查
            输入："服务器启动报错了怎么办"
            输出：服务器启动 报错
            
            输入："数据库连接超时是什么原因"
            输出：数据库连接超时
            
            ### 非检索类查询（返回null）
            输入："今天天气怎么样"
            输出：null
            
            输入："你好"
            输出：null
            
            输入："谢谢"
            输出：null
            
            ## 用户查询
            "%s"
            
            ## 你的输出（仅输出关键词，不要其他内容）
            """.formatted(userQuery);
    }

    /**
     * 解析AI响应，提取关键词
     */
    private String parseAiResponse(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }

        // 清理响应文本
        String cleaned = response.trim()
                .replace("\"", "")      // 去除引号
                .replace("'", "")       // 去除单引号
                .replace("```", "")     // 去除代码块标记
                .replace("\n", "")      // 去除换行
                .trim();

        // 如果AI返回"null"或类似表示无法提取的词
        if (cleaned.equalsIgnoreCase("null") || 
            cleaned.equals("无") || 
            cleaned.equals("没有") ||
            cleaned.length() < 2) {
            return null;
        }

        // 验证提取结果是否合理（长度限制）
        if (cleaned.length() > 50) {
            System.out.println("[AI关键词提取] ⚠️ 提取结果过长，可能不准确: " + cleaned);
            return null;
        }

        return cleaned;
    }
}
