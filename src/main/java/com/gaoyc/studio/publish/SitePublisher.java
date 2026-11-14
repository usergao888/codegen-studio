package com.gaoyc.studio.publish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.gaoyc.studio.config.StudioProperties;
import com.gaoyc.studio.domain.SiteDraft;

/** 把生成结果落盘，并给出可直接访问的预览地址。 */
@Component
public class SitePublisher {

    private final Path root;

    public SitePublisher(StudioProperties properties) {
        this.root = Path.of(properties.workspace()).toAbsolutePath().normalize();
    }

    public String publish(String slug, SiteDraft draft) throws IOException {
        Path directory = root.resolve(slug).normalize();
        // 防目录穿越：slug 一旦被外部拼接成 ../../ 就会写到工作区之外
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("非法 slug：" + slug);
        }
        Files.createDirectories(directory);
        for (Map.Entry<String, String> entry : draft.files().entrySet()) {
            Path file = directory.resolve(entry.getKey()).normalize();
            if (!file.startsWith(directory)) {
                throw new IllegalArgumentException("非法文件名：" + entry.getKey());
            }
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
        return "/preview/" + slug + "/index.html";
    }

    public Path root() {
        return root;
    }
}
