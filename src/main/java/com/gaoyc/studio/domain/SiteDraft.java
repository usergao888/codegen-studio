package com.gaoyc.studio.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一次生成产出的文件集合（文件名 -> 内容）。 */
public record SiteDraft(Map<String, String> files) implements java.io.Serializable {

    public SiteDraft {
        files = Map.copyOf(files);
    }

    public static SiteDraft of(Map<String, String> files) {
        return new SiteDraft(new LinkedHashMap<>(files));
    }

    public String file(String name) {
        return files.getOrDefault(name, "");
    }

    public boolean has(String name) {
        return files.containsKey(name);
    }

    public SiteDraft withFile(String name, String content) {
        Map<String, String> next = new LinkedHashMap<>(files);
        next.put(name, content);
        return new SiteDraft(next);
    }
}
