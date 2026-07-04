package com.devai.devaiplatform.repository;

import com.devai.devaiplatform.entity.UploadedFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UploadedFileRepository extends JpaRepository<UploadedFile, Long> {

    /** 按文件名查找 */
    UploadedFile findByFileName(String fileName);

    /** 按来源查询 */
    List<UploadedFile> findBySourceOrderByCreateTimeDesc(String source);

    /** 按入库状态查询 */
    List<UploadedFile> findByIngestStatus(String ingestStatus);

    /** 按创建时间倒序 */
    List<UploadedFile> findAllByOrderByCreateTimeDesc();
}
