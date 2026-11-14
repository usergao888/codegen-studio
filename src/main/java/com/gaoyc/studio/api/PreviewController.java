package com.gaoyc.studio.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.gaoyc.studio.publish.SitePublisher;

/** 预览：把生成到磁盘的静态站点直接服出去，方便"生成完就能点开看"。 */
@RestController
public class PreviewController {

    private final SitePublisher publisher;

    public PreviewController(SitePublisher publisher) {
        this.publisher = publisher;
    }

    @GetMapping("/preview/{slug}")
    public ResponseEntity<?> root(@PathVariable String slug) {
        return ResponseEntity.status(302)
                .header("Location", "/preview/" + slug + "/index.html")
                .build();
    }

    @GetMapping("/preview/{slug}/{file}")
    public ResponseEntity<?> file(@PathVariable String slug, @PathVariable String file) {
        Path root = publisher.root();
        Path directory = root.resolve(slug).normalize();
        Path target = directory.resolve(file).normalize();
        // 防目录穿越：/preview/xxx/../../pom.xml 这类请求必须挡掉
        if (!directory.startsWith(root) || !target.startsWith(directory)) {
            return ResponseEntity.badRequest().body(Map.of("error", "非法路径"));
        }
        if (!Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            return ResponseEntity.ok()
                    .contentType(mediaType(file))
                    .header("Cache-Control", "no-cache")
                    .body(bytes);
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "读取失败：" + e.getMessage()));
        }
    }

    private MediaType mediaType(String file) {
        String lower = file.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return new MediaType("text", "html", StandardCharsets.UTF_8);
        }
        if (lower.endsWith(".css")) {
            return new MediaType("text", "css", StandardCharsets.UTF_8);
        }
        if (lower.endsWith(".js")) {
            return new MediaType("text", "javascript", StandardCharsets.UTF_8);
        }
        if (lower.endsWith(".md")) {
            return new MediaType("text", "markdown", StandardCharsets.UTF_8);
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
