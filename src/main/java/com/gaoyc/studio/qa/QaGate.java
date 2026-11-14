package com.gaoyc.studio.qa;

import java.util.ArrayList;
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

    public List<QaIssue> check(SiteDraft draft) {
        List<QaIssue> issues = new ArrayList<>();

        String html = draft.file("index.html");
        if (html.isBlank()) {
            issues.add(QaIssue.error("MISSING_ENTRY", "index.html", "没有生成入口页面"));
            return issues;
        }
        issues.addAll(htmlValidator.validate(html, "index.html"));
        issues.addAll(policyValidator.validateHtml(html, "index.html"));

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
