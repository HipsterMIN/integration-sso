package io.github.hipstermin.idem.hub.audit.anomaly;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 관찰 모드 규칙 5개 — 결정적이고 설명 가능하다(건수·창·기준선이 details 에 남는다). 기준 시각은 <b>행의 occurred_at</b> 이라
 * 밀린 배치를 나중에 점수 내도 같은 답이 나온다. 규칙 하나의 버스트는 같은 축(행위자·기관)에 창 안 플래그 하나만.
 * <ol>
 *   <li>ADMIN_LOGIN_FAILURE_BURST — 같은 관리자 계정의 로그인·2단계 실패가 창 안 임계 이상</li>
 *   <li>ADMIN_NEW_SOURCE_IP — 관리자 로그인 성공인데 기준선(30일) 안 그 계정이 그 IP 에서 로그인한 적이 없다(첫 로그인은 제외)</li>
 *   <li>ADMIN_OFF_HOURS_WRITE — 관리자 쓰기(프로파일·기관·관리자·OIDC·동기화 …)가 업무 외 시간·주말</li>
 *   <li>AGENCY_FAILURE_BURST — 같은 기관의 FAILURE 가 창 안 임계 이상이고 7일 기준선(창 평균)의 배수 이상</li>
 *   <li>TICKET_REPLAY — 같은 기관의 Handoff 재검증 실패(TICKET_CONSUMED)가 창 안 임계 이상</li>
 * </ol>
 */
@Slf4j
@Component
public class AnomalyRules {

    public static final String R_LOGIN_BURST = "ADMIN_LOGIN_FAILURE_BURST";
    public static final String R_NEW_IP = "ADMIN_NEW_SOURCE_IP";
    public static final String R_OFF_HOURS = "ADMIN_OFF_HOURS_WRITE";
    public static final String R_AGENCY_BURST = "AGENCY_FAILURE_BURST";
    public static final String R_TICKET_REPLAY = "TICKET_REPLAY";
    public static final List<String> ALL = List.of(R_LOGIN_BURST, R_NEW_IP, R_OFF_HOURS, R_AGENCY_BURST, R_TICKET_REPLAY);

    static final Set<String> LOGIN_FAILURE_ACTIONS = Set.of("ADMIN_LOGIN_FAILED", "ADMIN_MFA_FAILED");
    static final String LOGIN_SUCCESS = "ADMIN_LOGIN_SUCCESS";
    /** 관리자 행위 중 "쓰기"가 아닌 것 — 나머지 ADMIN 분류 SUCCESS 는 쓰기로 본다 */
    static final Set<String> ADMIN_NON_WRITE = Set.of("ADMIN_LOGIN_SUCCESS", "ADMIN_LOGIN_FAILED", "ADMIN_LOGOUT", "ADMIN_MFA_ENROLLED",
            "ADMIN_MFA_FAILED", "ADMIN_ACCESS_DENIED", "ADMIN_LOCKED", "ADMIN_PASSWORD_CHANGED",
            "AI_PROFILE_DRAFT", "AI_AUDIT_SUMMARY", "AI_INCIDENT_SUMMARY", "ANOMALY_REVIEWED");

    private final AnomalyRepository repo;
    private final AnomalyProperties props;
    private final ZoneId zone;

    public AnomalyRules(AnomalyRepository repo, AnomalyProperties props, @Value("${idem.hub.zone:UTC}") String zone) {
        this.repo = repo;
        this.props = props;
        this.zone = ZoneId.of(zone);
    }

    public List<AnomalyFlag> evaluate(AuditRow row) {
        List<AnomalyFlag> out = new ArrayList<>();
        Instant t = row.occurredAt();
        Duration window = Duration.ofMinutes(props.getWindowMinutes());
        Instant winStart = t.minus(window);
        AnomalyProperties.Thresholds th = props.getThresholds();

        // 1. 관리자 로그인·2단계 실패 버스트
        if (LOGIN_FAILURE_ACTIONS.contains(row.action()) && row.actorId() != null) {
            long n = repo.countActorActions(row.actorId(), LOGIN_FAILURE_ACTIONS, winStart, t);
            if (n >= th.getLoginFailureBurst() && !repo.flagExists(R_LOGIN_BURST, AnomalyFlag.SUBJECT_ACTOR, row.actorId(), winStart)) {
                int score = (int) Math.min(100, 50 + 10 * (n - th.getLoginFailureBurst()));
                out.add(new AnomalyFlag(row, R_LOGIN_BURST, score, AnomalyFlag.SUBJECT_ACTOR, row.actorId(),
                        Map.of("count", n, "threshold", th.getLoginFailureBurst(), "windowMinutes", props.getWindowMinutes())));
            }
        }

        // 2. 관리자 로그인 성공 — 새 출처 IP
        if (LOGIN_SUCCESS.equals(row.action()) && "SUCCESS".equals(row.outcome()) && row.actorId() != null && row.sourceIp() != null) {
            Instant baseStart = t.minus(Duration.ofDays(props.getNewIpBaselineDays()));
            if (!repo.actorSeenFromIp(row.actorId(), row.sourceIp(), LOGIN_SUCCESS, baseStart, t, row.auditId())
                    && repo.actorHasPrior(row.actorId(), LOGIN_SUCCESS, t, row.auditId())) {
                String subject = row.actorId() + "@" + row.sourceIp();
                if (!repo.flagExists(R_NEW_IP, AnomalyFlag.SUBJECT_ACTOR, subject, t.minus(Duration.ofDays(1)))) {
                    out.add(new AnomalyFlag(row, R_NEW_IP, 40, AnomalyFlag.SUBJECT_ACTOR, subject,
                            Map.of("baselineDays", props.getNewIpBaselineDays(), "ip", row.sourceIp())));
                }
            }
        }

        // 3. 관리자 쓰기 — 업무 외 시간·주말
        if ("ADMIN".equals(row.category()) && "ADMIN".equals(row.actorType()) && "SUCCESS".equals(row.outcome())
                && !ADMIN_NON_WRITE.contains(row.action()) && row.actorId() != null) {
            ZonedDateTime local = t.atZone(zone);
            boolean weekend = props.isWeekendOffHours() && (local.getDayOfWeek() == DayOfWeek.SATURDAY || local.getDayOfWeek() == DayOfWeek.SUNDAY);
            int h = local.getHour();
            boolean offHours = props.getOffHoursStart() > props.getOffHoursEnd()
                    ? (h >= props.getOffHoursStart() || h < props.getOffHoursEnd())
                    : (h >= props.getOffHoursStart() && h < props.getOffHoursEnd());
            if ((weekend || offHours) && !repo.flagExists(R_OFF_HOURS, AnomalyFlag.SUBJECT_ACTOR, row.actorId(), t.minus(Duration.ofHours(1)))) {
                out.add(new AnomalyFlag(row, R_OFF_HOURS, 30, AnomalyFlag.SUBJECT_ACTOR, row.actorId(),
                        Map.of("localTime", local.toLocalDateTime().toString(), "zone", zone.getId(), "weekend", weekend,
                                "offHours", props.getOffHoursStart() + "-" + props.getOffHoursEnd())));
            }
        }

        // 4. 기관 실패 버스트 (기준선 대비)
        if (!"SUCCESS".equals(row.outcome()) && row.agencyCode() != null) {
            long n = repo.countAgencyFailures(row.agencyCode(), winStart, t);
            if (n >= th.getAgencyFailureBurst()) {
                Instant baseStart = t.minus(Duration.ofDays(props.getBaselineDays()));
                long baseTotal = repo.countAgencyFailures(row.agencyCode(), baseStart, winStart);
                double windows = (double) Duration.ofDays(props.getBaselineDays()).toMinutes() / props.getWindowMinutes();
                double perWindow = baseTotal / windows;
                double ratio = n / Math.max(perWindow, 1.0);
                if (ratio >= th.getAgencyFailureBurstRatio() && !repo.flagExists(R_AGENCY_BURST, AnomalyFlag.SUBJECT_AGENCY, row.agencyCode(), winStart)) {
                    int score = (int) Math.min(100, Math.round(50 + ratio * 5));
                    out.add(new AnomalyFlag(row, R_AGENCY_BURST, score, AnomalyFlag.SUBJECT_AGENCY, row.agencyCode(),
                            Map.of("count", n, "threshold", th.getAgencyFailureBurst(), "windowMinutes", props.getWindowMinutes(),
                                    "baselinePerWindow", Math.round(perWindow * 100) / 100.0, "ratio", Math.round(ratio * 100) / 100.0,
                                    "baselineDays", props.getBaselineDays())));
                }
            }
        }

        // 5. Handoff 티켓 재검증(재사용) 반복
        if ("HANDOFF".equals(row.category()) && "HANDOFF_VERIFIED".equals(row.action()) && !"SUCCESS".equals(row.outcome())
                && "TICKET_CONSUMED".equals(row.outcomeDetail()) && row.agencyCode() != null) {
            long n = repo.countAgencyActionDetail(row.agencyCode(), "HANDOFF_VERIFIED", "TICKET_CONSUMED", winStart, t);
            if (n >= th.getTicketReplay() && !repo.flagExists(R_TICKET_REPLAY, AnomalyFlag.SUBJECT_AGENCY, row.agencyCode(), winStart)) {
                out.add(new AnomalyFlag(row, R_TICKET_REPLAY, 70, AnomalyFlag.SUBJECT_AGENCY, row.agencyCode(),
                        Map.of("count", n, "threshold", th.getTicketReplay(), "windowMinutes", props.getWindowMinutes())));
            }
        }
        return out;
    }
}
