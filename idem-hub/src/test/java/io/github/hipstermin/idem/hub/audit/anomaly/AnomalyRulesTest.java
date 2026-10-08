package io.github.hipstermin.idem.hub.audit.anomaly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AnomalyRules — 규칙 5개: 창은 행의 occurred_at 기준, 축당 창 안 플래그 하나, 점수→심각도")
class AnomalyRulesTest {

    @Mock AnomalyRepository repo;
    AnomalyProperties props = new AnomalyProperties();
    AnomalyRules rules;
    static final Instant T = Instant.parse("2026-10-07T03:30:00Z");   // Asia/Seoul 12:30 수요일

    @BeforeEach
    void setUp() {
        rules = new AnomalyRules(repo, props, "Asia/Seoul");
    }

    private static AuditRow row(String cat, String action, String actorType, String actor, String agency, String ip, String outcome, String detail, Instant at) {
        return new AuditRow("01a0-" + at.toEpochMilli(), cat, action, actorType, actor, agency, ip, "cid", outcome, detail, at);
    }

    @Test
    void loginFailureBurstScoresByExcessAndDedupes() {
        AuditRow r = row("ADMIN", "ADMIN_LOGIN_FAILED", "ADMIN", "admin", null, "10.0.0.1", "FAILURE", "bad password", T);
        given(repo.countActorActions(eq("admin"), any(), any(), eq(T))).willReturn(4L);
        assertThat(rules.evaluate(r)).isEmpty();                                   // 임계(5) 미만
        given(repo.countActorActions(eq("admin"), any(), any(), eq(T))).willReturn(8L);
        List<AnomalyFlag> f = rules.evaluate(r);
        assertThat(f).hasSize(1);
        assertThat(f.get(0).rule()).isEqualTo(AnomalyRules.R_LOGIN_BURST);
        assertThat(f.get(0).score()).isEqualTo(80);                                // 50 + 10×(8-5)
        assertThat(f.get(0).severity()).isEqualTo("HIGH");
        assertThat(f.get(0).subject()).isEqualTo("admin");
        assertThat(f.get(0).details()).containsEntry("count", 8L);
        given(repo.flagExists(eq(AnomalyRules.R_LOGIN_BURST), eq("ACTOR"), eq("admin"), any())).willReturn(true);
        assertThat(rules.evaluate(r)).isEmpty();                                   // 같은 버스트는 한 번만
    }

    @Test
    void newSourceIpOnlyWhenActorHasHistoryElsewhere() {
        AuditRow r = row("ADMIN", "ADMIN_LOGIN_SUCCESS", "ADMIN", "admin", null, "203.0.113.9", "SUCCESS", null, T);
        given(repo.actorSeenFromIp(eq("admin"), eq("203.0.113.9"), eq("ADMIN_LOGIN_SUCCESS"), any(), eq(T), anyString())).willReturn(false);
        given(repo.actorHasPrior(eq("admin"), eq("ADMIN_LOGIN_SUCCESS"), eq(T), anyString())).willReturn(false);
        assertThat(rules.evaluate(r)).isEmpty();                                   // 첫 로그인은 새 IP 가 아니다
        given(repo.actorHasPrior(eq("admin"), eq("ADMIN_LOGIN_SUCCESS"), eq(T), anyString())).willReturn(true);
        List<AnomalyFlag> f = rules.evaluate(r);
        assertThat(f).extracting(AnomalyFlag::rule).containsExactly(AnomalyRules.R_NEW_IP);
        assertThat(f.get(0).severity()).isEqualTo("MEDIUM");
        assertThat(f.get(0).subject()).isEqualTo("admin@203.0.113.9");
        given(repo.actorSeenFromIp(eq("admin"), eq("203.0.113.9"), eq("ADMIN_LOGIN_SUCCESS"), any(), eq(T), anyString())).willReturn(true);
        assertThat(rules.evaluate(r)).isEmpty();                                   // 기준선 안에 본 IP
    }

    @Test
    void offHoursWriteUsesZoneAndWeekend() {
        Instant night = Instant.parse("2026-10-07T14:30:00Z");                     // Asia/Seoul 23:30 수요일
        AuditRow write = row("ADMIN", "TENANT_PROFILE_UPDATED", "ADMIN", "pol", "AG1", "10.0.0.2", "SUCCESS", null, night);
        List<AnomalyFlag> f = rules.evaluate(write);
        assertThat(f).extracting(AnomalyFlag::rule).containsExactly(AnomalyRules.R_OFF_HOURS);
        assertThat(f.get(0).severity()).isEqualTo("LOW");
        assertThat(f.get(0).details()).containsEntry("weekend", false).containsEntry("zone", "Asia/Seoul");
        assertThat(rules.evaluate(row("ADMIN", "TENANT_PROFILE_UPDATED", "ADMIN", "pol", "AG1", null, "SUCCESS", null, T))).isEmpty();   // 낮
        Instant saturdayNoon = Instant.parse("2026-10-10T03:00:00Z");              // 토요일 12:00
        assertThat(rules.evaluate(row("ADMIN", "AGENCY_KEY_ROTATED", "ADMIN", "pol", "AG1", null, "SUCCESS", null, saturdayNoon)))
                .extracting(AnomalyFlag::rule).containsExactly(AnomalyRules.R_OFF_HOURS);
        assertThat(rules.evaluate(row("ADMIN", "ADMIN_LOGIN_SUCCESS", "ADMIN", "pol", null, null, "SUCCESS", null, night))).isEmpty();   // 읽기·로그인은 쓰기가 아니다
        assertThat(rules.evaluate(row("ADMIN", "AI_AUDIT_SUMMARY", "ADMIN", "pol", null, null, "SUCCESS", null, night))).isEmpty();
        props.setWeekendOffHours(false);
        assertThat(rules.evaluate(row("ADMIN", "AGENCY_KEY_ROTATED", "ADMIN", "pol", "AG1", null, "SUCCESS", null, saturdayNoon))).isEmpty();
    }

    @Test
    void agencyFailureBurstComparesWithBaseline() {
        AuditRow r = row("HANDOFF", "HANDOFF_ISSUED", "AGENCY", null, "AG1", null, "FAILURE", "RATE_LIMIT_EXCEEDED", T);
        Instant winStart = T.minusSeconds(600);
        given(repo.countAgencyFailures("AG1", winStart, T)).willReturn(25L);
        given(repo.countAgencyFailures(eq("AG1"), any(), eq(winStart))).willReturn(1008L * 10);   // 7일 = 1008 창, 창당 10 → 비율 2.5 < 3
        assertThat(rules.evaluate(r)).isEmpty();
        given(repo.countAgencyFailures(eq("AG1"), any(), eq(winStart))).willReturn(1008L);        // 창당 1 → 비율 25
        List<AnomalyFlag> f = rules.evaluate(r);
        assertThat(f).extracting(AnomalyFlag::rule).containsExactly(AnomalyRules.R_AGENCY_BURST);
        assertThat(f.get(0).score()).isEqualTo(100);
        assertThat(f.get(0).subjectType()).isEqualTo("AGENCY");
        assertThat(f.get(0).details()).containsEntry("count", 25L).containsEntry("ratio", 25.0);
        given(repo.countAgencyFailures("AG1", winStart, T)).willReturn(3L);
        org.mockito.Mockito.clearInvocations(repo);
        assertThat(rules.evaluate(r)).isEmpty();                                   // 임계(20) 미만이면 기준선을 보지 않는다
        verify(repo, never()).countAgencyFailures(eq("AG1"), eq(T.minusSeconds(7 * 86400)), eq(winStart));
    }

    @Test
    void ticketReplayAndSuccessRowsIgnored() {
        AuditRow replay = row("HANDOFF", "HANDOFF_VERIFIED", "AGENCY", null, "AG2", null, "FAILURE", "TICKET_CONSUMED", T);
        given(repo.countAgencyActionDetail(eq("AG2"), eq("HANDOFF_VERIFIED"), eq("TICKET_CONSUMED"), any(), eq(T))).willReturn(3L);
        given(repo.countAgencyFailures(eq("AG2"), any(), eq(T))).willReturn(3L);
        List<AnomalyFlag> f = rules.evaluate(replay);
        assertThat(f).extracting(AnomalyFlag::rule).containsExactly(AnomalyRules.R_TICKET_REPLAY);
        assertThat(f.get(0).score()).isEqualTo(70);
        assertThat(rules.evaluate(row("HANDOFF", "HANDOFF_VERIFIED", "AGENCY", null, "AG2", null, "SUCCESS", null, T))).isEmpty();
        assertThat(rules.evaluate(row("SCIM", "SCIM_DISPATCHED", "SYSTEM", "idem-hub", "AG2", null, "SUCCESS", null, T))).isEmpty();
    }

    @Test
    void severityBands() {
        assertThat(AnomalyFlag.severityOf(0)).isEqualTo("LOW");
        assertThat(AnomalyFlag.severityOf(39)).isEqualTo("LOW");
        assertThat(AnomalyFlag.severityOf(40)).isEqualTo("MEDIUM");
        assertThat(AnomalyFlag.severityOf(70)).isEqualTo("HIGH");
    }
}
