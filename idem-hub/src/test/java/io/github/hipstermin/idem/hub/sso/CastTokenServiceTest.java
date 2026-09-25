package io.github.hipstermin.idem.hub.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.CastToken;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.domain.AgencyMeta;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import io.github.hipstermin.idem.hub.infrastructure.ServiceAccess;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CastTokenService 단위 테스트 (Mock 기반)
 *
 * <p>Docker 없는 환경에서도 실행 가능한 순수 단위 테스트.
 * Redis/PostgreSQL은 Mock으로 대체.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CastTokenService 단위 테스트")
class CastTokenServiceTest {

    @Mock private FeSessionService           feSessionService;
    @Mock private AgencyMetaRepository       agencyMetaRepository;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOps;
    @Mock private JdbcTemplate               jdbcTemplate;
    @Mock private QAuthzClient               qAuthzClient;
    @Mock private ServiceProfileService      serviceProfileService;

    /** Ed25519 키페어 (테스트용 인메모리 생성) */
    private KeyPair testKeyPair;

    private CastTokenServiceImpl castTokenService;

    private static final String QIM_USER_ID   = "qim-test-user-001";
    private static final String TARGET_AGENCY = "TEST_AGENCY_B";
    private static final String CORRELATION_ID = "test-cid-001";

    @BeforeEach
    void setUp() throws Exception {
        // Ed25519 키페어 생성
        testKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        castTokenService = new CastTokenServiceImpl(
                feSessionService, agencyMetaRepository,
                redisTemplate, jdbcTemplate, qAuthzClient, serviceProfileService, testKeyPair
        );

        // Redis mock 공통 설정 — lenient: 일부 테스트에서 미사용 허용
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 연합 인가 — 기본 빈 역할(개별 테스트에서 필요 시 재정의)
        lenient().when(qAuthzClient.getServiceAccess(anyString(), anyString(), any()))
                .thenReturn(new ServiceAccess(true, false, null, java.util.List.of()));
        lenient().when(serviceProfileService.find(anyString())).thenReturn(Optional.empty());
    }

    // ── issue() 성공 케이스 ───────────────────────────────────────────────

    @Test
    @DisplayName("issue: FE 세션 + 기관 등록 → CAST 토큰 발급 성공")
    void issue_success() {
        // Given
        FeSession session = FeSession.builder()
                .feSessionId("fe-session-001")
                .qimUserId(QIM_USER_ID)
                .authLevel("MEDIUM")
                .authResultId("auth-001")
                .createdAt(Instant.now())
                .lastActivityAt(Instant.now())
                .absoluteExpiresAt(Instant.now().plusSeconds(3600))
                .build();

        AgencyMeta agency = mock(AgencyMeta.class);
        when(agency.isActive()).thenReturn(true);

        when(feSessionService.findById("fe-session-001")).thenReturn(Optional.of(session));
        when(agencyMetaRepository.findByCode(TARGET_AGENCY)).thenReturn(Optional.of(agency));
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        // When
        CastToken result = castTokenService.issue("fe-session-001", TARGET_AGENCY, CORRELATION_ID);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.qimUserId()).isEqualTo(QIM_USER_ID);
        assertThat(result.targetAgency()).isEqualTo(TARGET_AGENCY);
        assertThat(result.authLevel()).isEqualTo("L2") /* S3: 토큰은 정규 어휘 L1~L3 */;
        assertThat(result.token()).isNotBlank();
        assertThat(result.jti()).isNotBlank();
        assertThat(result.isExpired()).isFalse();
        assertThat(result.expiresAt().getEpochSecond() - result.issuedAt().getEpochSecond())
                .isEqualTo(CastToken.TTL_SECONDS);

        // Redis SET NX 호출 검증
        verify(valueOps).setIfAbsent(
                argThat(k -> k != null && k.toString().startsWith(CastToken.REDIS_CONSUMED_PREFIX)),
                eq("ISSUED"),
                any()
        );
    }

    // ── issue() 실패 케이스: FE 세션 없음 ───────────────────────────────

    @Test
    @DisplayName("issue: FE 세션 없음 → SSO_CAST_SESSION_NOT_FOUND")
    void issue_noSession_throws() {
        // Given
        when(feSessionService.findById(anyString())).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() ->
                castTokenService.issue("invalid-session", TARGET_AGENCY, CORRELATION_ID))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode())
                        .isEqualTo(PlatformErrorCode.SSO_CAST_SESSION_NOT_FOUND));
    }

    // ── issue() 실패 케이스: 미등록 기관 ────────────────────────────────

    @Test
    @DisplayName("issue: 미등록 대상 기관 → AGENCY_NOT_REGISTERED")
    void issue_unknownAgency_throws() {
        // Given
        FeSession session = FeSession.builder()
                .feSessionId("fe-session-002")
                .qimUserId(QIM_USER_ID)
                .authLevel("LOW")
                .authResultId("auth-002")
                .createdAt(Instant.now())
                .lastActivityAt(Instant.now())
                .absoluteExpiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(feSessionService.findById("fe-session-002")).thenReturn(Optional.of(session));
        when(agencyMetaRepository.findByCode("UNKNOWN_AGENCY")).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() ->
                castTokenService.issue("fe-session-002", "UNKNOWN_AGENCY", CORRELATION_ID))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode())
                        .isEqualTo(PlatformErrorCode.AGENCY_NOT_REGISTERED));
    }

    // ── verify() 성공 케이스 ─────────────────────────────────────────────

    @Test
    @DisplayName("verify: 유효한 CAST 토큰 → 성공적으로 소비")
    void verify_success() {
        // Given: 먼저 CAST 토큰 발급
        FeSession session = FeSession.builder()
                .feSessionId("fe-session-003")
                .qimUserId(QIM_USER_ID)
                .authLevel("HIGH")
                .authResultId("auth-003")
                .createdAt(Instant.now())
                .lastActivityAt(Instant.now())
                .absoluteExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        AgencyMeta agency = mock(AgencyMeta.class);
        when(agency.isActive()).thenReturn(true);
        when(feSessionService.findById("fe-session-003")).thenReturn(Optional.of(session));
        when(agencyMetaRepository.findByCode(TARGET_AGENCY)).thenReturn(Optional.of(agency));
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        CastToken issued = castTokenService.issue("fe-session-003", TARGET_AGENCY, "issue-cid");

        // verify() Mock 설정 — ISSUED 상태
        when(valueOps.getAndSet(argThat(k -> k != null && k.toString().startsWith(CastToken.REDIS_CONSUMED_PREFIX)), eq("CONSUMED")))
                .thenReturn("ISSUED");
        when(redisTemplate.getExpire(anyString())).thenReturn(250L);

        // When
        CastToken verified = castTokenService.verify(
                issued.token(), TARGET_AGENCY, "1.2.3.4", "verify-cid");

        // Then
        assertThat(verified.jti()).isEqualTo(issued.jti());
        assertThat(verified.qimUserId()).isEqualTo(QIM_USER_ID);
        assertThat(verified.targetAgency()).isEqualTo(TARGET_AGENCY);

        // CONSUMED 값으로 덮어씌움 검증
        verify(redisTemplate).expire(argThat(k -> k != null && k.toString().startsWith(CastToken.REDIS_CONSUMED_PREFIX)), any(java.time.Duration.class));
    }

    // ── 연합 인가: roles 클레임 임베드 + verify 추출 라운드트립 ────────────

    @Test
    @DisplayName("issue+verify: q-authz 역할이 CAST roles 클레임으로 왕복 전달")
    void rolesEmbeddedAndExtracted() {
        // Given
        FeSession session = FeSession.builder()
                .feSessionId("fe-session-roles")
                .qimUserId(QIM_USER_ID)
                .authLevel("MEDIUM")
                .authResultId("auth-roles")
                .createdAt(Instant.now())
                .lastActivityAt(Instant.now())
                .absoluteExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        AgencyMeta agency = mock(AgencyMeta.class);
        when(agency.isActive()).thenReturn(true);
        when(feSessionService.findById("fe-session-roles")).thenReturn(Optional.of(session));
        when(agencyMetaRepository.findByCode(TARGET_AGENCY)).thenReturn(Optional.of(agency));
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        // q-authz가 역할 2개 반환
        when(qAuthzClient.getServiceAccess(QIM_USER_ID, TARGET_AGENCY, "issue-cid-roles"))
                .thenReturn(new ServiceAccess(true, true, "CONSOLE", java.util.List.of("MANAGER", "REVIEWER")));

        // When: 발급 → roles가 토큰 도메인 객체에 반영
        CastToken issued = castTokenService.issue("fe-session-roles", TARGET_AGENCY, "issue-cid-roles");
        assertThat(issued.roles()).containsExactly("MANAGER", "REVIEWER");

        // verify Mock: ISSUED 상태
        when(valueOps.getAndSet(argThat(k -> k != null && k.toString().startsWith(CastToken.REDIS_CONSUMED_PREFIX)), eq("CONSUMED")))
                .thenReturn("ISSUED");
        when(redisTemplate.getExpire(anyString())).thenReturn(250L);

        // Then: verify가 JWT roles 클레임을 그대로 추출 (q-authz 재호출 없음)
        CastToken verified = castTokenService.verify(issued.token(), TARGET_AGENCY, "1.2.3.4", "verify-cid-roles");
        assertThat(verified.roles()).containsExactly("MANAGER", "REVIEWER");
    }

    // ── verify() 실패: 이미 소비 ─────────────────────────────────────────

    @Test
    @DisplayName("D3 GETSET: 키가 없던 경우(만료) → SSO_CAST_EXPIRED 이고 GETSET 이 만든 키는 지운다")
    void verify_expired_getAndSetCreatedKeyIsDeleted() {
        FeSession session = FeSession.builder()
                .feSessionId("fe-session-d3").qimUserId(QIM_USER_ID).authLevel("LOW").authResultId("auth-d3")
                .createdAt(Instant.now()).lastActivityAt(Instant.now()).absoluteExpiresAt(Instant.now().plusSeconds(3600)).build();
        AgencyMeta agency = mock(AgencyMeta.class);
        when(agency.isActive()).thenReturn(true);
        when(feSessionService.findById("fe-session-d3")).thenReturn(Optional.of(session));
        when(agencyMetaRepository.findByCode(TARGET_AGENCY)).thenReturn(Optional.of(agency));
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        CastToken issued = castTokenService.issue("fe-session-d3", TARGET_AGENCY, "issue-cid-d3");

        when(valueOps.getAndSet(argThat(k -> k != null && k.toString().startsWith(CastToken.REDIS_CONSUMED_PREFIX)), eq("CONSUMED"))).thenReturn(null);
        assertThatThrownBy(() -> castTokenService.verify(issued.token(), TARGET_AGENCY, "1.2.3.4", "verify-cid-d3"))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.SSO_CAST_EXPIRED);
        verify(redisTemplate).delete(argThat((String k) -> k != null && k.startsWith(CastToken.REDIS_CONSUMED_PREFIX)));
    }

    @Test
    @DisplayName("verify: 이미 소비된 CAST 토큰 → SSO_CAST_CONSUMED")
    void verify_alreadyConsumed_throws() {
        // Given: 발급
        FeSession session = FeSession.builder()
                .feSessionId("fe-session-004")
                .qimUserId(QIM_USER_ID)
                .authLevel("LOW")
                .authResultId("auth-004")
                .createdAt(Instant.now())
                .lastActivityAt(Instant.now())
                .absoluteExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        AgencyMeta agency = mock(AgencyMeta.class);
        when(agency.isActive()).thenReturn(true);
        when(feSessionService.findById("fe-session-004")).thenReturn(Optional.of(session));
        when(agencyMetaRepository.findByCode(TARGET_AGENCY)).thenReturn(Optional.of(agency));
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        CastToken issued = castTokenService.issue("fe-session-004", TARGET_AGENCY, "issue-cid-4");

        // Redis에서 CONSUMED 상태 반환 (이미 소비됨)
        when(valueOps.getAndSet(argThat(k -> k != null && k.toString().startsWith(CastToken.REDIS_CONSUMED_PREFIX)), eq("CONSUMED")))
                .thenReturn("CONSUMED");

        // When & Then
        assertThatThrownBy(() ->
                castTokenService.verify(issued.token(), TARGET_AGENCY, "1.2.3.4", "verify-cid-4"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode())
                        .isEqualTo(PlatformErrorCode.SSO_CAST_CONSUMED));
    }

    // ── verify() 실패: 기관 불일치 ───────────────────────────────────────

    @Test
    @DisplayName("verify: 기관 불일치 → SSO_CAST_AGENCY_MISMATCH")
    void verify_agencyMismatch_throws() {
        // Given: TARGET_AGENCY로 발급
        FeSession session = FeSession.builder()
                .feSessionId("fe-session-005")
                .qimUserId(QIM_USER_ID)
                .authLevel("LOW")
                .authResultId("auth-005")
                .createdAt(Instant.now())
                .lastActivityAt(Instant.now())
                .absoluteExpiresAt(Instant.now().plusSeconds(3600))
                .build();
        AgencyMeta agency = mock(AgencyMeta.class);
        when(agency.isActive()).thenReturn(true);
        when(feSessionService.findById("fe-session-005")).thenReturn(Optional.of(session));
        when(agencyMetaRepository.findByCode(TARGET_AGENCY)).thenReturn(Optional.of(agency));
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        CastToken issued = castTokenService.issue("fe-session-005", TARGET_AGENCY, "issue-cid-5");

        // When & Then: WRONG_AGENCY로 검증 시도
        assertThatThrownBy(() ->
                castTokenService.verify(issued.token(), "WRONG_AGENCY", "1.2.3.4", "verify-cid-5"))
                .isInstanceOf(PlatformException.class)
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode())
                        .isEqualTo(PlatformErrorCode.SSO_CAST_AGENCY_MISMATCH));
    }

    // ── S8-b 할당 정책 ──────────────────────────────────────────────────────
    @Test
    @DisplayName("S8-b: 대상 프로파일이 할당 필수인데 미할당 → IDO_ASSIGNMENT_REQUIRED (CAST 는 GUEST 가 없다)")
    void issue_assignmentRequired_unassigned_denied() {
        FeSession session = FeSession.builder()
                .feSessionId("fe-session-asgn").qimUserId(QIM_USER_ID).authLevel("L2").authResultId("auth-asgn")
                .createdAt(Instant.now()).lastActivityAt(Instant.now())
                .absoluteExpiresAt(Instant.now().plusSeconds(3600)).build();
        AgencyMeta agency = mock(AgencyMeta.class);
        when(agency.isActive()).thenReturn(true);
        when(feSessionService.findById("fe-session-asgn")).thenReturn(Optional.of(session));
        when(agencyMetaRepository.findByCode(TARGET_AGENCY)).thenReturn(Optional.of(agency));
        when(serviceProfileService.find(TARGET_AGENCY)).thenReturn(Optional.of(ServiceProfile.builder().schemaVersion(1)
                .service(new ServiceProfile.Service(TARGET_AGENCY, "기관 B", ServiceProfile.ServiceStatus.ACTIVE))
                .policy(ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L1)
                        .assignment(new ServiceProfile.Assignment(true, true)).build()).build()));
        when(qAuthzClient.getServiceAccess(QIM_USER_ID, TARGET_AGENCY, "cid-asgn"))
                .thenReturn(new ServiceAccess(true, false, null, java.util.List.of()));

        assertThatThrownBy(() -> castTokenService.issue("fe-session-asgn", TARGET_AGENCY, "cid-asgn"))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode())
                .isEqualTo(PlatformErrorCode.IDO_ASSIGNMENT_REQUIRED);
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any());
    }
}
