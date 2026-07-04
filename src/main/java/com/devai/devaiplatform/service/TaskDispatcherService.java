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
            return switch (intent) {
                // ==================== 代码生成类 ====================
                case BACKEND_CODE_GEN -> agentService.generateBackendCode(content);
                case FRONTEND_CODE_GEN -> agentService.generateFrontendCode(content);
                case CRUD_CODE_GEN -> agentService.genCrudCode(content);
                case API_CALL_GEN -> agentService.generateApiCallCode(content);
                case VALIDATION_CODE_GEN -> agentService.generateValidationCode(content);
                case MIGRATION_SCRIPT_GEN -> agentService.generateMigrationScript(content);
                case CONFIG_FILE_GEN -> agentService.generateConfigFile("通用配置", content);

                // ==================== 测试类 ====================
                case UNIT_TEST_GEN -> agentService.generateUnitTest(content);
                case INTEGRATION_TEST_GEN -> {
                    String prompt = String.format(PromptTemplate.INTEGRATION_TEST_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }

                // ==================== 代码审查类 ====================
                case CODE_REVIEW -> agentService.codeReview(content);
                case CODE_REFACTOR -> agentService.suggestCodeRefactoring(content);
                case CODE_COMMENT -> {
                    String prompt = String.format(PromptTemplate.CODE_COMMENT_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }

                // ==================== SQL/数据库类 ====================
                case SQL_OPTIMIZE -> agentService.optimizeSql(content);
                case SQL_REWRITE -> agentService.rewriteSql(content);
                case INDEX_DESIGN -> agentService.designIndex(content, "常见查询模式");
                case EXPLAIN_ANALYSIS -> agentService.analyzeExplainPlan(content);
                case TABLE_SCHEMA_DESIGN -> agentService.designTableSchema(content);

                // ==================== 文档生成类 ====================
                case API_DOC_GEN -> agentService.genApiDoc(content);
                case PRD_DOC_GEN -> agentService.genPrdDoc(content);
                case TECH_SOLUTION_DOC -> {
                    String prompt = String.format(PromptTemplate.TECH_SOLUTION_DOC_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }
                case CHANGELOG_GEN -> {
                    String prompt = String.format(PromptTemplate.CHANGELOG_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }

                // ==================== 分析排查类 ====================
                case ERROR_LOG_ANALYSIS -> agentService.analyzeErrorLog(content);
                case MEMORY_LEAK_DEBUG -> {
                    String prompt = String.format(PromptTemplate.MEMORY_LEAK_DEBUG_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }
                case DEADLOCK_ANALYSIS -> {
                    String prompt = String.format(PromptTemplate.DEADLOCK_ANALYSIS_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }
                case PERFORMANCE_DEBUG -> {
                    String prompt = String.format(PromptTemplate.API_PERFORMANCE_DEBUG_TEMPLATE, content, "未知", "未知");
                    yield chatModel.generate(prompt);
                }
                case SECURITY_SCAN -> {
                    String prompt = String.format(PromptTemplate.SECURITY_VULNERABILITY_SCAN_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }

                // ==================== 架构设计类 ====================
                case ARCHITECTURE_DESIGN -> {
                    String prompt = String.format(PromptTemplate.ARCHITECTURE_DESIGN_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }
                case API_DESIGN -> {
                    String prompt = String.format(PromptTemplate.API_DESIGN_STANDARD_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }
                case MICROSERVICE_SPLIT -> {
                    String prompt = String.format(PromptTemplate.MICROSERVICE_SPLIT_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }
                case CACHE_DESIGN -> {
                    String prompt = String.format(PromptTemplate.CACHE_DESIGN_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }

                // ==================== 性能优化类 ====================
                case JVM_TUNING -> {
                    String prompt = String.format(PromptTemplate.JVM_TUNING_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }
                case CONCURRENCY_OPTIMIZE -> {
                    String prompt = String.format(PromptTemplate.CONCURRENCY_OPTIMIZATION_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }

                // ==================== DevOps类 ====================
                case DOCKERFILE_OPTIMIZE -> {
                    String prompt = String.format(PromptTemplate.DOCKERFILE_OPTIMIZE_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }
                case K8S_DEPLOYMENT -> {
                    String prompt = String.format(PromptTemplate.K8S_DEPLOYMENT_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }

                // ==================== 知识检索类 ====================
                case KNOWLEDGE_SEARCH -> agentService.searchDevLib(content);
                case MEMORY_SEARCH -> agentService.retrieveMemories(content, "");
                case WEB_SEARCH -> agentService.searchWeb(content);

                // ==================== 文本处理类 ====================
                case TEXT_SUMMARY -> agentService.generateTextSummary(content, "请精炼总结");
                case DATA_MASKING -> {
                    String prompt = String.format(PromptTemplate.DATA_MASKING_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }

                // ==================== 代码转换类 ====================
                case LANGUAGE_CONVERT -> {
                    String prompt = String.format(PromptTemplate.CODE_LANGUAGE_CONVERT_TEMPLATE, "原语言", "目标语言", content);
                    yield chatModel.generate(prompt);
                }
                case FRAMEWORK_MIGRATION -> {
                    String prompt = String.format(PromptTemplate.FRAMEWORK_MIGRATION_TEMPLATE, "原框架", "目标框架", content);
                    yield chatModel.generate(prompt);
                }

                // ==================== 业务分析类 ====================
                case REQUIREMENT_ANALYSIS -> {
                    String prompt = String.format(PromptTemplate.REQUIREMENT_ANALYSIS_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }
                case COMPETITIVE_ANALYSIS -> {
                    String prompt = String.format(PromptTemplate.COMPETITIVE_ANALYSIS_TEMPLATE, content);
                    yield chatModel.generate(prompt);
                }

                // ==================== 通用对话 ====================
                case GENERAL_CHAT -> agentService.askWithContext(originalMessage, null, false, false);

                // ==================== 未知/默认 ====================
                default -> agentService.runDevTask(originalMessage);
            };
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
