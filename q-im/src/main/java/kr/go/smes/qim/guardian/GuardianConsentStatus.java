package kr.go.smes.qim.guardian;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 보호자 동의 상태 응답 DTO
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

    /** 보호자 동의 완료 시각 (동의 완료 시에만 설정) */
    private final LocalDateTime guardianConsentAt;
}
