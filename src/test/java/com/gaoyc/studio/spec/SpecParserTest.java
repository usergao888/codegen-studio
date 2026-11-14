package com.gaoyc.studio.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.gaoyc.studio.domain.AppSpec;

public class SpecParserTest {

    private final SpecParser parser = new SpecParser();

    @Test
    void detectsTodoArchetypeAndFeatures() {
        AppSpec spec = parser.parse("做一个待办清单，要能筛选和深色模式");
        assertEquals(AppSpec.Archetype.TODO, spec.archetype());
        assertTrue(spec.has("filter"), "应识别出筛选");
        assertTrue(spec.has("dark-mode"), "应识别出深色模式");
        assertFalse(spec.richText());
    }

    @Test
    void detectsDashboardAndChart() {
        AppSpec spec = parser.parse("做一个运营数据看板，要图表");
        assertEquals(AppSpec.Archetype.DASHBOARD, spec.archetype());
        assertTrue(spec.wantsChart());
    }

    @Test
    void fallsBackToLandingForUnknownPrompt() {
        AppSpec spec = parser.parse("随便来个页面");
        assertEquals(AppSpec.Archetype.LANDING, spec.archetype());
    }

    @Test
    void parsesExplicitName() {
        AppSpec spec = parser.parse("做一个待办，叫周计划");
        assertEquals("周计划", spec.title());
    }

    @Test
    void accentIsStableForSamePrompt() {
        String prompt = "做一个待办清单";
        assertEquals(parser.parse(prompt).accent(), parser.parse(prompt).accent());
    }

    @Test
    void desktopPromptTurnsOffMobileFirst() {
        assertFalse(parser.parse("给 PC 端做一个官网").mobileFirst());
        assertTrue(parser.parse("做一个移动端官网").mobileFirst());
    }

    @Test
    void richTextFeatureIsDetected() {
        AppSpec spec = parser.parse("做一个待办应用，支持富文本备注");
        assertTrue(spec.richText());
        assertTrue(spec.features().contains("rich-text"));
    }

    @Test
    void emptyPromptDoesNotThrow() {
        AppSpec spec = parser.parse("");
        assertEquals(AppSpec.Archetype.LANDING, spec.archetype());
        assertEquals(List.of(), spec.features());
    }

    @Test
    void detectsSpaArchetype() {
        AppSpec spec = parser.parse("做一个管理后台，用 Vue 和组件化");
        assertEquals(AppSpec.Archetype.SPA, spec.archetype());
        assertTrue(spec.has("multi-file"), "组件化/工程化应识别为多文件需求");
    }

    @Test
    void englishSpaKeywordWorks() {
        assertEquals(AppSpec.Archetype.SPA, parser.parse("build a vue spa").archetype());
    }

}
