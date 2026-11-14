package com.gaoyc.studio.qa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.gaoyc.studio.domain.QaIssue;

/**
 * HTML 结构检查：标签闭合、根节点、标题、重复 id、viewport。
 *
 * <p>手写解析而不是引第三方库：产物是生成器自己拼的，规则可控；
 * 而正则 + 栈足以发现"少写一个 {@code </div>}"这类最常发生的生成缺陷。
 */
@Component
public class HtmlValidator {

    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9-]*)([^>]*)>");
    private static final Pattern ID = Pattern.compile("\\sid\\s*=\\s*\"([^\"]+)\"");
    private static final Set<String> VOID = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr"
    );

    public List<QaIssue> validate(String html, String fileName) {
        List<QaIssue> issues = new ArrayList<>();
        String trimmed = html.stripLeading().toLowerCase(Locale.ROOT);

        if (!trimmed.startsWith("<!doctype html>")) {
            issues.add(QaIssue.error("HTML_NO_DOCTYPE", fileName, "缺少 <!DOCTYPE html>"));
        }
        if (!html.contains("<html lang=")) {
            issues.add(QaIssue.error("HTML_NO_LANG", fileName, "<html> 缺少 lang 属性"));
        }
        if (!html.matches("(?s).*<title>\\s*\\S+.*")) {
            issues.add(QaIssue.error("HTML_NO_TITLE", fileName, "<title> 为空"));
        }
        if (!html.contains("name=\"viewport\"")) {
            issues.add(QaIssue.error("HTML_MISSING_VIEWPORT", fileName, "缺少 viewport meta，移动端会缩放异常"));
        }
        if (!html.contains("rel=\"stylesheet\"")) {
            issues.add(QaIssue.warn("HTML_NO_STYLESHEET", fileName, "没有引用 styles.css"));
        }

        issues.addAll(checkTagBalance(html, fileName));
        issues.addAll(checkDuplicateIds(html, fileName));
        return issues;
    }

    private List<QaIssue> checkTagBalance(String html, String fileName) {
        List<QaIssue> issues = new ArrayList<>();
        // 去掉脚本/样式/注释里的内容，避免把 JS 字符串里的 "<div>" 当成标签
        String cleaned = html
                .replaceAll("(?s)<script.*?</script>", "<script></script>")
                .replaceAll("(?s)<style.*?</style>", "<style></style>")
                .replaceAll("(?s)<!--.*?-->", "");

        Deque<String> stack = new ArrayDeque<>();
        Matcher matcher = TAG.matcher(cleaned);
        while (matcher.find()) {
            boolean closing = "/".equals(matcher.group(1));
            String name = matcher.group(2).toLowerCase(Locale.ROOT);
            String attrs = matcher.group(3);
            boolean selfClosing = attrs.stripTrailing().endsWith("/");

            if (VOID.contains(name) || selfClosing) {
                continue;
            }
            if (!closing) {
                stack.push(name);
                continue;
            }
            if (stack.isEmpty()) {
                issues.add(QaIssue.error("HTML_STRAY_CLOSE", fileName, "多余的结束标签 </" + name + ">"));
                continue;
            }
            if (stack.peek().equals(name)) {
                stack.pop();
                continue;
            }
            if (stack.contains(name)) {
                while (!stack.isEmpty() && !stack.peek().equals(name)) {
                    issues.add(QaIssue.error("HTML_UNCLOSED_TAG", fileName,
                            "<" + stack.pop() + "> 没有对应的结束标签"));
                }
                if (!stack.isEmpty()) {
                    stack.pop();
                }
            } else {
                issues.add(QaIssue.error("HTML_STRAY_CLOSE", fileName, "多余的结束标签 </" + name + ">"));
            }
        }
        while (!stack.isEmpty()) {
            issues.add(QaIssue.error("HTML_UNCLOSED_TAG", fileName,
                    "<" + stack.pop() + "> 没有对应的结束标签"));
        }
        return issues;
    }

    private List<QaIssue> checkDuplicateIds(String html, String fileName) {
        List<QaIssue> issues = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher matcher = ID.matcher(html);
        while (matcher.find()) {
            String id = matcher.group(1);
            if (!seen.add(id)) {
                issues.add(QaIssue.error("HTML_DUPLICATE_ID", fileName, "重复的 id=\"" + id + "\""));
            }
        }
        return issues;
    }
}
