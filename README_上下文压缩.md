# 上下文压缩功能说明

## 📋 功能概述

上下文压缩服务（Context Compression Service）实现了智能的历史对话压缩机制，当对话达到一定长度时自动提取有价值信息进行保留，避免上下文过长影响AI回答质量。

## 🎯 核心功能

### 1. 智能压缩触发
- **消息数量阈值**：当对话消息数 ≥ 20条时触发压缩
- **字符数阈值**：当对话总字符数 ≥ 15000字符时触发压缩
- **手动触发**：支持通过API手动触发压缩

### 2. AI驱动的内容提取
使用AI模型智能分析对话内容，提取以下关键信息：
- 🔧 **技术问题与解决方案**：错误排查、修复方案
- 💻 **代码与技术要点**：代码示例、技术实现细节
- 🎯 **关键决策与结论**：架构决策、技术选型
- ⚠️ **待处理事项**：未完成的任务、后续计划

### 3. 结构化摘要输出
压缩后的摘要按主题分类整理，格式清晰，便于后续对话引用。

## 🔌 API接口

### 1. 检查是否需要压缩
```http
GET /api/dev-ai/context/should-compress?sessionId={sessionId}
```

**返回示例：**
```json
{
  "code": 200,
  "data": {
    "sessionId": "abc123",
    "shouldCompress": true,
    "messageCount": 25,
    "totalChars": 18500,
    "thresholdMessages": 20,
    "thresholdChars": 15000
  }
}
```

### 2. 手动触发压缩
```http
POST /api/dev-ai/context/compress?sessionId={sessionId}
```

**返回示例：**
```json
{
  "code": 200,
  "data": {
    "sessionId": "abc123",
    "originalMessageCount": 25,
    "compressedSummary": "### 📋 对话摘要\n\n#### 🔧 技术问题与解决方案\n- ...",
    "compressionStats": {
      "sessionId": "abc123",
      "originalMessageCount": 25,
      "compressedMessageCount": 1,
      "compressionRatio": "96.0%",
      "lastCompressionTime": 1720567890123,
      "hasCompressedSummary": true
    }
  }
}
```

### 3. 获取压缩统计
```http
GET /api/dev-ai/context/stats?sessionId={sessionId}
```

### 4. 清除压缩状态
```http
DELETE /api/dev-ai/context/state?sessionId={sessionId}
```

## 🏗️ 技术架构

### 核心组件

#### 1. ContextCompressionService
- **位置**: `src/main/java/com/devai/devaiplatform/service/ContextCompressionService.java`
- **职责**: 
  - 监控对话长度
  - 调用AI生成压缩摘要
  - 管理压缩状态

#### 2. 集成点
- **DevAgentService**: 在对话流程中集成压缩检查
- **DevAiController**: 提供REST API接口
- **ChatHistoryService**: 获取历史对话数据

### 压缩流程

```
用户发送消息
    ↓
检查消息数量/字符数
    ↓
是否达到阈值？ ──否──→ 正常对话
    ↓是
构建对话内容字符串
    ↓
调用AI生成压缩摘要
    ↓
保存压缩状态
    ↓
返回压缩后的上下文
```

## ⚙️ 配置参数

在 `ContextCompressionService.java` 中可调整以下参数：

```java
// 最大消息数阈值
private static final int MAX_CONTEXT_MESSAGES = 20;

// 最大字符数阈值  
private static final int MAX_CONTEXT_CHARS = 15000;

// 压缩摘要最大长度
private static final int COMPRESSED_SUMMARY_MAX_LENGTH = 2000;
```

## 📊 使用场景

### 场景1：长对话自动压缩
当用户与AI进行长时间的技术讨论（如项目架构设计），对话超过20条消息后，系统自动提示或执行压缩，保留关键技术决策和代码示例。

### 场景2：手动清理上下文
用户在切换话题前，可以手动触发压缩，将之前的对话精华保留，同时释放上下文空间。

### 场景3：会话恢复
当用户重新打开一个历史会话时，系统可以使用压缩后的摘要快速恢复上下文，而不需要加载所有历史消息。

## 🎨 压缩摘要格式示例

```markdown
### 📋 对话摘要

#### 🔧 技术问题与解决方案
- [Spring Bean循环依赖]: 使用@Lazy注解延迟加载解决
- [SQL查询性能慢]: 添加复合索引(user_id, create_time)优化

#### 💻 代码与技术要点
- [UserService重构]: 采用策略模式替代if-else分支
- [Redis缓存策略]: 使用LRU淘汰策略，过期时间30分钟

#### 🎯 关键决策与结论
- [数据库选型]: 选择PostgreSQL而非MySQL，因为需要JSONB支持
- [前端框架]: 确定使用Vue3 + TypeScript组合

#### ⚠️ 待处理事项
- [单元测试]: UserService的测试覆盖率需提升到80%
- [文档更新]: API文档需要同步更新新增的接口
```

## 🔍 与现有功能的集成

### 1. 与记忆蒸馏的关系
- **上下文压缩**：针对单次会话的临时压缩，保留在当前会话中
- **记忆蒸馏**：将高价值内容永久保存到记忆库，跨会话可用
- **协同工作**：压缩后的摘要可以作为蒸馏的输入，提高蒸馏质量

### 2. 与RAG检索的结合
压缩后的摘要可以注入到RAG检索的上下文中，帮助AI更好地理解历史背景。

## 📝 注意事项

1. **压缩时机**：建议在对话自然停顿时进行压缩，避免打断用户思路
2. **摘要质量**：AI生成的摘要需要人工审核，确保关键信息未丢失
3. **性能考虑**：压缩过程会调用AI模型，可能需要几秒钟时间
4. **存储开销**：压缩状态保存在内存中，重启后会丢失（可扩展为持久化）

## 🚀 未来优化方向

1. **增量压缩**：只压缩新增的消息，而不是全部重新压缩
2. **多级压缩**：支持不同粒度的压缩（简要/详细）
3. **用户反馈**：允许用户标记哪些信息必须保留
4. **自动触发**：在对话过程中智能判断最佳压缩时机
5. **持久化存储**：将压缩状态保存到数据库，支持跨设备同步

## 🐛 故障排查

### 问题1：压缩失败
**现象**：API返回"上下文压缩失败"
**原因**：AI模型调用失败或网络问题
**解决**：检查AI服务配置和网络连接

### 问题2：压缩后信息丢失
**现象**：重要技术细节在压缩后消失
**原因**：AI未能正确识别关键信息
**解决**：调整Prompt模板，增加关键信息识别规则

### 问题3：压缩状态不更新
**现象**：多次压缩但统计信息不变
**原因**：会话ID不一致或状态未正确保存
**解决**：确认使用相同的sessionId，检查日志输出
