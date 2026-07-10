package com.devai.devaiplatform.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * 带自动降级能力的 ChatLanguageModel 包装器
 * <p>
 * 工作流程：
 * 1. 优先使用云端模型（DeepSeek）
 * 2. 云端模型调用失败时，自动降级到本地模型（Ollama）
 * 3. 定期探测云端模型是否恢复，恢复后自动切回
 */
public class FallbackChatLanguageModel implements ChatLanguageModel {

    private final ChatLanguageModel primaryModel;   // 云端模型（DeepSeek）
    private final ChatLanguageModel fallbackModel;  // 本地模型（Ollama）
    private final String primaryName;
    private final String fallbackName;
    private final String healthCheckUrl;  // 云端模型健康检查地址（可选）

    // 降级状态管理
    private final AtomicBoolean useFallback = new AtomicBoolean(false);
    private final AtomicLong lastHealthCheckTime = new AtomicLong(0);
    private final long healthCheckIntervalMs;  // 健康检查间隔（毫秒）

    public FallbackChatLanguageModel(ChatLanguageModel primaryModel,
                                      ChatLanguageModel fallbackModel,
                                      String primaryName,
                                      String fallbackName,
                                      String healthCheckUrl,
                                      long healthCheckIntervalSeconds) {
        this.primaryModel = primaryModel;
        this.fallbackModel = fallbackModel;
        this.primaryName = primaryName;
        this.fallbackName = fallbackName;
        this.healthCheckUrl = healthCheckUrl;
        this.healthCheckIntervalMs = healthCheckIntervalSeconds * 1000L;
    }

    @Override
    public String generate(String prompt) {
        // 如果当前处于降级状态，先检查是否应该尝试恢复
        if (useFallback.get()) {
            if (shouldCheckHealth()) {
                if (checkPrimaryAvailable()) {
                    // 云端恢复，切回主模型
                    useFallback.set(false);
                    System.out.println("[LLM降级] ✅ 云端模型 " + primaryName + " 已恢复，自动切回云端");
                }
            }
            // 仍然使用降级模型
            if (useFallback.get()) {
                System.out.println("[LLM降级] 📡 使用本地模型 " + fallbackName + " 处理请求");
                return fallbackModel.generate(prompt);
            }
        }

        // 优先尝试主模型
        try {
            String result = primaryModel.generate(prompt);
            if (result == null || result.isBlank()) {
                throw new RuntimeException("云端模型返回空结果");
            }
            return result;
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            boolean isAuthError = errorMsg != null && (errorMsg.contains("Authentication") || 
                                                       errorMsg.contains("invalid_request_error") ||
                                                       errorMsg.contains("api key"));
            
            if (isAuthError) {
                System.err.println("[LLM降级] ⚠️ 云端模型 API Key 无效: " + errorMsg);
                System.err.println("[LLM降级] 💡 请在 application.properties 中配置正确的 deepseek.api-key");
            } else {
                System.err.println("[LLM降级] ⚠️ 云端模型 " + primaryName + " 调用失败: " + errorMsg);
            }
            System.err.println("[LLM降级] 📡 自动降级到本地模型 " + fallbackName);
            useFallback.set(true);

            // 用本地模型重试
            try {
                String result = fallbackModel.generate(prompt);
                if (result == null || result.isBlank()) {
                    return "[警告] 云端模型不可用，本地模型返回空结果。请检查网络或Ollama服务。";
                }
                return result;
            } catch (Exception ex) {
                System.err.println("[LLM降级] ❌ 本地模型也调用失败: " + ex.getMessage());
                String hint = isAuthError ? 
                    "\n\n【解决方案】请在 application.properties 文件中配置有效的 DeepSeek API Key，或者确保 Ollama 服务正在运行。" :
                    "\n\n【解决方案】请检查网络连接或确保 Ollama 服务已启动（http://127.0.0.1:11434）。";
                return "[错误] 云端模型和本地模型均不可用。" + hint +
                       "\n云端错误: " + errorMsg +
                       "\n本地错误: " + ex.getMessage();
            }
        }
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        // 如果当前处于降级状态，先检查是否应该尝试恢复
        if (useFallback.get()) {
            if (shouldCheckHealth()) {
                if (checkPrimaryAvailable()) {
                    useFallback.set(false);
                    System.out.println("[LLM降级] ✅ 云端模型 " + primaryName + " 已恢复，自动切回云端");
                }
            }
            if (useFallback.get()) {
                System.out.println("[LLM降级] 📡 使用本地模型 " + fallbackName + " 处理请求");
                return fallbackModel.generate(messages);
            }
        }

        try {
            return primaryModel.generate(messages);
        } catch (Exception e) {
            System.err.println("[LLM降级] ⚠️ 云端模型 " + primaryName + " 调用失败: " + e.getMessage());
            System.err.println("[LLM降级] 📡 自动降级到本地模型 " + fallbackName);
            useFallback.set(true);

            try {
                return fallbackModel.generate(messages);
            } catch (Exception ex) {
                System.err.println("[LLM降级] ❌ 本地模型也调用失败: " + ex.getMessage());
                AiMessage errorMsg = AiMessage.from("[错误] 云端和本地模型均不可用，请检查网络或Ollama服务。");
                return Response.from(errorMsg);
            }
        }
    }

    /**
     * 【关键】重写带工具规范的 generate 方法
     * AiServices 调用 @Tool 工具时会走这个方法，必须委托给底层模型
     * 否则接口默认实现会抛出 "Tools are currently not supported by this model"
     */
    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        ChatLanguageModel activeModel = getActiveModel();
        System.out.println("[Fallback] 带工具调用 generate, 使用模型: " +
                (activeModel == primaryModel ? primaryName : fallbackName) +
                ", 工具数: " + (toolSpecifications != null ? toolSpecifications.size() : 0));
        return activeModel.generate(messages, toolSpecifications);
    }

    /**
     * 获取当前活跃的底层模型（带降级逻辑）
     */
    private ChatLanguageModel getActiveModel() {
        if (useFallback.get()) {
            if (shouldCheckHealth()) {
                if (checkPrimaryAvailable()) {
                    useFallback.set(false);
                    System.out.println("[LLM降级] ✅ 云端模型 " + primaryName + " 已恢复，自动切回云端");
                }
            }
            if (useFallback.get()) {
                System.out.println("[LLM降级] 📡 使用本地模型 " + fallbackName + " 处理工具请求");
                return fallbackModel;
            }
        }
        return primaryModel;
    }

    /**
     * 判断是否应该进行健康检查（避免频繁探测）
     */
    private boolean shouldCheckHealth() {
        long now = System.currentTimeMillis();
        long last = lastHealthCheckTime.get();
        if (now - last > healthCheckIntervalMs) {
            lastHealthCheckTime.set(now);
            return true;
        }
        return false;
    }

    /**
     * 探测云端模型是否可用
     * 通过发送一个轻量请求来测试连通性
     */
    private boolean checkPrimaryAvailable() {
        try {
            // 如果有健康检查URL，先做HTTP探测
            if (healthCheckUrl != null && !healthCheckUrl.isBlank()) {
                return checkHttpHealth(healthCheckUrl);
            }
            // 否则直接发一个轻量generate请求测试
            String testResult = primaryModel.generate("hi");
            return testResult != null && !testResult.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * HTTP健康检查（用于探测API端点是否可达）
     */
    private boolean checkHttpHealth(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取当前使用的模型名称（供外部查询状态）
     */
    public String getCurrentModelName() {
        return useFallback.get() ? fallbackName + " (降级中)" : primaryName;
    }

    /**
     * 是否处于降级状态
     */
    public boolean isUsingFallback() {
        return useFallback.get();
    }
}
