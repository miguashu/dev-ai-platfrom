package com.devai.devaiplatform;

import com.devai.devaiplatform.config.agent.AgentConfig;
import com.devai.devaiplatform.config.agent.AgentConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent配置加载与验证单元测试
 * 读取 agent-config.json，验证配置完整性，初始化 DevAgentService 配置上下文
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentConfigTest {

    private static AgentConfig config;
    private static AgentConfigService configService;

    @BeforeAll
    static void setUp() throws Exception {
        // 1. 从 classpath 加载 agent-config.json
        ClassPathResource resource = new ClassPathResource("agent-config.json");
        assertTrue(resource.exists(), "agent-config.json 文件必须存在于 resources 目录");

        ObjectMapper mapper = new ObjectMapper();
        try (InputStream is = resource.getInputStream()) {
            config = mapper.readValue(is, AgentConfig.class);
        }
        assertNotNull(config, "配置对象不能为空");

        // 2. 初始化 AgentConfigService（模拟 Spring 启动加载）
        configService = new AgentConfigService();
        // 通过反射调用 @PostConstruct init 方法
        java.lang.reflect.Method initMethod = AgentConfigService.class.getDeclaredMethod("init");
        initMethod.setAccessible(true);
        initMethod.invoke(configService);

        System.out.println("✅ Agent配置加载成功: " +
                config.getAgentMetadata().getAgentName() + " v" +
                config.getAgentMetadata().getAgentVersion());
    }

    // ==================== 元信息测试 ====================

    @Test
    @Order(1)
    @DisplayName("验证 Agent 元信息")
    void testAgentMetadata() {
        AgentConfig.AgentMetadata meta = config.getAgentMetadata();
        assertNotNull(meta, "元信息不能为空");
        assertEquals("AutoDevRagAgent", meta.getAgentName());
        assertEquals("1.0.0", meta.getAgentVersion());
        assertNotNull(meta.getDescription(), "描述不能为空");
        assertTrue(meta.getDescription().contains("自愈"), "描述应包含'自愈'关键词");
        assertNotNull(meta.getCreatedTimestamp(), "创建时间不能为空");
    }

    // ==================== 感知层测试 ====================

    @Test
    @Order(2)
    @DisplayName("验证感知层传感器配置")
    void testPerceptionSensors() {
        AgentConfig.Perception perception = config.getCoreCapabilities().getPerception();
        assertNotNull(perception, "感知层不能为空");

        List<String> inputTypes = perception.getInputTypes();
        assertNotNull(inputTypes, "输入类型不能为空");
        assertTrue(inputTypes.contains("text"), "应支持文本输入");
        assertTrue(inputTypes.contains("image"), "应支持图片输入");
        assertTrue(inputTypes.contains("system_logs"), "应支持系统日志");

        List<AgentConfig.Sensor> sensors = perception.getSensors();
        assertNotNull(sensors, "传感器列表不能为空");
        assertEquals(4, sensors.size(), "应有4个传感器");

        // 验证 OCR 传感器配置
        AgentConfig.Sensor ocrSensor = sensors.stream()
                .filter(s -> "image_ocr_sensor".equals(s.getSensorId()))
                .findFirst().orElse(null);
        assertNotNull(ocrSensor, "应存在 image_ocr_sensor");
        assertEquals("tesseract_ocr", ocrSensor.getType());
        assertNotNull(ocrSensor.getConfig().get("tesseract_path"), "Tesseract路径不能为空");
        assertEquals("chi_sim+eng", ocrSensor.getConfig().get("lang"));
    }

    // ==================== 认知层测试 ====================

    @Test
    @Order(3)
    @DisplayName("验证认知层规划引擎与知识库")
    void testCognitionLayer() {
        AgentConfig.Cognition cognition = config.getCoreCapabilities().getCognition();
        assertNotNull(cognition, "认知层不能为空");

        // 规划引擎
        AgentConfig.PlanningEngine engine = cognition.getPlanningEngine();
        assertNotNull(engine, "规划引擎不能为空");
        assertEquals("tree_of_thoughts", engine.getType());
        assertNotNull(engine.getConfig(), "引擎配置不能为空");
        assertEquals(5, engine.getConfig().get("max_reason_steps"));
        assertEquals("ollama", engine.getConfig().get("llm_provider"));

        // 知识库
        AgentConfig.KnowledgeBase kb = cognition.getKnowledgeBase();
        assertNotNull(kb, "知识库不能为空");
        assertEquals("vector_db", kb.getType());
        assertTrue(kb.getConnectionString().contains("9200"), "应连接ES 9200端口");
        assertEquals(3, kb.getConfig().get("top_n_retrieve"));
        assertEquals(0.65, kb.getConfig().get("min_similarity_score"));
    }

    // ==================== 执行层测试 ====================

    @Test
    @Order(4)
    @DisplayName("验证执行层工具配置")
    void testActionTools() {
        AgentConfig.Action action = config.getCoreCapabilities().getAction();
        assertNotNull(action, "执行层不能为空");

        List<AgentConfig.ActionTool> tools = action.getActionTools();
        assertNotNull(tools, "工具列表不能为空");
        assertTrue(tools.size() >= 5, "应至少有5个工具");

        // 验证安全命令拦截
        AgentConfig.ActionTool shellTool = tools.stream()
                .filter(t -> "execute_shell_command".equals(t.getToolName()))
                .findFirst().orElse(null);
        assertNotNull(shellTool, "应存在 execute_shell_command 工具");
        assertEquals("windows", shellTool.getParameters().get("os_type"));
        @SuppressWarnings("unchecked")
        List<String> blocked = (List<String>) shellTool.getParameters().get("block_risk_cmd");
        assertTrue(blocked.contains("rm -rf"), "应拦截 rm -rf");

        // 验证文件操作路径白名单
        AgentConfig.ActionTool fileTool = tools.stream()
                .filter(t -> "local_file_operate".equals(t.getToolName()))
                .findFirst().orElse(null);
        assertNotNull(fileTool, "应存在 local_file_operate 工具");
    }

    // ==================== 自愈机制测试 ====================

    @Test
    @Order(5)
    @DisplayName("验证自愈机制健康阈值和生产配置")
    void testSelfHealingThresholds() {
        AgentConfig.SelfHealingMechanism heal = config.getSelfHealingMechanism();
        assertNotNull(heal, "自愈机制不能为空");
        assertTrue(heal.isEnabled(), "自愈应保持启用");

        AgentConfig.HealthCheck hc = heal.getHealthCheck();
        assertNotNull(hc, "健康检查不能为空");
        assertEquals(60, hc.getFrequencySeconds());

        // 验证监控指标
        List<String> metrics = hc.getMetrics();
        assertTrue(metrics.contains("response_time_ms"));
        assertTrue(metrics.contains("es_connect_status"));
        assertTrue(metrics.contains("ollama_connect_status"));

        // 验证阈值配置（开发机配置）
        AgentConfig.Thresholds t = hc.getThresholds();
        assertEquals(5000, t.getResponseTimeMs().getWarning(), 0.01);
        assertEquals(10000, t.getResponseTimeMs().getCritical(), 0.01);
        assertEquals(80, t.getMemoryUsagePercent().getWarning(), 0.01, "开发机内存预警应为80%");
        assertEquals(92, t.getMemoryUsagePercent().getCritical(), 0.01, "开发机内存临界应为92%");
        assertEquals(85, t.getCpuUsagePercent().getWarning(), 0.01);
        assertEquals(95, t.getCpuUsagePercent().getCritical(), 0.01);

        System.out.println("✅ 内存阈值: 预警=" + t.getMemoryUsagePercent().getWarning() +
                "%, 临界=" + t.getMemoryUsagePercent().getCritical() + "%");
    }

    @Test
    @Order(6)
    @DisplayName("验证故障诊断步骤完整性")
    void testDiagnosisSteps() {
        AgentConfig.Diagnosis diagnosis = config.getSelfHealingMechanism().getDiagnosis();
        assertNotNull(diagnosis, "诊断配置不能为空");

        List<AgentConfig.DiagnosisStep> steps = diagnosis.getDiagnosisSteps();
        assertEquals(4, steps.size(), "应有4个诊断步骤");

        // 验证 Ollama 连通性检测
        AgentConfig.DiagnosisStep step1 = steps.get(0);
        assertEquals("ping_dependency", step1.getAction());
        assertEquals("ollama", step1.getParameters().get("service_name"));

        // 验证 ES 连通性检测
        AgentConfig.DiagnosisStep step2 = steps.get(1);
        assertEquals("elasticsearch", step2.getParameters().get("service_name"));
    }

    @Test
    @Order(7)
    @DisplayName("验证修复策略")
    void testRemediationStrategies() {
        List<AgentConfig.RemediationStrategy> strategies =
                config.getSelfHealingMechanism().getRemediationStrategies();
        assertNotNull(strategies, "修复策略不能为空");
        assertEquals(4, strategies.size(), "应有4个修复策略");

        // 验证重启策略
        AgentConfig.RemediationStrategy restart = strategies.stream()
                .filter(s -> "restart_agent_process".equals(s.getStrategyId()))
                .findFirst().orElse(null);
        assertNotNull(restart, "应存在重启策略");
        assertNotNull(restart.getAction(), "策略动作不能为空");
        assertNotNull(restart.getRollbackPlan(), "兜底方案不能为空");

        // 验证清缓存策略
        AgentConfig.RemediationStrategy clear = strategies.stream()
                .filter(s -> "clear_temp_cache".equals(s.getStrategyId()))
                .findFirst().orElse(null);
        assertNotNull(clear, "应存在清缓存策略");
        assertEquals("function_call", clear.getAction().getType());
    }

    // ==================== 自升级机制测试 ====================

    @Test
    @Order(8)
    @DisplayName("验证自升级已禁用且策略为手动")
    void testUpgradeDisabled() {
        AgentConfig.SelfUpgradeMechanism upgrade = config.getSelfUpgradeMechanism();
        assertNotNull(upgrade, "升级机制不能为空");
        assertFalse(upgrade.isEnabled(), "自升级应已禁用");

        AgentConfig.UpgradePolicy policy = upgrade.getUpgradePolicy();
        assertNotNull(policy, "升级策略不能为空");
        assertEquals("manual", policy.getType(), "生产环境应为手动模式");
        assertTrue(policy.isApprovalRequired(), "应需人工审批");

        System.out.println("✅ 升级策略: " + policy.getType() +
                ", 审批: " + (policy.isApprovalRequired() ? "需要" : "不需要"));
    }

    @Test
    @Order(9)
    @DisplayName("验证升级流程步骤完整性")
    void testUpgradeProcess() {
        AgentConfig.UpgradeProcess process =
                config.getSelfUpgradeMechanism().getUpgradeProcess();
        assertNotNull(process, "升级流程不能为空");
        assertEquals(7, process.getSteps().size(), "应有7个升级步骤");
        assertTrue(process.isRollbackOnFailure(), "应支持失败回滚");

        // 验证关键步骤
        List<AgentConfig.UpgradeStep> steps = process.getSteps();
        assertTrue(steps.get(0).getDescription().contains("Git"), "第一步应为Git拉取");
        assertTrue(steps.get(3).getDescription().contains("mvn"), "第四步应为Maven打包");
        assertTrue(steps.get(6).getDescription().contains("健康"), "最后一步应为健康检测");
    }

    // ==================== 通信协议测试 ====================

    @Test
    @Order(10)
    @DisplayName("验证通信协议配置")
    void testCommunicationProtocol() {
        AgentConfig.CommunicationProtocol comm = config.getCommunicationProtocol();
        assertNotNull(comm, "通信协议不能为空");
        assertEquals("json", comm.getMessageFormat());

        AgentConfig.AuthConfig auth = comm.getAuthentication();
        assertNotNull(auth, "认证配置不能为空");
        assertEquals("api_key", auth.getType());
        assertNotNull(auth.getCredentials().get("api_key"), "API Key不能为空");

        System.out.println("✅ API端点: " + comm.getApiEndpoint());
    }

    // ==================== ConfigService 集成测试 ====================

    @Test
    @Order(11)
    @DisplayName("验证 AgentConfigService 方法")
    void testAgentConfigService() {
        assertNotNull(configService.getConfig(), "Service返回的配置不能为空");

        assertFalse(configService.isUpgradeEnabled(), "自升级应为禁用");
        assertTrue(configService.isSelfHealingEnabled(), "自愈应为启用");
        assertEquals("manual", configService.getUpgradePolicyType(), "策略应为manual");

        System.out.println("✅ ConfigService 验证通过");
    }

    // ==================== 完整性检查 ====================

    @Test
    @Order(12)
    @DisplayName("验证配置完整性（所有顶层节点非空）")
    void testConfigIntegrity() {
        assertNotNull(config.getAgentMetadata(), "agent_metadata 不能为空");
        assertNotNull(config.getCoreCapabilities(), "core_capabilities 不能为空");
        assertNotNull(config.getSelfHealingMechanism(), "self_healing_mechanism 不能为空");
        assertNotNull(config.getSelfUpgradeMechanism(), "self_upgrade_mechanism 不能为空");
        assertNotNull(config.getCommunicationProtocol(), "communication_protocol 不能为空");
    }

    @AfterAll
    static void tearDown() {
        System.out.println("\n========== Agent配置测试全部完成 ==========");
    }
}
