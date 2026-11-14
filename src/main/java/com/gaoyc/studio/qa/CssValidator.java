package com.gaoyc.studio.qa;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.gaoyc.studio.domain.QaIssue;

/** CSS 检查：括号闭合、注释闭合、引用了未定义的变量。 */
@Component
public class CssValidator {

    private static final Pattern VAR_USE = Pattern.compile("var\\(\\s*--([a-zA-Z0-9-]+)");
    private static final Pattern VAR_DEF = Pattern.compile("--([a-zA-Z0-9-]+)\\s*:");

    public List<QaIssue> validate(String css, String fileName) {
        List<QaIssue> issues = new ArrayList<>();

        int depth = 0;
        for (int i = 0; i < css.length(); i++) {
            char c = css.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth < 0) {
                    issues.add(QaIssue.error("CSS_UNBALANCED_BRACE", fileName, "出现了多余的 }"));
                    depth = 0;
                }
            }
        }
        if (depth > 0) {
            issues.add(QaIssue.error("CSS_UNBALANCED_BRACE", fileName, "缺少 " + depth + " 个 }"));
        }
        if (css.contains("/*") && !css.contains("*/")) {
            issues.add(QaIssue.error("CSS_UNCLOSED_COMMENT", fileName, "注释没有闭合"));
        }

        Set<String> defined = new HashSet<>();
        Matcher defs = VAR_DEF.matcher(css);
        while (defs.find()) {
            defined.add(defs.group(1));
        }
        Set<String> reported = new HashSet<>();
        Matcher uses = VAR_USE.matcher(css);
        while (uses.find()) {
            String name = uses.group(1);
            if (!defined.contains(name) && reported.add(name)) {
                issues.add(QaIssue.error("CSS_UNDEFINED_VAR", fileName, "引用了未定义的变量 --" + name));
            }
        }
        return issues;
    }
}
