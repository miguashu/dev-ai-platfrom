package com.devai.devaiplatform.repository;

import com.devai.devaiplatform.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 按会话ID查询所有消息，按序号升序 */
    List<ChatMessage> findBySessionIdOrderBySeqIndexAsc(String sessionId);

    /** 删除会话下所有消息 */
    void deleteBySessionId(String sessionId);

    /** 统计会话消息数 */
    long countBySessionId(String sessionId);
}
