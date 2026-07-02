# 定时批量蒸馏功能 - 快速测试指南

## 🧪 测试步骤

### 1. 启动应用

```bash
cd E:/plugin/dev-ai-platform/dev-ai-platform
mvn spring-boot:run
```

**预期输出：**
```
[蒸馏调度器] 已启动，当前待处理对话: 0
[记忆服务] 已加载 X 条记忆
=== Dev AI Platform 启动成功 ===
```

---

### 2. 执行几个Agent任务（生成待蒸馏对话）

#### 任务1：技术实现类

```bash
curl -X POST http://localhost:8081/api/dev-ai/agent/run \
  -d "task=Spring Boot如何配置多数据源？需要详细的步骤和代码示例"
```

#### 任务2：SQL优化类

```bash
curl -X POST http://localhost:8081/api/dev-ai/agent/run \
  -d "task=MySQL查询性能优化，SELECT * FROM users WHERE age > 18 AND status = 'active'，这个查询很慢怎么优化？"
```

#### 任务3：错误排查类

```bash
curl -X POST http://localhost:8081/api/dev-ai/agent/run \
  -d "task=报错信息：java.lang.NullPointerException at UserService.getUserById()，如何排查和修复？"
```

#### 任务4：简单对话（应该被过滤）

```bash
curl -X POST http://localhost:8081/api/dev-ai/agent/run \
  -d "task=你好"
```

---

### 3. 查看待处理数量

```bash
curl http://localhost:8081/api/dev-ai/memory/pending-count
```

**预期返回：**
```json
{
  "pendingCount": 4
}
```

---

### 4. 手动触发蒸馏（测试用）

```bash
curl -X POST http://localhost:8081/api/dev-ai/memory/distill-now
```

**预期控制台输出：**
```
========== 开始定时记忆蒸馏 ==========
当前时间: 2026-07-01T15:30:00.000
待处理对话: 4 条

[1/4] 处理: Spring Boot如何配置多数据源？需要详细的步骤和代码示例
  ✓ 已保存 (ID: a1b2c3d4, 重要性: 8/10)

[2/4] 处理: MySQL查询性能优化...
  ✓ 已保存 (ID: e5f6g7h8, 重要性: 7/10)

[3/4] 处理: 报错信息：java.lang.NullPointerException...
  ✓ 已保存 (ID: i9j0k1l2, 重要性: 7/10)

[4/4] 处理: 你好
  → 跳过（低价值内容）

========== 定时蒸馏完成 ==========
总计处理: 4 条
成功保存: 3 条
跳过过滤: 1 条
====================================
```

---

### 5. 验证记忆已保存

```bash
curl http://localhost:8081/api/dev-ai/memory/stats
```

**预期返回：**
```json
{
  "totalMemories": 159,
  "byCategory": {
    "technical": 46,
    "database": 33,
    "problem": 29,
    "business": 21,
    "general": 30
  },
  "avgImportance": 7.3,
  "totalAccesses": 423
}
```

---

### 6. 搜索相关记忆

```bash
curl "http://localhost:8081/api/dev-ai/memory/search?query=多数据源"
```

**预期返回：**
刚刚保存的关于多数据源配置的对话记录

---

## 🔍 验证检查点

### ✅ 检查点1：对话自动记录

**验证方法：**
- 执行Agent任务后，控制台应显示：`[对话记录] 已记录，当前待处理: X 条`
- 文件 `./agent_memory/pending_conversations.json` 应该存在

**预期内容：**
```json
[
  {
    "question": "Spring Boot如何配置多数据源？...",
    "answer": "配置多数据源的完整方案：...",
    "taskType": "agent_task",
    "timestamp": "2026-07-01T15:30:00"
  }
]
```

---

### ✅ 检查点2：智能过滤生效

**验证方法：**
- "你好"这样的简单对话应该被跳过
- 技术性强的对话应该被保存

**日志中应该看到：**
```
[4/4] 处理: 你好
  → 跳过（低价值内容）
```

---

### ✅ 检查点3：重要性评分准确

**验证标准：**
- 包含代码示例的对话评分 >= 7
- 简单技术问题评分 5-6
- 日常对话评分 < 6（被过滤）

---

### ✅ 检查点4：分类正确

**验证方法：**
查看保存的记忆分类是否合理：
- 多数据源 → `technical`
- SQL优化 → `database`
- 异常排查 → `problem`

---

### ✅ 检查点5：持久化存储

**验证方法：**
1. 记录几个对话后，停止应用
2. 重新启动应用
3. 再次查询待处理数量，应该保持不变

**文件位置：**
```
./agent_memory/pending_conversations.json
./agent_memory/persistent_memory.json
```

---

## 🐛 常见问题排查

### 问题1：待处理数量始终为0

**可能原因：**
- `DevAgentService.runDevTask()` 没有被调用
- 依赖注入失败

**解决方法：**
```bash
# 检查日志是否有错误
grep "distillationScheduler" logs/spring.log

# 检查端点是否正常
curl http://localhost:8081/api/dev-ai/memory/pending-count
```

---

### 问题2：手动蒸馏没有反应

**可能原因：**
- 定时任务未启用
- `@EnableScheduling` 注解缺失

**解决方法：**
1. 确认启动类有 `@EnableScheduling` 注解
2. 检查控制台是否有错误日志

---

### 问题3：所有对话都被跳过

**可能原因：**
- 过滤条件太严格
- 回答长度不足

**解决方法：**
临时调整 `MemoryDistillationScheduler.java` 中的阈值：
```java
// 降低最小回答长度
if (record.getAnswer().length() < 100) {  // 改为100
    return false;
}

// 降低关键词要求
return keywordCount >= 1;  // 改为1
```

---

## 📊 完整测试脚本（一键执行）

创建 `test_distillation.sh`（Linux/Mac）或 `test_distillation.bat`（Windows）：

### Linux/Mac版本

```bash
#!/bin/bash

BASE_URL="http://localhost:8081/api/dev-ai"

echo "===== 1. 执行Agent任务 ====="
curl -s -X POST "$BASE_URL/agent/run" \
  -d "task=Spring Boot如何配置多数据源？" | head -c 100
echo ""

echo "===== 2. 查看待处理数量 ====="
curl -s "$BASE_URL/memory/pending-count"
echo ""

echo "===== 3. 手动触发蒸馏 ====="
curl -s -X POST "$BASE_URL/memory/distill-now"
echo ""

echo "===== 4. 查看记忆统计 ====="
curl -s "$BASE_URL/memory/stats" | python3 -m json.tool
echo ""

echo "===== 测试完成 ====="
```

### Windows版本（PowerShell）

```powershell
$BASE_URL = "http://localhost:8081/api/dev-ai"

Write-Host "===== 1. 执行Agent任务 ====="
Invoke-RestMethod -Uri "$BASE_URL/agent/run" -Method Post -Body "task=Spring Boot如何配置多数据源？"

Write-Host "===== 2. 查看待处理数量 ====="
Invoke-RestMethod -Uri "$BASE_URL/memory/pending-count"

Write-Host "===== 3. 手动触发蒸馏 ====="
Invoke-RestMethod -Uri "$BASE_URL/memory/distill-now" -Method Post

Write-Host "===== 4. 查看记忆统计 ====="
Invoke-RestMethod -Uri "$BASE_URL/memory/stats"

Write-Host "===== 测试完成 ====="
```

---

## 🎯 测试成功标志

✅ 执行Agent任务后，控制台显示 `[对话记录] 已记录`  
✅ 待处理数量 > 0  
✅ 手动蒸馏后，控制台显示详细的蒸馏报告  
✅ 记忆统计中 totalMemories 增加  
✅ 可以搜索到新保存的记忆  

**全部通过说明定时批量蒸馏功能工作正常！** 🚀
