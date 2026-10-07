package io.github.hipstermin.idem.hub.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AuditDigest — 집계만 보내고 IP·metadata 는 빼고 행위자는 마스킹")
class AuditDigestTest {

    private static Map<String, Object> row(String at, String cat, String action, String actor, String agency, String outcome, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("occurredAt", at); m.put("category", cat); m.put("action", action); m.put("actorType", "ADMIN"); m.put("actorId", actor);
        m.put("agencyCode", agency); m.put("outcome", outcome); m.put("outcomeDetail", detail);
        m.put("sourceIp", "10.1.2.3"); m.put("metadata", "{\"secret\":\"x\"}");
        return m;
    }

    @Test
    void aggregatesAndMasks() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(row("2026-10-07T01:00:00Z", "ADMIN", "ADMIN_LOGIN_SUCCESS", "administrator", null, "SUCCESS", null));
        items.add(row("2026-10-07T00:00:00Z", "ADMIN", "ADMIN_LOGIN_FAILED", "administrator", null, "FAILURE", "bad password"));
        items.add(row("2026-10-07T02:00:00Z", "AUTHZ", "ASSIGNMENT_CHANGED", "sys", "AG1", "FAILURE", "bad password"));
        items.add(row("2026-10-07T03:00:00Z", "SCIM", "SCIM_DISPATCHED", "idem-hub", "AG1", "SUCCESS", null));

        AuditDigest d = AuditDigest.of(99, items, 2);

        assertThat(d.total()).isEqualTo(99);
        assertThat(d.rows()).isEqualTo(4);
        assertThat(d.from()).isEqualTo("2026-10-07T00:00:00Z");
        assertThat(d.to()).isEqualTo("2026-10-07T03:00:00Z");
        assertThat(d.byCategory()).containsEntry("ADMIN", 2L).containsEntry("AUTHZ", 1L).containsEntry("SCIM", 1L);
        assertThat(d.byOutcome()).containsEntry("SUCCESS", 2L).containsEntry("FAILURE", 2L);
        assertThat(d.topAgencies()).extracting(AuditDigest.Count::key).containsExactly("AG1");
        assertThat(d.topFailures()).hasSize(2);
        assertThat(d.sample()).hasSize(2);
        assertThat(d.sample().get(0).actor()).isEqualTo("ad***");
        assertThat(d.toString()).doesNotContain("10.1.2.3").doesNotContain("secret");
    }

    @Test
    void truncatesLongDetailAndHandlesEmpty() {
        String longDetail = "x".repeat(500);
        AuditDigest d = AuditDigest.of(1, List.of(row("2026-10-07T00:00:00Z", "ADMIN", "A", "a", null, "FAILURE", longDetail)), 10);
        assertThat(d.topFailures().get(0).detail()).hasSize(AuditDigest.DETAIL_MAX + 1).endsWith("…");
        assertThat(d.sample().get(0).actor()).isEqualTo("a***");
        AuditDigest empty = AuditDigest.of(0, List.of(), 10);
        assertThat(empty.rows()).isZero();
        assertThat(empty.from()).isNull();
        assertThat(empty.sample()).isEmpty();
    }
}
