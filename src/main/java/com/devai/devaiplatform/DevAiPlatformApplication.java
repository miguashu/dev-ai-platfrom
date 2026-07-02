package com.devai.devaiplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;  // 【新增】启用定时任务
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
@EnableScheduling  // 【新增】启用Spring定时任务功能
public class DevAiPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevAiPlatformApplication.class, args);
        log.info("=== Dev AI Platform 启动成功 ===");
    }

}
