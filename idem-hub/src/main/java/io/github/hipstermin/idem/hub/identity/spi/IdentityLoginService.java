package io.github.hipstermin.idem.hub.identity.spi;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.spi.identity.IdentityProviderRegistry;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import io.github.hipstermin.idem.common.spi.identity.VerificationCallback;
import io.github.hipstermin.idem.common.spi.identity.VerificationRequest;
import io.github.hipstermin.idem.common.spi.identity.VerificationStart;
import io.github.hipstermin.idem.common.spi.identity.VerifiedIdentity;
import io.github.hipstermin.idem.hub.identity.SubjectRegistrationService;
import io.github.hipstermin.idem.hub.identity.audit.AuthAuditService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 본인인증 SPI 로그인 — initiate/complete + registry 등록 + 감사를 한 곳에 (1.1).
 *
 * <p>{@link IdentityVerificationController}(API) 와 코어 로그인 프런트({@code HandoffLoginController}) 가 같은 절차를 쓴다.
 * complete 가 성공하면 registry 사용자가 확정된다 — registry 장애면 503, 인증 성공을 등록 없이 돌려주지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityLoginService {

    private final IdentityProviderRegistry   registry;
    private final SubjectRegistrationService subjectRegistrationService;
    private final AuthAuditService           authAuditService;

    /** complete 결과 — 표준 결과 + registry 확정. */
    public record Completed(VerifiedIdentity identity, String qimUserId, boolean newUser) {}

    public IdentityVerificationProvider require(String code, String correlationId) {
        return registry.find(code).orElseThrow(() ->
                new PlatformException(PlatformErrorCode.IDO_AUTH_PROVIDER_UNKNOWN, correlationId, "provider=" + code));
    }

    public VerificationStart initiate(String code, String returnUrl, Map<String, String> params, String correlationId) {
        IdentityVerificationProvider provider = require(code, correlationId);
        try {
            VerificationStart start = provider.initiate(new VerificationRequest(correlationId, returnUrl, params));
            authAuditService.publishProviderInitiate(provider.code(), start.txId(), "2000", null);
            return start;
        } catch (IdentityVerificationException e) {
            authAuditService.publishProviderInitiate(provider.code(), null, e.getReasonCode(), e.getMessage());
            throw failed(e, correlationId);
        }
    }

    public Completed complete(String code, String txId, Map<String, String> params, String correlationId) {
        IdentityVerificationProvider provider = require(code, correlationId);
        VerifiedIdentity identity;
        try {
            identity = provider.complete(new VerificationCallback(provider.code(), txId, correlationId, params));
        } catch (IdentityVerificationException e) {
            authAuditService.publishProviderComplete(provider.code(), txId, e.getReasonCode(), null, null, e.getMessage());
            throw failed(e, correlationId);
        }
        SubjectRegistrationService.Result reg;
        try {
            reg = subjectRegistrationService.register(identity, correlationId);
        } catch (RuntimeException e) {
            authAuditService.publishProviderComplete(provider.code(), txId, "5010", null, null, "registry 등록 실패: " + e.getMessage());
            throw e;
        }
        authAuditService.publishProviderComplete(provider.code(), txId, "2000", reg.qimUserId(), reg.newUser(), null);
        return new Completed(identity, reg.qimUserId(), reg.newUser());
    }

    private PlatformException failed(IdentityVerificationException e, String correlationId) {
        log.warn("[IdO] 본인인증 실패 provider={} reason={} correlationId={} msg={}",
                e.getProviderCode(), e.getReasonCode(), correlationId, e.getMessage());
        return new PlatformException(PlatformErrorCode.IDO_AUTH_VERIFICATION_FAILED, correlationId,
                e.getProviderCode() + "/" + e.getReasonCode() + ": " + e.getMessage());
    }
}
