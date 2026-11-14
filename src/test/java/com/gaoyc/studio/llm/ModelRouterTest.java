package com.gaoyc.studio.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.gaoyc.studio.config.StudioProperties;

public class ModelRouterTest {

    private final ModelRouter router = new ModelRouter(new StudioProperties(
            "./var/sites", 1800, 256, 2,
            new StudioProperties.RateLimit(5, 10),
            new StudioProperties.Models("deepseek-chat", "deepseek-reasoner", 120),
            new StudioProperties.Redis(false, "127.0.0.1", 6379, 0, "test:")));

    @Test
    void shortSimplePromptUsesSmallModel() {
        assertEquals("small", router.route("做一个待办").tier());
        assertEquals("deepseek-chat", router.route("做一个待办").model());
    }

    @Test
    void manyComplexHintsEscalateToStrongModel() {
        assertEquals("strong", router.route("做一个看板，要图表、深色模式、筛选").tier());
    }

    @Test
    void longPromptEscalatesToStrongModel() {
        String longPrompt = "做一个后台".repeat(40);
        assertTrue(longPrompt.length() >= 120);
        assertEquals("strong", router.route(longPrompt).tier());
    }

    @Test
    void costGrowsWithPromptLength() {
        double shortCost = router.route("做一个页面").estimateCost(12);
        double longCost = router.route("做一个页面").estimateCost(600);
        assertTrue(longCost > shortCost);
    }
}
