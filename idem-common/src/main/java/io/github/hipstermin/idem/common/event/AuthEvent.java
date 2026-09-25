package io.github.hipstermin.idem.common.event;

import io.github.hipstermin.idem.common.domain.AuthResult;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

/**
 * Q-Sign 인증 완료 이벤트
 * 설계서 9.3절 / EDA 기반 최종 일관성 모델
 * Kafka Topic: idem.gate.auth.events
 */
@Getter
@SuperBuilder
@Jacksonized // D1-b: Kafka JsonDeserializer·아웃박스 프로세스 내 배달 모두 이 클래스로 역직렬화한다 (생성자만으로는 Jackson 이 만들 수 없었다)
public class AuthEvent extends DomainEvent {

    public static final String TYPE_AUTH_COMPLETED = "AUTH_COMPLETED";
    public static final String TYPE_AUTH_FAILED    = "AUTH_FAILED";
    public static final String TYPE_AUTH_LOCKED    = "AUTH_LOCKED";

    private final String authResultId;
    private final AuthResult.AuthLevel authLevel;
    private final String providerCode;
    private final String providerTxId;
    private final AuthResult.VerificationResult verificationResult;

    public AuthEvent(String eventType, String sourceSystem, String correlationId,
                     String qimUserId, Long eventVersion,
                     String authResultId, AuthResult.AuthLevel authLevel,
                     String providerCode, String providerTxId,
                     AuthResult.VerificationResult verificationResult) {
        super(eventType, sourceSystem, correlationId, qimUserId, eventVersion);
        this.authResultId        = authResultId;
        this.authLevel           = authLevel;
        this.providerCode        = providerCode;
        this.providerTxId        = providerTxId;
        this.verificationResult  = verificationResult;
    }
}
