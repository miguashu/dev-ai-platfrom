# 定时批量蒸馏功能使用指南

## 📋 功能概述

定时批量蒸馏功能会自动从每次AI对话中提取有价值的知识，并在每天凌晨2点批量保存为永久记忆。

### 核心优势

1. **自动化**：无需手动调用蒸馏接口，系统自动记录和处理
2. **智能过滤**：基于内容长度、技术关键词、重要性评分自动判断是否保存
3. **定时执行**：每天凌晨2点批量处理，避免影响正常业务性能
4. **持久化存储**：待处理对话实时保存到文件，重启不丢失
5. **高质量摘要**：自动提取关键信息并生成精炼标题

---

## 🔧 配置说明

### 1. 启用定时任务

已在启动类添加 `@EnableScheduling` 注解：

```java
@SpringBootApplication
@EnableScheduling
public class DevAiPlatformApplication {
    // ...
}
```

### 2. 定时任务配置

**默认执行时间**：每天凌晨2点

**Cron表达式**：`0 0 2 * * ?`

如需修改时间，在 `MemoryDistillationScheduler.java` 中调整：

```java
@Scheduled(cron = "0 0 2 * * ?")  // 可自定义cron表达式
public void scheduledDistillation() {
    // ...
}
```

### 3. 蒸馏条件配置

当前配置（可在代码中调整）：

```java
// 最小回答长度
if (record.getAnswer().length() < 200) {
    return false;  // 跳过短回答
}

// 至少包含2个技术关键词
String[] techKeywords = {"代码", "sql", "配置", "优化", "异常", ...};
return keywordCount >= 2;

// 重要性评分阈值
if (importance < 6) {
    return false;  // 只保存高价值内容
}
```

---

## 🚀 使用方法

### 方式一：正常使用（自动记录）

**只需正常调用Agent接口，系统会自动记录：**

```bash
POST http://localhost:8081/api/dev-ai/agent/run
Content-Type: application/x-www-form-urlencoded

task=Spring Boot如何配置多数据源？需要详细的步骤和代码示例
```

**系统会自动：**
1. ✅ 执行任务
2. ✅ 记录对话到待处理队列
3. ✅ 保存到文件（防止重启丢失）
4. ⏰ 等待凌晨2点批量蒸馏

### 方式二：手动触发蒸馏（用于测试）

```bash
POST http://localhost:8081/api/dev-ai/memory/distill-now
```

**立即触发一次批量蒸馏，无需等待定时任务。**

### 方式三：查看待处理数量

```bash
GET http://localhost:8081/api/dev-ai/memory/pending-count
```

**返回：**
```json
{
  "pendingCount": 15
}
```

---

## 📊 工作流程

### 1️⃣ 对话记录阶段

```
用户提问 → Agent执行 → 获取回答
                    ↓
        distillationScheduler.recordConversation()
                    ↓
        保存到内存队列 + 持久化到文件
```

### 2️⃣ 定时蒸馏阶段（凌晨2点）

```
触发定时任务
    ↓
遍历待处理对话队列
    ↓
对每条对话：
  ├─ 判断是否需要蒸馏（shouldDistill）
  ├─ 计算重要性评分（calculateImportance）
  ├─ 生成高质量摘要（generateHighQualitySummary）
  ├─ 分类（categorize）
  ├─ 提取关键词（extractKeywords）
  └─ 保存到永久记忆（memoryService.addMemory）
    ↓
清空已处理的对话
    ↓
输出统计报告
```

---

## 🎯 智能过滤规则

### 过滤条件（满足任一条件则跳过）

| 条件 | 说明 |
|------|------|
| **回答过短** | 回答长度 < 200字符 |
| **简单对话** | 包含"你好"、"谢谢"、"再见"等 |
| **技术含量低** | 技术关键词 < 2个 |
| **重要性不足** | 评分 < 6分 |

### 重要性评分标准

| 加分项 | 分值 |
|--------|------|
| 基础分 | 5分 |
| 包含代码示例 | +2分 |
| 包含SQL优化 | +1分 |
| 包含错误处理 | +1分 |
| 包含最佳实践 | +1分 |
| 回答详细（>1000字） | +1分 |
| **最高分** | **10分** |

### 分类规则

根据内容自动分类为：

- `database` - 数据库相关（SQL、查询等）
- `technical` - 技术实现（代码、类等）
- `problem` - 问题排查（错误、异常等）
- `business` - 业务需求（功能、需求等）
- `testing` - 测试相关（JUnit、Mock等）
- `general` - 其他通用内容

---

## 📝 示例输出

### 定时蒸馏日志

```
========== 开始定时记忆蒸馏 ==========
当前时间: 2026-07-01T02:00:00.000
待处理对话: 25 条

[1/25] 处理: Spring Boot如何配置多数据源？需要详细的步骤和代码示例
  ✓ 已保存 (ID: a1b2c3d4, 重要性: 8/10)

[2/25] 处理: 谢谢你的帮助
  → 跳过（低价值内容）

[3/25] 处理: MySQL查询优化建议
  ✓ 已保存 (ID: e5f6g7h8, 重要性: 7/10)

...

========== 定时蒸馏完成 ==========
总计处理: 25 条
成功保存: 18 条
跳过过滤: 7 条
====================================
```

### 生成的记忆示例

**原始对话：**
```
用户：Spring Boot如何配置多数据源？需要详细的步骤和代码示例

AI：配置多数据源的完整方案：

1. 添加依赖...
2. 配置文件application.yml...
3. 创建DataSource配置类...
@Configuration
public class DataSourceConfig {
    @Bean
    @Primary
    public DataSource primaryDataSource() {
        // ...
    }
}
```

**自动生成的记忆：**
- **标题**：Spring Boot如何配置多数据源？需要详细的步骤和代码示例
- **分类**：technical
- **重要性**：8/10
- **关键词**：spring, boot, 数据源, 配置, datasource
- **内容**：完整的对话内容和代码示例

---

## 🔍 管理接口

### 1. 查看待处理数量

```bash
GET /api/dev-ai/memory/pending-count
```

### 2. 手动触发蒸馏

```bash
POST /api/dev-ai/memory/distill-now
```

### 3. 查看记忆统计

```bash
GET /api/dev-ai/memory/stats
```

返回：
```json
{
  "totalMemories": 156,
  "byCategory": {
    "technical": 45,
    "database": 32,
    "problem": 28,
    "business": 21,
    "general": 30
  },
  "avgImportance": 7.2,
  "totalAccesses": 423
}
```

### 4. 清理低价值记忆

```bash
POST /api/dev-ai/memory/cleanup
```

### 5. 归档旧记忆

```bash
POST /api/dev-ai/memory/archive
```

---

## 💡 最佳实践

### 1. 正常使用场景

```bash
# 正常工作流程，无需额外操作
curl -X POST http://localhost:8081/api/dev-ai/agent/run \
  -d "task=帮我生成用户管理的CRUD代码"

# 系统会自动记录并在凌晨2点蒸馏
```

### 2. 重要知识立即蒸馏

如果希望立即保存重要知识，可以手动触发：

```bash
# 先执行任务
curl -X POST http://localhost:8081/api/dev-ai/agent/run \
  -d "task=分析这个报错日志..."

# 然后立即蒸馏
curl -X POST http://localhost:8081/api/dev-ai/memory/distill-now
```

### 3. 定期维护

每周检查一次记忆库健康状态：

```bash
# 查看待处理数量
curl http://localhost:8081/api/dev-ai/memory/pending-count

# 查看记忆统计
curl http://localhost:8081/api/dev-ai/memory/stats

# 清理低价值记忆
curl -X POST http://localhost:8081/api/dev-ai/memory/cleanup
```

---

## 🛠️ 故障排查

### 问题1：定时任务未执行

**检查：**
1. 确认启动类有 `@EnableScheduling` 注解
2. 查看应用日志是否有定时任务相关日志
3. 检查服务器时间是否正确

**解决：**
```bash
# 手动触发测试
curl -X POST http://localhost:8081/api/dev-ai/memory/distill-now
```

### 问题2：对话未被记录

**检查：**
1. 确认 `DevAgentService.runDevTask()` 被正确调用
2. 查看控制台是否有 `[对话记录] 已记录` 日志
3. 检查 `./agent_memory/pending_conversations.json` 文件是否存在

**解决：**
```bash
# 查看待处理数量
curl http://localhost:8081/api/dev-ai/memory/pending-count
```

### 问题3：蒸馏后没有保存记忆

**检查：**
1. 查看蒸馏日志，确认是否通过过滤条件
2. 检查重要性评分是否 >= 6
3. 确认 `PersistentMemoryService` 工作正常

**解决：**
```bash
# 查看记忆统计
curl http://localhost:8081/api/dev-ai/memory/stats
```

---

## 📈 性能优化建议

### 1. 调整蒸馏频率

如果对话量很大，可以调整为每小时执行：

```java
@Scheduled(cron = "0 0 * * * ?")  // 每小时整点
```

### 2. 增加批处理大小限制

在 `scheduledDistillation()` 中添加分批处理：

```java
int batchSize = 50;  // 每批最多处理50条
List<ConversationRecord> batch = pendingConversations.subList(0, 
    Math.min(batchSize, pendingConversations.size()));
```

### 3. 异步处理

将蒸馏任务改为异步执行：

```java
@Async
@Scheduled(cron = "0 0 2 * * ?")
public void scheduledDistillation() {
    // ...
}
```

---

## 🎉 总结

定时批量蒸馏功能让你的RAG系统具备**自动学习能力**：

✅ **零干预**：正常使用即可，系统自动记录  
✅ **智能化**：基于多维度评分自动过滤低价值内容  
✅ **高性能**：定时批量处理，不影响正常业务  
✅ **可靠性**：持久化存储，重启不丢失  
✅ **可扩展**：可根据需求调整过滤规则和评分标准  

**越用越聪明，知识自动积累！** 🚀
