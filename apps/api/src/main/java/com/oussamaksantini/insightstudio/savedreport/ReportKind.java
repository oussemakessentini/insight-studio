package com.oussamaksantini.insightstudio.savedreport;

import java.util.Arrays;
import java.util.Optional;

/** Which of the two reports a saved definition runs; codes as stored in {@code saved_reports.kind}. */
public enum ReportKind {
    MONTHLY("monthly"),
    CATEGORIES("categories");

    private final String code;

    ReportKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<ReportKind> fromCode(String code) {
        return Arrays.stream(values()).filter(k -> k.code.equals(code)).findFirst();
    }
}
