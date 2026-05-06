package kr.go.smes.ido.domain;

import kr.go.smes.common.domain.AuthResult;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 기관 메타데이터 (IdO 정책 SoR)
 * 설계서 11.2 / 16.11절 참조
 *
 * - 기관코드, 콜백 화이트리스트, 최소 인증수준, 허용 속성, 점검 시간 등
 * - TTL ≤ 60분 캐시 (Redis)
 */
@Getter
@Builder
public class AgencyMeta {

    private final String agencyCode;
    private final String officialName;

    /** 허용된 콜백 URL 화이트리스트 */
    private final List<String> callbackWhitelist;

    /** 기관 최소 인증 수준 */
    private final AuthResult.AuthLevel minAuthLevel;

    /** 기관에 전달할 허용 속성 목록 */
    private final List<String> allowedAttributes;

    /** 현재 정책 버전 (설계서 16.4절) */
    private final String policyVersion;

    /** 점검 시간대 (설계서 16.11절) */
    private final List<MaintenanceWindow> maintenanceWindows;

    /** 기관 API Key (검증용) */
    private final String apiKeyHash;

    /** 활성 여부 */
    private final boolean active;

    @Getter
    @Builder
    public static class MaintenanceWindow {
        private final String dayOfWeek;
        private final String startTime;
        private final String endTime;
    }
}
