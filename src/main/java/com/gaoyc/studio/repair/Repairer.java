package com.gaoyc.studio.repair;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.gaoyc.studio.domain.QaIssue;
import com.gaoyc.studio.domain.SiteDraft;

/**
 * 定向修复器：一个缺陷码对应一条确定的改法。
 *
 * <p>和"整段重生成"相比，定向修复的好处是**可解释**：日志里能写清"第 2 轮把 innerHTML 改成了
 * textContent"，而不是"又跑了一次模型，这次好像过了"。代价是缺陷种类必须可枚举，
 * 所以它只兜底常见类目，兜不住的会留在报告里交给人。
 */
@Component
public class Repairer {

    public record RepairOutcome(SiteDraft draft, List<String> applied) {
    }

    private static final Pattern INNER_HTML_ASSIGN =
            Pattern.compile("\\.innerHTML(\\s*)=(\\s*)(?![\"'`])");

    public RepairOutcome repair(SiteDraft draft, List<QaIssue> issues) {
        SiteDraft current = draft;
        List<String> applied = new ArrayList<>();

        for (String code : issues.stream().map(QaIssue::code).distinct().toList()) {
            switch (code) {
                case "XSS_INNERHTML" -> {
                    List<String> touched = new ArrayList<>();
                    for (String name : filesEndingWith(current, ".js")) {
                        String js = current.file(name);
                        String fixed = INNER_HTML_ASSIGN.matcher(js).replaceAll(".textContent$1=$2");
                        fixed = fixed.replace("  // unsafe:rich-text\n", "");
                        if (!fixed.equals(js)) {
                            current = current.withFile(name, fixed);
                            touched.add(name);
                        }
                    }
                    if (!touched.isEmpty()) {
                        applied.add("XSS_INNERHTML → innerHTML 改为 textContent（"
                                + String.join(", ", touched) + "）");
                    }
                }
                case "EXTERNAL_ASSET" -> {
                    List<String> touched = new ArrayList<>();
                    for (String name : filesEndingWith(current, ".html")) {
                        String html = current.file(name);
                        String fixed = stripExternalAssets(html);
                        if (!fixed.equals(html)) {
                            current = current.withFile(name, fixed);
                            touched.add(name);
                        }
                    }
                    for (String name : filesEndingWith(current, ".js")) {
                        String js = current.file(name);
                        String jsFixed = stripExternalHooks(js);
                        if (!jsFixed.equals(js)) {
                            current = current.withFile(name, jsFixed);
                            touched.add(name);
                        }
                    }
                    if (!touched.isEmpty()) {
                        applied.add("EXTERNAL_ASSET → 移除 CDN 外链/全局调用（"
                                + String.join(", ", touched) + "）");
                    }
                }
                case "HTML_MISSING_VIEWPORT" -> {
                    String html = current.file("index.html");
                    if (!html.contains("name=\"viewport\"") && html.contains("<meta charset=\"UTF-8\">")) {
                        String fixed = html.replace(
                                "<meta charset=\"UTF-8\">",
                                "<meta charset=\"UTF-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
                        current = current.withFile("index.html", fixed);
                        applied.add("HTML_MISSING_VIEWPORT → 补上 viewport meta");
                    }
                }
                case "HTML_UNCLOSED_TAG" -> {
                    String html = current.file("index.html");
                    String fixed = closeTags(html, issues);
                    if (!fixed.equals(html)) {
                        current = current.withFile("index.html", fixed);
                        applied.add("HTML_UNCLOSED_TAG → 补齐结束标签");
                    }
                }
                default -> {
                    // 没有对应改法就原样保留，缺陷会出现在最终报告里
                }
            }
        }
        Map<String, String> unused = new LinkedHashMap<>();
        unused.size();
        return new RepairOutcome(current, List.copyOf(applied));
    }

    private List<String> filesEndingWith(SiteDraft draft, String suffix) {
        return draft.files().keySet().stream()
                .filter(name -> name.toLowerCase().endsWith(suffix))
                .toList();
    }

    private String stripExternalAssets(String html) {
        StringBuilder out = new StringBuilder();
        boolean skippingScript = false;
        for (String line : html.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.equals("<!-- external:icon-font -->")) {
                continue;
            }
            if (trimmed.startsWith("<script") && trimmed.contains("http")) {
                skippingScript = true;
                continue;
            }
            if (skippingScript) {
                if (trimmed.contains("</script>")) {
                    skippingScript = false;
                }
                continue;
            }
            if ((trimmed.startsWith("<link") && trimmed.contains("http"))
                    || (trimmed.startsWith("<script") && trimmed.contains("http"))) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private String stripExternalHooks(String js) {
        StringBuilder out = new StringBuilder();
        for (String line : js.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("// external:")) {
                continue;
            }
            if (trimmed.contains("window.lucide") || trimmed.contains("lucide.createIcons")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private String closeTags(String html, List<QaIssue> issues) {
        List<String> missing = new ArrayList<>();
        for (QaIssue issue : issues) {
            if (!"HTML_UNCLOSED_TAG".equals(issue.code())) {
                continue;
            }
            Matcher matcher = Pattern.compile("<([a-zA-Z][a-zA-Z0-9-]*)>").matcher(issue.message());
            if (matcher.find()) {
                missing.add(matcher.group(1));
            }
        }
        if (missing.isEmpty()) {
            return html;
        }
        // 栈结构是后进先出，报告里的顺序也是栈顶先出，补齐时按逆序放回
        StringBuilder closing = new StringBuilder();
        for (int i = missing.size() - 1; i >= 0; i--) {
            closing.append("</").append(missing.get(i)).append(">\n");
        }
        return html.contains("</body>")
                ? html.replace("</body>", closing + "</body>")
                : html + "\n" + closing;
    }
}
