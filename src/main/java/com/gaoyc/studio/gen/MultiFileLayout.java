package com.gaoyc.studio.gen;

import java.util.LinkedHashMap;
import java.util.Map;

import com.gaoyc.studio.domain.SiteDraft;

/**
 * 把平坦的产物重排成"工程化"的目录结构：
 *
 * <pre>
 * index.html
 * assets/css/styles.css
 * assets/js/app.js
 * </pre>
 *
 * <p>为什么用"后处理"而不是直接改写六个模板：模板里只关心内容，路径统一在这一层收口，
 * 改一处即可；而且对模型产物同样适用 —— 它给出 {@code styles.css} 也会被挪进去。
 *
 * <p>注意：只搬运已知文件名，不碰其他路径，避免把模型自定义的目录结构打乱。
 */
public final class MultiFileLayout {

    public static final String STYLES = "styles.css";
    public static final String SCRIPT = "app.js";
    public static final String NESTED_STYLES = "assets/css/styles.css";
    public static final String NESTED_SCRIPT = "assets/js/app.js";

    private MultiFileLayout() {
    }

    public static SiteDraft apply(SiteDraft draft) {
        Map<String, String> files = new LinkedHashMap<>(draft.files());
        String html = files.remove("index.html");
        if (html == null) {
            return draft;
        }

        String styles = files.remove(STYLES);
        String script = files.remove(SCRIPT);

        Map<String, String> out = new LinkedHashMap<>();
        String rewritten = html;
        if (styles != null) {
            out.put(NESTED_STYLES, styles);
            rewritten = rewritten
                    .replace("href=\"" + STYLES + "\"", "href=\"" + NESTED_STYLES + "\"")
                    .replace("href='"+ STYLES + "'", "href='" + NESTED_STYLES + "'");
        }
        if (script != null) {
            out.put(NESTED_SCRIPT, script);
            rewritten = rewritten
                    .replace("src=\"" + SCRIPT + "\"", "src=\"" + NESTED_SCRIPT + "\"")
                    .replace("src='"+ SCRIPT + "'", "src='" + NESTED_SCRIPT + "'");
        }

        out.put("index.html", rewritten);
        out.putAll(files);
        return SiteDraft.of(out);
    }

    public static boolean isNested(SiteDraft draft) {
        return draft.has(NESTED_STYLES) || draft.has(NESTED_SCRIPT);
    }
}
