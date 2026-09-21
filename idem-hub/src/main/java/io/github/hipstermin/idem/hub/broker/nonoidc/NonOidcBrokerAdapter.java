package io.github.hipstermin.idem.hub.broker.nonoidc;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.IdOAuthInput;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.broker.IdpBrokerResult;
import io.github.hipstermin.idem.hub.broker.IdpBrokerService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 비OIDC 인증 수단용 {@link IdpBrokerService} 구현체 (문서 §7 — Option 3)
 *
 * <p>대상 인증 수단:
 * <ul>
 *   <li>PASS — 통신 3사 본인인증 (L2)</li>
 *   <li>FINANCIAL_CERT — 금융인증서 (L3)</li>
 *   <li>GPKI — 정부 공개키 인증서 (L3)</li>
 *   <li>JOINT_CERT — 공동인증서 (L3)</li>
 * </ul>
 *
 * <p>흐름 (Option 3 — IdO 직접 처리):
 * <pre>
 *   FE → GET /api/v1/broker/{provider}/nonoidc/initiate
 *       → NonOidcBrokerController
 *           → this.initiateAuth()   ← 외부 IdP 인증 시작 URL 반환
 *   외부 IdP → GET /api/v1/broker/{provider}/nonoidc/callback
 *       → NonOidcBrokerController
 *           → this.normalizeResponse()  ← 응답 정규화 → IdOAuthInput
 *           → NonOidcAuthService.processAuth()  ← AuthResult 생성·Kafka 발행
 * </pre>
 *
 * <p>PoC 단계: 실제 외부 IdP SDK/API 연동 대신 플레이스홀더 구현.
 * 운영 전환 시 각 사업자별 SDK 또는 REST API 호출 코드로 교체 필요.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NonOidcBrokerAdapter implements IdpBrokerService {

    private final NonOidcAuthService nonOidcAuthService;
    /** D2: 사업자별 응답 검증기 — 없으면 그 사업자는 사용 불가(503) */
    private final List<NonOidcProviderVerifier> providerVerifiers;

    // ──────────────────────────────────────────────────────────────────────
    // IdpBrokerService 구현
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 외부 IdP 인증 시작 — 리다이렉트 URL 또는 요청 토큰 반환
     *
     * <p>PoC: 각 providerCode 별 더미 URL 반환.
     * 운영: 실제 사업자 API(PASS 등) 호출 후 redirect URL 또는 txId 반환.
     *
     * @param providerCode  인증 수단 (PASS / FINANCIAL_CERT / GPKI / JOINT_CERT)
     * @param correlationId 흐름 추적 ID
     * @param callbackUrl   인증 완료 후 사업자가 호출할 ido callback URL
     * @return {@link IdpBrokerResult} — redirect URL 또는 직접 호출 결과
     */
    @Override
    public IdpBrokerResult initiateAuth(String providerCode,
                                        String correlationId,
                                        String callbackUrl) {
        log.info("[NonOidcBrokerAdapter] 인증 시작: provider={} correlationId={}", providerCode, correlationId);

        validateProvider(providerCode, correlationId);

        return switch (providerCode.toUpperCase()) {
            case "PASS" -> initPass(correlationId, callbackUrl);
            case "FINANCIAL_CERT" -> initFinancialCert(correlationId, callbackUrl);
            case "GPKI" -> initGpki(correlationId, callbackUrl);
            case "JOINT_CERT" -> initJointCert(correlationId, callbackUrl);
            default -> throw new PlatformException(
                    PlatformErrorCode.IDP_PROVIDER_UNAVAILABLE, correlationId,
                    "지원하지 않는 비OIDC provider: " + providerCode);
        };
    }

    /**
     * 외부 IdP 콜백 응답 정규화 → {@link IdOAuthInput} 생성
     *
     * <p>PoC: rawResponse를 Map으로 가정하여 rawIdentifier 추출.
     * 운영: 사업자별 응답 스키마 파싱 + 서명 검증 + 복호화 적용.
     *
     * @param providerCode  인증 수단 코드
     * @param correlationId 흐름 추적 ID
     * @param providerTxId  사업자 트랜잭션 ID
     * @param rawResponse   사업자 원본 응답 (Map 또는 사업자별 DTO)
     * @return 정규화된 {@link IdOAuthInput}
     */
    @Override
    @SuppressWarnings("unchecked")
    public IdOAuthInput normalizeResponse(String providerCode,
                                          String correlationId,
                                          String providerTxId,
                                          Object rawResponse) {
        log.info("[NonOidcBrokerAdapter] 응답 정규화: provider={} correlationId={}", providerCode, correlationId);

        validateProvider(providerCode, correlationId);

        // PoC: rawResponse를 Map<String, Object>로 취급
        Map<String, Object> responseMap = castToMap(rawResponse, correlationId);

        // 사업자 응답에서 원본 식별자 추출 (PoC: "identifier" 키)
        String rawIdentifier = extractRawIdentifier(responseMap, providerCode, correlationId);

        // 응답 1차 검증 (PoC: 항상 통과 — 운영: 사업자 서명 검증)
        boolean providerVerified = verifyProviderResponse(responseMap, providerCode, correlationId);

        AuthResult.AuthLevel authLevel = resolveAuthLevel(providerCode);

        // IdOAuthInput 빌드 후 NonOidcAuthService.processAuth() 진입
        NonOidcAuthCommand command = NonOidcAuthCommand.builder()
                .correlationId(correlationId)
                .providerCode(providerCode.toUpperCase())
                .providerTxId(providerTxId)
                .rawIdentifier(rawIdentifier)
                .requestedLevel(authLevel.name())
                .providerVerified(providerVerified)
                .build();

        // AuthResult 생성 + Kafka 발행 (Strategy B)
        String authResultId = nonOidcAuthService.processAuth(command);
        log.info("[NonOidcBrokerAdapter] 정규화 완료: authResultId={} correlationId={}", authResultId, correlationId);

        // 호출자(NonOidcBrokerController)가 FE 세션 발급에 사용할 IdOAuthInput 반환
        return IdOAuthInput.builder()
                .correlationId(correlationId)
                .providerCode(providerCode.toUpperCase())
                .providerTxId(providerTxId)
                .requestedAuthLevel(authLevel)
                .identifierHash(computeIdentifierHashPreview(rawIdentifier))   // 미리보기 — 실제는 service 내부
                .providerVerified(providerVerified)
                .claims(responseMap)
                .internalSignature(authResultId)   // authResultId를 서명 대용으로 전달 (PoC)
                .createdAt(Instant.now())
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 사업자별 initiateAuth 구현 (PoC placeholder)
    // ──────────────────────────────────────────────────────────────────────

    /** PASS 본인인증 시작 — 통신사 API에 txId 요청 후 redirect URL 반환 */
    private IdpBrokerResult initPass(String correlationId, String callbackUrl) {
        // PoC: 더미 redirect URL
        // 운영: PASS API (예: KT/SKT/LGU+ 사업자 SDK) 호출
        String dummyRedirectUrl = "https://pass.example.com/auth?callback="
                + encodeUrl(callbackUrl) + "&cid=" + correlationId;

        log.debug("[NonOidcBrokerAdapter] PASS redirect URL 생성: correlationId={}", correlationId);
        return IdpBrokerResult.builder()
                .providerCode("PASS")
                .providerTxId("PASS-TX-" + correlationId.replace("-", "").substring(0, 8))
                .redirectUrl(dummyRedirectUrl)
                .state(correlationId)
                .status(IdpBrokerResult.BrokerStatus.REDIRECT_REQUIRED)
                .build();
    }

    /** 금융인증서 인증 시작 — 금융결제원 API 호출 */
    private IdpBrokerResult initFinancialCert(String correlationId, String callbackUrl) {
        // PoC: 더미 — 운영: 금융결제원 금융인증서 API 연동
        log.debug("[NonOidcBrokerAdapter] 금융인증서 인증 시작: correlationId={}", correlationId);
        return IdpBrokerResult.builder()
                .providerCode("FINANCIAL_CERT")
                .providerTxId("FCERT-TX-" + correlationId.replace("-", "").substring(0, 8))
                .redirectUrl("https://financial-cert.example.com/auth?cid=" + correlationId)
                .state(correlationId)
                .status(IdpBrokerResult.BrokerStatus.REDIRECT_REQUIRED)
                .build();
    }

    /** GPKI 정부 공개키 인증서 시작 */
    private IdpBrokerResult initGpki(String correlationId, String callbackUrl) {
        // PoC: 더미 — 운영: 행정안전부 GPKI 연동
        log.debug("[NonOidcBrokerAdapter] GPKI 인증 시작: correlationId={}", correlationId);
        return IdpBrokerResult.builder()
                .providerCode("GPKI")
                .providerTxId("GPKI-TX-" + correlationId.replace("-", "").substring(0, 8))
                .redirectUrl("https://gpki.example.go.kr/auth?cid=" + correlationId)
                .state(correlationId)
                .status(IdpBrokerResult.BrokerStatus.REDIRECT_REQUIRED)
                .build();
    }

    /** 공동인증서 인증 시작 */
    private IdpBrokerResult initJointCert(String correlationId, String callbackUrl) {
        // PoC: 더미 — 운영: 금융결제원 / KICA / CrossCert 연동
        log.debug("[NonOidcBrokerAdapter] 공동인증서 인증 시작: correlationId={}", correlationId);
        return IdpBrokerResult.builder()
                .providerCode("JOINT_CERT")
                .providerTxId("JCERT-TX-" + correlationId.replace("-", "").substring(0, 8))
                .redirectUrl("https://joint-cert.example.com/auth?cid=" + correlationId)
                .state(correlationId)
                .status(IdpBrokerResult.BrokerStatus.REDIRECT_REQUIRED)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // 내부 유틸
    // ──────────────────────────────────────────────────────────────────────

    private void validateProvider(String providerCode, String correlationId) {
        if (providerCode == null || providerCode.isBlank()) {
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "providerCode 없음");
        }
        // D2 fail-secure: 응답을 검증할 수 없는 사업자는 시작 단계부터 막는다 (막다른 인증 화면 방지)
        if (findVerifier(providerCode) == null) {
            throw new PlatformException(PlatformErrorCode.IDO_PROVIDER_NOT_CONFIGURED, correlationId,
                    "비OIDC 사업자 응답 검증기(NonOidcProviderVerifier) 미등록: " + providerCode);
        }
    }

    private NonOidcProviderVerifier findVerifier(String providerCode) {
        if (providerVerifiers == null) return null;
        return providerVerifiers.stream()
                .filter(v -> v.supports(providerCode))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToMap(Object rawResponse, String correlationId) {
        if (rawResponse instanceof Map) {
            return (Map<String, Object>) rawResponse;
        }
        throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                "rawResponse 형식 오류 — Map 예상: " + (rawResponse != null ? rawResponse.getClass() : "null"));
    }

    private String extractRawIdentifier(Map<String, Object> responseMap,
                                         String providerCode, String correlationId) {
        // PoC: "identifier" 키에서 추출 — 운영: 사업자별 필드명 상이
        Object id = responseMap.get("identifier");
        if (id == null || id.toString().isBlank()) {
            log.warn("[NonOidcBrokerAdapter] identifier 없음: provider={} correlationId={}", providerCode, correlationId);
            throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                    "사업자 응답에 identifier 없음: " + providerCode);
        }
        return id.toString();
    }

    /**
     * 사업자 응답 검증 — 등록된 {@link NonOidcProviderVerifier} 에 위임한다 (D2 fail-secure).
     * 검증기가 없거나 검증에 실패하면 {@link PlatformErrorCode#IDP_SIGNATURE_MISMATCH} 로 거부한다. 종전의 "PoC: 항상 통과" 는 제거.
     */
    private boolean verifyProviderResponse(Map<String, Object> responseMap,
                                            String providerCode, String correlationId) {
        NonOidcProviderVerifier verifier = findVerifier(providerCode);
        if (verifier == null) {
            throw new PlatformException(PlatformErrorCode.IDO_PROVIDER_NOT_CONFIGURED, correlationId,
                    "비OIDC 사업자 응답 검증기 미등록: " + providerCode);
        }
        boolean ok;
        try {
            ok = verifier.verify(responseMap, providerCode, correlationId);
        } catch (RuntimeException e) {
            log.error("[NonOidcBrokerAdapter] 응답 검증 불가 → 거부: provider={} cid={} err={}",
                    providerCode, correlationId, e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                    "사업자 응답 검증 불가: " + e.getMessage());
        }
        if (!ok) {
            log.warn("[NonOidcBrokerAdapter] 응답 검증 실패 → 거부: provider={} cid={}", providerCode, correlationId);
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, correlationId,
                    "사업자 응답 서명 불일치: " + providerCode);
        }
        return true;
    }

    private AuthResult.AuthLevel resolveAuthLevel(String providerCode) {
        if (providerCode == null) return AuthResult.AuthLevel.L1;
        return switch (providerCode.toUpperCase()) {
            case "PASS"           -> AuthResult.AuthLevel.L2;
            case "FINANCIAL_CERT",
                 "GPKI",
                 "JOINT_CERT"    -> AuthResult.AuthLevel.L3;
            default               -> AuthResult.AuthLevel.L1;
        };
    }

    /**
     * rawIdentifier SHA-256 해시 — 반환 값은 미리보기용
     * 실제 해싱은 NonOidcAuthService.computeIdentifierHash()에서 수행
     */
    private String computeIdentifierHashPreview(String rawIdentifier) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(rawIdentifier.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "hash-preview-error";
        }
    }

    private String encodeUrl(String url) {
        if (url == null) return "";
        try {
            return java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return url;
        }
    }
}
