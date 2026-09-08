package io.github.hipstermin.idem.gate.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.IdOAuthInput;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.gate.infrastructure.AuthResultRepository;
import io.github.hipstermin.idem.gate.infrastructure.LockRepository;
import io.github.hipstermin.idem.gate.metrics.AuthMetrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * AuthServiceImpl 단위 테스트 — q-sign 신뢰 루트의 핵심 비즈니스 로직 회귀 안전망
 *
 * <p>검증 범위:
 * <ul>
 *   <li>issueFromOidc(): idToken sub → SHA-256 → AuthResult 발급 + Kafka publish</li>
 *   <li>issueFromIdOAuthInput(): 비OIDC 정규화 입력 → AuthResult 발급 + Kafka publish</li>
 *   <li>findById(): 존재/부재 분기</li>
 *   <li>isLocked(): LockRepository 위임 검증</li>
 *   <li>잠금 상태 / providerVerified=false / idToken 형식 오류 / sub 누락 분기</li>
 * </ul>
 *
 * <p>외부 의존 (LockRepository / AuthResultRepository / KafkaTemplate / AuthMetrics) 은
 * 모두 Mock 으로 대체하여 순수 비즈니스 로직만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthServiceImpl — q-sign 신뢰 루트 단위 테스트")
class AuthServiceImplTest {

    private static final String TOPIC_AUTH_EVENTS = "qsign.auth.events";

    @Mock private AuthResultRepository authResultRepository;
    @Mock private LockRepository       lockRepository;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private AuthMetrics          authMetrics;

    @InjectMocks
    private AuthServiceImpl authService;

    // ── 유틸: 테스트용 JWT (header.payload.signature 형식) ─────────────────────

    /** 테스트용 JWT 생성 — payload 만 의미 있는 JSON, 서명 부분은 더미. */
    private static String buildTestJwt(String payloadJson) {
        String header  = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64Url(payloadJson);
        return header + "." + payload + ".dummysignature";
    }

    private static String base64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // issueFromOidc()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("issueFromOidc() — OIDC idToken 기반 AuthResult 발급")
    class IssueFromOidc {

        @Test
        @DisplayName("정상 흐름: AuthResult 저장 + Kafka publish + 성공 메트릭")
        void normalFlowSavesAndPublishes() {
            // given
            String correlationId = "cid-001";
            String provider      = "KAKAO_OIDC";
            String sub           = "kakao-user-12345";
            String idToken       = buildTestJwt("{\"sub\":\"" + sub + "\",\"iss\":\"keycloak\"}");
            given(lockRepository.isLocked(provider, provider)).willReturn(false);

            // when
            AuthResult result = authService.issueFromOidc(correlationId, provider, idToken, "L1");

            // then
            assertThat(result).isNotNull();
            assertThat(result.getCorrelationId()).isEqualTo(correlationId);
            assertThat(result.getProviderCode()).isEqualTo(provider);
            assertThat(result.getAuthLevel()).isEqualTo(AuthResult.AuthLevel.L1);
            assertThat(result.getVerificationResult())
                    .isEqualTo(AuthResult.VerificationResult.SUCCESS);
            // identifierHash = SHA-256(sub) 정확성 검증 — PII 비보관 원칙 회귀 방어
            assertThat(result.getIdentifierHash()).isEqualTo(sha256Hex(sub));
            // authResultId 는 UUIDv7 생성 → 비어있지 않음
            assertThat(result.getAuthResultId()).isNotBlank();
            // authenticatedAt 는 현재 시각 근처
            assertThat(result.getAuthenticatedAt()).isCloseTo(Instant.now(),
                    within(5, java.time.temporal.ChronoUnit.SECONDS));

            then(authResultRepository).should().save(result);
            then(kafkaTemplate).should().send(
                    eq(TOPIC_AUTH_EVENTS), eq(result.getIdentifierHash()), any());
            then(authMetrics).should().incrementAuthSuccess(provider, "L1");
            then(authMetrics).should().recordAuthDuration(eq(provider), eq("L1"), anyLong());
        }

        @Test
        @DisplayName("잠금 상태이면 QS_AUTH_LOCKED 예외 + lockedMetric 증가 + 저장 미실행")
        void lockedStateThrowsAndDoesNotSave() {
            // given
            String correlationId = "cid-locked";
            String provider      = "KAKAO_OIDC";
            given(lockRepository.isLocked(provider, provider)).willReturn(true);

            // when + then
            assertThatThrownBy(() -> authService.issueFromOidc(
                            correlationId, provider, buildTestJwt("{\"sub\":\"x\"}"), "L1"))
                    .isInstanceOf(PlatformException.class)
                    .extracting("errorCode")
                    .isEqualTo(PlatformErrorCode.QS_AUTH_LOCKED);

            then(authMetrics).should().incrementAuthLocked(provider);
            then(authResultRepository).shouldHaveNoInteractions();
            then(kafkaTemplate).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("idToken 형식 오류(부분 수 부족)이면 IDP_RESPONSE_INVALID 예외")
        void malformedIdTokenThrows() {
            given(lockRepository.isLocked(anyString(), anyString())).willReturn(false);

            assertThatThrownBy(() -> authService.issueFromOidc(
                            "cid-malformed", "KAKAO_OIDC", "only-one-part", "L1"))
                    .isInstanceOf(PlatformException.class)
                    .extracting("errorCode")
                    .isEqualTo(PlatformErrorCode.IDP_RESPONSE_INVALID);

            then(authResultRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("idToken payload 에 sub 클레임 없으면 IDP_RESPONSE_INVALID 예외")
        void missingSubClaimThrows() {
            given(lockRepository.isLocked(anyString(), anyString())).willReturn(false);
            String idTokenWithoutSub = buildTestJwt("{\"iss\":\"keycloak\",\"aud\":\"client\"}");

            assertThatThrownBy(() -> authService.issueFromOidc(
                            "cid-no-sub", "KAKAO_OIDC", idTokenWithoutSub, "L1"))
                    .isInstanceOf(PlatformException.class)
                    .extracting("errorCode")
                    .isEqualTo(PlatformErrorCode.IDP_RESPONSE_INVALID);

            then(authResultRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("idToken payload 에 sub 가 빈 문자열이면 IDP_RESPONSE_INVALID 예외")
        void blankSubClaimThrows() {
            given(lockRepository.isLocked(anyString(), anyString())).willReturn(false);
            String idTokenBlankSub = buildTestJwt("{\"sub\":\"\",\"iss\":\"keycloak\"}");

            assertThatThrownBy(() -> authService.issueFromOidc(
                            "cid-blank-sub", "KAKAO_OIDC", idTokenBlankSub, "L1"))
                    .isInstanceOf(PlatformException.class)
                    .extracting("errorCode")
                    .isEqualTo(PlatformErrorCode.IDP_RESPONSE_INVALID);
        }

        @Test
        @DisplayName("같은 sub → 항상 같은 identifierHash (결정적 출력) — 회귀 방어")
        void deterministicIdentifierHash() {
            given(lockRepository.isLocked(anyString(), anyString())).willReturn(false);
            String sub = "kakao-user-deterministic";
            String idToken = buildTestJwt("{\"sub\":\"" + sub + "\"}");

            AuthResult r1 = authService.issueFromOidc("cid-A", "KAKAO_OIDC", idToken, "L1");
            AuthResult r2 = authService.issueFromOidc("cid-B", "KAKAO_OIDC", idToken, "L1");

            assertThat(r1.getIdentifierHash()).isEqualTo(r2.getIdentifierHash());
            // 단, authResultId / correlationId 는 매번 달라야 함
            assertThat(r1.getAuthResultId()).isNotEqualTo(r2.getAuthResultId());
            assertThat(r1.getCorrelationId()).isNotEqualTo(r2.getCorrelationId());
        }

        @Test
        @DisplayName("authMethod 분류 규칙 — KAKAO_OIDC → STANDARD_OIDC_KAKAO_OIDC")
        void authMethodResolvedForOidcProvider() {
            given(lockRepository.isLocked(anyString(), anyString())).willReturn(false);
            String idToken = buildTestJwt("{\"sub\":\"u-1\"}");

            AuthResult result = authService.issueFromOidc(
                    "cid-method", "KAKAO_OIDC", idToken, "L2");

            assertThat(result.getAuthLevel()).isEqualTo(AuthResult.AuthLevel.L2);
            assertThat(result.getAuthMethod()).isEqualTo("STANDARD_OIDC_KAKAO_OIDC");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // issueFromIdOAuthInput() — 비OIDC broker-input 경로
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("issueFromIdOAuthInput() — IdO broker-input 정규화 입력")
    class IssueFromIdOAuthInput {

        private IdOAuthInput buildInput(boolean providerVerified, String identifierHash) {
            return IdOAuthInput.builder()
                    .correlationId("cid-broker")
                    .providerCode("PASS")
                    .providerTxId("pass-tx-001")
                    .requestedAuthLevel(AuthResult.AuthLevel.L2)
                    .identifierHash(identifierHash)
                    .providerVerified(providerVerified)
                    .build();
        }

        @Test
        @DisplayName("정상 흐름: providerVerified=true → AuthResult 저장 + Kafka publish")
        void normalFlowSavesAndPublishes() {
            String identifierHash = "abcdef1234567890";
            IdOAuthInput input = buildInput(true, identifierHash);
            given(lockRepository.isLocked(identifierHash, "PASS")).willReturn(false);

            AuthResult result = authService.issueFromIdOAuthInput(input);

            assertThat(result.getCorrelationId()).isEqualTo("cid-broker");
            assertThat(result.getProviderCode()).isEqualTo("PASS");
            assertThat(result.getProviderTxId()).isEqualTo("pass-tx-001");
            assertThat(result.getIdentifierHash()).isEqualTo(identifierHash);
            assertThat(result.getAuthLevel()).isEqualTo(AuthResult.AuthLevel.L2);
            // PASS 는 OIDC 아니므로 NON_STANDARD_PASS
            assertThat(result.getAuthMethod()).isEqualTo("NON_STANDARD_PASS");

            then(authResultRepository).should().save(result);
            then(kafkaTemplate).should().send(eq(TOPIC_AUTH_EVENTS), eq(identifierHash), any());
            then(authMetrics).should().incrementAuthSuccess("PASS", "L2");
        }

        @Test
        @DisplayName("providerVerified=false 이면 IDP_RESPONSE_INVALID + failureMetric")
        void providerNotVerifiedThrows() {
            IdOAuthInput input = buildInput(false, "hash-x");

            assertThatThrownBy(() -> authService.issueFromIdOAuthInput(input))
                    .isInstanceOf(PlatformException.class)
                    .extracting("errorCode")
                    .isEqualTo(PlatformErrorCode.IDP_RESPONSE_INVALID);

            then(authMetrics).should()
                    .incrementAuthFailure("PASS", AuthMetrics.REASON_INVALID_RESPONSE);
            then(authResultRepository).shouldHaveNoInteractions();
            then(kafkaTemplate).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("identifierHash 기반 잠금이면 QS_AUTH_LOCKED 예외 + 저장 미실행")
        void lockedByIdentifierHashThrows() {
            String identifierHash = "locked-hash";
            IdOAuthInput input = buildInput(true, identifierHash);
            given(lockRepository.isLocked(identifierHash, "PASS")).willReturn(true);

            assertThatThrownBy(() -> authService.issueFromIdOAuthInput(input))
                    .isInstanceOf(PlatformException.class)
                    .extracting("errorCode")
                    .isEqualTo(PlatformErrorCode.QS_AUTH_LOCKED);

            then(authMetrics).should().incrementAuthLocked("PASS");
            then(authResultRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("Kafka publish 시 partition key 가 identifierHash 와 동일해야 한다")
        void kafkaPartitionKeyIsIdentifierHash() {
            String identifierHash = "partition-key-hash";
            IdOAuthInput input = buildInput(true, identifierHash);
            given(lockRepository.isLocked(identifierHash, "PASS")).willReturn(false);

            authService.issueFromIdOAuthInput(input);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            then(kafkaTemplate).should().send(
                    eq(TOPIC_AUTH_EVENTS), keyCaptor.capture(), any());
            assertThat(keyCaptor.getValue()).isEqualTo(identifierHash);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // findById() / isLocked()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("findById() / isLocked()")
    class QueryMethods {

        @Test
        @DisplayName("findById() 존재하면 AuthResult 반환")
        void findByIdExisting() {
            AuthResult stored = AuthResult.builder()
                    .authResultId("ar-001")
                    .correlationId("cid-find")
                    .build();
            given(authResultRepository.findById("ar-001")).willReturn(Optional.of(stored));

            AuthResult result = authService.findById("ar-001", "cid-find");

            assertThat(result).isSameAs(stored);
        }

        @Test
        @DisplayName("findById() 부재이면 QS_AUTH_FAILED 예외")
        void findByIdMissing() {
            given(authResultRepository.findById("missing")).willReturn(Optional.empty());

            assertThatThrownBy(() -> authService.findById("missing", "cid-find"))
                    .isInstanceOf(PlatformException.class)
                    .extracting("errorCode")
                    .isEqualTo(PlatformErrorCode.QS_AUTH_FAILED);
        }

        @Test
        @DisplayName("isLocked() 는 LockRepository 위임")
        void isLockedDelegates() {
            given(lockRepository.isLocked("h1", "p1")).willReturn(true);
            given(lockRepository.isLocked("h2", "p2")).willReturn(false);

            assertThat(authService.isLocked("h1", "p1")).isTrue();
            assertThat(authService.isLocked("h2", "p2")).isFalse();
        }
    }
}
