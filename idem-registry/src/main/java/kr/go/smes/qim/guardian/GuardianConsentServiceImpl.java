package kr.go.smes.qim.guardian;

import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.qim.domain.MinorGuardianPolicy;
import kr.go.smes.qim.infrastructure.jpa.entity.UserProfileJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.repository.UserProfileJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 14세 미만 회원 보호자 동의 서비스 구현체
 *
 * <p>흐름:
 * <ol>
 *   <li>미성년자 프로필 조회 → is_minor=true 검증</li>
 *   <li>이미 보호자 동의 완료 여부 확인</li>
 *   <li>보호자 회원이 실제 존재하는지 확인</li>
 *   <li>guardian_qim_user_id + guardian_consent_at 업데이트</li>
 * </ol>
 *
 * <p>설계서 §P3-05 참조
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardianConsentServiceImpl implements GuardianConsentService {

    private final UserProfileJpaRepository profileRepository;

    @Override
    @Transactional
    public void grantConsent(String minorQimUserId, String guardianQimUserId, String correlationId) {
        log.info("[Guardian] 보호자 동의 요청: minorQimUserId={} guardianQimUserId={} correlationId={}",
                minorQimUserId, guardianQimUserId, correlationId);

        // 1. 미성년자 프로필 조회
        UserProfileJpaEntity minorProfile = profileRepository.findById(minorQimUserId)
                .orElseThrow(() -> {
                    log.warn("[Guardian] 미성년자 프로필 없음: qimUserId={}", minorQimUserId);
                    return new PlatformException(PlatformErrorCode.IM_USER_NOT_FOUND, correlationId);
                });

        // 2. is_minor 검증
        if (!Boolean.TRUE.equals(minorProfile.getIsMinor())) {
            log.warn("[Guardian] 미성년자 아님: qimUserId={}", minorQimUserId);
            throw new PlatformException(
                    PlatformErrorCode.IM_MINOR_GUARDIAN_REQUIRED, correlationId,
                    "해당 회원은 14세 미만 미성년자가 아닙니다: " + minorQimUserId);
        }

        // 3. 이미 보호자 동의 완료 여부
        if (minorProfile.getGuardianConsentAt() != null) {
            log.warn("[Guardian] 이미 보호자 동의 완료: qimUserId={} guardianQimUserId={}",
                    minorQimUserId, minorProfile.getGuardianQimUserId());
            throw new PlatformException(PlatformErrorCode.IM_GUARDIAN_CONSENT_ALREADY, correlationId);
        }

        // 4. 보호자 회원 존재 여부 확인
        UserProfileJpaEntity guardianProfile = profileRepository.findById(guardianQimUserId)
                .orElseThrow(() -> {
                    log.warn("[Guardian] 보호자 프로필 없음: guardianQimUserId={}", guardianQimUserId);
                    return new PlatformException(PlatformErrorCode.IM_GUARDIAN_NOT_FOUND, correlationId);
                });

        // 5. 보호자 자신도 미성년자인지 확인 (보호자는 성인이어야 함)
        if (Boolean.TRUE.equals(guardianProfile.getIsMinor())) {
            log.warn("[Guardian] 보호자가 미성년자: guardianQimUserId={}", guardianQimUserId);
            throw new PlatformException(
                    PlatformErrorCode.IM_GUARDIAN_NOT_FOUND, correlationId,
                    "보호자가 14세 미만이므로 동의 권한이 없습니다: " + guardianQimUserId);
        }

        // 6. 보호자 동의 완료 처리
        // Instant.now()는 JVM/DB 타임존에 무관하게 UTC epoch를 반환한다.
        // JDBC serverTimezone=UTC 설정과 결합하여 DB의 DATETIME(6) 컬럼에
        // UTC 값 그대로 저장되므로 고객이 보는 시각과 DB 저장값이 일치한다.
        Instant consentAt = Instant.now();
        int updated = profileRepository.updateGuardianConsent(minorQimUserId, guardianQimUserId, consentAt);
        if (updated == 0) {
            log.error("[Guardian] 보호자 동의 업데이트 실패 (0 rows): qimUserId={}", minorQimUserId);
            throw new PlatformException(PlatformErrorCode.IM_USER_NOT_FOUND, correlationId);
        }

        log.info("[Guardian] 보호자 동의 완료: minorQimUserId={} guardianQimUserId={} consentAt={}",
                minorQimUserId, guardianQimUserId, consentAt);
    }

    @Override
    @Transactional(readOnly = true)
    public GuardianConsentStatus getStatus(String qimUserId, String correlationId) {
        return profileRepository.findById(qimUserId)
                .map(p -> GuardianConsentStatus.builder()
                        .minor(Boolean.TRUE.equals(p.getIsMinor()))
                        .consentGranted(p.getGuardianConsentAt() != null)
                        .guardianQimUserId(p.getGuardianQimUserId())
                        .guardianConsentAt(p.getGuardianConsentAt())
                        .build())
                .orElseThrow(() -> {
                    log.warn("[Guardian] 사용자 프로필 없음: qimUserId={}", qimUserId);
                    return new PlatformException(PlatformErrorCode.IM_USER_NOT_FOUND, correlationId);
                });
    }
}
