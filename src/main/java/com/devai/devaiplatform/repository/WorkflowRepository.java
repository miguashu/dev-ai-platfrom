package com.devai.devaiplatform.repository;

import com.devai.devaiplatform.entity.WorkflowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * 工作流定义 Repository
 */
@Repository
public interface WorkflowRepository extends JpaRepository<WorkflowDefinition, Long> {
    
    Optional<WorkflowDefinition> findByWorkflowId(String workflowId);
    
    List<WorkflowDefinition> findByEnabledTrue();
    
    boolean existsByWorkflowId(String workflowId);
}
