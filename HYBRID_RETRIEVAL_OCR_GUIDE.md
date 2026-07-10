# 混合检索 + OCR 图片PDF识别功能使用指南

## 功能概述

本系统为 RAG 架构提供了以下核心能力：

1. **混合检索（Hybrid Retrieval）**：Elasticsearch BM25 关键词检索 + HNSW KNN 向量语义检索 + RRF 融合排序
2. **PDF 全面数据提取**：文本提取（三层降级容错）+ 表格提取（tabula-java → JSON）+ 图片提取 → OCR 文字识别
3. **OCR 图片PDF识别**：自动处理扫描版PDF、图片型PDF，支持中英文混合识别
4. **动态参数控制**：根据查询复杂度自动调整检索策略，简单查询快速响应，复杂查询深度召回

---

## 一、混合检索功能

### 1.1 架构总览

```
用户提问
   ↓
【动态参数分析】RetrievalParams.analyze()
   - 评估查询复杂度: SIMPLE / MODERATE / COMPLEX
   - 自动调整: topK / minScore / ef_search / fuzziness / boost权重
   
   ↓ （ES模式）
【策略1】BM25 关键词检索               【策略2】HNSW KNN 向量检索
   - ES DSL match_query 走倒排索引        - ES DSL knn query 利用 HNSW 索引
   - 支持模糊匹配（fuzziness 动态控制）    - 动态 ef_search 控制精度-速度平衡
   - minimum_should_match=70%             - numCandidates 动态扩展
   
   ↓                                        ↓
【RRF 融合排序】Reciprocal Rank Fusion
   - score(d) = Σ boost_i / (k + rank_i(d))
   - k=60（标准常数）
   - BM25 boost + KNN boost 动态加权
   - 去重 + 按 RRF 分数降序 Top-K
   
   ↓
AI 生成答案（带引用标注）
```

### 1.2 核心组件

| 组件 | 类名 | 职责 |
|------|------|------|
| 混合检索服务 | `HybridRetrievalService` | BM25 + KNN 并行检索 + RRF 融合 |
| 动态参数 | `RetrievalParams` | 根据查询复杂度自动调整检索参数 |
| ES向量存储 | `ElasticsearchVectorStoreService` | ES索引管理、HNSW mapping、RestClient |
| 向量存储网关 | `VectorStoreService` | 统一接口，自动判断 ES/内存模式 |
| PDF全面提取 | `PdfDataExtractor` | 文本 + 表格 + 图片OCR 三位一体提取 |

### 1.3 动态参数配置

`RetrievalParams.analyze()` 根据查询长度和句子数自动分为三个等级：

| 参数 | SIMPLE（≤20字, ≤1句） | MODERATE（≤80字, ≤3句） | COMPLEX（长文本） |
|------|----------------------|------------------------|-------------------|
| topK | 5 | 8 | 12 |
| minScore | 0.25 | 0.1 | 0.0 |
| bm25Boost | 1.0 | 1.2 | 1.5 |
| knnBoost | 1.0 | 1.0 | 1.0 |
| efSearch | 50 | 100 | 200 |
| numCandidates | 20 | 50 | 100 |
| fuzziness | 0（精确） | 1（允许1字符差异） | 1 |

### 1.4 HNSW 索引配置

通过 `application.properties` 配置：

```properties
# HNSW 索引参数（仅在新建索引时生效）
retrieval.hnsw.m=16                    # 每个节点的最大连接数
retrieval.hnsw.ef-construction=128     # 构建时的搜索宽度
retrieval.hnsw.dims=768                # 向量维度（nomic-embed-text）
```

ES 索引 Mapping 结构：

```json
{
  "properties": {
    "vector": {
      "type": "dense_vector",
      "dims": 768,
      "index": true,
      "similarity": "cosine",
      "index_options": {
        "type": "hnsw",
        "m": 16,
        "ef_construction": 128
      }
    },
    "text": { "type": "text", "analyzer": "standard" },
    "metadata": { "type": "object" }
  }
}
```

### 1.5 RRF 融合算法

**Reciprocal Rank Fusion** 公式：

```
score(d) = Σ boost_i / (k + rank_i(d))
```

- `k = 60`（标准常数，平滑排名差异）
- `rank` 从 0 开始
- `boost` 为各路检索的权重系数（由动态参数控制）
- 优势：不需要分数归一化，只依赖排名，对 BM25 和 KNN 不同评分体系天然兼容

### 1.6 降级策略

| 场景 | 处理方式 |
|------|---------|
| 内存模式（非ES） | 自动降级为纯向量检索（EmbeddingStoreContentRetriever） |
| ES 检索异常 | catch 后降级为纯向量检索 |
| 单路检索失败 | 另一路结果仍然有效，RRF 只融合可用结果 |

### 1.7 使用示例

```bash
# RAG 问答（自动执行混合检索）
POST http://localhost:8081/api/dev-ai/rag/query
Content-Type: application/x-www-form-urlencoded

question=Spring Boot如何配置数据库连接池？
```

控制台输出：

```
========== 用户提问: Spring Boot如何配置数据库连接池？ ==========
[混合检索] 动态参数: RetrievalParams{level=SIMPLE, topK=5, minScore=0.25, bm25Boost=1.0, knnBoost=1.0, ef=50, candidates=20, fuzz=0}
[RRF] 融合: BM25=3条(boost=1.0), KNN=5条(boost=1.0), k=60
[混合检索] BM25命中: 3 条, KNN命中: 5 条
[混合检索] ✅ 融合后 5 条, 耗时 120ms

【混合检索结果】共检索到 5 条相关内容:
[1] 来源: 《Spring Boot实战》 [层级: L2_NORMAL]
    内容: 第5章 数据访问 - 5.3 连接池配置...
```

---

## 二、PDF 全面数据提取

### 2.1 功能特性

`PdfDataExtractor` 提供三大提取能力，将所有数据合并为统一 JSON 结构：

1. **文本提取**（三层降级容错）
   - Round 1: 标准提取（按位置排序，保留段落结构）
   - Round 2: 降级提取（关闭格式增强和字体替换）
   - Round 3: 逐页提取（单页失败只跳过该页）

2. **表格提取** → JSON（基于 tabula-java）
   - Nurminen 检测算法自动识别表格区域
   - 自动提取表头 + 数据行
   - 输出 JSON 格式（key=表头, value=数据）

3. **图片提取** → OCR 文字识别
   - PDFBox 提取嵌入图片
   - 保存到临时目录（`./uploads/temp/images/`）
   - Tesseract OCR 识别图片中的文字

### 2.2 数据合并流程

```
PDF文件
   ↓
[步骤1] 文本提取 → fullText
[步骤2] 表格提取 → List<ExtractedTable> → JSON
[步骤3] 图片提取 → List<ExtractedImage> → OCR文字
   ↓
[合并] fullText + 表格JSON + 图片OCR文字 → mergedText
   ↓
三级层级分片 → 向量化入库
```

### 2.3 三级层级分片

文档入库时自动进行三级分片，不同粒度覆盖不同检索场景：

| 层级 | 标签 | Chunk大小 | 重叠 | 用途 |
|------|------|----------|------|------|
| Level 1 | L1_SUMMARY | 2000字符 | 200 | 理解文档结构和主题 |
| Level 2 | L2_NORMAL | 800字符 | 150 | 常规检索用 |
| Level 3 | L3_PRECISE | 300字符 | 80 | 精确匹配用 |

---

## 三、OCR 图片PDF识别

### 3.1 功能特性

- **智能检测**：自动判断 PDF 类型（文本型 vs 图片型），启发式规则：文件大小 > 500KB 且文本 < 100字符
- **双重提取**：优先直接提取，失败则启动 OCR
- **中英文识别**：支持简体中文 + 英文混合识别（`chi_sim+eng`）
- **批量处理**：一次性处理整个文件夹的 PDF
- **逐页识别**：对多页 PDF 逐页渲染为 300 DPI 图片并 OCR
- **图片预处理**：灰度转换 + 二值化处理，提升识别准确率

### 3.2 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Tess4J | 5.10.0 | Tesseract OCR 引擎 Java 封装 |
| Apache PDFBox | 2.0.32 | PDF 渲染为图片 + 文本提取 |
| tabula-java | 1.0.5 | PDF 表格自动检测与提取 |
| 渲染精度 | 300 DPI | 高精度渲染，保证识别准确率 |

### 3.3 安装 OCR 语言数据

在使用 OCR 功能前，需要下载语言数据包：

```powershell
# 1. 创建 tessdata 目录
mkdir ./tessdata

# 2. 下载语言数据文件
# 从 https://github.com/tesseract-ocr/tessdata 下载

# 中文简体
# 下载 chi_sim.traineddata 放入 tessdata/

# 英文
# 下载 eng.traineddata 放入 tessdata/
```

在 `application.properties` 中配置路径：

```properties
# Tesseract OCR 语言数据路径
tesseract.data-path=D:/ruanjian/tesseract/tessdata
```

### 3.4 使用方式

#### 方式1：自动处理（推荐）

上传 PDF 时，`PdfDataExtractor` 会自动判断是否需要 OCR：

```bash
# 上传单个PDF文件（自动提取文本+表格+图片OCR）
POST http://localhost:8081/api/dev-ai/lib/upload-file
Content-Type: multipart/form-data

file=扫描版技术手册.pdf
```

#### 方式2：文件夹批量加载

```bash
# 批量加载文件夹内PDF到知识库
POST http://localhost:8081/api/dev-ai/lib/upload-doc
Content-Type: application/x-www-form-urlencoded

folderPath=./docs/technical
```

#### 方式3：手动调用 OCR 批量处理

```bash
# 批量处理文件夹（OCR + 自动入库向量库）
POST http://localhost:8081/api/dev-ai/ocr/process-folder
Content-Type: application/x-www-form-urlencoded

folderPath=./docs/scanned_pdfs
```

#### 方式4：OCR 批量上传文件

```bash
# 前端选择多个PDF文件直接上传（OCR处理 + 入库 + 源文件保存）
POST http://localhost:8081/api/dev-ai/ocr/process-files
Content-Type: multipart/form-data

files=file1.pdf, file2.pdf, ...
```

#### 方式5：识别单个图片

```bash
GET http://localhost:8081/api/dev-ai/ocr/recognize-image?imagePath=./images/screenshot.png
```

### 3.5 OCR 处理日志示例

```
[OCR处理] 开始处理PDF: ./docs/扫描版技术手册.pdf
[OCR处理] 直接提取失败或无文本，启动OCR识别...
[OCR处理] PDF共 45 页，开始逐页OCR...
  处理第 1/45 页...
    ✓ 识别成功，本页文字: 523 字符
  处理第 2/45 页...
    ✓ 识别成功，本页文字: 487 字符
  ...
[OCR处理] ✓ OCR完成，总文本长度: 21543 字符
```

### 3.6 性能优化建议

#### 识别速度优化

```java
// OcrService.java 中可调整的参数

// 降低 DPI（速度提升，识别率略降）
BufferedImage image = pdfRenderer.renderImageWithDPI(page, 200);  // 原 300

// 简化页面分割模式
tesseract.setPageSegMode(6);  // 原 3，假设单列文本
```

#### 识别准确率优化

`OcrService.preprocessImage()` 已内置：
- 灰度转换（`TYPE_BYTE_GRAY`）
- 二值化处理（`TYPE_BYTE_BINARY`）

### 3.7 常见问题

**Q1: OCR 识别速度慢？**
- A: 正常现象，一页 300 DPI 约需 2-5 秒
- 建议：批量处理时耐心等待，或降低 DPI

**Q2: 识别准确率低？**
- A: 检查以下几点：
  1. 确保下载了正确的语言数据（chi_sim / eng）
  2. PDF 清晰度足够（至少 200 DPI）
  3. 避免严重倾斜或模糊的图片
  4. 确认 `tesseract.data-path` 配置正确

**Q3: 内存溢出？**
- A: 大文件 PDF 逐页处理时会占用较多内存
- 建议：JVM 堆内存设置为 2GB+（当前配置 `-Xms256m -Xmx512m`）

---

## 四、配置说明

### 4.1 Maven 依赖

```xml
<!-- OCR 图片文字识别 -->
<dependency>
    <groupId>net.sourceforge.tess4j</groupId>
    <artifactId>tess4j</artifactId>
    <version>5.10.0</version>
</dependency>

<!-- PDF表格提取（自动识别表格并导出为JSON） -->
<dependency>
    <groupId>technology.tabula</groupId>
    <artifactId>tabula</artifactId>
    <version>1.0.5</version>
</dependency>

<!-- Elasticsearch 向量存储 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-elasticsearch</artifactId>
    <version>0.36.2</version>
</dependency>

<!-- PDF 解析 -->
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>2.0.32</version>
</dependency>
```

### 4.2 关键配置项（application.properties）

```properties
# ======================== Elasticsearch 向量存储 ========================
vector.store.type=elasticsearch
elasticsearch.url=http://127.0.0.1:9200
elasticsearch.index-name=dev-ai-vectors

# ======================== HNSW 索引参数 ========================
retrieval.hnsw.m=16
retrieval.hnsw.ef-construction=128
retrieval.hnsw.dims=768

# ======================== Tesseract OCR ========================
tesseract.data-path=D:/ruanjian/tesseract/tessdata

# ======================== Ollama Embedding ========================
ollama.base-url=http://127.0.0.1:11434
ollama.embedding-model.name=nomic-embed-text

# ======================== 文件上传限制 ========================
file.upload.max-size=52428800
spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=500MB
```

### 4.3 服务依赖关系

```
DevAiController
   ├── DevRagService
   │     ├── HybridRetrievalService    ← 混合检索核心
   │     │     ├── VectorStoreService  ← ES/内存模式网关
   │     │     ├── ElasticsearchVectorStoreService  ← ES操作
   │     │     └── EmbeddingModel      ← Ollama nomic-embed-text
   │     ├── PdfDataExtractor          ← PDF全面提取（文本+表格+图片OCR）
   │     └── OcrService                ← OCR独立服务
   └── MessageRouterService            ← 智能消息路由
```

---

## 五、API 接口汇总

### 知识库管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/dev-ai/lib/upload-file` | 上传单个PDF文件到知识库（带安全校验） |
| POST | `/api/dev-ai/lib/upload-doc` | 批量加载文件夹内PDF到知识库 |
| POST | `/api/dev-ai/rag/query` | RAG 问答（自动混合检索） |

### OCR 处理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/dev-ai/ocr/process-folder` | 批量OCR处理文件夹 |
| POST | `/api/dev-ai/ocr/process-files` | 批量OCR处理上传的PDF文件 |
| GET | `/api/dev-ai/ocr/recognize-image` | OCR识别单个图片 |

### 智能对话

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/dev-ai/agent/context-run` | SSE流式多轮对话（自动智能路由） |
| POST | `/api/dev-ai/chat/ask` | 简单问答接口（支持上下文） |

### 文件预览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/dev-ai/file/preview` | 文件预览/下载（支持PDF等） |

---

## 六、实际应用场景

### 场景1：处理扫描版合同

```bash
# 上传包含扫描图片的合同 PDF
POST /api/dev-ai/lib/upload-file
file=合同扫描件.pdf

# 系统自动：
# 1. PdfDataExtractor 全面提取（文本+表格+图片OCR）
# 2. 检测到图片型内容，Tesseract OCR 逐页识别
# 3. 合并所有数据，三级分片入库
# 4. 支持后续混合检索（BM25 + KNN）
```

### 场景2：技术文档混合检索

```bash
# 用户提问
question=如何优化 MySQL 查询性能？

# 系统检索流程：
# 1. 动态参数分析 → MODERATE（topK=8, fuzziness=1）
# 2. BM25：精确匹配"INDEX"、"EXPLAIN"等术语（倒排索引）
# 3. KNN：语义匹配"查询优化"相关章节（HNSW加速）
# 4. RRF 融合排序：综合返回最优结果
# 5. AI 生成答案 + 引用标注
```

### 场景3：含表格的PDF数据提取

```bash
# 上传包含数据表格的PDF
POST /api/dev-ai/lib/upload-file
file=性能测试报告.pdf

# PdfDataExtractor 自动：
# 1. 提取纯文本内容
# 2. tabula-java 检测并提取表格 → JSON（表头+数据行）
# 3. 提取嵌入图片 → OCR 识别文字
# 4. 合并为完整文本 → 三级分片 → 向量化入库
```

---

## 七、总结

### 核心价值

1. **真混合检索**：
   - ES BM25 走倒排索引（非模拟），KNN 利用 HNSW 索引加速
   - RRF 融合排序，兼顾语义理解和精确匹配
   - 动态参数控制，简单查询快响应，复杂查询深召回

2. **PDF 全面提取**：
   - 文本 + 表格 + 图片 OCR 三位一体
   - 三层降级容错，确保文本提取不中断
   - tabula-java 自动检测表格并结构化输出

3. **OCR 识别**：
   - 自动检测文档类型，智能切换提取策略
   - 中英文混合识别，图片预处理提升准确率
   - 支持单文件/批量/文件夹多种处理模式

### 环境依赖

- **Elasticsearch**：混合检索核心（BM25 + HNSW KNN）
- **Ollama**：本地 Embedding 模型（nomic-embed-text, 768维）
- **Tesseract OCR**：扫描版PDF/图片文字识别（需安装 tessdata）
