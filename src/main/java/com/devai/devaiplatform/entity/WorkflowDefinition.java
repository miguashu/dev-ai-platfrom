package com.devai.devaiplatform.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 工作流定义实体
 */
@Entity
@Table(name = "agent_workflow")
@Data
public class WorkflowDefinition {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private String workflowId;  // 工作流唯一标识
    
    @Column(nullable = false)
    private String name;        // 工作流名称
    
    private String description; // 描述
    
    @Column(columnDefinition = "TEXT")
    private String nodesJson;   // 节点列表 JSON
    
    @Column(columnDefinition = "TEXT")
    private String edgesJson;   // 连线列表 JSON
    
    @Column(columnDefinition = "TEXT")
    private String configJson;  // 全局配置 JSON
    
    private Boolean enabled = true;  // 是否启用
    
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    /**
     * 节点定义
     */
    @Data
    public static class WorkflowNode {
        private String id;              // 节点ID
        private String type;            // 节点类型: AGENT/TOOL/CONDITION/MERGE/START/END
        private String agentType;       // Agent类型: RESEARCH/CODE/FILE/PROJECT/DIRECT
        private String toolName;        // 工具名称（当type=TOOL时）
        private Map<String, Object> config;  // 节点配置
        private Integer x;              // 画布X坐标
        private Integer y;              // 画布Y坐标
        private String label;           // 显示标签
    }
    
    /**
     * 连线定义
     */
    @Data
    public static class WorkflowEdge {
        private String id;              // 连线ID
        private String source;          // 源节点ID
        private String target;          // 目标节点ID
        private String condition;       // 条件表达式（可选，用于分支）
        private String label;           // 显示标签
    }
}
