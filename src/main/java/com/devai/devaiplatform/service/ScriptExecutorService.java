package com.devai.devaiplatform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 脚本执行服务 - 执行scripts目录下的预定义脚本
 * AI通过Tool调用此服务，实现本地文件操作
 */
@Service
public class ScriptExecutorService {

    @Value("${script.base-dir:./scripts}")
    private String scriptBaseDir;

    @Value("${script.timeout-seconds:30}")
    private int timeoutSeconds;

    /**
     * 执行指定脚本，传入参数
     * @param scriptName 脚本文件名（如 file_list.bat）
     * @param args 脚本参数
     * @return 执行输出结果
     */
    public String execute(String scriptName, String... args) {
        Path scriptPath = resolveScriptPath(scriptName);

        if (scriptPath == null || !Files.exists(scriptPath)) {
            return "[错误] 脚本不存在: " + scriptName + "，可用脚本: " + listAvailableScripts();
        }

        try {
            // 构建命令
            ProcessBuilder pb;
            String ext = getExtension(scriptName).toLowerCase();
            if (ext.equals("bat") || ext.equals("cmd")) {
                pb = new ProcessBuilder("cmd.exe", "/c", scriptPath.toAbsolutePath().toString());
            } else if (ext.equals("ps1")) {
                pb = new ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", scriptPath.toAbsolutePath().toString());
            } else if (ext.equals("sh")) {
                pb = new ProcessBuilder("bash", scriptPath.toAbsolutePath().toString());
            } else {
                return "[错误] 不支持的脚本类型: " + ext;
            }

            // 添加参数
            for (String arg : args) {
                if (arg != null && !arg.isBlank()) {
                    pb.command().add(arg);
                }
            }

            // 设置工作目录为脚本所在目录
            pb.directory(scriptPath.getParent().toFile());
            pb.redirectErrorStream(true);

            System.out.println("[脚本执行] " + String.join(" ", pb.command()));

            Process process = pb.start();

            // 读取输出
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            // 等待执行完成，超时则终止
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "[超时] 脚本执行超时(" + timeoutSeconds + "秒): " + scriptName;
            }

            int exitCode = process.exitValue();
            String result = output.isEmpty() ? "(脚本执行成功，无输出)" : output;
            System.out.println("[脚本执行] 退出码: " + exitCode + ", 输出长度: " + output.length());

            return result;

        } catch (Exception e) {
            System.err.println("[脚本执行] 失败: " + e.getMessage());
            return "[错误] 脚本执行失败: " + e.getMessage();
        }
    }

    /**
     * 列出scripts目录下所有可用脚本
     */
    public String listAvailableScripts() {
        Path dir = Paths.get(scriptBaseDir).toAbsolutePath();
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return "(scripts目录不存在: " + dir + ")";
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.joining(", "));
        } catch (Exception e) {
            return "(读取scripts目录失败: " + e.getMessage() + ")";
        }
    }

    private Path resolveScriptPath(String scriptName) {
        // 防止路径遍历攻击
        if (scriptName.contains("..") || scriptName.contains("/") || scriptName.contains("\\")) {
            return null;
        }
        return Paths.get(scriptBaseDir).toAbsolutePath().resolve(scriptName).normalize();
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1);
    }
}
