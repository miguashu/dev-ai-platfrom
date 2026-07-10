package com.devai.devaiplatform.repository;

import com.devai.devaiplatform.entity.RagFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RagFeedbackRepository extends JpaRepository<RagFeedback, Long> {

    /** 按时间倒序获取所有反馈 */
    List<RagFeedback> findAllByOrderByCreateTimeDesc();

    /** 按评分筛选反馈 */
    List<RagFeedback> findByScoreLessThanEqualOrderByCreateTimeDesc(Integer score);

    /** 查找未应用优化的低分反馈 */
    List<RagFeedback> findByScoreLessThanEqualAndOptimizationAppliedFalseOrderByCreateTimeDesc(Integer score);
}
