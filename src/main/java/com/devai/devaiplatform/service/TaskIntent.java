package com.devai.devaiplatform.service;

/**
 * 任务意图枚举 - 定义所有可识别的用户意图类型
 * 用于智能消息路由，将用户消息分类到对应的处理Agent
 */
public enum TaskIntent {

    // ==================== 代码生成类 ====================
    BACKEND_CODE_GEN("后端代码生成", "生成Spring Boot后端业务代码"),
    FRONTEND_CODE_GEN("前端代码生成", "生成Vue/React前端组件代码"),
    CRUD_CODE_GEN("CRUD代码生成", "根据表结构生成增删改查代码"),
    API_CALL_GEN("API调用代码生成", "生成HTTP接口调用代码"),
    VALIDATION_CODE_GEN("验证代码生成", "生成数据校验代码"),
    MIGRATION_SCRIPT_GEN("迁移脚本生成", "生成数据库迁移SQL脚本"),
    CONFIG_FILE_GEN("配置文件生成", "生成Dockerfile/CI/CD等配置"),

    // ==================== 测试类 ====================
    UNIT_TEST_GEN("单元测试生成", "生成JUnit5单元测试"),
    INTEGRATION_TEST_GEN("集成测试生成", "生成Spring Boot集成测试"),

    // ==================== 代码审查类 ====================
    CODE_REVIEW("代码审查", "CodeReview检查代码质量"),
    CODE_REFACTOR("代码重构", "分析代码给出重构建议"),
    CODE_COMMENT("代码注释", "为代码生成JavaDoc注释"),

    // ==================== SQL/数据库类 ====================
    SQL_OPTIMIZE("SQL优化", "分析SQL性能并给出优化建议"),
    SQL_REWRITE("SQL重写", "将SQL重写为高性能版本"),
    INDEX_DESIGN("索引设计", "设计数据库索引策略"),
    EXPLAIN_ANALYSIS("执行计划分析", "解读EXPLAIN输出"),
    TABLE_SCHEMA_DESIGN("表结构设计", "设计数据库表结构"),

    // ==================== 文档生成类 ====================
    API_DOC_GEN("接口文档生成", "生成API接口文档"),
    PRD_DOC_GEN("PRD文档生成", "生成产品需求文档"),
    TECH_SOLUTION_DOC("技术方案文档", "编写技术方案"),
    CHANGELOG_GEN("CHANGELOG生成", "生成变更日志"),

    // ==================== 分析排查类 ====================
    ERROR_LOG_ANALYSIS("错误日志分析", "分析报错日志给出修复方案"),
    MEMORY_LEAK_DEBUG("内存泄漏排查", "排查Java内存泄漏问题"),
    DEADLOCK_ANALYSIS("死锁分析", "排查多线程死锁问题"),
    PERFORMANCE_DEBUG("性能排查", "排查接口性能问题"),
    SECURITY_SCAN("安全扫描", "代码安全漏洞扫描"),

    // ==================== 架构设计类 ====================
    ARCHITECTURE_DESIGN("架构设计", "系统架构方案设计"),
    API_DESIGN("API设计", "RESTful API规范设计"),
    MICROSERVICE_SPLIT("微服务拆分", "单体应用微服务拆分方案"),
    CACHE_DESIGN("缓存设计", "缓存方案设计"),

    // ==================== 性能优化类 ====================
    JVM_TUNING("JVM调优", "JVM参数调优方案"),
    CONCURRENCY_OPTIMIZE("并发优化", "并发性能优化"),

    // ==================== DevOps类 ====================
    DOCKERFILE_OPTIMIZE("Dockerfile优化", "优化Dockerfile"),
    K8S_DEPLOYMENT("K8s部署", "Kubernetes部署方案"),

    // ==================== 知识检索类 ====================
    KNOWLEDGE_SEARCH("知识检索", "检索项目内部知识库"),
    MEMORY_SEARCH("记忆检索", "检索历史记忆"),
    WEB_SEARCH("网页搜索", "联网搜索最新技术资料"),

    // ==================== 文本处理类 ====================
    TEXT_SUMMARY("文本摘要", "生成文本摘要"),
    DATA_MASKING("数据脱敏", "设计数据脱敏方案"),

    // ==================== 代码转换类 ====================
    LANGUAGE_CONVERT("语言转换", "代码语言转换"),
    FRAMEWORK_MIGRATION("框架迁移", "框架版本迁移"),

    // ==================== 业务分析类 ====================
    REQUIREMENT_ANALYSIS("需求分析", "业务需求分析报告"),
    COMPETITIVE_ANALYSIS("竞品分析", "竞品对比分析"),

    // ==================== 通用对话类 ====================
    GENERAL_CHAT("通用对话", "普通问答/闲聊"),
    MULTI_TASK("复合任务", "包含多个子任务需要拆分"),

    // ==================== 未知 ====================
    UNKNOWN("未知意图", "无法识别的意图");

    private final String displayName;
    private final String description;

    TaskIntent(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据意图名称查找枚举
     */
    public static TaskIntent fromName(String name) {
        for (TaskIntent intent : values()) {
            if (intent.name().equalsIgnoreCase(name) || intent.displayName.equals(name)) {
                return intent;
            }
        }
        return UNKNOWN;
    }
}
