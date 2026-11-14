package com.gaoyc.studio.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.gaoyc.studio.domain.AppSpec;
import com.gaoyc.studio.domain.SiteDraft;
import com.gaoyc.studio.spec.SpecParser;

public class SiteGeneratorTest {

    private final SpecParser parser = new SpecParser();
    private final SiteGenerator generator = new SiteGenerator();

    @Test
    void generatesFourFilesForEveryArchetype() {
        for (String prompt : new String[]{
                "做一个待办清单", "做一个数据看板", "做一个官网落地页",
                "做一个报名表单", "做一个价格方案页", "做一个技术博客"}) {
            SiteDraft draft = generator.generate(parser.parse(prompt));
            assertTrue(draft.has("index.html"), prompt + " 缺 index.html");
            assertTrue(draft.has("styles.css"), prompt + " 缺 styles.css");
            assertTrue(draft.has("app.js"), prompt + " 缺 app.js");
            assertTrue(draft.has("README.md"), prompt + " 缺 README.md");
        }
    }

    @Test
    void plainSiteHasNoExternalAssets() {
        SiteDraft draft = generator.generate(parser.parse("做一个待办清单"));
        assertFalse(draft.file("index.html").contains("https://"));
    }

    @Test
    void iconsFeatureAddsExternalScriptOnPurpose() {
        // 生成器明知外链有问题也会加（对应"需求点名要图标"），这是留给质检去抓的
        SiteDraft draft = generator.generate(parser.parse("做一个待办清单，要图标"));
        assertTrue(draft.file("index.html").contains("https://"));
    }

    @Test
    void richTextProducesInnerHtml() {
        SiteDraft draft = generator.generate(parser.parse("做一个待办，支持富文本备注"));
        assertTrue(draft.file("app.js").contains("innerHTML"));
    }

    @Test
    void outputIsDeterministic() {
        AppSpec spec = parser.parse("做一个待办清单，要筛选");
        SiteDraft first = generator.generate(spec);
        SiteDraft second = generator.generate(spec);
        assertEquals(first.file("index.html"), second.file("index.html"));
        assertEquals(first.file("app.js"), second.file("app.js"));
    }

    @Test
    void htmlHasLangTitleAndCharset() {
        SiteDraft draft = generator.generate(parser.parse("做一个待办清单"));
        String html = draft.file("index.html");
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("lang=\"zh-CN\""));
        assertTrue(html.contains("<title>"));
        assertTrue(html.contains("charset=\"UTF-8\""));
    }

    @Test
    void cssDefinesEveryUsedVariable() {
        SiteDraft draft = generator.generate(parser.parse("做一个待办清单"));
        String css = draft.file("styles.css");
        assertTrue(css.contains("--accent"));
        assertTrue(css.contains("[data-theme=\"dark\"]"));
    }

    @Test
    void spaProjectHasExpectedSkeleton() throws Exception {
        SiteDraft draft = generator.generate(parser.parse("做一个管理后台，用 Vue 和组件化"));
        assertTrue(draft.has("index.html"));
        assertTrue(draft.has("src/App.vue"), "应有 SFC");
        assertTrue(draft.has("src/main.js"));
        assertTrue(draft.has("package.json"));
        assertTrue(draft.has("vite.config.js"));
        assertFalse(draft.has("styles.css"), "样式写在 SFC 里，不应再塞一份全局 css");

        String vue = draft.file("src/App.vue");
        assertTrue(vue.contains("<script setup>"));
        assertTrue(vue.contains("<template>"));

        // package.json 必须是合法 JSON，否则 npm 直接装不上
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(draft.file("package.json"));
        assertTrue(draft.file("index.html").contains("/src/main.js"));
    }

}
