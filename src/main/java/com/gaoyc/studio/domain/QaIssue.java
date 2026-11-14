package com.gaoyc.studio.domain;

/** 质检发现的一条问题。 */
public record QaIssue(Severity severity, String code, String file, String message) implements java.io.Serializable {

    public enum Severity {
        /** 必须修复，否则不发布。 */
        ERROR,
        /** 记录但不阻塞发布。 */
        WARN
    }

    public static QaIssue error(String code, String file, String message) {
        return new QaIssue(Severity.ERROR, code, file, message);
    }

    public static QaIssue warn(String code, String file, String message) {
        return new QaIssue(Severity.WARN, code, file, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return "%s %s [%s] %s".formatted(severity, code, file, message);
    }
}
