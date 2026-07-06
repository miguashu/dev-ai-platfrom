package com.devai.devaiplatform.service;

/**
 * Prompt 模板统一管理类
 * 集中管理所有 AI 提示词模板，便于维护和版本管理
 */
public class PromptTemplate {

    // ==================== 代码生成类模板 ====================

    /**
     * 单元测试生成模板
     */
    public static final String UNIT_TEST_TEMPLATE = """
        基于下面SpringBoot Service代码，输出完整可运行JUnit5单元测试：
        要求：
        1. 使用 @ExtendWith(MockitoExtension.class)
        2. Mock所有依赖
        3. 覆盖正常流程和异常流程
        4. 添加必要的注释

        代码：
        %s
        """;

    /**
     * 接口文档生成模板
     */
    public static final String API_DOC_TEMPLATE = """
        解析下面Controller代码，生成标准接口文档：
        格式要求：
        ## 接口名称
        - URL: 
        - Method: 
        - 请求参数: (表格形式)
        - 返回结果: (JSON示例)
        - 业务说明: 

        代码：
        %s
        """;

    /**
     * CRUD代码生成模板
     */
    public static final String CRUD_CODE_TEMPLATE = """
        根据以下表结构SQL，生成完整的增删改查代码：
        生成内容：
        1. Entity实体类（JPA注解）
        2. Repository接口
        3. Service业务层
        4. Controller控制层

        表结构：
        %s
        """;

    /**
     * 后端代码生成模板
     */
    public static final String BACKEND_CODE_TEMPLATE = """
        根据以下业务需求，生成完整的Spring Boot后端代码：

        ## 需求描述
        %s

        ## 生成要求
        1. **Controller层**：RESTful API接口，包含必要的注解
        2. **Service层**：业务逻辑实现，包含事务管理
        3. **Entity层**：JPA实体类，包含字段注释和验证
        4. **DTO层**：数据传输对象（Request/Response）
        5. **Repository层**：数据访问接口

        请按照标准Spring Boot项目结构输出完整代码，每个类都要有详细注释。
        """;

    /**
     * 前端代码生成模板
     */
    public static final String FRONTEND_CODE_TEMPLATE = """
        根据以下前端需求，生成现代化的组件代码：

        ## 组件需求
        %s

        ## 生成要求（以Vue 3为例）
        1. **Template部分**：HTML结构，使用语义化标签
        2. **Script部分**：Composition API，TypeScript类型定义
        3. **Style部分**：Scoped CSS，响应式设计
        4. **Props定义**：明确的属性类型和默认值
        5. **事件处理**：emit事件定义和处理

        如果是React，请使用Functional Component + Hooks。
        请输出完整可运行的代码。
        """;

    /**
     * API调用代码生成模板
     */
    public static final String API_CALL_CODE_TEMPLATE = """
        根据以下API描述，生成标准的接口调用代码：

        ## API描述
        %s

        ## 生成要求
        ### TypeScript版本
        1. 接口类型定义（Interface）
        2. Axios请求封装
        3. 错误处理和重试机制
        4. 请求/响应拦截器

        ### JavaScript版本
        提供简化的fetch实现

        请同时提供两种语言的实现。
        """;

    /**
     * 数据验证代码生成模板
     */
    public static final String VALIDATION_CODE_TEMPLATE = """
        根据以下验证规则，生成完整的数据校验代码：

        ## 验证需求
        %s

        ## 生成要求
        ### Spring Validation（后端）
        1. Bean Validation注解
        2. 自定义Validator实现
        3. 全局异常处理

        ### Zod/Yup（前端）
        1. Schema定义
        2. 类型推断
        3. 错误消息定制

        请提供前后端两种实现。
        """;

    /**
     * 数据库迁移脚本生成模板
     */
    public static final String MIGRATION_SCRIPT_TEMPLATE = """
        根据以下需求，生成数据库迁移SQL：

        ## 需求
        %s

        ## 生成要求
        1. **CREATE TABLE**：完整的表结构定义
        2. **索引**：主键、唯一索引、普通索引
        3. **外键约束**：关联关系定义
        4. **默认值**：合理的默认配置
        5. **注释**：表和字段的中文注释
        6. **回滚脚本**：DROP语句

        支持MySQL/PostgreSQL语法。
        """;

    /**
     * 单元测试生成模板（新版）
     */
    public static final String GENERATE_UNIT_TEST_TEMPLATE = """
        为以下代码生成完整的单元测试：

        ## 待测试代码
        %s

        ## 测试要求
        1. **正常流程测试**：主要功能验证
        2. **边界条件测试**：空值、极限值
        3. **异常流程测试**：各种异常情况
        4. **Mock依赖**：使用Mockito模拟外部依赖
        5. **断言完整**：验证所有关键结果
        6. **测试覆盖率**：目标80%以上

        使用JUnit 5 + Mockito框架。
        """;

    /**
     * 配置文件生成模板
     */
    public static final String CONFIG_FILE_TEMPLATE = """
        生成以下类型的配置文件：

        ## 配置类型: %s
        ## 需求
        %s

        ## 支持的配置类型
        - Dockerfile（多阶段构建优化）
        - docker-compose.yml（服务编排）
        - .github/workflows/ci.yml（GitHub Actions）
        - application.yml（Spring Boot配置）
        - nginx.conf（反向代理）
        - .gitignore（Git忽略规则）

        请输出完整可用的配置文件，包含必要注释。
        """;

    // ==================== 代码审查类模板 ====================

    /**
     * CodeReview模板
     */
    public static final String CODE_REVIEW_TEMPLATE = """
        对以下代码进行CodeReview，检查维度：
        1. 代码规范性（命名、注释）
        2. 潜在Bug（空指针、并发问题）
        3. 性能优化（循环、数据库查询）
        4. 安全隐患（SQL注入、敏感信息）
        5. 改进建议

        代码：
        %s
        """;

    /**
     * 代码重构建议模板
     */
    public static final String CODE_REFACTORING_TEMPLATE = """
        分析以下代码并提供专业的重构建议：

        ## 现有代码
        %s

        ## 分析维度
        1. **代码异味识别**：重复代码、过长方法等
        2. **设计模式应用**：适合的设计模式
        3. **性能优化**：算法复杂度、资源使用
        4. **可读性提升**：命名、注释、结构
        5. **安全性检查**：潜在安全风险
        6. **最佳实践**：行业标准和规范

        请给出具体重构步骤和示例代码。
        """;

    // ==================== SQL优化类模板 ====================

    /**
     * SQL性能分析模板
     */
    public static final String SQL_OPTIMIZE_TEMPLATE = """
        对以下SQL语句进行性能分析和优化建议：

        ## 原始SQL
        ```sql
        %s
        ```

        ## 分析维度
        ### 1. 性能问题诊断
        - 是否存在全表扫描
        - JOIN操作是否合理
        - 子查询是否可以优化
        - 是否有N+1查询问题

        ### 2. 索引建议
        - 需要添加的索引字段
        - 复合索引设计
        - 索引选择性分析

        ### 3. SQL重写建议
        - 优化后的SQL写法
        - 使用EXPLAIN分析执行计划
        - 避免的操作（如SELECT *）

        ### 4. 其他优化
        - 分页查询优化
        - 缓存策略建议
        - 读写分离可行性

        请给出具体的优化方案和改写后的SQL。
        """;

    /**
     * 索引设计模板
     */
    public static final String INDEX_DESIGN_TEMPLATE = """
        基于以下表结构和查询模式，设计最优索引策略：

        ## 表结构
        ```sql
        %s
        ```

        ## 常见查询模式
        %s

        ## 索引设计方案
        ### 1. 主键设计
        - 自增ID vs UUID
        - 聚簇索引选择

        ### 2. 单列索引
        - WHERE条件常用字段
        - ORDER BY排序字段
        - GROUP BY分组字段

        ### 3. 复合索引
        - 联合查询字段组合
        - 最左前缀原则应用
        - 覆盖索引设计

        ### 4. 特殊索引
        - 唯一索引（UNIQUE）
        - 部分索引（PARTIAL）
        - 全文索引（FULLTEXT）

        ### 5. 索引维护建议
        - 避免冗余索引
        - 定期重建索引
        - 监控索引使用率

        请给出具体的索引创建SQL和优化建议。
        """;

    /**
     * SQL重写优化模板
     */
    public static final String SQL_REWRITE_TEMPLATE = """
        将以下SQL重写为高性能版本：

        ## 原始SQL
        ```sql
        %s
        ```

        ## 优化方向
        ### 1. JOIN优化
        - 减少JOIN数量
        - 使用临时表预聚合
        - 调整JOIN顺序

        ### 2. 子查询优化
        - 相关子查询改为JOIN
        - EXISTS替代IN
        - 派生表物化

        ### 3. 聚合优化
        - 先过滤再聚合
        - 使用窗口函数
        - 避免多次扫描

        ### 4. 分页优化
        - 延迟关联分页
        - 游标分页（无偏移量）

        ### 5. 其他技巧
        - UNION ALL替代UNION
        - CASE WHEN减少扫描
        - 批量操作替代循环

        请给出优化后的SQL和详细对比说明。
        """;

    /**
     * 执行计划分析模板
     */
    public static final String EXPLAIN_ANALYSIS_TEMPLATE = """
        分析以下EXPLAIN执行计划：

        ## EXPLAIN输出
        ```
        %s
        ```

        ## 分析内容
        ### 1. 关键指标解读
        - type（访问类型）：system > const > eq_ref > ref > range > index > ALL
        - possible_keys：可能使用的索引
        - key：实际使用的索引
        - rows：扫描行数（越少越好）
        - Extra：额外信息（Using filesort/Using temporary需要优化）

        ### 2. 性能瓶颈识别
        - 全表扫描（type=ALL）
        - 文件排序（Using filesort）
        - 临时表（Using temporary）
        - 回表查询（需要回主键索引查完整数据）

        ### 3. 优化建议
        - 需要添加的索引
        - SQL改写建议
        - 表结构调整建议

        请给出详细的分析报告和优化方案。
        """;

    /**
     * 数据库表结构设计模板
     */
    public static final String TABLE_SCHEMA_TEMPLATE = """
        根据以下业务需求，设计符合规范的数据库表结构：

        ## 业务需求
        %s

        ## 设计要求
        ### 1. 规范化设计
        - 符合第三范式（3NF）
        - 合理的反范式化（性能考虑）
        - 外键关系设计

        ### 2. 字段设计
        - 数据类型选择（INT/BIGINT/VARCHAR等）
        - 字段长度合理性
        - 默认值和NOT NULL约束
        - 枚举值设计

        ### 3. 主键和索引
        - 主键策略（自增/UUID/雪花算法）
        - 索引规划（单列/复合/唯一）
        - 外键索引

        ### 4. 审计字段
        - create_time, update_time
        - create_by, update_by
        - is_deleted（逻辑删除）
        - version（乐观锁）

        ### 5. 表设计规范
        - 表名命名规范（小写+下划线）
        - 字段命名规范
        - 注释完整性

        请输出完整的CREATE TABLE SQL语句，包含所有索引和注释。
        """;

    // ==================== 文档生成类模板 ====================

    /**
     * PRD文档生成模板
     */
    public static final String PRD_DOC_TEMPLATE = """
        根据以下需求描述，生成标准PRD文档：
        结构要求：
        1. 需求背景
        2. 功能列表（表格）
        3. 业务流程图（Mermaid）
        4. 数据模型设计
        5. 接口设计要点

        需求：
        %s
        """;

    // ==================== 错误分析类模板 ====================

    /**
     * 错误日志分析模板
     */
    public static final String ERROR_LOG_TEMPLATE = """
        结合历史故障案例：%s

        分析以下日志并给出：
        1. 问题根因
        2. 排查步骤
        3. 修复方案
        4. 预防措施

        报错日志：
        %s
        """;

    // ==================== 文本处理类模板 ====================

    /**
     * 文本摘要模板
     */
    public static final String TEXT_SUMMARY_TEMPLATE = """
        你是专业技术文档摘要助手，严格按照要求精简总结内容：
        1. 输出简短精炼摘要，不要冗余描述、不要重复原文
        2. 技术内容重点保留：SQL、代码逻辑、报错原因、解决方案、业务规则
        3. 额外要求提示：%s

        待总结原文内容：
        %s
        """;

    /**
     * 带上下文的问答模板
     */
    public static final String CHAT_WITH_CONTEXT_TEMPLATE = """
        你是一个专业的开发助手。请基于以下对话历史和当前问题给出回答。

        【引用规则 - 确保答案可信】
        1. 如果下文提供了【知识库相关内容】，答案中涉及的事实必须明确引用来源文件名
        2. 引用格式示例："根据《xxx.pdf》中的记录..."或"参考知识库中《xxx》..."
        3. 没有明确来源的信息，请说明"根据已有知识推断"
        4. 系统会自动在回答末尾附加 📚 **参考文件** 链接，确保用户可点击验证

        【对话历史】
        %s

        【当前问题】
        %s
        """;

    // ==================== 架构设计类模板 ====================

    /**
     * 系统架构设计模板
     */
    public static final String ARCHITECTURE_DESIGN_TEMPLATE = """
        根据以下业务需求，设计系统架构方案：

        ## 业务需求
        %s

        ## 设计要求
        ### 1. 架构选型
        - 单体/微服务/Serverless选择及理由
        - 技术栈推荐（前端、后端、中间件、数据库）

        ### 2. 核心模块划分
        - 模块职责边界
        - 模块间通信方式（HTTP/RPC/MQ）

        ### 3. 高可用设计
        - 负载均衡策略
        - 服务降级与熔断
        - 多活/容灾方案

        ### 4. 数据架构
        - 数据库选型（MySQL/PG/MongoDB/ES）
        - 缓存策略（Redis本地缓存）
        - 分库分表方案

        ### 5. 部署架构
        - 容器化方案（Docker + K8s）
        - CI/CD流水线设计
        - 监控告警体系

        请输出架构图（Mermaid）和详细说明。
        """;

    /**
     * API设计规范模板
     */
    public static final String API_DESIGN_STANDARD_TEMPLATE = """
        根据以下业务场景，设计RESTful API规范：

        ## 业务场景
        %s

        ## 设计要求
        ### 1. URL设计规范
        - 资源命名规则（复数名词、小写连字符）
        - 层级关系表达（/users/{id}/orders）
        - 版本控制策略（/api/v1/）

        ### 2. HTTP方法规范
        - GET/POST/PUT/PATCH/DELETE使用场景
        - 幂等性设计
        - 批量操作接口设计

        ### 3. 请求/响应规范
        - 统一响应体结构（code/message/data）
        - 分页参数设计（page/size/total）
        - 排序和过滤参数

        ### 4. 错误处理
        - HTTP状态码使用规范
        - 业务错误码设计
        - 错误响应体结构

        ### 5. 安全与性能
        - 认证方式（JWT/OAuth2）
        - 限流策略
        - 缓存控制（ETag/Last-Modified）

        请输出完整的API设计文档和示例。
        """;

    /**
     * 微服务拆分模板
     */
    public static final String MICROSERVICE_SPLIT_TEMPLATE = """
        分析以下单体应用代码，给出微服务拆分方案：

        ## 现有单体代码/模块结构
        %s

        ## 分析维度
        ### 1. 领域边界识别
        - 核心域/支撑域/通用域划分
        - 限界上下文（Bounded Context）识别
        - 领域模型关系图

        ### 2. 服务拆分方案
        - 每个微服务的职责
        - 服务粒度评估
        - 数据库拆分策略

        ### 3. 服务通信
        - 同步调用（Feign/gRPC）
        - 异步消息（RabbitMQ/Kafka）
        - 事件驱动设计

        ### 4. 数据一致性
        - 分布式事务方案（Saga/TCC）
        - 最终一致性保障
        - 数据同步策略

        ### 5. 基础设施
        - 服务注册发现
        - 配置中心
        - 网关设计

        请给出拆分步骤和架构图（Mermaid）。
        """;

    // ==================== 调试排查类模板 ====================

    /**
     * 内存泄漏排查模板
     */
    public static final String MEMORY_LEAK_DEBUG_TEMPLATE = """
        分析以下Java代码，排查可能的内存泄漏问题：

        ## 代码内容
        %s

        ## 排查维度
        ### 1. 常见泄漏场景
        - 未关闭的资源（Stream/Connection/Session）
        - 静态集合类持有对象引用
        - 内部类持有外部类引用
        - ThreadLocal未清理
        - 监听器/回调未注销

        ### 2. 代码级分析
        - 逐方法检查对象生命周期
        - 集合类增长趋势
        - 缓存淘汰策略

        ### 3. JVM层面
        - 堆内存分布（Eden/Survivor/Old）
        - GC日志分析要点
        - MAT/JVisualvm排查步骤

        ### 4. 修复方案
        - 具体代码修改建议
        - try-with-resources改造
        - 弱引用/软引用应用

        请给出问题定位和修复代码。
        """;

    /**
     * 死锁分析模板
     */
    public static final String DEADLOCK_ANALYSIS_TEMPLATE = """
        分析以下多线程代码，排查死锁风险：

        ## 代码内容
        %s

        ## 分析维度
        ### 1. 锁顺序分析
        - 多把锁的获取顺序是否一致
        - 嵌套锁场景
        - 锁粒度是否合理

        ### 2. 线程交互
        - wait/notify使用是否正确
        - Condition信号通知
        - CountDownLatch/CyclicBarrier使用

        ### 3. 线程池风险
        - 线程池配置是否合理
        - 任务提交是否会阻塞
        - 线程饥饿场景

        ### 4. 修复方案
        - 锁顺序统一方案
        - 超时锁（tryLock）应用
        - 无锁算法替代

        请给出死锁场景分析和修复代码。
        """;

    /**
     * 接口性能排查模板
     */
    public static final String API_PERFORMANCE_DEBUG_TEMPLATE = """
        以下接口响应时间异常，请分析原因并给出优化方案：

        ## 接口信息
        - URL: %s
        - 平均响应时间: %s
        - QPS: 正常

        ## 排查维度
        ### 1. 应用层
        - 是否存在N+1查询
        - 循环内是否有RPC/DB调用
        - 大对象序列化耗时
        - 日志打印过多

        ### 2. 数据库层
        - 慢SQL排查
        - 索引是否命中
        - 锁等待/死锁
        - 连接池耗尽

        ### 3. 中间件层
        - Redis大Key/热Key
        - MQ消费延迟
        - 网络延迟

        ### 4. JVM层
        - 频繁GC
        - 线程阻塞
        - 内存不足

        请给出排查步骤和优化方案。
        """;

    // ==================== 安全审计类模板 ====================

    /**
     * 安全漏洞扫描模板
     */
    public static final String SECURITY_VULNERABILITY_SCAN_TEMPLATE = """
        对以下代码进行安全漏洞扫描：

        ## 代码内容
        %s

        ## 扫描维度
        ### 1. 注入漏洞
        - SQL注入（拼接SQL、未使用参数化查询）
        - XSS（未转义用户输入）
        - 命令注入（Runtime.exec拼接用户输入）
        - LDAP注入

        ### 2. 认证与授权
        - 密码存储方式（是否明文/弱哈希）
        - JWT/Session安全
        - 权限校验缺失
        - 越权访问风险

        ### 3. 敏感信息泄露
        - 日志中打印密码/Token
        - 异常堆栈暴露给前端
        - 硬编码密钥/密码
        - 接口返回敏感字段

        ### 4. 依赖安全
        - 已知漏洞依赖（Log4j/Fastjson等）
        - 不安全的反序列化
        - SSRF风险

        请列出所有风险点、等级和修复方案。
        """;

    /**
     * 数据脱敏方案模板
     */
    public static final String DATA_MASKING_TEMPLATE = """
        根据以下业务场景，设计数据脱敏方案：

        ## 业务场景
        %s

        ## 设计要求
        ### 1. 脱敏范围识别
        - 个人身份信息（PII）：姓名、身份证、手机号
        - 金融信息：银行卡号、CVV
        - 商业机密：合同金额、客户名单
        - 系统敏感：密码、Token、密钥

        ### 2. 脱敏策略
        - 遮盖（如：138****1234）
        - 替换（如：***@company.com）
        - 加密存储+按需解密
        - 令牌化（Tokenization）

        ### 3. 脱敏场景
        - 日志输出脱敏
        - 接口返回脱敏
        - 数据库存储脱敏
        - 测试环境数据脱敏

        ### 4. 实现方案
        - 注解式脱敏（@SensitiveField）
        - Jackson序列化脱敏
        - MyBatis插件脱敏

        请给出完整代码实现。
        """;

    // ==================== 性能优化类模板 ====================

    /**
     * JVM调优模板
     */
    public static final String JVM_TUNING_TEMPLATE = """
        根据以下应用特征，给出JVM调优方案：

        ## 应用信息
        %s

        ## 调优维度
        ### 1. 堆内存配置
        - Xms/Xmx设置（建议物理内存的60-80%%）
        - 新生代/老年代比例（-XX:NewRatio）
        - 大对象阈值（-XX:PretenureSizeThreshold）

        ### 2. GC选择
        - G1/ZGC/Shenandoah适用场景
        - GC参数调优
        - GC日志分析

        ### 3. 线程配置
        - 线程栈大小（-Xss）
        - 线程池核心/最大线程数
        - 虚拟线程（Java 21+）

        ### 4. 类加载优化
        - CDS（Class Data Sharing）
        - 类元数据区大小
        - 反射优化

        ### 5. 监控与诊断
        - JMX配置
        - Arthas常用命令
        - 性能指标基线

        请给出具体的JVM启动参数和调优建议。
        """;

    /**
     * 缓存方案设计模板
     */
    public static final String CACHE_DESIGN_TEMPLATE = """
        根据以下业务场景，设计缓存方案：

        ## 业务场景
        %s

        ## 设计要求
        ### 1. 缓存选型
        - 本地缓存（Caffeine/Guava）适用场景
        - 分布式缓存（Redis）适用场景
        - 多级缓存架构

        ### 2. 缓存策略
        - Cache-Aside / Read-Through / Write-Through
        - 过期时间设计（TTL + 随机抖动）
        - 缓存预热方案

        ### 3. 缓存一致性
        - 双写一致性方案
        - 延迟双删策略
        - Canal监听Binlog异步更新

        ### 4. 缓存异常处理
        - 缓存穿透（布隆过滤器/空值缓存）
        - 缓存击穿（互斥锁/逻辑过期）
        - 缓存雪崩（随机TTL/多级缓存）

        ### 5. 代码实现
        - Spring Cache注解使用
        - RedisTemplate操作封装
        - 缓存监控与统计

        请给出完整方案设计和代码实现。
        """;

    /**
     * 并发优化模板
     */
    public static final String CONCURRENCY_OPTIMIZATION_TEMPLATE = """
        分析以下代码的并发性能，给出优化方案：

        ## 代码内容
        %s

        ## 分析维度
        ### 1. 锁竞争分析
        - synchronized vs ReentrantLock选择
        - 锁粒度是否过大
        - 读写锁（ReadWriteLock）应用
        - 无锁并发（CAS/Atomic）可行性

        ### 2. 线程安全
        - 共享变量可见性（volatile）
        - 复合操作原子性
        - 线程安全集合选择

        ### 3. 异步化改造
        - CompletableFuture链式调用
        - 响应式编程（Reactor/WebFlux）
        - 虚拟线程（Java 21+）

        ### 4. 性能优化
        - 减少锁持有时间
        - 分段锁/条带锁
        - 批量处理减少同步

        请给出优化后的代码和性能对比。
        """;

    // ==================== 测试类模板 ====================

    /**
     * 集成测试模板
     */
    public static final String INTEGRATION_TEST_TEMPLATE = """
        为以下Spring Boot应用生成集成测试代码：

        ## 应用代码
        %s

        ## 测试要求
        ### 1. 测试框架
        - @SpringBootTest启动完整容器
        - TestContainers启动依赖（MySQL/Redis）
        - MockMvc/WebTestClient测试API

        ### 2. 测试场景
        - 正常业务流程端到端测试
        - 数据库事务回滚测试
        - 缓存命中/失效测试
        - 异常场景测试

        ### 3. 测试数据
        - @Sql脚本初始化数据
        - @BeforeEach清理数据
        - 测试数据工厂

        ### 4. 断言要求
        - 响应状态码断言
        - JSON路径断言
        - 数据库状态断言

        请输出完整可运行的集成测试代码。
        """;

    /**
     * 压力测试方案模板
     */
    public static final String LOAD_TEST_PLAN_TEMPLATE = """
        根据以下接口信息，设计压力测试方案：

        ## 接口信息
        %s

        ## 设计要求
        ### 1. 测试工具选择
        - JMeter适用场景
        - Gatling代码化压测
        - k6云原生压测

        ### 2. 测试场景设计
        - 基准测试（单接口低压）
        - 负载测试（逐步加压到瓶颈）
        - 压力测试（超过极限观察行为）
        - 稳定性测试（长时间恒定负载）

        ### 3. 监控指标
        - TPS/QPS目标值
        - P50/P95/P99响应时间
        - 错误率阈值
        - CPU/内存/网络/磁盘

        ### 4. 瓶颈分析
        - 应用层瓶颈定位
        - 数据库层瓶颈定位
        - 网络层瓶颈定位

        请给出完整的压测脚本和分析报告模板。
        """;

    // ==================== 文档生成类扩展模板 ====================

    /**
     * 技术方案文档模板
     */
    public static final String TECH_SOLUTION_DOC_TEMPLATE = """
        根据以下需求，编写技术方案文档：

        ## 需求描述
        %s

        ## 文档结构
        ### 1. 背景与目标
        - 业务背景
        - 技术目标
        - 非功能性需求（性能/可用性/安全）

        ### 2. 方案设计
        - 整体架构图（Mermaid）
        - 核心流程时序图
        - 数据模型设计
        - 接口设计

        ### 3. 方案对比
        - 备选方案列举
        - 优劣势对比表
        - 选择理由

        ### 4. 实施计划
        - 里程碑节点
        - 风险评估
        - 回滚方案

        ### 5. 监控与运维
        - 关键指标监控
        - 告警规则
        - 应急预案

        请输出完整的技术方案文档。
        """;

    /**
     * 故障复盘报告模板
     */
    public static final String INCIDENT_REVIEW_TEMPLATE = """
        根据以下故障信息，生成故障复盘报告：

        ## 故障信息
        %s

        ## 报告结构
        ### 1. 故障概述
        - 故障时间线
        - 影响范围
        - 故障等级

        ### 2. 根因分析
        - 5-Why分析法
        - 直接原因
        - 根本原因

        ### 3. 处理过程
        - 发现方式
        - 响应时间线
        - 修复步骤

        ### 4. 改进措施
        - 短期修复
        - 长期预防
        - 责任人与deadline

        ### 5. 经验总结
        - 做得好的地方
        - 需要改进的地方
        - 可复用的经验

        请输出结构化的复盘报告。
        """;

    /**
     * 代码注释生成模板
     */
    public static final String CODE_COMMENT_TEMPLATE = """
        为以下代码生成规范的JavaDoc注释：

        ## 代码内容
        %s

        ## 注释要求
        ### 1. 类级别
        - 类的职责说明
        - @author / @since
        - 使用示例

        ### 2. 方法级别
        - 方法功能描述
        - @param 每个参数说明
        - @return 返回值说明
        - @throws 异常说明
        - 使用示例

        ### 3. 关键逻辑
        - 复杂算法说明
        - 业务规则注释
        - TODO/FIXME标记

        请输出添加完整注释后的代码。
        """;

    /**
     * CHANGELOG生成模板
     */
    public static final String CHANGELOG_TEMPLATE = """
        根据以下代码变更，生成CHANGELOG：

        ## 代码变更（Git Diff）
        %s

        ## 格式要求
        遵循 Keep a Changelog 规范：
        ### [版本号] - 日期
        #### Added（新增）
        #### Changed（变更）
        #### Fixed（修复）
        #### Deprecated（废弃）
        #### Removed（移除）
        #### Security（安全）

        每条记录需包含：
        - 变更简述（一句话）
        - 关联Issue/PR编号
        - 影响范围

        请输出标准格式的CHANGELOG。
        """;

    // ==================== DevOps类模板 ====================

    /**
     * Dockerfile优化模板
     */
    public static final String DOCKERFILE_OPTIMIZE_TEMPLATE = """
        优化以下Dockerfile：

        ## 原始Dockerfile
        %s

        ## 优化维度
        ### 1. 镜像体积
        - 多阶段构建
        - 基础镜像选择（distroless/alpine）
        - 清理临时文件
        - .dockerignore配置

        ### 2. 安全性
        - 非root用户运行
        - 最小权限原则
        - 敏感信息不入镜像
        - 漏洞扫描

        ### 3. 构建速度
        - 层缓存优化
        - 依赖层与代码层分离
        - BuildKit特性

        ### 4. 运行时
        - 健康检查（HEALTHCHECK）
        - 优雅停机（SIGTERM处理）
        - 日志输出到stdout

        请给出优化后的Dockerfile和说明。
        """;

    /**
     * K8s部署方案模板
     */
    public static final String K8S_DEPLOYMENT_TEMPLATE = """
        根据以下应用信息，生成Kubernetes部署方案：

        ## 应用信息
        %s

        ## 生成内容
        ### 1. Deployment
        - 副本数与HPA配置
        - 资源限制（requests/limits）
        - 滚动更新策略
        - 健康检查（liveness/readiness）

        ### 2. Service
        - ClusterIP/NodePort/LoadBalancer选择
        - 端口映射

        ### 3. ConfigMap & Secret
        - 配置外部化
        - 敏感信息加密

        ### 4. Ingress
        - 路由规则
        - TLS配置
        - 限流策略

        ### 5. 运维
        - 日志收集（EFK/Loki）
        - 监控告警（Prometheus + Grafana）
        - 灰度发布（Argo Rollouts）

        请输出完整的YAML配置和说明。
        """;

    // ==================== 代码转换类模板 ====================

    /**
     * 代码语言转换模板
     */
    public static final String CODE_LANGUAGE_CONVERT_TEMPLATE = """
        将以下代码从 %s 转换为 %s：

        ## 原始代码
        %s

        ## 转换要求
        1. 保持原有逻辑不变
        2. 使用目标语言的最佳实践和惯用写法
        3. 处理语言特性差异（如空值处理、异常处理）
        4. 添加目标语言的规范注释
        5. 处理依赖库的对应替换

        请输出转换后的完整代码和关键差异说明。
        """;

    /**
     * 框架迁移模板
     */
    public static final String FRAMEWORK_MIGRATION_TEMPLATE = """
        将以下代码从 %s 迁移到 %s：

        ## 原始代码
        %s

        ## 迁移要求
        ### 1. API变更
        - 废弃API替换
        - 新API使用方式
        - 配置项变更

        ### 2. 依赖变更
        - Maven/Gradle依赖更新
        - 版本兼容性
        - 新增依赖

        ### 3. 行为差异
        - 默认行为变更
        - 生命周期变更
        - 性能特征差异

        ### 4. 测试验证
        - 迁移后测试要点
        - 回归测试范围

        请输出迁移后的代码和迁移检查清单。
        """;

    // ==================== 业务分析类模板 ====================

    /**
     * 需求分析模板
     */
    public static final String REQUIREMENT_ANALYSIS_TEMPLATE = """
        分析以下业务需求，输出需求分析报告：

        ## 原始需求
        %s

        ## 分析维度
        ### 1. 需求理解
        - 核心诉求提炼
        - 用户角色识别
        - 使用场景描述

        ### 2. 功能拆解
        - 功能模块划分
        - 优先级排序（P0/P1/P2）
        - 功能依赖关系

        ### 3. 非功能性需求
        - 性能要求
        - 安全要求
        - 可用性要求
        - 兼容性要求

        ### 4. 风险评估
        - 技术风险
        - 业务风险
        - 资源风险

        ### 5. 工作量估算
        - 开发人天
        - 测试人天
        - 里程碑计划

        请输出结构化的需求分析报告。
        """;

    /**
     * 竞品分析模板
     */
    public static final String COMPETITIVE_ANALYSIS_TEMPLATE = """
        对以下产品/技术方案进行竞品分析：

        ## 我的方案
        %s

        ## 分析维度
        ### 1. 功能对比
        - 核心功能覆盖度
        - 差异化功能
        - 功能成熟度

        ### 2. 技术架构
        - 技术栈对比
        - 性能指标对比
        - 扩展性对比

        ### 3. 用户体验
        - 易用性
        - 文档完善度
        - 社区活跃度

        ### 4. 商业模式
        - 定价策略
        - 目标客户
        - 市场份额

        ### 5. SWOT分析
        - 优势（Strengths）
        - 劣势（Weaknesses）
        - 机会（Opportunities）
        - 威胁（Threats）

        请输出对比表格和分析报告。
        """;

    // ==================== 智能路由类模板 ====================

    /**
     * 意图分析模板 - 用于AI理解用户消息并分类意图
     * 参数1: 用户消息
     * 参数2: 可用意图列表
     */
    public static final String INTENT_ANALYSIS_TEMPLATE = """
        你是一个智能任务路由器。请分析用户的消息，理解其真实需求，并完成以下任务：

        ## 用户消息
        %s

        ## 可用意图列表
        %s

        ## 分析要求
        1. **理解需求**：用一句话概括用户真正想要什么
        2. **识别意图**：从可用意图列表中选择最匹配的意图（返回枚举名称）
        3. **置信度评估**：给出0.0~1.0的置信度
        4. **复合任务判断**：如果用户消息包含多个独立需求，标记为复合任务并拆分子任务
        5. **内容提取**：提取用户消息中的关键内容（代码、SQL、需求描述等）

        ## 输出格式（严格JSON）
        ```json
        {
          "understoodRequirement": "对用户需求的精炼理解",
          "primaryIntent": "意图枚举名称，如BACKEND_CODE_GEN",
          "confidence": 0.95,
          "isMultiTask": false,
          "extractedContent": "提取的关键内容（代码/SQL/需求等）",
          "subTasks": [
            {
              "intent": "子任务意图枚举名称",
              "description": "子任务描述",
              "content": "子任务提取的内容"
            }
          ]
        }
        ```

        注意：
        - 只输出JSON，不要输出其他内容
        - primaryIntent必须是可用意图列表中的枚举名称
        - 如果无法确定，primaryIntent返回"GENERAL_CHAT"
        - 复合任务时，每个子任务都要有独立的intent和content
        ```
        """;

    /**
     * 复合任务拆分模板 - 用于将复杂消息拆分为多个子任务
     */
    public static final String TASK_SPLIT_TEMPLATE = """
        你是一个任务拆分专家。请将以下用户消息拆分为多个独立的子任务：

        ## 用户消息
        %s

        ## 拆分要求
        1. 每个子任务应该是独立可执行的
        2. 子任务之间如果有依赖关系，请按顺序排列
        3. 每个子任务需要明确：意图类型、描述、提取的内容

        ## 可用意图类型
        %s

        ## 输出格式（严格JSON数组）
        ```json
        [
          {
            "intent": "意图枚举名称",
            "description": "子任务描述",
            "content": "提取的关键内容"
          }
        ]
        ```

        只输出JSON数组，不要输出其他内容。
        """;

    // ==================== IDEA错误修复类模板 ====================

    /**
     * IDEA编译错误修复模板
     * 参数1: 编译错误上下文（包含文件路径、行号、错误信息、源码片段）
     */
    public static final String IDEA_COMPILE_ERROR_FIX_TEMPLATE = """
        你是一个资深的Java开发专家。请分析以下IntelliJ IDEA编译错误，并给出修复方案。

        ## 编译错误信息
        %s

        ## 分析要求
        请对每个错误进行：

        ### 1. 错误诊断
        - 错误类型识别（语法错误、类型不匹配、缺少导入、方法签名不匹配等）
        - 错误根因分析（为什么会出现这个错误）
        - 影响范围评估

        ### 2. 修复方案
        - 给出具体的代码修改建议
        - 说明修改的原因
        - 如果有多种修复方式，列出所有方案并推荐最优方案

        ### 3. 修复后的代码
        - 输出完整的修复后代码（包含上下文）
        - 用注释标记修改的位置

        ### 4. 预防措施
        - 如何避免类似错误再次发生
        - 相关的编码规范建议

        请按以下格式输出：
        ```
        ## 错误1: [错误类型]
        **位置**: 文件:行号
        **原因**: ...
        **修复方案**: ...
        **修复后代码**:
        ```java
        // 修复后的代码
        ```
        **预防建议**: ...
        ```
        """;

    /**
     * IDEA运行时错误分析模板
     * 参数1: 运行时异常堆栈
     * 参数2: 相关源码（可选）
     */
    public static final String IDEA_RUNTIME_ERROR_FIX_TEMPLATE = """
        你是一个Java调试专家。请分析以下运行时异常堆栈，定位问题根因并给出修复方案。

        ## 异常堆栈
        %s

        ## 相关源码（如有）
        %s

        ## 分析要求
        ### 1. 异常分析
        - 异常类型识别
        - 触发位置定位（精确到文件:行号）
        - 调用链路追踪

        ### 2. 根因定位
        - 直接原因
        - 根本原因（为什么会导致这个异常）
        - 数据流分析（如果涉及空指针/类型转换等）

        ### 3. 修复方案
        - 具体代码修改建议
        - 防御性编程建议
        - 异常处理改进

        ### 4. 测试验证
        - 如何验证修复是否有效
        - 需要添加的测试用例

        请给出详细的诊断报告和修复代码。
        """;

    // ==================== 项目架构生成类模板 ====================

    /**
     * 项目架构设计模板 - 用于AI分析需求并生成项目结构
     * 参数1: 用户需求描述
     */
    public static final String PROJECT_STRUCTURE_DESIGN_TEMPLATE = """
        你是一个资深架构师。请根据以下需求，设计完整的项目架构方案。

        ## 用户需求
        %s

        ## 设计要求
        请输出以下内容：

        ### 1. 技术选型建议
        - 开发语言版本（如 Java 17/21）
        - 框架选择（Spring Boot/Quarkus/Micronaut）
        - 数据库选择（MySQL/PostgreSQL/MongoDB）
        - 缓存方案（Redis/Caffeine）
        - 消息队列（如需要）

        ### 2. 项目结构设计
        - 项目类型（单体/多模块/微服务）
        - 目录结构树形图
        - 各层职责说明

        ### 3. 核心模块划分
        - 模块名称和职责
        - 模块间依赖关系
        - 建议的包名规划

        ### 4. 关键代码骨架
        - 实体类设计
        - 接口定义
        - 配置类示例

        ### 5. 开发规范建议
        - 命名规范
        - 异常处理规范
        - 日志规范

        请以结构化的方式输出完整方案。
        """;

    /**
     * 代码文件生成模板 - 用于AI生成具体代码文件内容
     * 参数1: 文件类型描述
     * 参数2: 业务需求
     * 参数3: 参考代码/结构（可选）
     */
    public static final String CODE_FILE_GENERATE_TEMPLATE = """
        请根据以下需求，生成完整的代码文件内容。

        ## 文件类型
        %s

        ## 业务需求
        %s

        ## 参考结构（如有）
        %s

        ## 生成要求
        1. 代码必须完整可运行
        2. 包含必要的import语句
        3. 添加规范的JavaDoc注释
        4. 遵循Java编码规范
        5. 考虑异常处理和边界情况
        6. 使用Lombok简化代码（如适用）

        请直接输出代码内容，不需要额外解释。
        """;

    // ==================== 网页搜索信息提炼模板 ====================

    /**
     * 网页搜索结果提炼模板
     * 将多轮筛选后的网页内容交给 AI 进行深度提炼
     */
    public static final String WEB_SEARCH_SUMMARY_TEMPLATE = """
        你是一个专业的技术信息提炼助手。请基于以下网络搜索结果，给出全面、结构化的回答。

        ## 用户原始问题
        %s

        ## 网络搜索结果（已过多轮筛选和去重）
        %s

        ## 回答要求
        ### 1. 信息提炼
        - 提取每个信息源的核心观点和关键数据
        - 交叉验证不同来源的信息一致性
        - 标注信息的时效性（发布/更新日期）

        ### 2. 结构化输出
        - **结论先行**：先给出直接回答
        - **分点详述**：按逻辑维度展开说明
        - **对比分析**：如有不同方案/观点，进行对比

        ### 3. 来源标注（溯源-有据可查）
        - 正文中每个来自搜索结果的事实标注来源序号，如 [1]、[2]
        - 回答末尾必须以 Markdown 可点击链接格式列出所有参考来源：
          ```
          ---
          📚 **参考来源**：
          - [来源标题1](https://完整URL1)
          - [来源标题2](https://完整URL2)
          ```
        - 链接必须使用 `[标题](完整URL)` 格式，确保用户可以直接点击跳转到原文
        - URL 必须完整保留，不得省略或缩短

        ### 4. 完整性检查
        - 确保覆盖用户问题的所有维度
        - 如搜索结果不完整，补充说明需要进一步了解的内容
        - 区分"来自搜索结果的事实"和"基于已有知识的推断"

        ### 5. 格式规范
        - 使用 Markdown 格式
        - 技术术语使用英文原文+中文解释
        - 代码块标注语言
        - 结论部分使用加粗或引用块突出

        请基于以上要求，给出高质量的回答。
        """;

    // ==================== 工具方法 ====================

    /**
     * 格式化模板，替换占位符
     * @param template 模板字符串
     * @param args 参数
     * @return 格式化后的字符串
     */
    public static String format(String template, Object... args) {
        return String.format(template, args);
    }

    /**
     * 根据任务类型获取对应的模板
     * @param taskType 任务类型
     * @return 模板字符串，如果找不到则返回null
     */
    public static String getTemplate(String taskType) {
        if (taskType == null) return null;
        return switch (taskType) {
            case "unit_test" -> UNIT_TEST_TEMPLATE;
            case "api_doc" -> API_DOC_TEMPLATE;
            case "prd_doc" -> PRD_DOC_TEMPLATE;
            case "backend_code" -> BACKEND_CODE_TEMPLATE;
            case "frontend_code" -> FRONTEND_CODE_TEMPLATE;
            case "api_call_code" -> API_CALL_CODE_TEMPLATE;
            case "validation_code" -> VALIDATION_CODE_TEMPLATE;
            case "migration_script" -> MIGRATION_SCRIPT_TEMPLATE;
            case "crud_sql" -> CRUD_CODE_TEMPLATE;
            case "code_review" -> CODE_REVIEW_TEMPLATE;
            case "refactoring" -> CODE_REFACTORING_TEMPLATE;
            case "config_file" -> CONFIG_FILE_TEMPLATE;
            case "text_summary" -> TEXT_SUMMARY_TEMPLATE;
            case "sql_optimize" -> SQL_OPTIMIZE_TEMPLATE;
            case "sql_design" -> INDEX_DESIGN_TEMPLATE;
            case "sql_rewrite" -> SQL_REWRITE_TEMPLATE;
            case "explain_analysis" -> EXPLAIN_ANALYSIS_TEMPLATE;
            case "table_design" -> TABLE_SCHEMA_TEMPLATE;
            default -> null;
        };
    }
}
