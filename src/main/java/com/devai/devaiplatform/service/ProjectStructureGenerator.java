package com.devai.devaiplatform.service;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 项目架构生成器
 * 根据需求生成标准项目结构和代码骨架
 */
@Service
public class ProjectStructureGenerator {

    private final LocalFileOperationService fileService;

    public ProjectStructureGenerator(LocalFileOperationService fileService) {
        this.fileService = fileService;
    }

    /**
     * 生成 Spring Boot 标准项目结构
     *
     * @param projectPath 项目根路径
     * @param projectName 项目名称
     * @param packageName 包名（如 com.example.demo）
     * @return 生成结果
     */
    public GenerateResult generateSpringBootProject(String projectPath, String projectName, String packageName) {
        GenerateResult result = new GenerateResult();
        result.projectName = projectName;
        result.projectPath = projectPath;

        String packagePath = packageName.replace(".", "/");
        String basePath = projectPath + "/" + projectName;

        System.out.println("[架构生成] 开始生成 Spring Boot 项目: " + projectName);

        try {
            // 1. 创建目录结构
            Map<String, String> directories = new LinkedHashMap<>();
            directories.put("src/main/java/" + packagePath + "/controller", "控制器层");
            directories.put("src/main/java/" + packagePath + "/service", "服务层");
            directories.put("src/main/java/" + packagePath + "/service/impl", "服务实现层");
            directories.put("src/main/java/" + packagePath + "/repository", "数据访问层");
            directories.put("src/main/java/" + packagePath + "/entity", "实体类");
            directories.put("src/main/java/" + packagePath + "/dto", "数据传输对象");
            directories.put("src/main/java/" + packagePath + "/config", "配置类");
            directories.put("src/main/java/" + packagePath + "/common", "公共类");
            directories.put("src/main/java/" + packagePath + "/exception", "异常处理");
            directories.put("src/main/resources/static", "静态资源");
            directories.put("src/main/resources/templates", "模板文件");
            directories.put("src/test/java/" + packagePath, "测试代码");

            for (Map.Entry<String, String> entry : directories.entrySet()) {
                String dirPath = basePath + "/" + entry.getKey();
                LocalFileOperationService.FileOperationResult dirResult = fileService.createDirectory(dirPath);
                result.addResult(entry.getValue(), dirPath, dirResult.success, dirResult.message);
            }

            // 2. 创建核心文件
            Map<String, String> files = new LinkedHashMap<>();

            // pom.xml
            files.put(basePath + "/pom.xml", generatePomXml(projectName, packageName));

            // 主启动类
            String mainClassName = toPascalCase(projectName) + "Application";
            files.put(basePath + "/src/main/java/" + packagePath + "/" + mainClassName + ".java",
                    generateMainClass(packageName, mainClassName));

            // application.yml
            files.put(basePath + "/src/main/resources/application.yml", generateApplicationYml(projectName));

            // .gitignore
            files.put(basePath + "/.gitignore", generateGitignore());

            // 全局异常处理
            files.put(basePath + "/src/main/java/" + packagePath + "/exception/GlobalExceptionHandler.java",
                    generateGlobalExceptionHandler(packageName));

            // 统一返回结果
            files.put(basePath + "/src/main/java/" + packagePath + "/common/Result.java",
                    generateResultClass(packageName));

            // 批量创建文件
            for (Map.Entry<String, String> entry : files.entrySet()) {
                LocalFileOperationService.FileOperationResult fileResult = fileService.createFile(entry.getKey(), entry.getValue());
                result.addResult("文件", entry.getKey(), fileResult.success, fileResult.message);
            }

            result.success = true;
            result.message = "项目生成成功！";
            System.out.println("[架构生成] " + result.message);

        } catch (Exception e) {
            result.success = false;
            result.message = "项目生成失败: " + e.getMessage();
            System.err.println("[架构生成] " + result.message);
        }

        return result;
    }

    /**
     * 生成多模块 Maven 项目结构
     */
    public GenerateResult generateMultiModuleProject(String projectPath, String projectName, 
                                                      String packageName, List<String> modules) {
        GenerateResult result = new GenerateResult();
        result.projectName = projectName;
        result.projectPath = projectPath;

        String basePath = projectPath + "/" + projectName;
        String packagePath = packageName.replace(".", "/");

        System.out.println("[架构生成] 开始生成多模块项目: " + projectName);

        try {
            // 1. 创建父项目目录
            fileService.createDirectory(basePath);

            // 2. 创建父 pom.xml
            String parentPom = generateParentPomXml(projectName, packageName, modules);
            LocalFileOperationService.FileOperationResult pomResult = 
                    fileService.createFile(basePath + "/pom.xml", parentPom);
            result.addResult("父POM", basePath + "/pom.xml", pomResult.success, pomResult.message);

            // 3. 创建各模块
            for (String module : modules) {
                String modulePath = basePath + "/" + module;
                
                // 创建模块目录结构
                fileService.createDirectory(modulePath + "/src/main/java/" + packagePath);
                fileService.createDirectory(modulePath + "/src/main/resources");
                fileService.createDirectory(modulePath + "/src/test/java/" + packagePath);

                // 创建模块 pom.xml
                String modulePom = generateModulePomXml(projectName, packageName, module);
                fileService.createFile(modulePath + "/pom.xml", modulePom);

                result.addResult("模块", modulePath, true, "模块创建成功: " + module);
            }

            result.success = true;
            result.message = "多模块项目生成成功！";

        } catch (Exception e) {
            result.success = false;
            result.message = "多模块项目生成失败: " + e.getMessage();
        }

        return result;
    }

    /**
     * 生成 Controller-Service-Repository 三层架构代码
     */
    public GenerateResult generateLayeredCode(String projectPath, String packageName, 
                                               String entityName, String moduleName) {
        GenerateResult result = new GenerateResult();
        result.projectName = moduleName;
        result.projectPath = projectPath;

        String packagePath = packageName.replace(".", "/");
        String basePath = projectPath;
        String entityLower = entityName.toLowerCase();

        System.out.println("[架构生成] 开始生成 " + entityName + " 模块代码");

        try {
            Map<String, String> files = new LinkedHashMap<>();

            // Entity
            files.put(basePath + "/src/main/java/" + packagePath + "/entity/" + entityName + ".java",
                    generateEntity(packageName, entityName));

            // Repository
            files.put(basePath + "/src/main/java/" + packagePath + "/repository/" + entityName + "Repository.java",
                    generateRepository(packageName, entityName));

            // Service接口
            files.put(basePath + "/src/main/java/" + packagePath + "/service/" + entityName + "Service.java",
                    generateServiceInterface(packageName, entityName));

            // Service实现
            files.put(basePath + "/src/main/java/" + packagePath + "/service/impl/" + entityName + "ServiceImpl.java",
                    generateServiceImpl(packageName, entityName));

            // Controller
            files.put(basePath + "/src/main/java/" + packagePath + "/controller/" + entityName + "Controller.java",
                    generateController(packageName, entityName));

            // DTO
            files.put(basePath + "/src/main/java/" + packagePath + "/dto/" + entityName + "DTO.java",
                    generateDTO(packageName, entityName));

            files.put(basePath + "/src/main/java/" + packagePath + "/dto/" + entityName + "Request.java",
                    generateRequestDTO(packageName, entityName));

            files.put(basePath + "/src/main/java/" + packagePath + "/dto/" + entityName + "Response.java",
                    generateResponseDTO(packageName, entityName));

            // 批量创建
            for (Map.Entry<String, String> entry : files.entrySet()) {
                LocalFileOperationService.FileOperationResult fileResult = fileService.createFile(entry.getKey(), entry.getValue());
                result.addResult("代码文件", entry.getKey(), fileResult.success, fileResult.message);
            }

            result.success = true;
            result.message = entityName + " 模块代码生成成功！";

        } catch (Exception e) {
            result.success = false;
            result.message = "代码生成失败: " + e.getMessage();
        }

        return result;
    }

    // ==================== 代码生成方法 ====================

    private String generatePomXml(String projectName, String packageName) {
        String groupId = packageName.substring(0, packageName.lastIndexOf('.'));
        String artifactId = projectName.toLowerCase().replaceAll("[^a-z0-9]", "");
        
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.2.0</version>
                        <relativePath/>
                    </parent>
                    
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <name>%s</name>
                    <description>Generated by DevAI Platform</description>
                    
                    <properties>
                        <java.version>17</java.version>
                    </properties>
                    
                    <dependencies>
                        <!-- Spring Boot Web -->
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        
                        <!-- Spring Data JPA -->
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-data-jpa</artifactId>
                        </dependency>
                        
                        <!-- MySQL Driver -->
                        <dependency>
                            <groupId>com.mysql</groupId>
                            <artifactId>mysql-connector-j</artifactId>
                            <scope>runtime</scope>
                        </dependency>
                        
                        <!-- Lombok -->
                        <dependency>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <optional>true</optional>
                        </dependency>
                        
                        <!-- Validation -->
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-validation</artifactId>
                        </dependency>
                        
                        <!-- Test -->
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-test</artifactId>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                    
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <configuration>
                                    <excludes>
                                        <exclude>
                                            <groupId>org.projectlombok</groupId>
                                            <artifactId>lombok</artifactId>
                                        </exclude>
                                    </excludes>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(groupId, artifactId, projectName);
    }

    private String generateMainClass(String packageName, String className) {
        return """
                package %s;
                
                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                
                /**
                 * %s - Spring Boot 主启动类
                 * Generated by DevAI Platform
                 */
                @SpringBootApplication
                public class %s {
                    public static void main(String[] args) {
                        SpringApplication.run(%s.class, args);
                    }
                }
                """.formatted(packageName, className, className, className);
    }

    private String generateApplicationYml(String projectName) {
        return """
                server:
                  port: 8080
                
                spring:
                  application:
                    name: %s
                  
                  # 数据源配置
                  datasource:
                    url: jdbc:mysql://localhost:3306/%s?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
                    username: root
                    password: root
                    driver-class-name: com.mysql.cj.jdbc.Driver
                  
                  # JPA配置
                  jpa:
                    hibernate:
                      ddl-auto: update
                    show-sql: true
                    properties:
                      hibernate:
                        format_sql: true
                
                # 日志配置
                logging:
                  level:
                    root: INFO
                    %s: DEBUG
                """.formatted(projectName, projectName.toLowerCase(), projectName);
    }

    private String generateGitignore() {
        return """
                # Compiled class files
                *.class
                
                # Log files
                *.log
                
                # BlueJ files
                *.ctxt
                
                # Mobile Tools for Java
                .mtj.tmp/
                
                # Package Files
                *.jar
                *.war
                *.nar
                *.ear
                *.zip
                *.tar.gz
                *.rar
                
                # Maven
                target/
                
                # Gradle
                .gradle/
                build/
                
                # IDE
                .idea/
                *.iml
                *.ipr
                *.iws
                .vscode/
                
                # OS
                .DS_Store
                Thumbs.db
                
                # Environment
                .env
                *.env.local
                """;
    }

    private String generateGlobalExceptionHandler(String packageName) {
        return """
                package %s.exception;
                
                import %s.common.Result;
                import org.springframework.http.HttpStatus;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                import org.springframework.web.bind.annotation.ResponseStatus;
                import org.springframework.web.bind.annotation.RestControllerAdvice;
                
                /**
                 * 全局异常处理器
                 */
                @RestControllerAdvice
                public class GlobalExceptionHandler {
                
                    @ExceptionHandler(Exception.class)
                    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    public Result<String> handleException(Exception e) {
                        return Result.error("系统异常: " + e.getMessage());
                    }
                
                    @ExceptionHandler(IllegalArgumentException.class)
                    @ResponseStatus(HttpStatus.BAD_REQUEST)
                    public Result<String> handleIllegalArgumentException(IllegalArgumentException e) {
                        return Result.error("参数错误: " + e.getMessage());
                    }
                }
                """.formatted(packageName, packageName);
    }

    private String generateResultClass(String packageName) {
        return """
                package %s.common;
                
                import lombok.Data;
                
                /**
                 * 统一返回结果
                 */
                @Data
                public class Result<T> {
                    private int code;
                    private String message;
                    private T data;
                
                    public static <T> Result<T> success(T data) {
                        Result<T> result = new Result<>();
                        result.setCode(200);
                        result.setMessage("success");
                        result.setData(data);
                        return result;
                    }
                
                    public static <T> Result<T> error(String message) {
                        Result<T> result = new Result<>();
                        result.setCode(500);
                        result.setMessage(message);
                        return result;
                    }
                
                    public static <T> Result<T> error(int code, String message) {
                        Result<T> result = new Result<>();
                        result.setCode(code);
                        result.setMessage(message);
                        return result;
                    }
                }
                """.formatted(packageName);
    }

    private String generateEntity(String packageName, String entityName) {
        return """
                package %s.entity;
                
                import jakarta.persistence.*;
                import lombok.Data;
                import java.time.LocalDateTime;
                
                /**
                 * %s 实体类
                 */
                @Data
                @Entity
                @Table(name = "%s")
                public class %s {
                
                    @Id
                    @GeneratedValue(strategy = GenerationType.IDENTITY)
                    private Long id;
                
                    // TODO: 添加业务字段
                
                    @Column(name = "create_time", updatable = false)
                    private LocalDateTime createTime;
                
                    @Column(name = "update_time")
                    private LocalDateTime updateTime;
                
                    @Column(name = "is_deleted")
                    private Boolean isDeleted = false;
                
                    @PrePersist
                    public void prePersist() {
                        this.createTime = LocalDateTime.now();
                        this.updateTime = LocalDateTime.now();
                    }
                
                    @PreUpdate
                    public void preUpdate() {
                        this.updateTime = LocalDateTime.now();
                    }
                }
                """.formatted(packageName, entityName, toSnakeCase(entityName), entityName);
    }

    private String generateRepository(String packageName, String entityName) {
        return """
                package %s.repository;
                
                import %s.entity.%s;
                import org.springframework.data.jpa.repository.JpaRepository;
                import org.springframework.stereotype.Repository;
                
                /**
                 * %s 数据访问层
                 */
                @Repository
                public interface %sRepository extends JpaRepository<%s, Long> {
                    // TODO: 添加自定义查询方法
                }
                """.formatted(packageName, packageName, entityName, entityName, entityName, entityName);
    }

    private String generateServiceInterface(String packageName, String entityName) {
        return """
                package %s.service;
                
                import %s.dto.%sRequest;
                import %s.dto.%sResponse;
                import %s.entity.%s;
                import java.util.List;
                
                /**
                 * %s 服务接口
                 */
                public interface %sService {
                
                    %s create(%sRequest request);
                
                    %s getById(Long id);
                
                    List<%s> list();
                
                    %s update(Long id, %sRequest request);
                
                    void delete(Long id);
                }
                """.formatted(packageName, packageName, entityName, packageName, entityName, packageName, entityName,
                        entityName, entityName, entityName, entityName, entityName, entityName, entityName, entityName);
    }

    private String generateServiceImpl(String packageName, String entityName) {
        return """
                package %s.service.impl;
                
                import %s.dto.%sRequest;
                import %s.dto.%sResponse;
                import %s.entity.%s;
                import %s.repository.%sRepository;
                import %s.service.%sService;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;
                import java.util.List;
                
                /**
                 * %s 服务实现
                 */
                @Service
                @Transactional
                public class %sServiceImpl implements %sService {
                
                    private final %sRepository repository;
                
                    public %sServiceImpl(%sRepository repository) {
                        this.repository = repository;
                    }
                
                    @Override
                    public %s create(%sRequest request) {
                        %s entity = new %s();
                        // TODO: 映射请求字段到实体
                        return repository.save(entity);
                    }
                
                    @Override
                    public %s getById(Long id) {
                        return repository.findById(id)
                                .orElseThrow(() -> new RuntimeException("%s不存在"));
                    }
                
                    @Override
                    public List<%s> list() {
                        return repository.findAll();
                    }
                
                    @Override
                    public %s update(Long id, %sRequest request) {
                        %s entity = getById(id);
                        // TODO: 更新字段
                        return repository.save(entity);
                    }
                
                    @Override
                    public void delete(Long id) {
                        repository.deleteById(id);
                    }
                }
                """.formatted(packageName, packageName, entityName, packageName, entityName, packageName, entityName,
                        packageName, entityName, packageName, entityName, entityName, entityName, entityName,
                        entityName, entityName, entityName, entityName, entityName, entityName, entityName,
                        entityName, entityName, entityName, entityName, entityName, entityName);
    }

    private String generateController(String packageName, String entityName) {
        return """
                package %s.controller;
                
                import %s.common.Result;
                import %s.dto.%sRequest;
                import %s.entity.%s;
                import %s.service.%sService;
                import org.springframework.web.bind.annotation.*;
                import java.util.List;
                
                /**
                 * %s 控制器
                 */
                @RestController
                @RequestMapping("/%s")
                public class %sController {
                
                    private final %sService service;
                
                    public %sController(%sService service) {
                        this.service = service;
                    }
                
                    @PostMapping
                    public Result<%s> create(@RequestBody %sRequest request) {
                        return Result.success(service.create(request));
                    }
                
                    @GetMapping("/{id}")
                    public Result<%s> getById(@PathVariable Long id) {
                        return Result.success(service.getById(id));
                    }
                
                    @GetMapping
                    public Result<List<%s>> list() {
                        return Result.success(service.list());
                    }
                
                    @PutMapping("/{id}")
                    public Result<%s> update(@PathVariable Long id, @RequestBody %sRequest request) {
                        return Result.success(service.update(id, request));
                    }
                
                    @DeleteMapping("/{id}")
                    public Result<Void> delete(@PathVariable Long id) {
                        service.delete(id);
                        return Result.success(null);
                    }
                }
                """.formatted(packageName, packageName, entityName, packageName, entityName, packageName, entityName,
                        entityName, entityName, toKebabCase(entityName), entityName, entityName, entityName, entityName,
                        entityName, entityName, entityName, entityName, entityName, entityName, entityName);
    }

    private String generateDTO(String packageName, String entityName) {
        return """
                package %s.dto;
                
                import lombok.Data;
                
                /**
                 * %s 数据传输对象
                 */
                @Data
                public class %sDTO {
                    // TODO: 定义需要传输的字段
                    private Long id;
                }
                """.formatted(packageName, entityName, entityName);
    }

    private String generateRequestDTO(String packageName, String entityName) {
        return """
                package %s.dto;
                
                import jakarta.validation.constraints.NotBlank;
                import lombok.Data;
                
                /**
                 * %s 请求对象
                 */
                @Data
                public class %sRequest {
                    // TODO: 定义请求字段和验证规则
                }
                """.formatted(packageName, entityName, entityName);
    }

    private String generateResponseDTO(String packageName, String entityName) {
        return """
                package %s.dto;
                
                import lombok.Data;
                import java.time.LocalDateTime;
                
                /**
                 * %s 响应对象
                 */
                @Data
                public class %sResponse {
                    private Long id;
                    private LocalDateTime createTime;
                    // TODO: 定义响应字段
                }
                """.formatted(packageName, entityName, entityName);
    }

    private String generateParentPomXml(String projectName, String packageName, List<String> modules) {
        String groupId = packageName.substring(0, packageName.lastIndexOf('.'));
        String modulesXml = modules.stream()
                .map(m -> "        <module>" + m + "</module>")
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.2.0</version>
                        <relativePath/>
                    </parent>
                    
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <name>%s</name>
                    
                    <modules>
                %s
                    </modules>
                    
                    <properties>
                        <java.version>17</java.version>
                    </properties>
                    
                    <dependencyManagement>
                        <dependencies>
                            <!-- 在此定义公共依赖版本 -->
                        </dependencies>
                    </dependencyManagement>
                </project>
                """.formatted(groupId, projectName, projectName, modulesXml);
    }

    private String generateModulePomXml(String projectName, String packageName, String module) {
        String groupId = packageName.substring(0, packageName.lastIndexOf('.'));
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    
                    <parent>
                        <groupId>%s</groupId>
                        <artifactId>%s</artifactId>
                        <version>0.0.1-SNAPSHOT</version>
                    </parent>
                    
                    <artifactId>%s-%s</artifactId>
                    <name>%s</name>
                    
                    <dependencies>
                        <!-- 模块依赖 -->
                    </dependencies>
                </project>
                """.formatted(groupId, projectName, projectName, module, module);
    }

    // ==================== 工具方法 ====================

    private String toPascalCase(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private String toSnakeCase(String str) {
        if (str == null) return "";
        return str.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private String toKebabCase(String str) {
        if (str == null) return "";
        return str.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    // ==================== 数据模型 ====================

    public static class GenerateResult {
        public String projectName;
        public String projectPath;
        public boolean success;
        public String message;
        public List<GenerateItem> items = new ArrayList<>();

        public void addResult(String type, String path, boolean success, String message) {
            items.add(new GenerateItem(type, path, success, message));
        }
    }

    public static class GenerateItem {
        public String type;
        public String path;
        public boolean success;
        public String message;

        public GenerateItem(String type, String path, boolean success, String message) {
            this.type = type;
            this.path = path;
            this.success = success;
            this.message = message;
        }
    }
}
