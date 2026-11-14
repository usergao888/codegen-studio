package com.gaoyc.studio.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gaoyc.studio.domain.SiteDraft;

public class MultiFileLayoutTest {

    private SiteDraft flat() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("index.html", """
                <html><head><link rel="stylesheet" href="styles.css"></head>
                <body><script src="app.js"></script></body></html>""");
        files.put("styles.css", "body{}");
        files.put("app.js", "console.log(1)");
        files.put("README.md", "# 说明");
        return SiteDraft.of(files);
    }

    @Test
    void movesAssetsIntoSubdirectories() {
        SiteDraft nested = MultiFileLayout.apply(flat());
        assertTrue(nested.has("assets/css/styles.css"));
        assertTrue(nested.has("assets/js/app.js"));
        assertFalse(nested.has("styles.css"), "原路径不应残留");
        assertFalse(nested.has("app.js"));
        assertEquals("# 说明", nested.file("README.md"), "其他文件原样保留");
    }

    @Test
    void rewritesReferencesInHtml() {
        SiteDraft nested = MultiFileLayout.apply(flat());
        String html = nested.file("index.html");
        assertTrue(html.contains("href=\"assets/css/styles.css\""));
        assertTrue(html.contains("src=\"assets/js/app.js\""));
        assertFalse(html.contains("href=\"styles.css\""));
    }

    @Test
    void isIdempotent() {
        SiteDraft once = MultiFileLayout.apply(flat());
        SiteDraft twice = MultiFileLayout.apply(once);
        assertEquals(once.files(), twice.files(), "重复应用不应改变结果");
        assertTrue(MultiFileLayout.isNested(twice));
    }

    @Test
    void leavesDraftWithoutEntryUntouched() {
        SiteDraft noHtml = SiteDraft.of(Map.of("styles.css", "body{}"));
        assertEquals(noHtml.files(), MultiFileLayout.apply(noHtml).files());
    }
}
