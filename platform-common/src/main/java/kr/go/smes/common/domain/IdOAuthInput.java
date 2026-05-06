package kr.go.smes.common.domain;

import kr.go.smes.common.domain.AuthResult;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/**
 * IdO → Q-Sign 비OIDC/반표준 인증 정규화 입력 모델
 * 설계서 11.7절 참조
 *
 * - Q-Sign이 authResultId를 발급하기 위한 입력 (인증 결과 자체가 아님)
 * - 인증 SoR은 항상 Q-Sign
 */
@Getter
@Builder
public class IdOAuthInput {

    private final String correlationId;

    /** 외부 IdP 제공자 코드 (PASS / FINANCIAL_CERT / GPKI 등) */
    private final String providerCode;

    /** 외부 사업자 트랜잭션 ID */
    private final String providerTxId;

    /** 요청한 인증 수준 */
    private final AuthResult.AuthLevel requestedAuthLevel;

    /** 정규화된 식별자 해시 (identifierHash) */
    private final String identifierHash;

    /** 사업자 응답 검증 결과 (IdO가 1차 검증) */
    private final boolean providerVerified;

    /** 추가 클레임 (사업자별 응답 속성) */
    private final Map<String, Object> claims;

    /** IdO 내부 서명 (mTLS + X-Internal-Sig) */
    private final String internalSignature;

    private final Instant createdAt;
}
