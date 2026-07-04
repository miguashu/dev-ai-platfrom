package com.devai.devaiplatform.repository;

import com.devai.devaiplatform.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    /** 按更新时间倒序查询所有会话 */
    List<ChatSession> findAllByOrderByUpdateTimeDesc();

    /** 按类型查询 */
    List<ChatSession> findBySessionTypeOrderByUpdateTimeDesc(String sessionType);
}
