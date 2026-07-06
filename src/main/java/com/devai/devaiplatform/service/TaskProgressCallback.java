package com.devai.devaiplatform.service;

/**
 * 任务执行进度回调，用于SSE实时推送到前端
 */
@FunctionalInterface
public interface TaskProgressCallback {
    void onProgress(String type, String message);
}
