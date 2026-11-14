package com.gaoyc.studio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI 零代码应用生成平台。
 *
 * <p>用户用一句话描述需求，平台跑一条六节点的 LangGraph4j 工作流：
 * 需求解析 → 提示词增强 → 代码生成 → 质检 → 定向修复（循环）→ 构建发布。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class StudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudioApplication.class, args);
    }
}
