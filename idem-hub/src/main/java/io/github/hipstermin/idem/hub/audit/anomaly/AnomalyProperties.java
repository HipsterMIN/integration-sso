package io.github.hipstermin.idem.hub.audit.anomaly;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 1.1 감사 이상 탐지 설정 ({@code idem.hub.audit.anomaly.*}). 관찰 모드만 있다 — 플래그를 남길 뿐 경보·차단은 없다.
 * 규칙과 기본값의 근거는 docs/audit-anomaly.md.
 */
@Component
@ConfigurationProperties(prefix = "idem.hub.audit.anomaly")
@Getter
@Setter
public class AnomalyProperties {

    private boolean enabled = true;
    /** observe 만 지원. alert 는 3개월 기준선 뒤 결정(예약) */
    private String mode = "observe";
    private long intervalMs = 30000;
    private int batchSize = 200;
    /** 이 초보다 새 행은 다음 주기로 — 비동기 발행 지연으로 audit_id(UUIDv7) 순서와 커밋 순서가 어긋나는 창을 피한다 */
    private int lagSeconds = 5;
    /** 집계 창(분) — 버스트 규칙의 분모 */
    private int windowMinutes = 10;
    /** 기관 실패 기준선(일) — 창 평균을 내는 기간 */
    private int baselineDays = 7;
    /** 새 출처 IP 규칙의 기준선(일) */
    private int newIpBaselineDays = 30;
    private int offHoursStart = 22;   // 포함 — 이 시각부터 업무 외
    private int offHoursEnd = 7;      // 미포함 — 이 시각부터 업무
    private boolean weekendOffHours = true;

    private Thresholds thresholds = new Thresholds();

    @Getter
    @Setter
    public static class Thresholds {
        private int loginFailureBurst = 5;          // 창 안 같은 행위자의 로그인·2단계 실패
        private int agencyFailureBurst = 20;        // 창 안 같은 기관의 FAILURE
        private double agencyFailureBurstRatio = 3.0; // 기준선(창 평균) 대비 배수
        private int ticketReplay = 3;               // 창 안 같은 기관의 TICKET_CONSUMED(재검증) 실패
    }
}
