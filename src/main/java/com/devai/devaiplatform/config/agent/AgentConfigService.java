package com.devai.devaiplatform.config.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * Agent配置加载服务，启动时自动读取 agent-config.json
 * 将配置作为Spring Bean注入到其他服务中使用
 */
@Service
public class AgentConfigService {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigService.class);

    private static final String CONFIG_FILE = "agent-config.json";

    private AgentConfig config;

    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource(CONFIG_FILE);
            if (!resource.exists()) {
                log.warn("Agent配置文件不存在: {}，使用默认空配置", CONFIG_FILE);
                this.config = new AgentConfig();
                return;
            }

            try (InputStream is = resource.getInputStream()) {
                ObjectMapper mapper = new ObjectMapper();
                this.config = mapper.readValue(is, AgentConfig.class);
                log.info("Agent配置加载成功: agent_name={}, version={}",
                        config.getAgentMetadata() != null ? config.getAgentMetadata().getAgentName() : "unknown",
                        config.getAgentMetadata() != null ? config.getAgentMetadata().getAgentVersion() : "unknown");
            }
        } catch (Exception e) {
            log.error("加载Agent配置文件失败: {}", e.getMessage(), e);
            this.config = new AgentConfig();
        }
    }

    public AgentConfig getConfig() {
        return config;
    }

    /**
     * 检查自升级是否启用
     */
    public boolean isUpgradeEnabled() {
        return config.getSelfUpgradeMechanism() != null
                && config.getSelfUpgradeMechanism().isEnabled();
    }

    /**
     * 检查自愈是否启用
     */
    public boolean isSelfHealingEnabled() {
        return config.getSelfHealingMechanism() != null
                && config.getSelfHealingMechanism().isEnabled();
    }

    /**
     * 获取升级策略类型
     */
    public String getUpgradePolicyType() {
        if (config.getSelfUpgradeMechanism() != null
                && config.getSelfUpgradeMechanism().getUpgradePolicy() != null) {
            return config.getSelfUpgradeMechanism().getUpgradePolicy().getType();
        }
        return "manual";
    }
}
