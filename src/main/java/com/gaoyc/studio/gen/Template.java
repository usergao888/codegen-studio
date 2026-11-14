package com.gaoyc.studio.gen;

import java.util.LinkedHashMap;
import java.util.Map;

/** 极简模板替换：把 ${key} 换成值。刻意不用 String.format，避免 CSS 里的 % 和 JS 里的 {} 打架。 */
public final class Template {

    private Template() {
    }

    public static String render(String template, Map<String, String> values) {
        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    public static String render(String template, String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("键值必须成对出现");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            values.put(kv[i], kv[i + 1]);
        }
        return render(template, values);
    }
}
