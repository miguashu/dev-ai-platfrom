package com.devai.devaiplatform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IDEA错误分析服务
 * 功能：执行Maven编译 → 解析错误输出 → 读取源码上下文 → 生成AI修复建议
 */
@Service
public class IdeaErrorAnalyzerService {

    @Value("${project.build.project-path:#{null}}")
    private String projectPath;

    @Value("${project.build.maven-cmd:mvn}")
    private String mavenCmd;

    // 匹配Maven编译错误的正则表达式
    // 格式: [ERROR] /path/to/File.java:[行号,列号] error: 错误信息
    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "\\[ERROR\\]\\s+(.+\\.java):\\[(\\d+),(\\d+)\\]\\s+(.*)"
    );

    // 匹配简化格式: File.java:行号: error: 错误信息
    private static final Pattern SIMPLE_ERROR_PATTERN = Pattern.compile(
            "(.+\\.java):(\\d+):\\s*(?:error:)?\\s*(.*)"
    );

    // 匹配符号找不到错误
    private static final Pattern SYMBOL_NOT_FOUND_PATTERN = Pattern.compile(
            "找不到符号|cannot find symbol|symbol:\\s*(.+)location:\\s*(.+)"
    );

    /**
     * 执行Maven编译并收集所有错误
     *
     * @param projectDir 项目目录（为空时使用配置的项目路径）
     * @return 编译错误分析结果
     */
    public CompileResult scanCompileErrors(String projectDir) {
        String targetDir = (projectDir != null && !projectDir.isBlank()) ? projectDir : projectPath;
        if (targetDir == null || targetDir.isBlank()) {
            targetDir = System.getProperty("user.dir");
        }

        System.out.println("[IDEA错误分析] 开始编译扫描，项目路径: " + targetDir);

        CompileResult result = new CompileResult();
        result.projectPath = targetDir;

        try {
            // 执行Maven编译命令（只编译不测试）
            ProcessBuilder pb = new ProcessBuilder();
            String os = System.getProperty("os.name").toLowerCase();
            
            if (os.contains("win")) {
                pb.command("cmd", "/c", mavenCmd + " compile -f \"" + targetDir + "\\pom.xml\" 2>&1");
            } else {
                pb.command("sh", "-c", mavenCmd + " compile -f \"" + targetDir + "/pom.xml\" 2>&1");
            }
            
            pb.directory(new File(targetDir));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    parseErrorLine(line, result);
                }
            }

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.success = false;
                result.message = "编译超时（超过120秒），请检查项目配置";
                return result;
            }

            result.rawOutput = output.toString();
            result.exitCode = process.exitValue();
            result.success = (result.exitCode == 0 && result.errors.isEmpty());

            if (result.success) {
                result.message = "编译成功，无错误！";
            } else {
                result.message = String.format("发现 %d 个编译错误", result.errors.size());
            }

            System.out.println("[IDEA错误分析] " + result.message);

        } catch (Exception e) {
            result.success = false;
            result.message = "执行编译失败: " + e.getMessage();
            System.err.println("[IDEA错误分析] " + result.message);
        }

        return result;
    }

    /**
     * 解析单行错误输出
     */
    private void parseErrorLine(String line, CompileResult result) {
        if (!line.contains("[ERROR]")) {
            return;
        }

        // 尝试标准格式匹配
        Matcher matcher = ERROR_PATTERN.matcher(line);
        if (matcher.find()) {
            CompileError error = new CompileError();
            error.filePath = matcher.group(1).trim();
            error.line = Integer.parseInt(matcher.group(2));
            error.column = Integer.parseInt(matcher.group(3));
            error.message = matcher.group(4).trim();
            error.errorType = classifyError(error.message);
            result.errors.add(error);
            return;
        }

        // 尝试简化格式匹配
        Matcher simpleMatcher = SIMPLE_ERROR_PATTERN.matcher(line);
        if (simpleMatcher.find()) {
            CompileError error = new CompileError();
            error.filePath = simpleMatcher.group(1).trim();
            error.line = Integer.parseInt(simpleMatcher.group(2));
            error.column = 0;
            error.message = simpleMatcher.group(3).trim();
            error.errorType = classifyError(error.message);
            result.errors.add(error);
        }
    }

    /**
     * 错误分类
     */
    private String classifyError(String message) {
        if (message == null) return "UNKNOWN";
        String lower = message.toLowerCase();
        if (lower.contains("找不到符号") || lower.contains("cannot find symbol")) return "SYMBOL_NOT_FOUND";
        if (lower.contains("不兼容的类型") || lower.contains("incompatible types")) return "TYPE_MISMATCH";
        if (lower.contains("无法将类") || lower.contains("cannot be applied")) return "METHOD_MISMATCH";
        if (lower.contains("不是抽象的") || lower.contains("not abstract")) return "ABSTRACT_ERROR";
        if (lower.contains("package") && lower.contains("does not exist")) return "PACKAGE_NOT_FOUND";
        if (lower.contains("非法") || lower.contains("illegal")) return "ILLEGAL_USAGE";
        if (lower.contains("未报告") || lower.contains("unreported exception")) return "UNCHECKED_EXCEPTION";
        if (lower.contains("已过时") || lower.contains("deprecated")) return "DEPRECATED";
        return "OTHER";
    }

    /**
     * 读取错误位置的源码上下文
     *
     * @param error 编译错误
     * @param contextLines 上下文行数（默认前后各5行）
     * @return 带上下文的源码片段
     */
    public String readErrorContext(CompileError error, int contextLines) {
        if (error.filePath == null) {
            return "（无法读取源码：文件路径为空）";
        }

        try {
            Path path = Paths.get(error.filePath);
            if (!Files.exists(path)) {
                return "（无法读取源码：文件不存在 " + error.filePath + "）";
            }

            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int errorLine = error.line - 1; // 转为0-based
            int startLine = Math.max(0, errorLine - contextLines);
            int endLine = Math.min(lines.size(), errorLine + contextLines + 1);

            StringBuilder sb = new StringBuilder();
            sb.append("文件: ").append(error.filePath).append("\n");
            sb.append("错误行: ").append(error.line).append("\n");
            sb.append("错误信息: ").append(error.message).append("\n");
            sb.append("错误类型: ").append(error.errorType).append("\n\n");
            sb.append("========== 源码上下文 ==========\n");

            for (int i = startLine; i < endLine; i++) {
                String prefix = (i == errorLine) ? ">>> " : "    ";
                sb.append(String.format("%s%d | %s\n", prefix, i + 1, lines.get(i)));
            }

            return sb.toString();

        } catch (Exception e) {
            return "（读取源码失败: " + e.getMessage() + "）";
        }
    }

    /**
     * 批量读取所有错误的源码上下文
     */
    public String readAllErrorsWithContext(CompileResult result, int contextLines) {
        if (result.errors.isEmpty()) {
            return "无编译错误";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("========== 编译错误汇总 ==========\n");
        sb.append("项目路径: ").append(result.projectPath).append("\n");
        sb.append("错误总数: ").append(result.errors.size()).append("\n\n");

        // 按文件分组
        result.errors.stream()
                .collect(java.util.stream.Collectors.groupingBy(e -> e.filePath != null ? e.filePath : "unknown"))
                .forEach((file, errors) -> {
                    sb.append("---------- ").append(file).append(" ----------\n");
                    for (CompileError error : errors) {
                        sb.append(readErrorContext(error, contextLines));
                        sb.append("\n");
                    }
                });

        return sb.toString();
    }

    /**
     * 生成AI修复所需的完整上下文
     */
    public String buildFixContext(CompileResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 编译错误信息\n\n");

        for (int i = 0; i < result.errors.size(); i++) {
            CompileError error = result.errors.get(i);
            sb.append("### 错误 ").append(i + 1).append("\n");
            sb.append("- 文件: ").append(error.filePath).append("\n");
            sb.append("- 位置: 第 ").append(error.line).append(" 行");
            if (error.column > 0) {
                sb.append(", 第 ").append(error.column).append(" 列");
            }
            sb.append("\n");
            sb.append("- 类型: ").append(error.errorType).append("\n");
            sb.append("- 信息: ").append(error.message).append("\n\n");

            // 附加源码上下文
            String context = readErrorContext(error, 5);
            sb.append("```\n").append(context).append("```\n\n");
        }

        return sb.toString();
    }

    // ==================== 数据模型 ====================

    /**
     * 编译结果
     */
    public static class CompileResult {
        public String projectPath;
        public boolean success;
        public int exitCode;
        public String message;
        public String rawOutput;
        public List<CompileError> errors = new ArrayList<>();

        public String getSummary() {
            if (success) return "编译成功，无错误";
            return String.format("编译失败，共 %d 个错误。错误类型分布: %s",
                    errors.size(), getErrorTypeDistribution());
        }

        public String getErrorTypeDistribution() {
            return errors.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> e.errorType, java.util.stream.Collectors.counting()))
                    .entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("无");
        }
    }

    /**
     * 单个编译错误
     */
    public static class CompileError {
        public String filePath;
        public int line;
        public int column;
        public String message;
        public String errorType;

        @Override
        public String toString() {
            return String.format("[%s] %s:%d - %s", errorType, filePath, line, message);
        }
    }
}
