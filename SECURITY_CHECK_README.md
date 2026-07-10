# 内容安全防护机制

## 概述

DevAI Platform 已集成全面的内容安全校验服务，防止数据泄漏和恶意代码注入。所有从网络搜索和RAG知识库检索的内容都会经过严格的安全检查。

## 核心防护能力

### 1. 🔴 敏感信息检测（高危）

自动检测并隐藏以下敏感信息：

- **密码/密钥**: `password`, `api_key`, `access_token`, `secret` 等
- **云服务密钥**: AWS Access Key, Azure Client Secret 等
- **私钥文件**: RSA/DSA/EC 私钥内容
- **数据库连接**: 含密码的 JDBC/MongoDB/Redis 连接字符串

**处理方式**: 自动替换为 `[已隐藏敏感信息]`

### 2. 🟡 个人隐私信息检测

保护个人身份信息不被泄漏：

- **身份证号**: 中国大陆18位身份证号码
- **手机号**: 中国大陆手机号码
- **银行卡号**: 信用卡/借记卡号码
- **邮箱地址**: 电子邮箱（仅在特定场景下过滤）

**处理方式**: 自动替换为 `[已隐藏XXX]`

### 3. 🔴 恶意代码检测（高危）

拦截各类恶意代码和攻击脚本：

- **危险系统命令**: `rm -rf`, `format`, `del /f`, `mkfs` 等
- **PowerShell恶意命令**: `Invoke-Expression`, `DownloadString` 等
- **Base64编码载荷**: 编码后的恶意脚本
- **反弹Shell**: Bash/Python/Perl 反弹Shell代码
- **Webshell后门**: `eval()`, `exec()`, `system()` 等危险函数

**处理方式**: 自动替换为 `[已拦截危险命令]` 或 `[已拦截恶意代码]`

### 4. 🟡 注入攻击检测

防止常见的Web攻击：

- **SQL注入**: `UNION SELECT`, `DROP TABLE`, `OR 1=1` 等
- **XSS攻击**: `<script>`, `javascript:`, `onerror=` 等

**处理方式**: 自动替换为 `[已拦截SQL注入]` 或 `[已拦截XSS攻击]`

### 5. 🟡 数据泄漏检测

防止本地敏感路径和内网信息泄漏：

- **本地路径**: Windows/Linux/Mac 用户目录、系统目录
- **内网IP**: 10.x.x.x, 172.16-31.x.x, 192.168.x.x

**处理方式**: 自动替换为 `[已隐藏本地路径]` 或 `[已隐藏内网IP]`

## 安全校验流程

### 网络搜索结果校验

```java
// WebSearchService.search() → DevAgentService.searchWeb()
WebSearchResult result = webSearchService.search(query);
String webContent = result.toPromptContext();

// 【安全校验】
SecurityCheckResult check = contentSecurityService.checkContent(webContent, "web_search");
if (!check.isSafe) {
    // 返回清理后的内容 + 安全提示
    return check.sanitizedContent + "\n⚠️ 安全风险已过滤";
}
```

### RAG知识库检索校验

```java
// DevRagService.ragQuery() → DevAgentService.searchDevLib()
String ragResult = ragService.ragQuery(question);

// 【安全校验】
SecurityCheckResult check = contentSecurityService.checkContent(ragResult, "rag_pdf");
if (!check.isSafe) {
    // 返回清理后的内容 + 安全提示
    return check.sanitizedContent + "\n⚠️ 敏感信息已过滤";
}
```

### 多轮对话上下文校验

在 `askWithContext()` 方法中，对以下内容进行校验：
1. 联网搜索结果
2. RAG向量库检索结果
3. 历史记忆内容

## 风险等级说明

| 等级 | 标识 | 说明 | 处理策略 |
|------|------|------|----------|
| 高危 | 🔴 | 可能导致严重安全事故 | **强制拦截**，内容不可用 |
| 中危 | 🟡 | 存在潜在风险 | 自动清理后使用 |
| 低危 | 🟢 | 轻微风险 | 记录日志，允许通过 |

**判定规则**: 只要包含任一🔴高危项，整体判定为不安全（`isSafe=false`）

## 日志与监控

所有安全事件都会输出到控制台：

```
[安全检查] 开始校验内容，来源: web_search，长度: 5000
[安全检查] ⚠️ 检测到密码/密钥信息
[安全检查] ⚠️ 检测到本地路径泄漏
[安全检查] 校验完成，风险数: 2，安全状态: ❌
```

## 扩展建议

如需增强安全防护，可考虑：

1. **自定义正则规则**: 在 `ContentSecurityService` 中添加业务特定的敏感模式
2. **白名单机制**: 对可信来源的内容跳过部分检查
3. **审计日志**: 将安全事件写入数据库，便于事后追溯
4. **实时告警**: 集成钉钉/企微机器人，高危事件即时通知
5. **AI辅助审核**: 对边界案例调用AI进行二次判断

## 配置文件

暂无外部配置，所有规则硬编码在 `ContentSecurityService.java` 中。

后续可提取到 `application.yml`:

```yaml
security:
  content-check:
    enabled: true
    strict-mode: true  # 严格模式：中危也拦截
    whitelist-sources: ["official_docs"]  # 白名单来源
```

## 常见问题

**Q: 为什么我的正常内容被误拦截了？**  
A: 可能是内容中包含类似密码格式的字符串。可以调整正则表达式的匹配精度，或使用白名单机制。

**Q: 能否临时关闭安全检查？**  
A: 不建议。如需调试，可在代码中注释掉相关校验逻辑，但生产环境必须启用。

**Q: 安全检查会影响性能吗？**  
A: 影响极小。正则匹配耗时通常在毫秒级，对整体响应时间影响 < 1%。
