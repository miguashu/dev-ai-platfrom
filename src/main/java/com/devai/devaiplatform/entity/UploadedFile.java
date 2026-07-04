package com.devai.devaiplatform.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 已上传文件表 — 记录所有上传到知识库的文件
 */
@Entity
@Table(name = "uploaded_file", indexes = {
    @Index(name = "idx_file_source", columnList = "source")
})
public class UploadedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 磁盘上的唯一文件名（带时间戳） */
    @Column(name = "file_name", length = 300)
    private String fileName;

    /** 用户上传时的原始文件名 */
    @Column(name = "original_name", length = 300)
    private String originalName;

    /** 文件存储路径 */
    @Column(name = "file_path", length = 500)
    private String filePath;

    /** 文件大小（字节） */
    @Column(name = "file_size")
    private Long fileSize;

    /** 来源: single_upload / ocr_batch */
    @Column(length = 32)
    private String source;

    /** 向量库入库状态: pending / ingested / failed */
    @Column(length = 16)
    private String ingestStatus;

    /** 入库的向量片段数 */
    @Column(name = "segment_count")
    private Integer segmentCount;

    /** 创建时间 */
    @Column(name = "create_time")
    private LocalDateTime createTime;

    // ===== Getters & Setters =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getIngestStatus() { return ingestStatus; }
    public void setIngestStatus(String ingestStatus) { this.ingestStatus = ingestStatus; }

    public Integer getSegmentCount() { return segmentCount; }
    public void setSegmentCount(Integer segmentCount) { this.segmentCount = segmentCount; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
