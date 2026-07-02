package com.devai.devaiplatform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 本地文件操作服务
 * 提供安全的文件读写、目录创建、文件删除等操作
 * 包含路径安全校验和白名单机制
 */
@Service
public class LocalFileOperationService {

    // 允许操作的根路径白名单（可在配置文件中设置）
    @Value("${file.allowed-paths:#{T(java.util.Collections).emptyList()}}")
    private List<String> allowedPaths;

    // 危险路径模式（系统目录等）
    private static final List<String> DANGEROUS_PATHS = List.of(
            "C:\\Windows", "C:\\Program Files", "C:\\Program Files (x86)",
            "/etc", "/usr", "/bin", "/sbin", "/boot", "/dev"
    );

    // 危险文件扩展名
    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
            "exe", "dll", "sys", "bat", "cmd", "sh", "ps1"
    );

    /**
     * 创建目录（支持多级目录）
     *
     * @param dirPath 目录路径
     * @return 创建结果
     */
    public FileOperationResult createDirectory(String dirPath) {
        FileOperationResult result = new FileOperationResult();
        result.operation = "CREATE_DIRECTORY";
        result.targetPath = dirPath;

        try {
            // 安全校验
            String securityError = validatePath(dirPath, false);
            if (securityError != null) {
                result.success = false;
                result.message = securityError;
                return result;
            }

            Path path = Paths.get(dirPath);
            if (Files.exists(path)) {
                result.success = true;
                result.message = "目录已存在: " + dirPath;
                return result;
            }

            Files.createDirectories(path);
            result.success = true;
            result.message = "目录创建成功: " + dirPath;
            System.out.println("[文件操作] 创建目录: " + dirPath);

        } catch (Exception e) {
            result.success = false;
            result.message = "创建目录失败: " + e.getMessage();
            System.err.println("[文件操作] " + result.message);
        }

        return result;
    }

    /**
     * 创建文件（自动创建父目录）
     *
     * @param filePath 文件路径
     * @param content  文件内容（可为空）
     * @return 创建结果
     */
    public FileOperationResult createFile(String filePath, String content) {
        FileOperationResult result = new FileOperationResult();
        result.operation = "CREATE_FILE";
        result.targetPath = filePath;

        try {
            // 安全校验
            String securityError = validatePath(filePath, true);
            if (securityError != null) {
                result.success = false;
                result.message = securityError;
                return result;
            }

            Path path = Paths.get(filePath);

            // 自动创建父目录
            Path parentDir = path.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                System.out.println("[文件操作] 自动创建父目录: " + parentDir);
            }

            // 写入文件
            if (content == null) {
                content = "";
            }
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));

            result.success = true;
            result.message = "文件创建成功: " + filePath;
            result.fileSize = Files.size(path);
            System.out.println("[文件操作] 创建文件: " + filePath + " (" + result.fileSize + " bytes)");

        } catch (Exception e) {
            result.success = false;
            result.message = "创建文件失败: " + e.getMessage();
            System.err.println("[文件操作] " + result.message);
        }

        return result;
    }

    /**
     * 批量创建文件
     *
     * @param files 文件列表（路径 -> 内容）
     * @return 批量操作结果
     */
    public BatchOperationResult batchCreateFiles(Map<String, String> files) {
        BatchOperationResult batchResult = new BatchOperationResult();
        batchResult.operation = "BATCH_CREATE_FILES";

        for (Map.Entry<String, String> entry : files.entrySet()) {
            FileOperationResult result = createFile(entry.getKey(), entry.getValue());
            batchResult.results.add(result);
            if (result.success) {
                batchResult.successCount++;
            } else {
                batchResult.failCount++;
            }
        }

        batchResult.success = (batchResult.failCount == 0);
        batchResult.message = String.format("批量创建完成，成功: %d, 失败: %d",
                batchResult.successCount, batchResult.failCount);

        return batchResult;
    }

    /**
     * 读取文件内容
     *
     * @param filePath 文件路径
     * @return 文件内容
     */
    public String readFile(String filePath) {
        try {
            String securityError = validatePath(filePath, true);
            if (securityError != null) {
                return "（读取失败: " + securityError + "）";
            }

            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                return "（文件不存在: " + filePath + "）";
            }

            return Files.readString(path, StandardCharsets.UTF_8);

        } catch (Exception e) {
            return "（读取失败: " + e.getMessage() + "）";
        }
    }

    /**
     * 列出目录内容
     *
     * @param dirPath   目录路径
     * @param recursive 是否递归列出
     * @return 目录内容列表
     */
    public List<FileItem> listDirectory(String dirPath, boolean recursive) {
        List<FileItem> items = new ArrayList<>();

        try {
            String securityError = validatePath(dirPath, false);
            if (securityError != null) {
                items.add(new FileItem(dirPath, "ERROR", securityError));
                return items;
            }

            Path path = Paths.get(dirPath);
            if (!Files.exists(path)) {
                items.add(new FileItem(dirPath, "ERROR", "目录不存在"));
                return items;
            }

            if (recursive) {
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        items.add(new FileItem(
                                file.toString(),
                                "FILE",
                                attrs.size() + " bytes"
                        ));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(path)) {
                            items.add(new FileItem(
                                    dir.toString(),
                                    "DIR",
                                    ""
                            ));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                try (Stream<Path> stream = Files.list(path)) {
                    stream.forEach(p -> {
                        try {
                            boolean isDir = Files.isDirectory(p);
                            long size = isDir ? 0 : Files.size(p);
                            items.add(new FileItem(
                                    p.toString(),
                                    isDir ? "DIR" : "FILE",
                                    size + " bytes"
                            ));
                        } catch (IOException e) {
                            items.add(new FileItem(p.toString(), "ERROR", e.getMessage()));
                        }
                    });
                }
            }

        } catch (Exception e) {
            items.add(new FileItem(dirPath, "ERROR", e.getMessage()));
        }

        return items;
    }

    /**
     * 删除文件（谨慎使用）
     *
     * @param filePath 文件路径
     * @return 删除结果
     */
    public FileOperationResult deleteFile(String filePath) {
        FileOperationResult result = new FileOperationResult();
        result.operation = "DELETE_FILE";
        result.targetPath = filePath;

        try {
            String securityError = validatePath(filePath, true);
            if (securityError != null) {
                result.success = false;
                result.message = securityError;
                return result;
            }

            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                result.success = true;
                result.message = "文件不存在，无需删除: " + filePath;
                return result;
            }

            Files.delete(path);
            result.success = true;
            result.message = "文件删除成功: " + filePath;
            System.out.println("[文件操作] 删除文件: " + filePath);

        } catch (Exception e) {
            result.success = false;
            result.message = "删除文件失败: " + e.getMessage();
        }

        return result;
    }

    /**
     * 复制文件
     *
     * @param sourcePath 源文件路径
     * @param targetPath 目标文件路径
     * @return 复制结果
     */
    public FileOperationResult copyFile(String sourcePath, String targetPath) {
        FileOperationResult result = new FileOperationResult();
        result.operation = "COPY_FILE";
        result.targetPath = targetPath;

        try {
            String srcError = validatePath(sourcePath, true);
            if (srcError != null) {
                result.success = false;
                result.message = "源文件校验失败: " + srcError;
                return result;
            }

            String tgtError = validatePath(targetPath, true);
            if (tgtError != null) {
                result.success = false;
                result.message = "目标路径校验失败: " + tgtError;
                return result;
            }

            Path source = Paths.get(sourcePath);
            Path target = Paths.get(targetPath);

            if (!Files.exists(source)) {
                result.success = false;
                result.message = "源文件不存在: " + sourcePath;
                return result;
            }

            // 自动创建目标目录
            Path parentDir = target.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            result.success = true;
            result.message = "文件复制成功: " + sourcePath + " -> " + targetPath;
            System.out.println("[文件操作] " + result.message);

        } catch (Exception e) {
            result.success = false;
            result.message = "复制文件失败: " + e.getMessage();
        }

        return result;
    }

    /**
     * 移动/重命名文件
     *
     * @param sourcePath 源文件路径
     * @param targetPath 目标文件路径
     * @return 移动结果
     */
    public FileOperationResult moveFile(String sourcePath, String targetPath) {
        FileOperationResult result = new FileOperationResult();
        result.operation = "MOVE_FILE";
        result.targetPath = targetPath;

        try {
            String srcError = validatePath(sourcePath, true);
            if (srcError != null) {
                result.success = false;
                result.message = "源文件校验失败: " + srcError;
                return result;
            }

            String tgtError = validatePath(targetPath, true);
            if (tgtError != null) {
                result.success = false;
                result.message = "目标路径校验失败: " + tgtError;
                return result;
            }

            Path source = Paths.get(sourcePath);
            Path target = Paths.get(targetPath);

            if (!Files.exists(source)) {
                result.success = false;
                result.message = "源文件不存在: " + sourcePath;
                return result;
            }

            // 自动创建目标目录
            Path parentDir = target.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            result.success = true;
            result.message = "文件移动成功: " + sourcePath + " -> " + targetPath;
            System.out.println("[文件操作] " + result.message);

        } catch (Exception e) {
            result.success = false;
            result.message = "移动文件失败: " + e.getMessage();
        }

        return result;
    }

    /**
     * 检查路径是否存在
     */
    public boolean exists(String path) {
        return Files.exists(Paths.get(path));
    }

    /**
     * 检查是否为目录
     */
    public boolean isDirectory(String path) {
        return Files.isDirectory(Paths.get(path));
    }

    // ==================== 安全校验 ====================

    /**
     * 路径安全校验
     *
     * @param path     待校验路径
     * @param isFile   是否为文件（true=文件，false=目录）
     * @return 校验失败返回错误信息，成功返回null
     */
    private String validatePath(String path, boolean isFile) {
        if (path == null || path.isBlank()) {
            return "路径不能为空";
        }

        // 规范化路径
        String normalizedPath;
        try {
            normalizedPath = Paths.get(path).normalize().toAbsolutePath().toString();
        } catch (InvalidPathException e) {
            return "无效的路径格式: " + e.getMessage();
        }

        // 检查危险路径
        for (String dangerous : DANGEROUS_PATHS) {
            if (normalizedPath.startsWith(dangerous)) {
                return "不允许操作系统目录: " + dangerous;
            }
        }

        // 检查路径遍历攻击
        if (path.contains("..") || path.contains("%") || path.contains("$")) {
            return "路径包含非法字符";
        }

        // 检查文件扩展名（如果是文件）
        if (isFile) {
            String extension = getFileExtension(path);
            if (DANGEROUS_EXTENSIONS.contains(extension.toLowerCase())) {
                return "不允许创建此类型文件: " + extension;
            }
        }

        // 白名单校验（如果配置了白名单）
        if (allowedPaths != null && !allowedPaths.isEmpty()) {
            boolean inWhitelist = allowedPaths.stream()
                    .anyMatch(normalizedPath::startsWith);
            if (!inWhitelist) {
                return "路径不在允许范围内。允许的路径: " + String.join(", ", allowedPaths);
            }
        }

        return null; // 校验通过
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String path) {
        int lastDot = path.lastIndexOf('.');
        if (lastDot == -1) return "";
        return path.substring(lastDot + 1);
    }

    // ==================== 数据模型 ====================

    /**
     * 单次文件操作结果
     */
    public static class FileOperationResult {
        public String operation;
        public String targetPath;
        public boolean success;
        public String message;
        public long fileSize;
    }

    /**
     * 批量操作结果
     */
    public static class BatchOperationResult {
        public String operation;
        public boolean success;
        public String message;
        public int successCount;
        public int failCount;
        public List<FileOperationResult> results = new ArrayList<>();
    }

    /**
     * 文件/目录项
     */
    public static class FileItem {
        public String path;
        public String type; // FILE, DIR, ERROR
        public String info;

        public FileItem(String path, String type, String info) {
            this.path = path;
            this.type = type;
            this.info = info;
        }
    }
}

