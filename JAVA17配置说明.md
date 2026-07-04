# Dev AI Platform - Java 17 环境配置说明

## 📋 问题描述

编译时出现错误：
```
[ERROR] No compiler is provided in this environment. Perhaps you are running on a JRE rather than a JDK?
```

**原因：**
- 系统环境变量 `JAVA_HOME` 指向的是 JRE（没有编译器）
- Spring Boot 3.x 需要 **Java 17+** 才能编译和运行

## ✅ 解决方案

### 方案一：使用启动脚本（推荐）

双击运行项目根目录下的 **`run-with-jdk17.bat`** 文件，会弹出菜单：

```
========================================
   Dev AI Platform - 启动菜单
========================================

1. 编译项目 (mvn compile)
2. 打包项目 (mvn package)
3. 运行应用 (mvn spring-boot:run)
4. 清理项目 (mvn clean)
5. 检查 Java 版本
0. 退出
```

这个脚本会自动设置 Java 17 环境，无需修改系统变量。

### 方案二：临时设置（当前终端有效）

在 PowerShell 中执行：

```powershell
$env:JAVA_HOME="D:\ruanjian\IntelliJIDEA201914\IntelliJIDEA2023.2.4\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

然后可以正常使用 Maven 命令：

```powershell
mvn compile          # 编译
mvn package          # 打包
mvn spring-boot:run  # 运行
```

### 方案三：永久设置系统环境变量

1. **右键点击"此电脑"** → **属性** → **高级系统设置**
2. 点击 **"环境变量"**
3. 找到 **JAVA_HOME** 变量，修改为：
   ```
   D:\ruanjian\IntelliJIDEA201914\IntelliJIDEA2023.2.4\jbr
   ```
4. 编辑 **Path** 变量，确保 `%JAVA_HOME%\bin` 在最前面
5. 点击确定保存
6. **重新打开终端**使设置生效

验证设置：

```cmd
java -version
```

应该显示：

```
openjdk version "17.0.8.1" 2023-08-24
OpenJDK Runtime Environment JBR-17.0.8.1+7-1000.32-jcef
```

##  快速开始

### 方法1：使用启动脚本

1. 双击 `run-with-jdk17.bat`
2. 选择 `3. 运行应用`
3. 等待启动完成
4. 浏览器访问：http://localhost:8081/index.html

### 方法2：手动命令

```powershell
# 设置 Java 17 环境
$env:JAVA_HOME="D:\ruanjian\IntelliJIDEA201914\IntelliJIDEA2023.2.4\jbr"
$env:Path="$env:JAVA_HOME\bin;$env:Path"

# 编译项目
mvn clean compile

# 运行应用
mvn spring-boot:run
```

##  项目结构

```
dev-ai-platform/
├── run-with-jdk17.bat          # 启动脚本（推荐使用）
├── start.bat                   # 旧版启动脚本
├── pom.xml                     # Maven配置
├── src/
│   ├── main/
│   │   ├── java/              # Java源码
│   │   └── resources/         # 配置文件
│   │       ├── static/
│   │       │   ├── index.html             # 智能对话前端页面
│   │       │   └── agent-dashboard.html   # Agent 可视化管理面板
│   │       ├── agent-config.json          # Agent 全局配置
│   │       └── application.properties     # 应用配置
│   └── test/
│       └── java/
│           └── AgentConfigTest.java       # Agent 配置单元测试
├── scripts/                    # 本地操作脚本
├── agent_memory/               # 记忆持久化存储
└── README_*.md                 # 各种使用文档
```

## 🔧 常用Maven命令

```bash
# 编译
mvn compile

# 打包（跳过测试）
mvn clean package -DskipTests

# 运行
mvn spring-boot:run

# 清理
mvn clean

# 安装到本地仓库
mvn install

# 查看依赖树
mvn dependency:tree
```

##  访问地址

启动成功后，访问以下地址：

- **智能对话页面**: http://localhost:8081/index.html
- **Agent 管理面板**: http://localhost:8081/agent-dashboard.html
- **API 配置接口**: http://localhost:8081/api/dev-ai/agent/config

##  功能列表

### 核心功能
- ✅ **实时对话** - 与AI助手进行智能对话（支持 QA 模式 / Agent 模式切换）
- ✅ **联网搜索** - 按需获取最新网络信息
- ✅ **PDF上传** - 单文件上传并自动解析入库（支持 OCR 扫描版）
- ✅ **批量OCR** - 批量识别图片型PDF
- ✅ **RAG 知识检索** - 混合检索（向量语义 + 关键词），文档三级分片
- ✅ **定时蒸馏** - 每天凌晨2点自动处理对话记录
- ✅ **永久记忆** - 重要知识持久化存储，支持上下文感知评分
- ✅ **Agent 自愈管理** - JSON 配置驱动，健康检查/故障诊断/自动修复
- ✅ **可视化管理面板** - 实时展示 Agent 配置、自愈规则、升级策略

### API接口
- `POST /api/dev-ai/agent/run` - 执行 Agent 任务
- `POST /api/dev-ai/chat/ask` - 智能问答（含联网搜索开关）
- `POST /api/dev-ai/lib/upload-file` - 上传 PDF 文件
- `POST /api/dev-ai/memory/distill-now` - 手动触发蒸馏
- `GET /api/dev-ai/memory/pending-count` - 查看待处理数量
- `GET /api/dev-ai/agent/config` - 获取 Agent 完整配置
- `GET /api/dev-ai/agent/config/summary` - 获取配置摘要

## ️ 注意事项

1. **必须使用 Java 17+** - Java 8 无法编译 Spring Boot 3.x 项目
2. **端口占用** - 确保 8081 端口未被占用
3. **内存要求** - 建议至少 4GB 可用内存
4. **网络连接** - 首次启动需要下载依赖，确保网络畅通

##  常见问题

### Q1: 为什么不能使用系统的 Java？
A: 您的系统安装的是 Java 8 (JRE)，缺少编译器且版本不满足 Spring Boot 3.x 要求。

### Q2: IntelliJ IDEA 的 JDK 可以用吗？
A: 可以！IntelliJ IDEA 2023.2.4 内置的 JBR (JetBrains Runtime) 是基于 OpenJDK 17 的完整JDK。

### Q3: 如何永久切换到 Java 17？
A: 修改系统环境变量 JAVA_HOME 指向 IntelliJ IDEA 的 jbr 目录，或下载官方 JDK 17。

### Q4: 编译成功但运行时出错？
A: 确保运行时也使用 Java 17，检查 `java -version` 输出。

##  技术支持

如有问题，请检查：
1. Java 版本是否为 17+
2. Maven 是否正常安装
3. 端口 8081 是否被占用
4. 查看控制台错误日志

---

**最后更新**: 2026年7月
**适用版本**: Dev AI Platform v1.0.0
