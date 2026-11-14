package com.gaoyc.studio.qa;

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

        String html = draft.file("index.html");
        if (html.isBlank()) {
            issues.add(QaIssue.error("MISSING_ENTRY", "index.html", "没有生成入口页面"));
            return issues;
        }
        issues.addAll(htmlValidator.validate(html, "index.html"));
        issues.addAll(policyValidator.validateHtml(html, "index.html"));
        issues.addAll(checkAssetRefs(draft));

        String css = draft.file("styles.css");
        if (!css.isBlank()) {
            issues.addAll(cssValidator.validate(css, "styles.css"));
        }

        String js = draft.file("app.js");
        if (!js.isBlank()) {
            issues.addAll(jsValidator.validate(js, "app.js"));
            issues.addAll(policyValidator.validateJs(js, "app.js"));
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
     * 外链、锚点、data: 与根绝对路径（SPA 由构建决定）跳过。
     */
    private List<QaIssue> checkAssetRefs(SiteDraft draft) {
        List<QaIssue> issues = new ArrayList<>();
        Set<String> files = draft.files().keySet();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = LOCAL_REF.matcher(draft.file("index.html"));
        while (matcher.find()) {
            String ref = matcher.group(1).trim();
            if (ref.isEmpty() || ref.startsWith("#") || ref.startsWith("http")
                    || ref.startsWith("//") || ref.startsWith("data:") || ref.startsWith("/")) {
                continue;
            }
            if (!seen.add(ref)) {
                continue;
            }
            if (!files.contains(ref)) {
                issues.add(QaIssue.error("BROKEN_ASSET_REF", "index.html",
                        "引用了不存在的文件：" + ref));
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
