package kr.go.smes.qim.guardian;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * 보호자 동의 상태 응답 DTO
 *
 * <p>시각 필드는 {@link Instant}(UTC epoch)로 반환한다.
 * {@code spring.jackson.serialization.write-dates-as-timestamps=false} 설정에 의해
 * ISO-8601 UTC 문자열({@code "2025-06-01T06:30:00Z"})로 직렬화된다.
 * FE에서 KST 표시가 필요하면 클라이언트 측 {@code toZonedDateTime(ZoneId.of("Asia/Seoul"))} 변환 사용.
 */
@Getter
@Builder
public class GuardianConsentStatus {

    /** 미성년자 여부 */
    private final boolean minor;

    /** 보호자 동의 완료 여부 */
    private final boolean consentGranted;

    /** 보호자 qim_user_id (동의 완료 시에만 설정) */
    private final String guardianQimUserId;

    /** 보호자 동의 완료 시각 (UTC, 동의 완료 시에만 설정) */
    private final Instant guardianConsentAt;
}
