package io.github.hipstermin.idem.hub.audit.anomaly;

import java.util.Map;

/** 규칙이 낸 플래그 — 점수·축·근거. severity 는 score 에서 정한다. */
public record AnomalyFlag(AuditRow row, String rule, int score, String subjectType, String subject, Map<String, Object> details) {

    public static final String SUBJECT_ACTOR = "ACTOR";
    public static final String SUBJECT_AGENCY = "AGENCY";

    public String severity() {
        return severityOf(score);
    }

    public static String severityOf(int score) {
        if (score >= 70) return "HIGH";
        if (score >= 40) return "MEDIUM";
        return "LOW";
    }
}
