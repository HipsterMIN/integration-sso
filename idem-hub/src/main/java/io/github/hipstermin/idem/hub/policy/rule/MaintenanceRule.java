package io.github.hipstermin.idem.hub.policy.rule;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 점검 시간대 차단 — 프로파일 {@code policy.maintenance[]} (dayOfWeek/startTime/endTime).
 *
 * <p>요일은 {@code MON} 과 {@code MONDAY} 를 모두 받는다. 종전 구현은 {@code DayOfWeek.name()}("MONDAY")과
 * 저장값("MON")을 그대로 비교해 점검 시간대가 한 번도 걸리지 않았다 — S3 에서 앞 3글자 비교로 고쳤다.
 * 시각은 {@code ido.policy.zone}(기본 Asia/Seoul) 기준.
 */
@Component
public class MaintenanceRule implements PolicyRule {

    public static final String TYPE = "MAINTENANCE";
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    private final ZoneId zone;

    public MaintenanceRule() {
        this(DEFAULT_ZONE);
    }

    public MaintenanceRule(ZoneId zone) {
        this.zone = zone;
    }

    @Override public String type() { return TYPE; }
    @Override public boolean builtIn() { return true; }
    @Override public int order() { return 10; }

    @Override
    public PolicyDecision evaluate(PolicyContext ctx, Map<String, Object> params) {
        List<ServiceProfile.MaintenanceWindow> windows = ctx.policy() != null ? ctx.policy().maintenance() : null;
        if (windows == null || windows.isEmpty()) {
            return PolicyDecision.skip(TYPE, "점검 시간대 설정 없음");
        }
        return isWithin(windows, ctx.now(), zone)
                ? PolicyDecision.deny(TYPE, "기관 점검 시간대", "MAINTENANCE", PlatformErrorCode.AGENCY_MAINTENANCE)
                : PolicyDecision.allow(TYPE, "점검 시간대 아님");
    }

    /** 공용 판정 — 레거시 {@code PolicyEngine.isUnderMaintenance} 도 이 로직을 쓴다. */
    public static boolean isWithin(List<ServiceProfile.MaintenanceWindow> windows, Instant at, ZoneId zone) {
        if (windows == null || windows.isEmpty() || at == null) return false;
        ZonedDateTime zdt = at.atZone(zone);
        DayOfWeek today = zdt.getDayOfWeek();
        LocalTime now = zdt.toLocalTime();
        return windows.stream().anyMatch(w -> {
            if (!sameDay(w.dayOfWeek(), today)) return false;
            try {
                LocalTime start = LocalTime.parse(w.startTime());
                LocalTime end   = LocalTime.parse(w.endTime());
                return !now.isBefore(start) && now.isBefore(end);
            } catch (RuntimeException e) {
                return false; // 형식 오류 창은 무시 (스키마가 PUT 시점에 막는다)
            }
        });
    }

    private static boolean sameDay(String configured, DayOfWeek today) {
        if (configured == null || configured.length() < 3) return false;
        String c = configured.trim().toUpperCase(Locale.ROOT);
        return today.name().startsWith(c.substring(0, 3)) && today.name().startsWith(c);
    }
}
