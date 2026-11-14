package com.gaoyc.studio.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gaoyc.studio.config.StudioProperties;
import com.gaoyc.studio.domain.SiteDraft;

public class SitePublisherTest {

    private static StudioProperties props(Path workspace) {
        return new StudioProperties(workspace.toString(), 1800, 16, 2,
                StudioProperties.RateLimit.standard(5, 10),
                new StudioProperties.Models("small", "strong", 120),
                new StudioProperties.Redis(false, "127.0.0.1", 6379, 0, "t:"),
                false);
    }

    @Test
    void createsNestedDirectoriesForMultiFileArtifacts() throws Exception {
        Path workspace = Files.createTempDirectory("studio-publish-");
        SitePublisher publisher = new SitePublisher(props(workspace));

        Map<String, String> files = new LinkedHashMap<>();
        files.put("index.html", "<html></html>");
        files.put("assets/css/styles.css", "body{}");
        files.put("assets/js/app.js", "console.log(1)");

        String url = publisher.publish("todo-x", SiteDraft.of(files));

        assertEquals("/preview/todo-x/index.html", url);
        assertTrue(Files.exists(workspace.resolve("todo-x/assets/css/styles.css")),
                "嵌套文件必须能写出来（父目录要逐级创建）");
        assertTrue(Files.exists(workspace.resolve("todo-x/assets/js/app.js")));
        assertEquals("body{}", Files.readString(
                workspace.resolve("todo-x/assets/css/styles.css"), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsPathTraversalInSlug() throws Exception {
        Path workspace = Files.createTempDirectory("studio-publish-");
        SitePublisher publisher = new SitePublisher(props(workspace));
        assertThrows(IllegalArgumentException.class,
                () -> publisher.publish("../../escape", SiteDraft.of(Map.of("index.html", "x"))));
    }

    @Test
    void rejectsPathTraversalInFileName() throws Exception {
        Path workspace = Files.createTempDirectory("studio-publish-");
        SitePublisher publisher = new SitePublisher(props(workspace));
        assertThrows(IllegalArgumentException.class,
                () -> publisher.publish("ok-slug", SiteDraft.of(Map.of("../evil.js", "x"))));
    }
}
