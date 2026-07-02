package com.devai.devaiplatform.service;

// ===================== 新增：AI摘要服务接口 =====================
    public interface AISummaryService {
        /**
         * 生成AI摘要
         * @param content 待摘要的内容
         * @param prompt 摘要提示词
         * @return 生成的摘要
         * @throws Exception 摘要生成失败时抛出
         */
        String generateSummary(String content, String prompt) throws Exception;
    }