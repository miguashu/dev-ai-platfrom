package com.devai.devaiplatform.config.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Agent全局配置根对象，对应 agent-config.json 结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentConfig {

    @JsonProperty("agent_metadata")
    private AgentMetadata agentMetadata;

    @JsonProperty("core_capabilities")
    private CoreCapabilities coreCapabilities;

    @JsonProperty("self_healing_mechanism")
    private SelfHealingMechanism selfHealingMechanism;

    @JsonProperty("self_upgrade_mechanism")
    private SelfUpgradeMechanism selfUpgradeMechanism;

    @JsonProperty("communication_protocol")
    private CommunicationProtocol communicationProtocol;

    // ==================== 子结构定义 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentMetadata {
        @JsonProperty("agent_name")
        private String agentName;

        @JsonProperty("agent_version")
        private String agentVersion;

        private String description;

        @JsonProperty("created_timestamp")
        private String createdTimestamp;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CoreCapabilities {
        private Perception perception;
        private Cognition cognition;
        private Action action;
    }

    // ==================== 感知层 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Perception {
        private String description;

        @JsonProperty("input_types")
        private List<String> inputTypes;

        private List<Sensor> sensors;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sensor {
        @JsonProperty("sensor_id")
        private String sensorId;

        private String type;

        private Map<String, Object> config;
    }

    // ==================== 认知层 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cognition {
        private String description;

        @JsonProperty("planning_engine")
        private PlanningEngine planningEngine;

        @JsonProperty("knowledge_base")
        private KnowledgeBase knowledgeBase;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanningEngine {
        private String type;

        private Map<String, Object> config;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KnowledgeBase {
        private String type;

        @JsonProperty("connection_string")
        private String connectionString;

        private Map<String, Object> config;
    }

    // ==================== 执行层 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {
        private String description;

        @JsonProperty("action_tools")
        private List<ActionTool> actionTools;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionTool {
        @JsonProperty("tool_name")
        private String toolName;

        private Map<String, Object> parameters;
    }

    // ==================== 自愈机制 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SelfHealingMechanism {
        private boolean enabled;

        @JsonProperty("health_check")
        private HealthCheck healthCheck;

        private Diagnosis diagnosis;

        @JsonProperty("remediation_strategies")
        private List<RemediationStrategy> remediationStrategies;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HealthCheck {
        @JsonProperty("frequency_seconds")
        private int frequencySeconds;

        private List<String> metrics;

        private Thresholds thresholds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Thresholds {
        @JsonProperty("response_time_ms")
        private ThresholdLevel responseTimeMs;

        @JsonProperty("error_rate")
        private ThresholdLevel errorRate;

        @JsonProperty("memory_usage_percent")
        private ThresholdLevel memoryUsagePercent;

        @JsonProperty("cpu_usage_percent")
        private ThresholdLevel cpuUsagePercent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ThresholdLevel {
        private double warning;
        private double critical;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Diagnosis {
        private String description;

        @JsonProperty("diagnosis_steps")
        private List<DiagnosisStep> diagnosisSteps;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiagnosisStep {
        @JsonProperty("step_id")
        private String stepId;

        private String description;
        private String action;

        private Map<String, Object> parameters;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemediationStrategy {
        @JsonProperty("strategy_id")
        private String strategyId;

        private String description;

        @JsonProperty("trigger_condition")
        private String triggerCondition;

        private RemAction action;

        @JsonProperty("rollback_plan")
        private String rollbackPlan;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemAction {
        private String type;
        private String command;

        @JsonProperty("function_name")
        private String functionName;
    }

    // ==================== 自升级机制 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SelfUpgradeMechanism {
        private boolean enabled;

        @JsonProperty("upgrade_policy")
        private UpgradePolicy upgradePolicy;

        private UpgradeSource source;

        @JsonProperty("upgrade_triggers")
        private List<UpgradeTrigger> upgradeTriggers;

        @JsonProperty("upgrade_process")
        private UpgradeProcess upgradeProcess;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpgradePolicy {
        private String type;

        @JsonProperty("approval_required")
        private boolean approvalRequired;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpgradeSource {
        private String type;
        private String url;
        private String branch;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpgradeTrigger {
        @JsonProperty("trigger_id")
        private String triggerId;

        private String description;

        @JsonProperty("frequency_seconds")
        private Long frequencySeconds;

        private String action;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpgradeProcess {
        private List<UpgradeStep> steps;

        @JsonProperty("rollback_on_failure")
        private boolean rollbackOnFailure;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpgradeStep {
        private String step;
        private String description;
    }

    // ==================== 通信协议 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommunicationProtocol {
        @JsonProperty("api_endpoint")
        private String apiEndpoint;

        @JsonProperty("message_format")
        private String messageFormat;

        private AuthConfig authentication;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthConfig {
        private String type;
        private Map<String, Object> credentials;
    }
}
