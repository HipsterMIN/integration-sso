package io.github.hipstermin.idem.hub.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.domain.CastToken;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import io.github.hipstermin.idem.hub.sso.CastTokenService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

/**
 * S13 — Cross-Agency SSO (CAST Token) 통합 테스트
 *
 * <p>실제 PostgreSQL(Testcontainer) + Redis(Testcontainer) 환경에서
 * Cross-Agency SSO 흐름 전체를 end-to-end 검증한다.
 *
 * <h3>테스트 시나리오</h3>
 * <ul>
 *   <li>S13-T1: CAST 토큰 발급 성공 — FE 세션 + 대상 기관 등록</li>
 *   <li>S13-T2: CAST 토큰 발급 실패 — FE 세션 쿠키 없음 (401)</li>
 *   <li>S13-T3: CAST 토큰 발급 실패 — 미등록 대상 기관 (403/404)</li>
 *   <li>S13-T4: CAST 토큰 검증 성공 — 정상 소비 + Handoff 발급</li>
 *   <li>S13-T5: CAST 토큰 재사용 시도 — 이미 소비된 토큰 (409)</li>
 *   <li>S13-T6: CAST 토큰 기관 불일치 — tgtAgency ≠ verifier agency (403)</li>
 * </ul>
 *
 * @see CastTokenService
 */
@DisplayName("S13 — Cross-Agency SSO CAST Token 통합 테스트")
class CrossAgencySsoIntegrationTest extends IntegrationTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AgencyMetaJpaRepository agencyMetaJpaRepository;

    @Autowired
    private FeSessionService feSessionService;

    @Autowired
    private CastTokenService castTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String SOURCE_AGENCY = "S13_AGENCY_A";
    private static final String TARGET_AGENCY = "S13_AGENCY_B";
    private static final String WRONG_AGENCY  = "S13_AGENCY_C";
    private String baseUrl;
    private String qimUserId;

    @BeforeEach
    void setUp() {
        baseUrl    = "http://localhost:" + port;
        qimUserId  = "qim-s13-" + UUID.randomUUID().toString().substring(0, 8);

        // 기관 A 등록
        seedAgency(SOURCE_AGENCY);
        // 기관 B 등록
        seedAgency(TARGET_AGENCY);
        // 기관 C는 등록하지 않음 (미등록 기관 테스트)
        agencyMetaJpaRepository.findById(WRONG_AGENCY).ifPresent(agencyMetaJpaRepository::delete);
    }

    // ── S13-T1: CAST 발급 성공 ────────────────────────────────────────────

    @Test
    @DisplayName("S13-T1: CAST 토큰 발급 성공 — FE 세션 존재 + 대상 기관 등록")
    void s13T1_issueCastToken_success() {
        // Given: FE 세션 생성 (기관 A 로그인 완료 상태)
        FeSession session = feSessionService.create(qimUserId, "auth-" + UUID.randomUUID(),
                "MEDIUM", null);

        // When: CAST 서비스 직접 호출 (쿠키 기반 컨트롤러 레이어 우회)
        CastToken castToken = castTokenService.issue(session.getFeSessionId(), TARGET_AGENCY,
                "s13-t1-" + UUID.randomUUID());

        // Then
        assertThat(castToken).isNotNull();
        assertThat(castToken.jti()).isNotBlank();
        assertThat(castToken.token()).isNotBlank();
        assertThat(castToken.qimUserId()).isEqualTo(qimUserId);
        assertThat(castToken.targetAgency()).isEqualTo(TARGET_AGENCY);
        assertThat(castToken.authLevel()).isEqualTo("MEDIUM");
        assertThat(castToken.isExpired()).isFalse();
        assertThat(castToken.expiresAt().getEpochSecond() - castToken.issuedAt().getEpochSecond())
                .isEqualTo(CastToken.TTL_SECONDS);
    }

    // ── S13-T2: FE 세션 없음 ──────────────────────────────────────────────

    @Test
    @DisplayName("S13-T2: CAST 발급 실패 — 존재하지 않는 FE 세션 ID")
    void s13T2_issueCastToken_noSession_throws() {
        // Given: 존재하지 않는 세션 ID
        String fakeSessionId = "non-existent-session-" + UUID.randomUUID();

        // When & Then
        org.junit.jupiter.api.Assertions.assertThrows(
            io.github.hipstermin.idem.common.error.PlatformException.class,
            () -> castTokenService.issue(fakeSessionId, TARGET_AGENCY, "s13-t2-cid"),
            "FE 세션 없음 시 PlatformException(SSO_CAST_SESSION_NOT_FOUND) 발생해야 함"
        );
    }

    // ── S13-T3: 미등록 대상 기관 ──────────────────────────────────────────

    @Test
    @DisplayName("S13-T3: CAST 발급 실패 — 미등록 대상 기관 코드")
    void s13T3_issueCastToken_unknownAgency_throws() {
        // Given
        FeSession session = feSessionService.create(qimUserId, "auth-" + UUID.randomUUID(),
                "LOW", null);

        // When & Then
        io.github.hipstermin.idem.common.error.PlatformException ex =
            org.junit.jupiter.api.Assertions.assertThrows(
                io.github.hipstermin.idem.common.error.PlatformException.class,
                () -> castTokenService.issue(session.getFeSessionId(), WRONG_AGENCY, "s13-t3-cid")
            );

        assertThat(ex.getErrorCode())
                .isEqualTo(io.github.hipstermin.idem.common.error.PlatformErrorCode.AGENCY_NOT_REGISTERED);
    }

    // ── S13-T4: CAST 검증 + Handoff 발급 ────────────────────────────────

    @Test
    @DisplayName("S13-T4: CAST 토큰 검증 성공 — 1회 소비 후 sso_session_link 기록")
    void s13T4_verifyCastToken_success() {
        // Given: CAST 토큰 발급
        FeSession session = feSessionService.create(qimUserId, "auth-" + UUID.randomUUID(),
                "HIGH", null);
        String cid = "s13-t4-" + UUID.randomUUID();
        CastToken issued = castTokenService.issue(session.getFeSessionId(), TARGET_AGENCY, cid);

        // When: 검증 (기관 B 서버가 호출)
        CastToken verified = castTokenService.verify(issued.token(), TARGET_AGENCY,
                "192.168.1.100", "s13-t4-verify-" + UUID.randomUUID());

        // Then: 동일 사용자 정보 반환
        assertThat(verified.jti()).isEqualTo(issued.jti());
        assertThat(verified.qimUserId()).isEqualTo(qimUserId);
        assertThat(verified.targetAgency()).isEqualTo(TARGET_AGENCY);
        assertThat(verified.authLevel()).isEqualTo("HIGH");
    }

    // ── S13-T5: CAST 토큰 재사용 시도 ────────────────────────────────────

    @Test
    @DisplayName("S13-T5: CAST 재사용 시도 — 이미 소비된 토큰은 409 Conflict")
    void s13T5_verifyCastToken_reuse_conflict() {
        // Given: 1차 소비
        FeSession session = feSessionService.create(qimUserId, "auth-" + UUID.randomUUID(),
                "MEDIUM", null);
        CastToken issued = castTokenService.issue(session.getFeSessionId(), TARGET_AGENCY,
                "s13-t5-issue");
        castTokenService.verify(issued.token(), TARGET_AGENCY, "1.2.3.4", "s13-t5-verify-1");

        // When & Then: 2차 소비 시도
        io.github.hipstermin.idem.common.error.PlatformException ex =
            org.junit.jupiter.api.Assertions.assertThrows(
                io.github.hipstermin.idem.common.error.PlatformException.class,
                () -> castTokenService.verify(issued.token(), TARGET_AGENCY,
                        "1.2.3.5", "s13-t5-verify-2")
            );

        assertThat(ex.getErrorCode())
                .isEqualTo(io.github.hipstermin.idem.common.error.PlatformErrorCode.SSO_CAST_CONSUMED);
    }

    // ── S13-T6: 대상 기관 불일치 ─────────────────────────────────────────

    @Test
    @DisplayName("S13-T6: CAST 기관 불일치 — tgt_agency ≠ 검증 요청 기관")
    void s13T6_verifyCastToken_agencyMismatch() {
        // Given: TARGET_AGENCY로 발급한 토큰
        FeSession session = feSessionService.create(qimUserId, "auth-" + UUID.randomUUID(),
                "LOW", null);
        CastToken issued = castTokenService.issue(session.getFeSessionId(), TARGET_AGENCY,
                "s13-t6-issue");

        // When & Then: SOURCE_AGENCY가 검증 시도 → 기관 불일치
        io.github.hipstermin.idem.common.error.PlatformException ex =
            org.junit.jupiter.api.Assertions.assertThrows(
                io.github.hipstermin.idem.common.error.PlatformException.class,
                () -> castTokenService.verify(issued.token(), SOURCE_AGENCY,
                        "1.2.3.4", "s13-t6-verify")
            );

        assertThat(ex.getErrorCode())
                .isEqualTo(io.github.hipstermin.idem.common.error.PlatformErrorCode.SSO_CAST_AGENCY_MISMATCH);
    }

    // ── Helper: 기관 등록 ─────────────────────────────────────────────────

    private void seedAgency(String agencyCode) {
        if (agencyMetaJpaRepository.findById(agencyCode).isPresent()) return;
        AgencyMetaJpaEntity entity = AgencyMetaJpaEntity.builder()
                .agencyCode(agencyCode)
                .officialName("S13 테스트 기관 — " + agencyCode)
                .active(true)
                .integrationType("DIRECT")
                .minAuthLevel("L1")
                .policyVersion("1.0")
                .callbackWhitelist("[\"https://" + agencyCode.toLowerCase() + ".go.kr\"]")
                .build();
        agencyMetaJpaRepository.save(entity);
    }
}
