package com.gaoyc.studio.qa;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.gaoyc.studio.domain.QaIssue;
import com.gaoyc.studio.domain.SiteDraft;

/** 把各路校验器串起来，产出一次质检报告。 */
@Component
public class QaGate {

    private final HtmlValidator htmlValidator;
    private final CssValidator cssValidator;
    private final JsValidator jsValidator;
    private final PolicyValidator policyValidator;

    public QaGate(HtmlValidator htmlValidator,
                  CssValidator cssValidator,
                  JsValidator jsValidator,
                  PolicyValidator policyValidator) {
        this.htmlValidator = htmlValidator;
        this.cssValidator = cssValidator;
        this.jsValidator = jsValidator;
        this.policyValidator = policyValidator;
    }

    private static final Pattern LOCAL_REF = Pattern.compile("(?:src|href)\\s*=\\s*[\"']([^\"']+)[\"']");

    public List<QaIssue> check(SiteDraft draft) {
        List<QaIssue> issues = new ArrayList<>();

        String index = draft.file("index.html");
        if (index.isBlank()) {
            issues.add(QaIssue.error("MISSING_ENTRY", "index.html", "没有生成入口页面"));
            return issues;
        }

        // 校验所有文件，而不是只认 index.html / styles.css / app.js：
        // 多文件布局会把脚本放到 assets/js，Vue 骨架还会出现 src/App.vue。
        for (Map.Entry<String, String> file : draft.files().entrySet()) {
            String name = file.getKey();
            String content = file.getValue();
            String lower = name.toLowerCase();
            if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                issues.addAll(htmlValidator.validate(content, name));
                issues.addAll(policyValidator.validateHtml(content, name));
                issues.addAll(checkAssetRefs(draft, name, content));
            } else if (lower.endsWith(".css")) {
                issues.addAll(cssValidator.validate(content, name));
            } else if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
                issues.addAll(jsValidator.validate(content, name));
                issues.addAll(policyValidator.validateJs(content, name));
            }
        }

        issues.sort(Comparator
                .comparing((QaIssue issue) -> issue.isError() ? 0 : 1)
                .thenComparing(QaIssue::code));
        return dedupe(issues);
    }

    /**
     * 本地资源引用必须真实存在。
     *
     * <p>这条规则抓的是"生成时引用了不存在的文件"：模板改名、模型给出了 assets 路径却没给文件，
     * 页面打开就是白屏或没样式 —— 机器能查，就不该留给人眼。
     * 外链、锚点、data:、mailto: 与根绝对路径（SPA 由构建决定）跳过。
     */
    private List<QaIssue> checkAssetRefs(SiteDraft draft, String htmlName, String html) {
        List<QaIssue> issues = new ArrayList<>();
        Set<String> files = draft.files().keySet();
        Set<String> seen = new LinkedHashSet<>();
        Path base = Path.of(htmlName).getParent();
        Matcher matcher = LOCAL_REF.matcher(html);
        while (matcher.find()) {
            String ref = matcher.group(1).trim();
            if (ref.isEmpty() || ref.startsWith("#") || ref.startsWith("http")
                    || ref.startsWith("//") || ref.startsWith("data:") || ref.startsWith("/")
                    || ref.startsWith("mailto:") || ref.startsWith("tel:")
                    || ref.startsWith("javascript:")) {
                continue;
            }
            String clean = ref.split("[?#]", 2)[0].trim();
            if (clean.isEmpty() || !seen.add(clean)) {
                continue;
            }
            String resolved;
            try {
                resolved = (base == null ? Path.of(clean) : base.resolve(clean))
                        .normalize().toString().replace('\\', '/');
            } catch (RuntimeException e) {
                issues.add(QaIssue.error("BROKEN_ASSET_REF", htmlName,
                        "非法的资源引用：" + ref));
                continue;
            }
            if (!files.contains(resolved)) {
                issues.add(QaIssue.error("BROKEN_ASSET_REF", htmlName,
                        "引用了不存在的文件：" + resolved));
            }
        }
        return issues;
    }

    public Map<String, Object> summarize(List<QaIssue> issues) {
        long errors = issues.stream().filter(QaIssue::isError).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", issues.size());
        summary.put("errors", errors);
        summary.put("warnings", issues.size() - errors);
        summary.put("passed", errors == 0);
        summary.put("codes", issues.stream().map(QaIssue::code).distinct().toList());
        return summary;
    }

    private List<QaIssue> dedupe(List<QaIssue> issues) {
        Map<String, QaIssue> unique = new LinkedHashMap<>();
        for (QaIssue issue : issues) {
            unique.putIfAbsent(issue.code() + "|" + issue.file() + "|" + issue.message(), issue);
        }
        return List.copyOf(unique.values());
    }
}
