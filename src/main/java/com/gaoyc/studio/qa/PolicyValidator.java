package com.gaoyc.studio.qa;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.gaoyc.studio.domain.QaIssue;

/**
 * 安全与可移植性规则。
 *
 * <p>两条最要命的：
 * <ul>
 *   <li>{@code innerHTML = 变量} —— 生成的应用可能把用户输入直接插进 DOM，等于自带 XSS；</li>
 *   <li>外链 CDN —— 预览环境常常不能联网，页面会白屏。</li>
 * </ul>
 * 判定按内容做（不看生成器打的标记），所以换任何生成器这些规则照样有效。
 */
@Component
public class PolicyValidator {

    /** 赋给 innerHTML / outerHTML 的不是字符串字面量，就要警惕。 */
    private static final Pattern INNER_HTML =
            Pattern.compile("\\.(?:innerHTML|outerHTML)\\s*=\\s*([^;]*)");
    private static final Pattern INSERT_HTML =
            Pattern.compile("\\.insertAdjacentHTML\\s*\\(");
    private static final Pattern INLINE_SCRIPT =
            Pattern.compile("(?is)<script[^>]*>(.*?)</script>");
    private static final Pattern EXTERNAL_ASSET =
            Pattern.compile("(?:src|href)\\s*=\\s*[\"']https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_URL =
            Pattern.compile("(?:src|href)\\s*=\\s*[\"']\\s*javascript:", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVAL = Pattern.compile("\\beval\\s*\\(");
    private static final Pattern DOC_WRITE = Pattern.compile("document\\.write\\s*\\(");

    public List<QaIssue> validateHtml(String html, String fileName) {
        List<QaIssue> issues = new ArrayList<>();
        Matcher external = EXTERNAL_ASSET.matcher(html);
        while (external.find()) {
            issues.add(QaIssue.error("EXTERNAL_ASSET", fileName,
                    "引用了外部资源，离线预览会失败：" + snippet(html, external.start())));
            break;
        }
        if (JS_URL.matcher(html).find()) {
            issues.add(QaIssue.error("JS_URL", fileName,
                    "src/href 指向 javascript: URL"));
        }
        // 内联脚本也要过 JS 规则，否则把 innerHTML 写进 <script> 就能绕过 app.js 检查
        Matcher inline = INLINE_SCRIPT.matcher(html);
        while (inline.find()) {
            String script = inline.group(1);
            if (!script.isBlank()) {
                issues.addAll(validateJs(script, fileName + "#inline"));
            }
        }
        return issues;
    }

    public List<QaIssue> validateJs(String js, String fileName) {
        List<QaIssue> issues = new ArrayList<>();

        Matcher inner = INNER_HTML.matcher(js);
        while (inner.find()) {
            String rhs = inner.group(1).strip();
            if (rhs.startsWith("\"") || rhs.startsWith("'")) {
                continue; // 静态字符串，安全
            }
            if (rhs.startsWith("`")) {
                if (rhs.contains("${")) {
                    issues.add(QaIssue.error("XSS_INNERHTML", fileName,
                            "innerHTML 使用含插值的模板字面量，存在注入风险：" + truncate(rhs, 40)));
                }
                continue; // 静态模板字面量，安全
            }
            issues.add(QaIssue.error("XSS_INNERHTML", fileName,
                    "innerHTML/outerHTML 直接赋变量，存在注入风险：" + truncate(rhs, 40)));
            break;
        }
        if (INSERT_HTML.matcher(js).find()) {
            issues.add(QaIssue.error("XSS_INSERT_HTML", fileName,
                    "使用 insertAdjacentHTML 插入不可信 HTML"));
        }
        if (EVAL.matcher(js).find()) {
            issues.add(QaIssue.error("JS_EVAL", fileName, "使用了 eval()"));
        }
        if (DOC_WRITE.matcher(js).find()) {
            issues.add(QaIssue.warn("JS_DOCUMENT_WRITE", fileName, "使用了 document.write()"));
        }
        return issues;
    }

    private String snippet(String text, int index) {
        int start = Math.max(0, index - 12);
        int end = Math.min(text.length(), index + 48);
        return text.substring(start, end).replace('\n', ' ').strip();
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
