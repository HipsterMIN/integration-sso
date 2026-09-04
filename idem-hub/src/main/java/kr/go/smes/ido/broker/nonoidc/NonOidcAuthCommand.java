package kr.go.smes.ido.broker.nonoidc;

import lombok.Builder;
import lombok.Getter;

/**
 * 비OIDC 인증 처리 명령 DTO (문서 §9)
 *
 * <p>외부 IdP(PASS/금융인증서/GPKI 등) 응답을 정규화한 뒤
 * {@link NonOidcAuthService}에 전달되는 내부 명령 객체.
 *
 * <p>rawIdentifier: 사업자가 반환한 원본 식별자 (평문).
 * NonOidcAuthService 내부에서 SHA-256 해싱 후 identifierHash로 변환.
 * 메모리 체류 시간 최소화를 위해 해싱 즉시 처리.
 */
@Getter
@Builder
public class NonOidcAuthCommand {

    /** 전체 흐름 추적 ID */
    private final String correlationId;

    /**
     * 인증 수단 코드
     * PASS / FINANCIAL_CERT / GPKI / JOINT_CERT
     */
    private final String providerCode;

    /**
     * 외부 사업자 트랜잭션 ID
     * PASS: 통신사 tx ID, FINANCIAL_CERT: 인증서 SN 등
     */
    private final String providerTxId;

    /**
     * 사업자가 반환한 원본 식별자 (평문 — 처리 즉시 해싱)
     * 예: 주민번호 앞 6자리 + 성별코드, 전화번호, 인증서 DN 등
     * PoC: 실운영에서는 암호화 채널 + HSM 처리 필요
     */
    private final String rawIdentifier;

    /** 요청 인증 수준 */
    private final String requestedLevel;

    /** 사업자 응답 검증 완료 여부 (IdpBrokerService 1차 검증) */
    private final boolean providerVerified;
}
