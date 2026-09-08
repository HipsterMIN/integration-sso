package io.github.hipstermin.idem.registry.consent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.ConsentRecordJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.ConsentVersionJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.ConsentRecordJpaRepository;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.ConsentVersionJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ConsentServiceImpl 단위 테스트 — 동의 기록/철회/조회 검증
 *
 * <p>커버 케이스 (10개):
 * <ul>
 *   <li>agree — 명시된 versionId로 동의 기록</li>
 *   <li>agree — versionId null → 최신 ACTIVE 버전 자동 선택</li>
 *   <li>agree — 최신 버전 없음 → IM_CONSENT_VERSION_INVALID</li>
 *   <li>withdraw — 선택 동의(required=false) 철회 성공</li>
 *   <li>withdraw — 필수 동의(required=true) 철회 시도 → IM_WITHDRAWAL_NOT_ALLOWED</li>
 *   <li>withdraw — 동의 기록 없음 → IM_CONSENT_NOT_FOUND</li>
 *   <li>withdraw — 이미 철회된 기록 → IM_CONSENT_NOT_FOUND</li>
 *   <li>getConsentStatus — AGREED 기록 목록 반환</li>
 *   <li>getActiveVersions — ACTIVE 버전 목록 반환</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConsentServiceImpl — 동의 서비스 단위 테스트")
class ConsentServiceImplTest {

    @Mock private ConsentVersionJpaRepository versionRepository;
    @Mock private ConsentRecordJpaRepository  recordRepository;

    @InjectMocks
    private ConsentServiceImpl sut;

    private static final String QIM_USER_ID    = "user-consent-001";
    private static final String VERSION_ID     = "version-001";
    private static final String RECORD_ID      = "record-001";
    private static final String CORRELATION_ID = "corr-consent-001";
    private static final String CONSENT_TYPE   = "PRIVACY_POLICY";

    private ConsentVersionJpaEntity version(boolean required) {
        ConsentVersionJpaEntity v = new ConsentVersionJpaEntity();
        v.setVersionId(VERSION_ID);
        v.setConsentType(CONSENT_TYPE);
        v.setVersionTag("2026-05-01");
        v.setTitle("개인정보 처리방침");
        v.setRequired(required);
        v.setStatus("ACTIVE");
        v.setEffectiveAt(Instant.now().minusSeconds(3600));
        return v;
    }

    private ConsentRecordJpaEntity record(String status) {
        ConsentRecordJpaEntity r = new ConsentRecordJpaEntity();
        r.setRecordId(RECORD_ID);
        r.setQimUserId(QIM_USER_ID);
        r.setVersionId(VERSION_ID);
        r.setConsentType(CONSENT_TYPE);
        r.setConsentStatus(status);
        r.setAgreedAt(Instant.now());
        return r;
    }

    // ── agree ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("agree — 동의 기록")
    class Agree {

        @Test
        @DisplayName("명시된 versionId로 동의 — recordId/consentStatus=AGREED 반환")
        void agree_explicitVersionId() {
            given(versionRepository.findById(VERSION_ID))
                    .willReturn(Optional.of(version(true)));
            given(recordRepository.save(any()))
                    .willAnswer(inv -> inv.getArgument(0));

            ConsentRequest req = ConsentRequest.builder()
                    .versionId(VERSION_ID)
                    .consentType(CONSENT_TYPE)
                    .agreedVia("WEB_SIGNUP")
                    .correlationId(CORRELATION_ID)
                    .build();

            ConsentResult result = sut.agree(QIM_USER_ID, req);

            assertThat(result.getConsentStatus()).isEqualTo("AGREED");
            assertThat(result.getConsentType()).isEqualTo(CONSENT_TYPE);
            assertThat(result.getVersionTag()).isEqualTo("2026-05-01");

            // DB에 새 레코드 INSERT 확인
            ArgumentCaptor<ConsentRecordJpaEntity> captor =
                    ArgumentCaptor.forClass(ConsentRecordJpaEntity.class);
            then(recordRepository).should().save(captor.capture());
            assertThat(captor.getValue().getQimUserId()).isEqualTo(QIM_USER_ID);
            assertThat(captor.getValue().getVersionId()).isEqualTo(VERSION_ID);
        }

        @Test
        @DisplayName("versionId null → 최신 ACTIVE 버전 자동 선택")
        void agree_autoSelectLatest() {
            given(versionRepository.findLatestActive(eq(CONSENT_TYPE), any()))
                    .willReturn(Optional.of(version(false)));
            given(recordRepository.save(any()))
                    .willAnswer(inv -> inv.getArgument(0));

            ConsentRequest req = ConsentRequest.builder()
                    .consentType(CONSENT_TYPE)
                    .agreedVia("APP_SIGNUP")
                    .build();

            ConsentResult result = sut.agree(QIM_USER_ID, req);
            assertThat(result.getConsentStatus()).isEqualTo("AGREED");
        }

        @Test
        @DisplayName("최신 ACTIVE 버전 없음 → IM_CONSENT_VERSION_INVALID")
        void agree_noActiveVersion_throws() {
            given(versionRepository.findLatestActive(eq(CONSENT_TYPE), any()))
                    .willReturn(Optional.empty());

            ConsentRequest req = ConsentRequest.builder()
                    .consentType(CONSENT_TYPE)
                    .build();

            assertThatThrownBy(() -> sut.agree(QIM_USER_ID, req))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_CONSENT_VERSION_INVALID);
        }
    }

    // ── withdraw ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("withdraw — 동의 철회")
    class Withdraw {

        @Test
        @DisplayName("선택 동의(required=false) 철회 성공 → WITHDRAWN 반환")
        void withdraw_optional_success() {
            given(recordRepository.findLatestByUserAndType(QIM_USER_ID, CONSENT_TYPE))
                    .willReturn(Optional.of(record("AGREED")));
            given(versionRepository.findById(VERSION_ID))
                    .willReturn(Optional.of(version(false))); // optional

            ConsentResult result = sut.withdraw(
                    QIM_USER_ID, CONSENT_TYPE, "더이상 필요 없음", CORRELATION_ID);
            assertThat(result.getConsentStatus()).isEqualTo("WITHDRAWN");
        }

        @Test
        @DisplayName("필수 동의(required=true) 철회 시도 → IM_WITHDRAWAL_NOT_ALLOWED")
        void withdraw_required_throws() {
            given(recordRepository.findLatestByUserAndType(QIM_USER_ID, CONSENT_TYPE))
                    .willReturn(Optional.of(record("AGREED")));
            given(versionRepository.findById(VERSION_ID))
                    .willReturn(Optional.of(version(true))); // required

            assertThatThrownBy(() ->
                    sut.withdraw(QIM_USER_ID, CONSENT_TYPE, "철회 시도", CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_WITHDRAWAL_NOT_ALLOWED);
        }

        @Test
        @DisplayName("동의 기록 없음 → IM_CONSENT_NOT_FOUND")
        void withdraw_noRecord_throws() {
            given(recordRepository.findLatestByUserAndType(QIM_USER_ID, CONSENT_TYPE))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    sut.withdraw(QIM_USER_ID, CONSENT_TYPE, "철회", CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_CONSENT_NOT_FOUND);
        }

        @Test
        @DisplayName("이미 철회된 기록 → IM_CONSENT_NOT_FOUND")
        void withdraw_alreadyWithdrawn_throws() {
            given(recordRepository.findLatestByUserAndType(QIM_USER_ID, CONSENT_TYPE))
                    .willReturn(Optional.of(record("WITHDRAWN")));

            assertThatThrownBy(() ->
                    sut.withdraw(QIM_USER_ID, CONSENT_TYPE, "재철회 시도", CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_CONSENT_NOT_FOUND);
        }
    }

    // ── getConsentStatus / getActiveVersions ──────────────────────────────

    @Test
    @DisplayName("getConsentStatus — AGREED 기록 목록 반환")
    void getConsentStatus_returnsList() {
        given(recordRepository.findActiveConsentsByUser(QIM_USER_ID))
                .willReturn(List.of(record("AGREED")));
        given(versionRepository.findById(VERSION_ID))
                .willReturn(Optional.of(version(true)));

        List<ConsentResult> results = sut.getConsentStatus(QIM_USER_ID);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getConsentStatus()).isEqualTo("AGREED");
    }

    @Test
    @DisplayName("getActiveVersions — ACTIVE 버전 목록 반환")
    void getActiveVersions_returnsList() {
        given(versionRepository.findAllActive(any()))
                .willReturn(List.of(version(true)));

        List<ConsentVersionInfo> versions = sut.getActiveVersions();
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getConsentType()).isEqualTo(CONSENT_TYPE);
        assertThat(versions.get(0).isRequired()).isTrue();
    }
}
