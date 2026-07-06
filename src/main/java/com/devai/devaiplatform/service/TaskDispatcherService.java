package com.devai.devaiplatform.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

/**
 * 任务路由分发服务 - 根据意图分析结果，将任务分发到对应的Agent/处理方法
 * 核心职责：
 * 1. 接收意图分析结果（TaskAnalysisResult）
 * 2. 根据意图类型路由到对应的处理方法
 * 3. 复合任务按顺序执行每个子任务并聚合结果
 * 4. 返回最终执行结果
 */
@Service
public class TaskDispatcherService {

    private final ChatLanguageModel chatModel;
    private final DevAgentService agentService;

    public TaskDispatcherService(ChatLanguageModel chatModel,
                                  DevAgentService agentService) {
        this.chatModel = chatModel;
        this.agentService = agentService;
    }

    /**
     * 根据意图分析结果分发并执行任务
     *
     * @param analysis 意图分析结果
     * @return 执行结果
     */
    public String dispatch(TaskAnalysisResult analysis) {
        System.out.println("\n========== 任务分发开始 ==========");
        System.out.println("分发意图: " + analysis.getPrimaryIntent().getDisplayName());
        System.out.println("是否复合任务: " + analysis.isMultiTask());

        String result;

        if (analysis.isMultiTask() && !analysis.getSubTasks().isEmpty()) {
            // 复合任务：按顺序执行每个子任务
            result = executeMultiTask(analysis);
        } else {
            // 单任务：直接路由到对应处理
            result = executeSingleTask(analysis);
        }

        System.out.println("========== 任务分发完成 ==========\n");
        return result;
    }

    /**
     * 执行单个任务 - 根据意图路由到对应方法
     */
    private String executeSingleTask(TaskAnalysisResult analysis) {
        TaskIntent intent = analysis.getPrimaryIntent();
        String content = analysis.getExtractedContent();
        String originalMessage = analysis.getOriginalMessage();

        // 如果提取内容为空，使用原始消息
        if (content == null || content.isEmpty()) {
            content = originalMessage;
        }

        System.out.println("[路由] 意图: " + intent.getDisplayName() + ", 内容长度: " + content.length());

        try {
            String result;
            switch (intent) {
                // ==================== 代码生成类 ====================
                case BACKEND_CODE_GEN: result = agentService.generateContent("backend_code", content); break;
                case FRONTEND_CODE_GEN: result = agentService.generateContent("frontend_code", content); break;
                case CRUD_CODE_GEN: result = agentService.generateContent("crud_sql", content); break;
                case API_CALL_GEN: result = agentService.generateContent("api_call_code", content); break;
                case VALIDATION_CODE_GEN: result = agentService.generateContent("validation_code", content); break;
                case MIGRATION_SCRIPT_GEN: result = agentService.generateContent("migration_script", content); break;
                case CONFIG_FILE_GEN: result = agentService.generateContent("config_file", "通用配置\n" + content); break;

                // ==================== 测试类 ====================
                case UNIT_TEST_GEN: result = agentService.generateContent("unit_test", content); break;
                case INTEGRATION_TEST_GEN: {
                    String p = String.format(PromptTemplate.INTEGRATION_TEST_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }

                // ==================== 代码审查类 ====================
                case CODE_REVIEW: result = agentService.generateContent("code_review", content); break;
                case CODE_REFACTOR: result = agentService.generateContent("refactoring", content); break;
                case CODE_COMMENT: {
                    String p = String.format(PromptTemplate.CODE_COMMENT_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }

                // ==================== SQL/数据库类 ====================
                case SQL_OPTIMIZE: result = agentService.generateContent("sql_optimize", content); break;
                case SQL_REWRITE: result = agentService.generateContent("sql_rewrite", content); break;
                case INDEX_DESIGN: result = agentService.generateContent("sql_design", content); break;
                case EXPLAIN_ANALYSIS: result = agentService.generateContent("explain_analysis", content); break;
                case TABLE_SCHEMA_DESIGN: result = agentService.generateContent("table_design", content); break;

                // ==================== 文档生成类 ====================
                case API_DOC_GEN: result = agentService.generateContent("api_doc", content); break;
                case PRD_DOC_GEN: result = agentService.generateContent("prd_doc", content); break;
                case TECH_SOLUTION_DOC: {
                    String p = String.format(PromptTemplate.TECH_SOLUTION_DOC_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }
                case CHANGELOG_GEN: {
                    String p = String.format(PromptTemplate.CHANGELOG_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }

                // ==================== 分析排查类 ====================
                case ERROR_LOG_ANALYSIS: result = agentService.analyzeErrorLog(content); break;
                case MEMORY_LEAK_DEBUG: {
                    String p = String.format(PromptTemplate.MEMORY_LEAK_DEBUG_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }
                case DEADLOCK_ANALYSIS: {
                    String p = String.format(PromptTemplate.DEADLOCK_ANALYSIS_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }
                case PERFORMANCE_DEBUG: {
                    String p = String.format(PromptTemplate.API_PERFORMANCE_DEBUG_TEMPLATE, content, "未知", "未知");
                    result = chatModel.generate(p); break;
                }
                case SECURITY_SCAN: {
                    String p = String.format(PromptTemplate.SECURITY_VULNERABILITY_SCAN_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }

                // ==================== 架构设计类 ====================
                case ARCHITECTURE_DESIGN: {
                    String p = String.format(PromptTemplate.ARCHITECTURE_DESIGN_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }
                case API_DESIGN: {
                    String p = String.format(PromptTemplate.API_DESIGN_STANDARD_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }
                case MICROSERVICE_SPLIT: {
                    String p = String.format(PromptTemplate.MICROSERVICE_SPLIT_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }
                case CACHE_DESIGN: {
                    String p = String.format(PromptTemplate.CACHE_DESIGN_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }

                // ==================== 性能优化类 ====================
                case JVM_TUNING: {
                    String p = String.format(PromptTemplate.JVM_TUNING_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }
                case CONCURRENCY_OPTIMIZE: {
                    String p = String.format(PromptTemplate.CONCURRENCY_OPTIMIZATION_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }

                // ==================== DevOps类 ====================
                case DOCKERFILE_OPTIMIZE: {
                    String p = String.format(PromptTemplate.DOCKERFILE_OPTIMIZE_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }
                case K8S_DEPLOYMENT: {
                    String p = String.format(PromptTemplate.K8S_DEPLOYMENT_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }

                // ==================== 知识检索类 ====================
                case KNOWLEDGE_SEARCH: result = agentService.searchDevLib(content); break;
                case MEMORY_SEARCH: result = agentService.retrieveMemories(content, ""); break;
                case WEB_SEARCH: result = agentService.searchWeb(content); break;

                // ==================== 文本处理类 ====================
                case TEXT_SUMMARY: result = agentService.generateContent("text_summary", content); break;
                case DATA_MASKING: {
                    String p = String.format(PromptTemplate.DATA_MASKING_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }

                // ==================== 代码转换类 ====================
                case LANGUAGE_CONVERT: {
                    String p = String.format(PromptTemplate.CODE_LANGUAGE_CONVERT_TEMPLATE, "原语言", "目标语言", content);
                    result = chatModel.generate(p); break;
                }
                case FRAMEWORK_MIGRATION: {
                    String p = String.format(PromptTemplate.FRAMEWORK_MIGRATION_TEMPLATE, "原框架", "目标框架", content);
                    result = chatModel.generate(p); break;
                }

                // ==================== 业务分析类 ====================
                case REQUIREMENT_ANALYSIS: {
                    String p = String.format(PromptTemplate.REQUIREMENT_ANALYSIS_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }
                case COMPETITIVE_ANALYSIS: {
                    String p = String.format(PromptTemplate.COMPETITIVE_ANALYSIS_TEMPLATE, content);
                    result = chatModel.generate(p); break;
                }

                // ==================== 通用对话 ====================
                case GENERAL_CHAT: result = agentService.askWithContext(originalMessage, null, false, false); break;

                // ==================== 未知/默认 ====================
                default: result = agentService.runDevTask(originalMessage); break;
            }
            return result;
        } catch (Exception e) {
            System.err.println("[任务分发] 执行失败: " + e.getMessage());
            return "任务执行失败: " + e.getMessage();
        }
    }

    /**
     * 执行复合任务 - 按顺序执行每个子任务并聚合结果
     */
    private String executeMultiTask(TaskAnalysisResult analysis) {
        System.out.println("[复合任务] 共 " + analysis.getSubTasks().size() + " 个子任务");

        StringBuilder aggregatedResult = new StringBuilder();
        aggregatedResult.append("# 复合任务执行结果\n\n");
        aggregatedResult.append("> 需求理解: ").append(analysis.getUnderstoodRequirement()).append("\n\n");
        aggregatedResult.append("---\n\n");

        int completedCount = 0;
        int failedCount = 0;

        for (TaskAnalysisResult.SubTask subTask : analysis.getSubTasks()) {
            System.out.println("[子任务 " + subTask.getIndex() + "] " + subTask.getIntent().getDisplayName()
                    + ": " + subTask.getDescription());

            try {
                // 构建单任务分析结果
                TaskAnalysisResult subAnalysis = new TaskAnalysisResult();
                subAnalysis.setOriginalMessage(subTask.getDescription());
                subAnalysis.setPrimaryIntent(subTask.getIntent());
                subAnalysis.setConfidence(0.9);
                subAnalysis.setExtractedContent(subTask.getContent());
                subAnalysis.setRelevantMemories(analysis.getRelevantMemories());

                // 执行子任务
                String subResult = executeSingleTask(subAnalysis);

                // 记录结果
                subTask.setResult(subResult);
                subTask.setSuccess(true);
                completedCount++;

                // 聚合结果
                aggregatedResult.append("## 子任务 ").append(subTask.getIndex())
                        .append(": ").append(subTask.getIntent().getDisplayName()).append("\n\n");
                aggregatedResult.append(subResult).append("\n\n---\n\n");

            } catch (Exception e) {
                subTask.setSuccess(false);
                subTask.setResult("执行失败: " + e.getMessage());
                failedCount++;

                aggregatedResult.append("## 子任务 ").append(subTask.getIndex())
                        .append(": ").append(subTask.getIntent().getDisplayName()).append("\n\n");
                aggregatedResult.append("**执行失败**: ").append(e.getMessage()).append("\n\n---\n\n");
            }
        }

        // 添加执行统计
        aggregatedResult.append("## 执行统计\n\n");
        aggregatedResult.append("- 总子任务数: ").append(analysis.getSubTasks().size()).append("\n");
        aggregatedResult.append("- 成功: ").append(completedCount).append("\n");
        aggregatedResult.append("- 失败: ").append(failedCount).append("\n");

        return aggregatedResult.toString();
    }

    /**
     * 获取当前支持的路由意图列表（用于调试）
     */
    public String getSupportedIntents() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 支持的任务路由列表\n\n");
        sb.append("| 意图枚举 | 显示名称 | 说明 |\n");
        sb.append("|---------|---------|------|\n");

        for (TaskIntent intent : TaskIntent.values()) {
            if (intent != TaskIntent.UNKNOWN && intent != TaskIntent.MULTI_TASK) {
                sb.append("| ").append(intent.name())
                        .append(" | ").append(intent.getDisplayName())
                        .append(" | ").append(intent.getDescription())
                        .append(" |\n");
            }
        }

        return sb.toString();
    }
}
