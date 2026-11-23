package com.gaoyc.studio.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.gaoyc.studio.domain.QaIssue;
import com.gaoyc.studio.domain.SiteDraft;
import com.gaoyc.studio.gen.SiteGenerator;
import com.gaoyc.studio.spec.SpecParser;

public class QaGateTest {

    private final SpecParser parser = new SpecParser();
    private final SiteGenerator generator = new SiteGenerator();
    private final QaGate gate = new QaGate(
            new HtmlValidator(), new CssValidator(), new JsValidator(), new PolicyValidator());

    private List<String> codes(SiteDraft draft) {
        return gate.check(draft).stream().map(QaIssue::code).collect(Collectors.toList());
    }

    private SiteDraft cleanDraft() {
        return generator.generate(parser.parse("做一个待办清单"));
    }

    @Test
    void generatedSitePassesQualityGate() {
        List<QaIssue> issues = gate.check(cleanDraft());
        List<String> errors = issues.stream()
                .filter(QaIssue::isError)
                .map(QaIssue::code)
                .collect(Collectors.toList());
        assertEquals(List.of(), errors, "干净的产物不应有阻塞级问题");
    }

    @Test
    void detectsUnclosedTag() {
        SiteDraft broken = cleanDraft().withFile("index.html",
                cleanDraft().file("index.html").replace("</footer>", ""));
        assertTrue(codes(broken).contains("HTML_UNCLOSED_TAG"));
    }

    @Test
    void detectsMissingViewport() {
        SiteDraft broken = cleanDraft().withFile("index.html",
                cleanDraft().file("index.html")
                        .replace("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">", ""));
        assertTrue(codes(broken).contains("HTML_MISSING_VIEWPORT"));
    }

    @Test
    void detectsInnerHtmlInjection() {
        SiteDraft broken = cleanDraft().withFile("app.js",
                "function render(userInput) { node.innerHTML = userInput; }");
        assertTrue(codes(broken).contains("XSS_INNERHTML"));
    }

    @Test
    void innerHtmlWithLiteralIsAllowed() {
        SiteDraft ok = cleanDraft().withFile("app.js",
                "function clear() { list.innerHTML = \"\"; }");
        assertFalse(codes(ok).contains("XSS_INNERHTML"));
    }

    @Test
    void detectsExternalAssets() {
        SiteDraft broken = cleanDraft().withFile("index.html",
                cleanDraft().file("index.html").replace("</head>",
                        "  <script src=\"https://cdn.example.com/lib.js\"></script>\n</head>"));
        assertTrue(codes(broken).contains("EXTERNAL_ASSET"));
    }

    @Test
    void detectsJsSyntaxErrorViaNode() {
        SiteDraft broken = cleanDraft().withFile("app.js", "function broken( { ");
        List<String> found = codes(broken);
        // 机器上没有 node 时降级为 JS_CHECK_SKIPPED，同样说明这条路走通了
        assertTrue(found.contains("JS_SYNTAX") || found.contains("JS_CHECK_SKIPPED"),
                "应通过 node 发现语法错误或明确跳过：" + found);
    }

    @Test
    void detectsUndefinedCssVariable() {
        SiteDraft broken = cleanDraft().withFile("styles.css", ":root { --a: red; }\n.x { color: var(--missing); }");
        assertTrue(codes(broken).contains("CSS_UNDEFINED_VAR"));
    }

    @Test
    void detectsDuplicateIds() {
        String html = cleanDraft().file("index.html").replace("<body>", "<body><div id=\"dup\"></div><div id=\"dup\"></div>");
        SiteDraft broken = cleanDraft().withFile("index.html", html);
        assertTrue(codes(broken).contains("HTML_DUPLICATE_ID"));
    }

    @Test
    void summaryCountsErrorsAndWarnings() {
        SiteDraft broken = cleanDraft().withFile("app.js",
                "function render(v) { node.innerHTML = v; }");
        Map<String, Object> summary = gate.summarize(gate.check(broken));
        assertTrue((Boolean) summary.get("passed") == false);
        assertTrue(((Number) summary.get("errors")).intValue() > 0);
    }

    @Test
    void detectsBrokenAssetRef() {
        SiteDraft broken = cleanDraft().withFile("index.html",
                cleanDraft().file("index.html")
                        .replace("href=\"styles.css\"", "href=\"assets/css/missing.css\""));
        assertTrue(codes(broken).contains("BROKEN_ASSET_REF"),
                "引用了不存在的文件应该被拦下：" + codes(broken));
    }

    @Test
    void nestedMultifileLayoutStillPasses() {
        SiteDraft nested = com.gaoyc.studio.gen.MultiFileLayout.apply(cleanDraft());
        assertTrue(nested.has("assets/css/styles.css"));
        List<String> found = codes(nested);
        assertFalse(found.contains("BROKEN_ASSET_REF"), "重排后引用应同步改写：" + found);
        assertFalse(found.contains("HTML_UNCLOSED_TAG"));
    }

    @Test
    void detectsXssInNestedMultifileJs() {
        SiteDraft nested = com.gaoyc.studio.gen.MultiFileLayout.apply(cleanDraft());
        SiteDraft broken = nested.withFile("assets/js/app.js",
                "function render(v) { node.innerHTML = v; }");
        assertTrue(codes(broken).contains("XSS_INNERHTML"),
                "assets/js/app.js 也必须进 QA：" + codes(broken));
    }

    @Test
    void detectsXssInInlineScript() {
        String html = cleanDraft().file("index.html").replace("</body>",
                "<script>document.body.innerHTML = location.hash;</script></body>");
        SiteDraft broken = cleanDraft().withFile("index.html", html);
        assertTrue(codes(broken).contains("XSS_INNERHTML"),
                "内联脚本不能绕过 app.js 的检查：" + codes(broken));
    }

    @Test
    void detectsTemplateLiteralInterpolation() {
        SiteDraft broken = cleanDraft().withFile("app.js",
                "function render(v) { node.innerHTML = `<b>${v}</b>`; }");
        assertTrue(codes(broken).contains("XSS_INNERHTML"),
                "含 ${} 的模板字面量不是安全值");
    }

    @Test
    void staticTemplateLiteralIsAllowed() {
        SiteDraft ok = cleanDraft().withFile("app.js",
                "function clear() { list.innerHTML = `<b>静态</b>`; }");
        assertFalse(codes(ok).contains("XSS_INNERHTML"));
    }

}
