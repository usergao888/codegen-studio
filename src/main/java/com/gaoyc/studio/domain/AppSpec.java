package com.gaoyc.studio.domain;

import java.util.List;

/**
 * 一句话需求解析出来的结构化规格。
 *
 * <p>生成器只认这个对象，不认原始自然语言 —— 这样"需求理解"和"代码合成"可以分别替换
 * （前者换真实模型，后者仍可保留模板合成）。
 */
public record AppSpec(
        Archetype archetype,
        String title,
        String subtitle,
        List<String> features,
        String accent,
        boolean mobileFirst,
        boolean richText,
        boolean wantsChart,
        String language
) implements java.io.Serializable {

    public enum Archetype {
        TODO("待办清单"),
        DASHBOARD("数据看板"),
        LANDING("产品落地页"),
        FORM("信息收集表"),
        PRICING("价格方案页"),
        BLOG("文章列表页"),
        SPA("单页应用");

        private final String defaultTitle;

        Archetype(String defaultTitle) {
            this.defaultTitle = defaultTitle;
        }

        public String defaultTitle() {
            return defaultTitle;
        }
    }

    public boolean has(String feature) {
        return features.contains(feature);
    }

    /**
     * 仅用作 LangGraph4j 状态通道的默认值。
     *
     * <p>通道默认值不能是 null —— langgraph4j 内部用 Collectors.toMap 收集初始值，
     * 遇到 null value 会直接 NPE。真正的内容由 parse 节点覆盖。
     */
    public static AppSpec empty() {
        return new AppSpec(Archetype.LANDING, "", "", List.of(), "#3d6bfb", true, false, false, "zh-CN");
    }
}
