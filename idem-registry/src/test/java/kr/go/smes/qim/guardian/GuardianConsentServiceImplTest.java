package kr.go.smes.qim.guardian;

import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.qim.infrastructure.jpa.entity.UserProfileJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.repository.UserProfileJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * GuardianConsentServiceImpl 단위 테스트
 *
 * <p>설계서 §P3-05 — 보호자 동의 서비스 검증 (Mockito)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuardianConsentServiceImpl — 보호자 동의 서비스")
class GuardianConsentServiceImplTest {

    private static final String MINOR_ID    = "minor-uuid-001";
    private static final String GUARDIAN_ID = "guardian-uuid-001";
    private static final String CID         = "corr-001";

    @Mock
    private UserProfileJpaRepository profileRepository;

    @InjectMocks
    private GuardianConsentServiceImpl sut;

    // ── grantConsent ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("grantConsent()")
    class GrantConsentTest {

        @Test
        @DisplayName("정상 흐름: 미성년자 + 미동의 + 성인 보호자 → 동의 완료")
        void happyPath() {
            // given
            UserProfileJpaEntity minorProfile = minorProfile(false);
            UserProfileJpaEntity guardianProfile = adultProfile();

            given(profileRepository.findById(MINOR_ID)).willReturn(Optional.of(minorProfile));
            given(profileRepository.findById(GUARDIAN_ID)).willReturn(Optional.of(guardianProfile));
            given(profileRepository.updateGuardianConsent(eq(MINOR_ID), eq(GUARDIAN_ID), any()))
                    .willReturn(1);

            // when
            sut.grantConsent(MINOR_ID, GUARDIAN_ID, CID);

            // then
            then(profileRepository).should().updateGuardianConsent(
                    eq(MINOR_ID), eq(GUARDIAN_ID), any(Instant.class));
        }

        @Test
        @DisplayName("미성년자 프로필 없음 → IM_USER_NOT_FOUND")
        void minorNotFound() {
            given(profileRepository.findById(MINOR_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> sut.grantConsent(MINOR_ID, GUARDIAN_ID, CID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_USER_NOT_FOUND);
        }

        @Test
        @DisplayName("is_minor=false인 회원에 보호자 동의 → IM_MINOR_GUARDIAN_REQUIRED")
        void notMinor_throwsMinorGuardianRequired() {
            UserProfileJpaEntity adultProfile = adultProfile();
            given(profileRepository.findById(MINOR_ID)).willReturn(Optional.of(adultProfile));

            assertThatThrownBy(() -> sut.grantConsent(MINOR_ID, GUARDIAN_ID, CID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_MINOR_GUARDIAN_REQUIRED);
        }

        @Test
        @DisplayName("이미 보호자 동의 완료 → IM_GUARDIAN_CONSENT_ALREADY")
        void alreadyConsented() {
            UserProfileJpaEntity minorProfile = minorProfile(true); // 이미 동의됨
            given(profileRepository.findById(MINOR_ID)).willReturn(Optional.of(minorProfile));

            assertThatThrownBy(() -> sut.grantConsent(MINOR_ID, GUARDIAN_ID, CID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_GUARDIAN_CONSENT_ALREADY);
        }

        @Test
        @DisplayName("보호자 프로필 없음 → IM_GUARDIAN_NOT_FOUND")
        void guardianNotFound() {
            UserProfileJpaEntity minorProfile = minorProfile(false);
            given(profileRepository.findById(MINOR_ID)).willReturn(Optional.of(minorProfile));
            given(profileRepository.findById(GUARDIAN_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> sut.grantConsent(MINOR_ID, GUARDIAN_ID, CID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_GUARDIAN_NOT_FOUND);
        }

        @Test
        @DisplayName("보호자도 미성년자 → IM_GUARDIAN_NOT_FOUND (보호자 자격 없음)")
        void guardianIsAlsoMinor() {
            UserProfileJpaEntity minorProfile = minorProfile(false);
            UserProfileJpaEntity guardianIsMinor = minorProfile(false); // 보호자도 미성년자
            given(profileRepository.findById(MINOR_ID)).willReturn(Optional.of(minorProfile));
            given(profileRepository.findById(GUARDIAN_ID)).willReturn(Optional.of(guardianIsMinor));

            assertThatThrownBy(() -> sut.grantConsent(MINOR_ID, GUARDIAN_ID, CID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_GUARDIAN_NOT_FOUND);
        }
    }

    // ── getStatus ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getStatus()")
    class GetStatusTest {

        @Test
        @DisplayName("미성년자 + 동의 미완료 → minor=true, consentGranted=false")
        void minorNotConsented() {
            given(profileRepository.findById(MINOR_ID)).willReturn(Optional.of(minorProfile(false)));

            GuardianConsentStatus status = sut.getStatus(MINOR_ID, CID);

            assertThat(status.isMinor()).isTrue();
            assertThat(status.isConsentGranted()).isFalse();
            assertThat(status.getGuardianConsentAt()).isNull();
        }

        @Test
        @DisplayName("미성년자 + 동의 완료 → minor=true, consentGranted=true")
        void minorConsented() {
            given(profileRepository.findById(MINOR_ID)).willReturn(Optional.of(minorProfile(true)));

            GuardianConsentStatus status = sut.getStatus(MINOR_ID, CID);

            assertThat(status.isMinor()).isTrue();
            assertThat(status.isConsentGranted()).isTrue();
            assertThat(status.getGuardianConsentAt()).isNotNull();
        }

        @Test
        @DisplayName("성인 → minor=false, consentGranted=false")
        void adultUser() {
            given(profileRepository.findById(MINOR_ID)).willReturn(Optional.of(adultProfile()));

            GuardianConsentStatus status = sut.getStatus(MINOR_ID, CID);

            assertThat(status.isMinor()).isFalse();
            assertThat(status.isConsentGranted()).isFalse();
        }

        @Test
        @DisplayName("프로필 없음 → IM_USER_NOT_FOUND")
        void profileNotFound() {
            given(profileRepository.findById(MINOR_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> sut.getStatus(MINOR_ID, CID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_USER_NOT_FOUND);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UserProfileJpaEntity minorProfile(boolean consentDone) {
        UserProfileJpaEntity e = new UserProfileJpaEntity();
        e.setQimUserId(MINOR_ID);
        e.setIsMinor(true);
        if (consentDone) {
            e.setGuardianQimUserId(GUARDIAN_ID);
            e.setGuardianConsentAt(Instant.now().minusSeconds(86400)); // 1일 전 UTC
        }
        return e;
    }

    private UserProfileJpaEntity adultProfile() {
        UserProfileJpaEntity e = new UserProfileJpaEntity();
        e.setQimUserId(GUARDIAN_ID);
        e.setIsMinor(false);
        return e;
    }
}
