package kr.go.smes.qim.guardian;

import kr.go.smes.qim.domain.MinorGuardianPolicy;
import kr.go.smes.qim.domain.UserProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.Year;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MinorGuardianPolicy 단위 테스트
 *
 * <p>설계서 §P3-05 — 14세 미만 보호자 인증 정책 검증
 */
@DisplayName("MinorGuardianPolicy — 14세 미만 판정 정책")
class MinorGuardianPolicyTest {

    @Nested
    @DisplayName("isMinor()")
    class IsMinorTest {

        @Test
        @DisplayName("현재 연도 기준 14세 미만 → true")
        void underFourteen_returnsTrue() {
            short birthYear = (short) (Year.now().getValue() - 10); // 만 10세
            assertThat(MinorGuardianPolicy.isMinor(birthYear)).isTrue();
        }

        @Test
        @DisplayName("현재 연도 기준 정확히 13세 → true (14세 미만)")
        void thirteenYearsOld_returnsTrue() {
            short birthYear = (short) (Year.now().getValue() - 13);
            assertThat(MinorGuardianPolicy.isMinor(birthYear)).isTrue();
        }

        @Test
        @DisplayName("현재 연도 기준 14세 → false (경계값)")
        void fourteenYearsOld_returnsFalse() {
            short birthYear = (short) (Year.now().getValue() - 14);
            assertThat(MinorGuardianPolicy.isMinor(birthYear)).isFalse();
        }

        @Test
        @DisplayName("현재 연도 기준 20세 → false")
        void twentyYearsOld_returnsFalse() {
            short birthYear = (short) (Year.now().getValue() - 20);
            assertThat(MinorGuardianPolicy.isMinor(birthYear)).isFalse();
        }

        @Test
        @DisplayName("birthYear가 null → false (보수적 기본값)")
        void nullBirthYear_returnsFalse() {
            assertThat(MinorGuardianPolicy.isMinor(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("requiresGuardianConsent()")
    class RequiresGuardianConsentTest {

        @Test
        @DisplayName("미성년자이고 동의 미완료 → true")
        void minorWithoutConsent_requiresConsent() {
            UserProfile profile = UserProfile.builder()
                    .isMinor(true)
                    .guardianConsentAt(null)
                    .build();
            assertThat(MinorGuardianPolicy.requiresGuardianConsent(profile)).isTrue();
        }

        @Test
        @DisplayName("미성년자이고 동의 완료 → false")
        void minorWithConsent_noRequirement() {
            UserProfile profile = UserProfile.builder()
                    .isMinor(true)
                    .guardianQimUserId("guardian-uuid")
                    .guardianConsentAt(LocalDateTime.now().minusDays(1))
                    .build();
            assertThat(MinorGuardianPolicy.requiresGuardianConsent(profile)).isFalse();
        }

        @Test
        @DisplayName("성인 → false")
        void adultUser_noRequirement() {
            UserProfile profile = UserProfile.builder()
                    .isMinor(false)
                    .build();
            assertThat(MinorGuardianPolicy.requiresGuardianConsent(profile)).isFalse();
        }

        @Test
        @DisplayName("isMinor가 null → false (NPE 방지)")
        void nullIsMinor_noRequirement() {
            UserProfile profile = UserProfile.builder()
                    .isMinor(null)
                    .build();
            assertThat(MinorGuardianPolicy.requiresGuardianConsent(profile)).isFalse();
        }
    }

    @Nested
    @DisplayName("UserProfile.isGuardianConsentDone()")
    class IsGuardianConsentDoneTest {

        @Test
        @DisplayName("미성년자 + guardianConsentAt 설정됨 → true")
        void consentDone() {
            UserProfile profile = UserProfile.builder()
                    .isMinor(true)
                    .guardianConsentAt(LocalDateTime.now())
                    .build();
            assertThat(profile.isGuardianConsentDone()).isTrue();
        }

        @Test
        @DisplayName("미성년자 + guardianConsentAt null → false")
        void consentNotDone() {
            UserProfile profile = UserProfile.builder()
                    .isMinor(true)
                    .guardianConsentAt(null)
                    .build();
            assertThat(profile.isGuardianConsentDone()).isFalse();
        }

        @Test
        @DisplayName("성인 + guardianConsentAt 설정됨 → false (성인은 동의 불필요)")
        void adultWithConsentTimestamp_false() {
            UserProfile profile = UserProfile.builder()
                    .isMinor(false)
                    .guardianConsentAt(LocalDateTime.now())
                    .build();
            assertThat(profile.isGuardianConsentDone()).isFalse();
        }
    }
}
