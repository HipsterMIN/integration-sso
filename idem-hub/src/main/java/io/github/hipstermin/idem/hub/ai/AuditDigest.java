package io.github.hipstermin.idem.hub.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 감사 행 → LLM 에 보내는 집계. <b>원문 행 전부는 보내지 않는다</b> — 분류·행위·결과·기관별 건수와 실패 상위, 그리고 표본 몇 줄.
 * 표본에서도 IP·metadata 는 빼고 행위자 ID 는 앞 두 글자만 남긴다(관리자 계정명이 LLM 로그에 남지 않도록).
 */
public record AuditDigest(long total, long rows, String from, String to,
                          Map<String, Long> byCategory, Map<String, Long> byOutcome,
                          List<Count> topActions, List<Count> topAgencies, List<Failure> topFailures,
                          List<Row> sample) {

    public record Count(String key, long n) {}
    public record Failure(String action, String detail, long n) {}
    public record Row(String occurredAt, String category, String action, String actorType, String actor,
                      String agencyCode, String outcome, String detail) {}

    static final int TOP = 10;
    static final int DETAIL_MAX = 160;

    /** @param total 검색 전체 건수(페이지 밖 포함) @param items 가져온 행(최대 200) @param sampleRows 표본 수 */
    public static AuditDigest of(long total, List<Map<String, Object>> items, int sampleRows) {
        Map<String, Long> byCategory = new TreeMap<>();
        Map<String, Long> byOutcome = new TreeMap<>();
        Map<String, Long> byAction = new TreeMap<>();
        Map<String, Long> byAgency = new TreeMap<>();
        Map<String, Long> failures = new LinkedHashMap<>();
        String from = null, to = null;
        for (Map<String, Object> it : items) {
            String at = str(it.get("occurredAt"));
            if (at != null) {
                if (from == null || at.compareTo(from) < 0) from = at;
                if (to == null || at.compareTo(to) > 0) to = at;
            }
            byCategory.merge(nz(str(it.get("category"))), 1L, Long::sum);
            byOutcome.merge(nz(str(it.get("outcome"))), 1L, Long::sum);
            byAction.merge(nz(str(it.get("action"))), 1L, Long::sum);
            String agency = str(it.get("agencyCode"));
            if (agency != null) byAgency.merge(agency, 1L, Long::sum);
            if (!"SUCCESS".equals(str(it.get("outcome")))) {
                failures.merge(nz(str(it.get("action"))) + "\t" + nz(truncate(str(it.get("outcomeDetail")))), 1L, Long::sum);
            }
        }
        List<Failure> topFailures = new ArrayList<>();
        failures.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(TOP).forEach(e -> {
            String[] k = e.getKey().split("\t", 2);
            topFailures.add(new Failure(k[0], k.length > 1 ? k[1] : "", e.getValue()));
        });
        List<Row> sample = new ArrayList<>();
        for (Map<String, Object> it : items.subList(0, Math.min(Math.max(sampleRows, 0), items.size()))) {
            sample.add(new Row(str(it.get("occurredAt")), str(it.get("category")), str(it.get("action")), str(it.get("actorType")),
                    maskActor(str(it.get("actorId"))), str(it.get("agencyCode")), str(it.get("outcome")), truncate(str(it.get("outcomeDetail")))));
        }
        return new AuditDigest(total, items.size(), from, to, byCategory, byOutcome, top(byAction), top(byAgency), topFailures, sample);
    }

    private static List<Count> top(Map<String, Long> m) {
        List<Count> out = new ArrayList<>();
        m.entrySet().stream().sorted(Comparator.comparing(Map.Entry<String, Long>::getValue).reversed()).limit(TOP)
                .forEach(e -> out.add(new Count(e.getKey(), e.getValue())));
        return out;
    }

    static String maskActor(String id) {
        if (id == null || id.isBlank()) return null;
        return id.length() <= 2 ? id.charAt(0) + "***" : id.substring(0, 2) + "***";
    }

    static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= DETAIL_MAX ? s : s.substring(0, DETAIL_MAX) + "…";
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String nz(String s) { return s == null ? "(none)" : s; }
}
