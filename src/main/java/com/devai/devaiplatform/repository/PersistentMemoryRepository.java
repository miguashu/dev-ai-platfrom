package com.devai.devaiplatform.repository;

import com.devai.devaiplatform.entity.PersistentMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PersistentMemoryRepository extends JpaRepository<PersistentMemory, Long> {

    /** 按分类查询 */
    List<PersistentMemory> findByCategoryOrderByCreateTimeDesc(String category);

    /** 按重要性降序 */
    List<PersistentMemory> findAllByOrderByImportanceDesc();

    /** 关键词模糊搜索（标题+内容） */
    @Query("SELECT m FROM PersistentMemory m WHERE m.title LIKE %:keyword% OR m.content LIKE %:keyword%")
    List<PersistentMemory> searchByKeyword(String keyword);
}
