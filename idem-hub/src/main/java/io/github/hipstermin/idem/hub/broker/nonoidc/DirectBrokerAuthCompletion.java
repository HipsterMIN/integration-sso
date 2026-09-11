package io.github.hipstermin.idem.hub.broker.nonoidc;

import io.github.hipstermin.idem.common.spi.broker.BrokerAuthCompletion;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link BrokerAuthCompletion} 코어 구현 — 직접 브로커 플러그인(AnyID 등)이 확정한 벤더 인증 결과를
 * {@link NonOidcAuthService}(AuthResult 저장·감사·이벤트) 와 {@link FeSessionService}(FE 세션) 로 잇는다 (S5b).
 *
 * <p>FE 세션 발급이 실패해도 인증 결과 저장은 유지하고 임시 세션 ID 를 돌려준다(종전 AnyID 컨트롤러 동작 유지 — 테스트·PoC 환경).
 * qimUserId 는 아직 registry 연동 전이므로 authResultId 를 대신 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectBrokerAuthCompletion implements BrokerAuthCompletion {

    private final NonOidcAuthService nonOidcAuthService;
    private final FeSessionService   feSessionService;

    @Override
    public Result complete(Command c) {
        NonOidcAuthCommand command = NonOidcAuthCommand.builder()
                .correlationId(c.correlationId())
                .providerCode(c.providerCode())
                .providerTxId(c.providerTxId())
                .rawIdentifier(c.rawIdentifier())
                .requestedLevel(c.authLevel())
                .providerVerified(true)
                .build();

        String authResultId = nonOidcAuthService.processAuth(command);

        String feSessionId;
        try {
            feSessionId = feSessionService
                    .create(authResultId, authResultId, c.authLevel(), c.returnUrl())
                    .getFeSessionId();
        } catch (Exception e) {
            log.warn("[DirectBrokerAuthCompletion] FeSession 생성 실패 (임시 ID 반환): cid={} err={}",
                    c.correlationId(), e.getMessage());
            feSessionId = UuidV7.generate();
        }
        return new Result(authResultId, feSessionId);
    }
}
