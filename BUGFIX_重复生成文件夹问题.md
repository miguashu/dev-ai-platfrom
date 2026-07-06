# Bug 修复：代码生成时出现重复 src 文件夹和多次调用 backend_code

## 问题描述

### 现象1：`backend_code` 被重复调用多次
从前端日志看到：
```
【第2步】代码Agent处理...
生成: backend_code
生成: backend_code
生成: backend_code
生成: backend_code
生成: frontend_code
```

### 现象2：生成的项目结构混乱，多了一个 src 文件夹
实际生成的目录结构：
```
E:\projects\myapp\
├── src\main\java\...          ← 正确的项目结构
└── myapp\src\main\java\...    ← 错误：多了一层 myapp/src
```

或者：
```
E:\projects\
├── myapp\src\main\java\...    ← 正确
└── src\main\java\...          ← 错误：直接在父目录下创建了 src
```

---

## 根本原因分析

### 原因1：CodeAgent 的 `generateContent` 工具可以被 AI 反复调用
- **位置**：[AgentOrchestrator.java](src/main/java/com/devai/devaiplatform/service/AgentOrchestrator.java#L174-L182)
- **问题**：AI 可能认为需要为不同模块多次生成后端代码，导致同一任务类型被重复调用
- **影响**：浪费工具调用配额，生成冗余内容

### 原因2：路径拼接逻辑不一致
- **`generateSpringBootProject`** (第34行): `basePath = projectPath + "/" + projectName`
- **`generateLayeredCode`** (第165行): `basePath = projectPath` （直接使用传入的路径）

**场景重现**：
1. AI 调用 `generateSpringBootProject("E:\projects", "myapp", "com.example.myapp")`
   - 创建：`E:\projects/myapp/src/main/java/...` ✅
   
2. AI 又调用 `generateLayeredCode("E:\projects/myapp", "com.example.myapp", "User", "User")`
   - 创建：`E:\projects/myapp/src/main/java/.../entity/User.java` ✅
   
3. 但如果 AI 误传路径 `generateLayeredCode("E:\projects", "com.example.myapp", "Order", "Order")`
   - 创建：`E:\projects/src/main/java/.../entity/Order.java` ❌ **直接在父目录创建 src！**

4. 或者 AI 传入完整路径但方法内部又拼接：
   - `generateLayeredCode("E:\projects\myapp\src\main\java", ...)` 
   - 创建：`E:\projects\myapp\src\main\java/src/main/java/...` ❌ **重复 src！**

---

## 修复方案

### 修复1：防止 CodeAgent 重复调用相同任务类型

**文件**：[AgentOrchestrator.java](src/main/java/com/devai/devaiplatform/service/AgentOrchestrator.java#L174-L191)

**修改内容**：
```java
class CodeTools {
    private final Set<String> calledTaskTypes = new HashSet<>(); // 跟踪已调用的任务类型
    
    @Tool("生成技术内容：unit_test/api_doc/prd_doc/backend_code/frontend_code/...")
    public String generateContent(String taskType, String requirement) {
        // 【防重复】检查是否已经调用过相同类型的任务
        if (calledTaskTypes.contains(taskType)) {
            reportProgress("warn", "⚠️ 警告: " + taskType + " 已生成过，避免重复调用");
            return "❌ 错误: 任务类型 '" + taskType + "' 已经调用过，请不要重复生成相同类型的代码。如需生成其他模块，请更换 taskType 或直接输出代码内容。";
        }
        
        calledTaskTypes.add(taskType);
        reportProgress("tool", "🖊️ 生成: " + taskType);
        String template = PromptTemplate.getTemplate(taskType);
        if (template == null) return "未知类型: " + taskType;
        return safeReturn(chatModel.generate(String.format(template, requirement)));
    }
}
```

**效果**：
- 每个任务类型（如 `backend_code`）只能调用一次
- 第二次调用时会返回明确的错误提示
- 前端会显示警告信息，让用户知道发生了重复调用

---

### 修复2：优化 CodeAgent 的 System Prompt

**文件**：[AgentOrchestrator.java](src/main/java/com/devai/devaiplatform/service/AgentOrchestrator.java#L184-L206)

**修改内容**：
```java
private static final String CODE_SYS_PROMPT = """
    你是代码生成专家。可以通过 generateContent 工具生成各种技术内容。
    
    ## 重要规则
    1. **不要重复生成相同类型的代码**：如果用户要求生成多个模块，请在一次调用中说明所有需求
    2. **generateContent 工具限制**：每个任务最多调用 3 次，超过将返回错误
    3. **输出格式**：直接输出代码即可，无需通过工具生成（除非明确要求创建文件）
    4. **模块化思维**：如果需要生成多个实体/模块的代码，请分步骤说明，但不要反复调用同一工具
    
    ## 可用任务类型
    - backend_code: 后端完整代码（Controller/Service/Entity/Repository/DTO）
    - frontend_code: 前端组件代码
    - unit_test: 单元测试
    - api_doc: 接口文档
    - crud_sql: CRUD SQL语句
    - config_file: 配置文件
    - code_review: 代码审查
    - refactoring: 重构建议
    
    收到需求后，优先直接输出代码内容。只有在需要实际创建文件时才使用文件操作工具。
    限制：工具调用不超过 3 次。
    """;
```

**效果**：
- 明确告知 AI 不要重复调用相同任务类型
- 提供清晰的任务类型列表
- 强调优先直接输出代码，减少不必要的工具调用

---

### 修复3：统一 `generateSpringBootProject` 的路径处理逻辑

**文件**：[ProjectStructureGenerator.java](src/main/java/com/devai/devaiplatform/service/ProjectStructureGenerator.java#L20-L51)

**修改内容**：
```java
public GenerateResult generateSpringBootProject(String projectPath, String projectName, String packageName) {
    GenerateResult result = new GenerateResult();
    result.projectName = projectName;
    
    // 【关键修复】规范化路径处理
    // 移除末尾的路径分隔符
    String normalizedPath = projectPath.replaceAll("[/\\\\]+$", "");
    
    // 如果传入的路径已经包含项目名（即以项目名结尾），则提取父目录
    if (normalizedPath.endsWith("/" + projectName) || normalizedPath.endsWith("\\" + projectName)) {
        int lastSepIndex = Math.max(normalizedPath.lastIndexOf('/'), normalizedPath.lastIndexOf('\\'));
        if (lastSepIndex > 0) {
            normalizedPath = normalizedPath.substring(0, lastSepIndex);
        }
    }
    
    result.projectPath = normalizedPath;
    String packagePath = packageName.replace(".", "/");
    String basePath = normalizedPath + "/" + projectName;

    System.out.println("[架构生成] 开始生成 Spring Boot 项目: " + projectName);
    System.out.println("[架构生成] 项目父目录: " + normalizedPath);
    System.out.println("[架构生成] 完整项目路径: " + basePath);
    
    // ... 后续代码不变
}
```

**效果**：
- 自动检测并纠正传入的路径参数
- 无论 AI 传入 `E:\projects` 还是 `E:\projects\myapp`，都能正确处理
- 添加详细的日志输出，便于调试

---

### 修复4：统一 `generateLayeredCode` 的路径处理逻辑

**文件**：[ProjectStructureGenerator.java](src/main/java/com/devai/devaiplatform/service/ProjectStructureGenerator.java#L155-L184)

**修改内容**：
```java
public GenerateResult generateLayeredCode(String projectPath, String packageName, 
                                           String entityName, String moduleName) {
    GenerateResult result = new GenerateResult();
    result.projectName = moduleName;
    result.projectPath = projectPath;

    String packagePath = packageName.replace(".", "/");
    
    // 【关键修复】检测 projectPath 是否已经是项目目录（包含 src 子目录）
    String basePath;
    if (projectPath.endsWith("/src") || projectPath.endsWith("\\src")) {
        // 如果传入的是 xxx/src，则去掉 /src
        basePath = projectPath.substring(0, projectPath.length() - 4);
    } else if (projectPath.contains("/src/") || projectPath.contains("\\src\\")) {
        // 如果传入的是 xxx/src/main/java，则取到项目根目录
        int srcIndex = Math.max(projectPath.indexOf("/src/"), projectPath.indexOf("\\src\\"));
        basePath = projectPath.substring(0, srcIndex);
    } else {
        // 否则直接使用传入的路径作为项目根目录
        basePath = projectPath;
    }
    
    String entityLower = entityName.toLowerCase();

    System.out.println("[架构生成] 开始生成 " + entityName + " 模块代码，项目根路径: " + basePath);
    
    // ... 后续代码不变
}
```

**效果**：
- 智能检测传入的路径是否已经包含 `/src` 目录
- 自动提取正确的项目根目录
- 避免在错误的位置创建 `src` 文件夹

---

### 修复5：优化 ProjectAgent 的 System Prompt

**文件**：[AgentOrchestrator.java](src/main/java/com/devai/devaiplatform/service/AgentOrchestrator.java#L211-L228)

**修改内容**：
```java
private static final String PROJECT_SYS_PROMPT = """
    你是项目架构专家。拥有生成Spring Boot项目和CRUD模块的能力。
    
    ## 重要规则
    1. **路径参数说明**：
       - `projectPath` 应该是项目的**父目录**（不包含项目名），例如：`E:\\projects`
       - `projectName` 是项目名称，例如：`myapp`
       - 系统会自动拼接为：`E:\\projects/myapp`
    
    2. **不要重复调用同一工具**：每个任务最多调用一次 generateSpringBootProject 或 generateCrudModuleCode
    
    3. **调用顺序**：先生成项目结构（generateSpringBootProject），再生成CRUD代码（generateCrudModuleCode）
    
    4. **CRUD代码路径**：调用 generateCrudModuleCode 时，projectPath 应该是**完整的项目路径**（包含项目名），例如：`E:\\projects/myapp`
    
    收到需求后直接调用对应工具生成项目结构，完成后告知结果。
    限制：工具调用不超过 3 次。
    """;
```

**效果**：
- 明确告知 AI 如何正确使用路径参数
- 区分 `generateSpringBootProject` 和 `generateCrudModuleCode` 的路径要求
- 防止 AI 传入错误的路径导致文件夹重复

---

## 测试验证

### 测试场景1：创建新项目 + 生成CRUD代码

**输入**：
```
帮我创建一个 Spring Boot 项目 myapp，包名 com.example.myapp，然后生成 User 和 Order 两个实体的 CRUD 代码
```

**预期行为**：
1. 只调用一次 `generateSpringBootProject`
2. 只调用两次 `generateCrudModuleCode`（User 和 Order 各一次）
3. 不会出现 `backend_code` 重复调用
4. 生成的目录结构：
   ```
   E:\projects\myapp\
   ├── pom.xml
   ├── src\main\java\com\example\myapp\
   │   ├── MyappApplication.java
   │   ├── controller\
   │   │   ├── UserController.java
   │   │   └── OrderController.java
   │   ├── service\
   │   │   ├── UserService.java
   │   │   ── OrderService.java
   │   ├── repository\
   │   │   ├── UserRepository.java
   │   │   └── OrderRepository.java
   │   └── entity\
   │       ├── User.java
   │       └── Order.java
   └── ...
   ```

### 测试场景2：仅生成后端代码（不创建文件）

**输入**：
```
帮我生成一个用户管理模块的后端代码，包括 Controller、Service、Entity
```

**预期行为**：
1. 只调用一次 `backend_code` 任务类型
2. 直接输出代码内容，不创建文件
3. 不会重复调用 `backend_code`

### 测试场景3：路径参数容错测试

**输入**：
```
在 E:\projects\myapp 目录下生成 Product 实体的 CRUD 代码
```

**预期行为**：
1. AI 可能传入 `E:\projects\myapp` 或 `E:\projects` 作为 `projectPath`
2. 系统应能自动识别并正确处理
3. 最终都生成到 `E:\projects\myapp\src\main\java\...`

---

## 注意事项

1. **重启应用**：修改 Java 代码后需要重新编译并启动 Spring Boot 应用
2. **清理旧文件**：如果之前生成了错误的目录结构，建议手动删除后重新生成
3. **观察日志**：查看控制台输出的 `[架构生成]` 日志，确认路径是否正确
4. **前端提示**：如果看到 `⚠️ 警告: backend_code 已生成过`，说明 AI 尝试重复调用，已被拦截

---

## 相关文件清单

- [AgentOrchestrator.java](src/main/java/com/devai/devaiplatform/service/AgentOrchestrator.java)
  - 修改了 `CodeTools` 类（添加防重复逻辑）
  - 修改了 `CODE_SYS_PROMPT`（优化提示词）
  - 修改了 `PROJECT_SYS_PROMPT`（明确路径参数说明）

- [ProjectStructureGenerator.java](src/main/java/com/devai/devaiplatform/service/ProjectStructureGenerator.java)
  - 修改了 `generateSpringBootProject` 方法（路径规范化）
  - 修改了 `generateLayeredCode` 方法（智能路径检测）

---

## 总结

本次修复解决了两个核心问题：
1. **防止 AI 重复调用相同任务类型** → 通过 `Set<String>` 跟踪已调用的任务类型
2. **统一路径处理逻辑** → 通过智能检测和规范化，确保无论 AI 传入什么路径都能正确处理

修复后，项目结构生成将更加可靠，不会出现重复的 `src` 文件夹或混乱的目录结构。
