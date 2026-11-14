package com.gaoyc.studio.spec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.gaoyc.studio.domain.AppSpec;
import com.gaoyc.studio.domain.AppSpec.Archetype;

/**
 * 把一句中文需求解析成 {@link AppSpec}。
 *
 * <p>规则式实现，可离线运行且结果确定 —— 这一点对评测很重要：每次跑同一批需求都得到同样的
 * spec，才能比较"生成质量"而不是"模型的随机性"。接真实 LLM 时替换这个类即可，
 * 下游生成器和质检都不受影响。
 */
@Component
public class SpecParser {

    private static final Map<Archetype, List<String>> ARCHETYPE_KEYWORDS = Map.of(
            Archetype.TODO, List.of("待办", "任务", "清单", "todo", "to-do", "打卡", "检查表"),
            Archetype.DASHBOARD, List.of("看板", "仪表盘", "报表", "数据", "统计", "dashboard", "大屏"),
            Archetype.FORM, List.of("表单", "问卷", "报名", "收集", "登记", "填表", "form"),
            Archetype.PRICING, List.of("价格", "定价", "套餐", "收费", "会员", "pricing"),
            Archetype.BLOG, List.of("博客", "文章", "资讯", "专栏", "blog"),
            Archetype.LANDING, List.of("官网", "落地页", "介绍页", "主页", "首页", "landing"),
            Archetype.SPA, List.of("管理后台", "后台管理", "单页应用", "单页", "spa", "vue", "控制台")
    );

    private static final Map<String, List<String>> FEATURE_KEYWORDS = Map.of(
            "filter", List.of("筛选", "过滤", "分类", "按状态"),
            "search", List.of("搜索", "查询", "检索"),
            "dark-mode", List.of("深色", "暗色", "夜间", "dark"),
            "storage", List.of("本地存储", "保存", "离线", "localstorage", "刷新不丢"),
            "chart", List.of("图表", "统计图", "趋势", "柱状", "折线"),
            "rich-text", List.of("富文本", "彩色", "标记", "html 备注", "备注样式"),
            "export", List.of("导出", "下载", "csv"),
            "icons", List.of("图标", "icon", "插画"),
            "multi-file", List.of("多文件", "工程化", "组件化", "目录结构")
    );

    private static final String[] PALETTE = {
            "#3d6bfb", "#0f9d8f", "#e26a2c", "#7c4dff", "#0ea5e9", "#d9467a"
    };

    public AppSpec parse(String prompt) {
        String text = prompt == null ? "" : prompt.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        Archetype archetype = detectArchetype(lower);
        List<String> features = detectFeatures(lower);
        boolean richText = features.contains("rich-text");
        boolean wantsChart = features.contains("chart") || archetype == Archetype.DASHBOARD;
        boolean mobileFirst = !(lower.contains("pc") || lower.contains("桌面端") || lower.contains("不要移动"));

        if (archetype == Archetype.DASHBOARD && !features.contains("chart")) {
            features = new ArrayList<>(features);
            features.add("chart");
        }

        return new AppSpec(
                archetype,
                titleFor(archetype, text),
                subtitleFor(archetype),
                List.copyOf(new LinkedHashSet<>(features)),
                PALETTE[Math.floorMod(text.hashCode(), PALETTE.length)],
                mobileFirst,
                richText,
                wantsChart,
                "zh-CN"
        );
    }

    private Archetype detectArchetype(String lower) {
        for (Map.Entry<Archetype, List<String>> entry : ARCHETYPE_KEYWORDS.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }
        return Archetype.LANDING;
    }

    private List<String> detectFeatures(String lower) {
        Set<String> found = new LinkedHashSet<>();
        FEATURE_KEYWORDS.forEach((feature, keywords) -> {
            for (String keyword : keywords) {
                if (lower.contains(keyword)) {
                    found.add(feature);
                    return;
                }
            }
        });
        return List.copyOf(found);
    }

    private String titleFor(Archetype archetype, String prompt) {
        // 需求里若显式给了名字（"叫XX"），优先用它
        int index = prompt.indexOf("叫");
        if (index > 0 && prompt.length() > index + 1) {
            String candidate = prompt.substring(index + 1).trim();
            int stop = firstIndexOfAny(candidate, "的", "，", ",", "。", " ");
            if (stop > 1 && stop <= 12) {
                return candidate.substring(0, stop);
            }
        }
        return archetype.defaultTitle();
    }

    private String subtitleFor(Archetype archetype) {
        return switch (archetype) {
            case TODO -> "把要做的事写下来，完成一件划掉一件。";
            case DASHBOARD -> "关键指标一眼看清，趋势变化按周对比。";
            case LANDING -> "用更少的话，把产品价值讲清楚。";
            case FORM -> "填完即可提交，提交前会做格式校验。";
            case PRICING -> "按需选择，随时可以升级或降级。";
            case BLOG -> "按主题浏览，点开看全文。";
            case SPA -> "组件化单页应用，由 Vite 构建。";
        };
    }

    private int firstIndexOfAny(String text, String... needles) {
        int best = -1;
        for (String needle : needles) {
            int index = text.indexOf(needle);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best < 0 ? text.length() : best;
    }
}
