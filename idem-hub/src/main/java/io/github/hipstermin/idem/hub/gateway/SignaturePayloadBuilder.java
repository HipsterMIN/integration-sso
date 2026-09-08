package io.github.hipstermin.idem.hub.gateway;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * F4.9 — 기관-IdO 간 HMAC 서명 페이로드 생성 공통 유틸 (Sprint β-3)
 *
 * <h3>배경</h3>
 * <p>운영 진입 직전 분석 결과 IdO 의 HMAC 서명 페이로드가 호출 경로마다 형식이 달랐다.
 * <ul>
 *   <li>{@code HmacSignatureFilter}      (인바운드 게이트웨이 검증):
 *       {@code "{agencyCode}:{idempotencyKey}:{epochSeconds}"}</li>
 *   <li>{@code AgencyGatewayServiceImpl} (아웃바운드 게이트웨이 발송):
 *       {@code "{agencyCode}:{idempotencyKey}:{epochSeconds}"} (이미 통일됨)</li>
 *   <li>{@code ProvisioningServiceImpl}  (아웃바운드 프로비저닝):
 *       <s>{@code "{idempotencyKey}:{epochSeconds}"}</s> ← <b>비대칭, F4.9 결함</b></li>
 * </ul>
 *
 * <p>본 유틸은 위 세 호출부가 모두 공유하는 단일 페이로드 규칙을 제공한다.
 * 기관 SDK 가 양방향(인바운드 검증 + 아웃바운드 서명) 통신을 같은 코드로 다룰 수 있게 하며,
 * 향후 cross-agency replay 공격 위험을 제거한다.
 *
 * <h3>규칙</h3>
 * <pre>
 * payload   = "{agencyCode}:{idempotencyKey}:{epochSeconds}"
 * signature = HMAC-SHA256(payload, secret) → 소문자 Hex 64자
 * </pre>
 *
 * <h3>null/blank 정책</h3>
 * <ul>
 *   <li>agencyCode 가 null/blank → {@link IllegalArgumentException}
 *       (서명 식별자가 결정되지 않은 상태에서 서명을 만들면 cross-agency 위험)</li>
 *   <li>idempotencyKey 가 null → 빈 문자열로 치환 (인바운드 필터와 동일 동작)</li>
 *   <li>secret 이 null/blank → {@link IllegalArgumentException}</li>
 * </ul>
 *
 * <h3>알고리즘 상수</h3>
 * <p>{@link #ALGORITHM} = {@code HmacSHA256}, 출력은 소문자 Hex.
 *
 * @see HmacSignatureFilter
 * @see io.github.hipstermin.idem.hub.gateway.AgencyGatewayServiceImpl
 * @see io.github.hipstermin.idem.hub.provision.ProvisioningServiceImpl
 * @see docs/analysis/sso-im-readiness/04_handoff_flow.md (F4.9)
 */
public final class SignaturePayloadBuilder {

    /** HMAC 알고리즘 상수 — JVM 필수 지원 */
    public static final String ALGORITHM = "HmacSHA256";

    /** 페이로드 구분자 */
    public static final String DELIMITER = ":";

    private SignaturePayloadBuilder() { /* static-only */ }

    // ════════════════════════════════════════════════════════════════════
    // 페이로드 빌더
    // ════════════════════════════════════════════════════════════════════

    /**
     * 표준 HMAC 페이로드 문자열 생성.
     *
     * <pre>
     * "{agencyCode}:{idempotencyKey}:{epochSeconds}"
     * </pre>
     *
     * @param agencyCode     대상 기관코드 (필수)
     * @param idempotencyKey 멱등성 키 (null 허용 → 빈 문자열)
     * @param epochSeconds   현재 유닉스 타임스탬프(초)
     * @return 페이로드 문자열
     * @throws IllegalArgumentException agencyCode 가 null/blank
     */
    public static String buildPayload(String agencyCode,
                                       String idempotencyKey,
                                       long   epochSeconds) {
        if (agencyCode == null || agencyCode.isBlank()) {
            throw new IllegalArgumentException(
                    "agencyCode 가 비어 있습니다 — cross-agency replay 위험으로 서명 생성 거부");
        }
        String safeKey = (idempotencyKey != null) ? idempotencyKey : "";
        return agencyCode + DELIMITER + safeKey + DELIMITER + epochSeconds;
    }

    // ════════════════════════════════════════════════════════════════════
    // 서명 계산
    // ════════════════════════════════════════════════════════════════════

    /**
     * 표준 페이로드 + secret 으로 HMAC-SHA256 서명 계산.
     *
     * @param agencyCode     대상 기관코드
     * @param idempotencyKey 멱등성 키 (null 허용)
     * @param epochSeconds   유닉스 타임스탬프(초)
     * @param secret         HMAC 공유 비밀키 (필수, blank 불가)
     * @return 소문자 Hex 서명 문자열 (64자)
     * @throws IllegalArgumentException agencyCode/secret 누락
     * @throws RuntimeException Mac 초기화/계산 오류 (JVM 필수 알고리즘이라 사실상 발생 불가)
     */
    public static String computeSignature(String agencyCode,
                                           String idempotencyKey,
                                           long   epochSeconds,
                                           String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("HMAC secret 이 비어 있습니다");
        }
        String payload = buildPayload(agencyCode, idempotencyKey, epochSeconds);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac);
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new RuntimeException("HMAC " + ALGORITHM + " 계산 실패", e);
        }
    }
}
