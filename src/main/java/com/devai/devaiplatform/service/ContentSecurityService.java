package com.devai.devaiplatform.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 内容安全校验服务 - 防止数据泄漏和恶意代码注入
 * 
 * 核心功能：
 * 1. 敏感信息检测（密码、密钥、Token、身份证号等）
 * 2. 恶意代码检测（木马、病毒、后门脚本）
 * 3. 本地路径泄漏检测
 * 4. SQL注入/XSS攻击检测
 * 5. 危险命令检测
 */
@Service
public class ContentSecurityService {

    // ==================== 敏感信息正则模式 ====================
    
    /** 密码/密钥模式 */
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
        "(?i)(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)\\s*[:=]\\s*['\"]?[^\\s'\"]{6,}['\"]?",
        Pattern.CASE_INSENSITIVE
    );
    
    /** AWS/云服务密钥 */
    private static final Pattern CLOUD_KEY_PATTERN = Pattern.compile(
        "(AKIA[0-9A-Z]{16}|aws[_-]?secret[_-]?access[_-]?key|azure[_-]?client[_-]?secret)",
        Pattern.CASE_INSENSITIVE
    );
    
    /** 私钥文件内容 */
    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
        "-----BEGIN (RSA |DSA |EC |OPENSSH )?PRIVATE KEY-----",
        Pattern.CASE_INSENSITIVE
    );
    
    /** 数据库连接字符串（含密码） */
    private static final Pattern DB_CONNECTION_PATTERN = Pattern.compile(
        "(jdbc:[a-z]+://[^\\s]+|mongodb(\\+srv)?://[^\\s]+|redis://[^\\s]+).*password[^\\s]*=[^\\s]+",
        Pattern.CASE_INSENSITIVE
    );
    
    /** 中国身份证号 */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile(
        "\\b[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]\\b"
    );
    
    /** 手机号（中国大陆） */
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "\\b1[3-9]\\d{9}\\b"
    );
    
    /** 邮箱地址 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b"
    );
    
    /** 银行卡号 */
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile(
        "\\b(?:\\d{4}[- ]?){3}\\d{4}\\b"
    );
    
    // ==================== 恶意代码检测模式 ====================
    
    /** 危险系统命令 */
    private static final Pattern DANGEROUS_COMMAND_PATTERN = Pattern.compile(
        "(rm\\s+-rf|format\\s+[a-zA-Z]:|del\\s+/[fqs]|mkfs|dd\\s+if=|chmod\\s+777|wget.*\\|.*sh|curl.*\\|.*bash)",
        Pattern.CASE_INSENSITIVE
    );
    
    /** PowerShell恶意命令 */
    private static final Pattern POWERSHELL_MALICIOUS_PATTERN = Pattern.compile(
        "(Invoke-Expression|IEX|DownloadString|DownloadFile|Net\\.WebClient|Start-Process.*-WindowStyle\\s+Hidden)",
        Pattern.CASE_INSENSITIVE
    );
    
    /** Base64编码的恶意载荷 */
    private static final Pattern BASE64_PAYLOAD_PATTERN = Pattern.compile(
        "(powershell|cmd|bash)\\s+.*-[eE]ncodedCommand\\s+[A-Za-z0-9+/=]{50,}"
    );
    
    /** 反弹Shell */
    private static final Pattern REVERSE_SHELL_PATTERN = Pattern.compile(
        "(bash\\s+-i\\s+>&|nc\\s+-e\\s+/bin/sh|python.*socket.*connect|perl.*socket.*INET)",
        Pattern.CASE_INSENSITIVE
    );
    
    /** Webshell特征 */
    private static final Pattern WEBSHELL_PATTERN = Pattern.compile(
        "(eval\\s*\\(|exec\\s*\\(|system\\s*\\(|passthru\\s*\\(|shell_exec\\s*\\(|Runtime\\.getRuntime\\(\\)\\.exec)",
        Pattern.CASE_INSENSITIVE
    );
    
    /** SQL注入攻击 */
    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
        "(union\\s+select|drop\\s+table|delete\\s+from|insert\\s+into.*values|update.*set.*=|or\\s+1\\s*=\\s*1|'\\s*or\\s*')",
        Pattern.CASE_INSENSITIVE
    );
    
    /** XSS攻击 */
    private static final Pattern XSS_PATTERN = Pattern.compile(
        "(<script[^>]*>|javascript:|onload\\s*=|onerror\\s*=|onclick\\s*=|<iframe[^>]*>)",
        Pattern.CASE_INSENSITIVE
    );
    
    /** 本地路径泄漏（Windows/Linux/Mac） */
    private static final Pattern LOCAL_PATH_PATTERN = Pattern.compile(
        "([A-Z]:\\\\[^\\s<>\"|*?]{3,}|/home/[a-z]+/|/root/|/etc/passwd|/var/log/|~/Documents/|~/Desktop/)",
        Pattern.CASE_INSENSITIVE
    );
    
    /** 内网IP地址 */
    private static final Pattern INTERNAL_IP_PATTERN = Pattern.compile(
        "\\b(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2[0-9]|3[01])\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3})\\b"
    );
    
    // ==================== 【最高优先级】下载安装命令拦截（防止病毒安装） ====================
    
    /** Windows下载安装命令 */
    private static final Pattern WINDOWS_INSTALL_PATTERN = Pattern.compile(
        "(winget\\s+install|choco\\s+install|scoop\\s+install|msiexec\\s+/i|start\\s+.*\\.exe|powershell.*Invoke-WebRequest.*-OutFile|certutil.*-urlcache.*-f)",
        Pattern.CASE_INSENSITIVE
    );
    
    /** Linux/Mac下载安装命令 */
    private static final Pattern UNIX_INSTALL_PATTERN = Pattern.compile(
        "(apt(-get)?\\s+(install|update)|yum\\s+install|dnf\\s+install|brew\\s+install|pacman\\s+-S|npm\\s+install|pip(3)?\\s+install|curl.*\\|.*sudo|wget.*\\|.*sh|curl.*-o.*\\|.*bash)",
        Pattern.CASE_INSENSITIVE
    );
    
    /** 通用下载工具（可能被用于下载恶意文件） */
    private static final Pattern DOWNLOAD_TOOL_PATTERN = Pattern.compile(
        "(wget\\s+http|curl\\s+(-O|-o).*http|Invoke-WebRequest\\s+http|Start-BitsTransfer|aria2c\\s+http|axel\\s+http)",
        Pattern.CASE_INSENSITIVE
    );
    
    /** 执行远程脚本（极高危） */
    private static final Pattern REMOTE_SCRIPT_EXEC_PATTERN = Pattern.compile(
        "(curl.*\\|.*(?:ba)?sh|wget.*\\|.*(?:ba)?sh|python.*-c.*urllib|php.*file_get_contents.*http|ruby.*open-uri|perl.*LWP::Simple)",
        Pattern.CASE_INSENSITIVE
    );
    
    /** 可疑的可执行文件下载（.exe, .bat, .ps1, .sh等） */
    private static final Pattern SUSPICIOUS_FILE_DOWNLOAD_PATTERN = Pattern.compile(
        "(\\.exe|\\.bat|\\.cmd|\\.ps1|\\.vbs|\\.js|\\.jar|\\.msi|\\.dll|\\.scr|\\.pif)(\"|'|\\s|$).*(download|fetch|get|save|out-file)",
        Pattern.CASE_INSENSITIVE
    );
    
    /** 提权命令（sudo, su, runas等） */
    private static final Pattern PRIVILEGE_ESCALATION_PATTERN = Pattern.compile(
        "(sudo\\s+|su\\s+-|runas\\s+/user|doas\\s+|pkexec\\s+)",
        Pattern.CASE_INSENSITIVE
    );
    
    // ==================== 安全校验结果 ====================
    
    public static class SecurityCheckResult {
        public final boolean isSafe;
        public final List<String> risks;
        public final String sanitizedContent;
        
        SecurityCheckResult(boolean isSafe, List<String> risks, String sanitizedContent) {
            this.isSafe = isSafe;
            this.risks = risks;
            this.sanitizedContent = sanitizedContent;
        }
        
        public String getRiskSummary() {
            if (risks.isEmpty()) return "✅ 内容安全";
            return "⚠️ 发现 " + risks.size() + " 个安全风险:\n" + String.join("\n", risks);
        }
    }
    
    // ==================== 核心校验方法 ====================
    
    /**
     * 全面安全校验（网络搜索 + RAG检索结果通用）
     * @param content 待校验内容
     * @param source 来源标识（"web_search" / "rag_pdf" / "user_input"）
     * @return 安全校验结果
     */
    public SecurityCheckResult checkContent(String content, String source) {
        if (content == null || content.isBlank()) {
            return new SecurityCheckResult(true, List.of(), "");
        }
        
        List<String> risks = new ArrayList<>();
        String sanitized = content;
        
        System.out.println("[安全检查] 开始校验内容，来源: " + source + "，长度: " + content.length());
        
        // 【最高优先级】0. 下载安装命令拦截（防止病毒安装 - 零容忍）
        if (WINDOWS_INSTALL_PATTERN.matcher(content).find()) {
            risks.add("🚨 **【严重威胁】检测到Windows软件安装命令 - 已强制拦截**");
            sanitized = WINDOWS_INSTALL_PATTERN.matcher(sanitized).replaceAll("[⛔ 已拦截: 禁止执行安装命令]");
        }
        
        if (UNIX_INSTALL_PATTERN.matcher(content).find()) {
            risks.add("🚨 **【严重威胁】检测到Linux/Mac软件安装命令 - 已强制拦截**");
            sanitized = UNIX_INSTALL_PATTERN.matcher(sanitized).replaceAll("[⛔ 已拦截: 禁止执行安装命令]");
        }
        
        if (DOWNLOAD_TOOL_PATTERN.matcher(content).find()) {
            risks.add("🚨 **【严重威胁】检测到文件下载命令 - 可能包含恶意软件**");
            sanitized = DOWNLOAD_TOOL_PATTERN.matcher(sanitized).replaceAll("[⛔ 已拦截: 禁止下载外部文件]");
        }
        
        if (REMOTE_SCRIPT_EXEC_PATTERN.matcher(content).find()) {
            risks.add("🚨 **【严重威胁】检测到远程脚本执行 - 极高危操作**");
            sanitized = REMOTE_SCRIPT_EXEC_PATTERN.matcher(sanitized).replaceAll("[⛔ 已拦截: 禁止执行远程脚本]");
        }
        
        if (SUSPICIOUS_FILE_DOWNLOAD_PATTERN.matcher(content).find()) {
            risks.add("🚨 **【严重威胁】检测到可疑可执行文件下载 - 疑似病毒传播**");
            sanitized = SUSPICIOUS_FILE_DOWNLOAD_PATTERN.matcher(sanitized).replaceAll("[⛔ 已拦截: 禁止下载可执行文件]");
        }
        
        if (PRIVILEGE_ESCALATION_PATTERN.matcher(content).find()) {
            risks.add("🚨 **【严重威胁】检测到提权命令 - 可能导致系统被控制**");
            sanitized = PRIVILEGE_ESCALATION_PATTERN.matcher(sanitized).replaceAll("[⛔ 已拦截: 禁止提权操作]");
        }
        
        // 1. 敏感信息检测
        if (PASSWORD_PATTERN.matcher(content).find()) {
            risks.add("🔴 检测到密码/密钥信息");
            sanitized = PASSWORD_PATTERN.matcher(sanitized).replaceAll("[已隐藏敏感信息]");
        }
        
        if (CLOUD_KEY_PATTERN.matcher(content).find()) {
            risks.add("🔴 检测到云服务密钥（AWS/Azure等）");
            sanitized = CLOUD_KEY_PATTERN.matcher(sanitized).replaceAll("[已隐藏云密钥]");
        }
        
        if (PRIVATE_KEY_PATTERN.matcher(content).find()) {
            risks.add("🔴 检测到私钥文件内容");
            sanitized = PRIVATE_KEY_PATTERN.matcher(sanitized).replaceAll("[已隐藏私钥]");
        }
        
        if (DB_CONNECTION_PATTERN.matcher(content).find()) {
            risks.add("🟡 检测到数据库连接字符串（可能含密码）");
            sanitized = DB_CONNECTION_PATTERN.matcher(sanitized).replaceAll("[已隐藏数据库连接]");
        }
        
        // 2. 个人隐私信息检测
        if (ID_CARD_PATTERN.matcher(content).find()) {
            risks.add("🟡 检测到身份证号码");
            sanitized = ID_CARD_PATTERN.matcher(sanitized).replaceAll("[已隐藏身份证号]");
        }
        
        if (PHONE_PATTERN.matcher(content).find()) {
            risks.add("🟡 检测到手机号码");
            sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll("[已隐藏手机号]");
        }
        
        if (BANK_CARD_PATTERN.matcher(content).find()) {
            risks.add("🟡 检测到银行卡号");
            sanitized = BANK_CARD_PATTERN.matcher(sanitized).replaceAll("[已隐藏银行卡号]");
        }
        
        // 3. 恶意代码检测
        if (DANGEROUS_COMMAND_PATTERN.matcher(content).find()) {
            risks.add("🔴 检测到危险系统命令（可能为恶意脚本）");
            sanitized = DANGEROUS_COMMAND_PATTERN.matcher(sanitized).replaceAll("[已拦截危险命令]");
        }
        
        if (POWERSHELL_MALICIOUS_PATTERN.matcher(content).find()) {
            risks.add("🔴 检测到PowerShell恶意命令");
            sanitized = POWERSHELL_MALICIOUS_PATTERN.matcher(sanitized).replaceAll("[已拦截恶意PowerShell]");
        }
        
        if (BASE64_PAYLOAD_PATTERN.matcher(content).find()) {
            risks.add("🔴 检测到Base64编码的恶意载荷");
            sanitized = BASE64_PAYLOAD_PATTERN.matcher(sanitized).replaceAll("[已拦截编码载荷]");
        }
        
        if (REVERSE_SHELL_PATTERN.matcher(content).find()) {
            risks.add("🔴 检测到反弹Shell攻击代码");
            sanitized = REVERSE_SHELL_PATTERN.matcher(sanitized).replaceAll("[已拦截反弹Shell]");
        }
        
        if (WEBSHELL_PATTERN.matcher(content).find()) {
            risks.add("🔴 检测到Webshell后门代码");
            sanitized = WEBSHELL_PATTERN.matcher(sanitized).replaceAll("[已拦截Webshell]");
        }
        
        // 4. 注入攻击检测
        if (SQL_INJECTION_PATTERN.matcher(content).find()) {
            risks.add("🟡 检测到疑似SQL注入攻击");
            sanitized = SQL_INJECTION_PATTERN.matcher(sanitized).replaceAll("[已拦截SQL注入]");
        }
        
        if (XSS_PATTERN.matcher(content).find()) {
            risks.add("🟡 检测到XSS跨站脚本攻击");
            sanitized = XSS_PATTERN.matcher(sanitized).replaceAll("[已拦截XSS攻击]");
        }
        
        // 5. 数据泄漏检测
        if (LOCAL_PATH_PATTERN.matcher(content).find()) {
            risks.add("🟡 检测到本地路径泄漏");
            sanitized = LOCAL_PATH_PATTERN.matcher(sanitized).replaceAll("[已隐藏本地路径]");
        }
        
        if (INTERNAL_IP_PATTERN.matcher(content).find()) {
            risks.add("🟡 检测到内网IP地址泄漏");
            sanitized = INTERNAL_IP_PATTERN.matcher(sanitized).replaceAll("[已隐藏内网IP]");
        }
        
        boolean isSafe = risks.stream().noneMatch(r -> r.startsWith("🔴"));
        
        System.out.println("[安全检查] 校验完成，风险数: " + risks.size() + "，安全状态: " + (isSafe ? "✅" : "❌"));
        if (!risks.isEmpty()) {
            risks.forEach(risk -> System.out.println("  " + risk));
        }
        
        return new SecurityCheckResult(isSafe, risks, sanitized);
    }
    
    /**
     * 快速校验（仅检测高危项，用于实时交互场景）
     */
    public SecurityCheckResult quickCheck(String content) {
        if (content == null || content.isBlank()) {
            return new SecurityCheckResult(true, List.of(), "");
        }
        
        List<String> risks = new ArrayList<>();
        String sanitized = content;
        
        // 仅检测最高危的项目
        if (PRIVATE_KEY_PATTERN.matcher(content).find()) {
            risks.add("🔴 检测到私钥内容");
            sanitized = PRIVATE_KEY_PATTERN.matcher(sanitized).replaceAll("[已隐藏]");
        }
        
        if (REVERSE_SHELL_PATTERN.matcher(content).find()) {
            risks.add("🔴 检测到反弹Shell");
            sanitized = REVERSE_SHELL_PATTERN.matcher(sanitized).replaceAll("[已拦截]");
        }
        
        if (WEBSHELL_PATTERN.matcher(content).find()) {
            risks.add("🔴 检测到Webshell");
            sanitized = WEBSHELL_PATTERN.matcher(sanitized).replaceAll("[已拦截]");
        }
        
        boolean isSafe = risks.isEmpty();
        return new SecurityCheckResult(isSafe, risks, sanitized);
    }
    
    /**
     * 对网络搜索结果进行安全过滤
     */
    public String sanitizeWebSearchResult(String webContent) {
        SecurityCheckResult result = checkContent(webContent, "web_search");
        if (!result.isSafe) {
            System.out.println("[安全检查] ⚠️ 网络搜索结果包含安全风险，已自动清理");
        }
        return result.sanitizedContent;
    }
    
    /**
     * 对RAG检索结果进行安全过滤
     */
    public String sanitizeRagResult(String ragContent) {
        SecurityCheckResult result = checkContent(ragContent, "rag_pdf");
        if (!result.isSafe) {
            System.out.println("[安全检查] ⚠️ RAG检索结果包含安全风险，已自动清理");
        }
        return result.sanitizedContent;
    }
}
