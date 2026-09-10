package io.github.hipstermin.idem.plugin.niceoacx;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.spi.identity.AuthWidgetDescriptor;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import io.github.hipstermin.idem.common.spi.identity.VerificationCallback;
import io.github.hipstermin.idem.common.spi.identity.VerificationRequest;
import io.github.hipstermin.idem.common.spi.identity.VerificationStart;
import io.github.hipstermin.idem.common.spi.identity.VerifiedIdentity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * NICE 휴대폰 본인인증 제공자 (플러그인판). 코드 {@code NICE_PHONE}, 등급 L2.
 *
 * <p>idem-hub 의 {@code NiceIdentityVerificationProvider}(P1 어댑터)와 같은 코드를 쓴다. 둘이 동시에 활성화되면
 * {@code IdentityProviderRegistry} 가 부팅 시 코드 중복으로 실패하므로, 플러그인을 켤 때는
 * {@code ido.auth.nice.provider-enabled=false} 로 코어 어댑터를 끈다. P2 본작업이 끝나면 코어 어댑터는 삭제된다.
 */
public class NicePhoneIdentityVerificationProvider implements IdentityVerificationProvider {

    public static final String CODE = "NICE_PHONE";
    static final String PARAM_WEB_TX_ID = "web_transaction_id";

    private final NicePhoneGateway gateway;
    private final AuthWidgetDescriptor widget;

    public NicePhoneIdentityVerificationProvider(NicePhoneGateway gateway, AuthWidgetDescriptor widget) {
        this.gateway = gateway;
        this.widget = widget;
    }

    @Override public String code() { return CODE; }

    @Override public AuthResult.AuthLevel level() { return AuthResult.AuthLevel.L2; }

    @Override
    public Optional<AuthWidgetDescriptor> widget() {
        return Optional.ofNullable(widget);
    }

    @Override
    public VerificationStart initiate(VerificationRequest request) {
        NicePhoneGateway.Started s = gateway.start(request.returnUrl());
        if (s == null || s.requestNo() == null || s.authUrl() == null) {
            throw new IdentityVerificationException(CODE, "START_FAILED", "NICE 인증 URL 발급 실패");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("requestNo", s.requestNo());
        return new VerificationStart(CODE, s.requestNo(), s.authUrl(), params);
    }

    @Override
    public VerifiedIdentity complete(VerificationCallback callback) {
        String webTxId = callback.param(PARAM_WEB_TX_ID);
        if (webTxId == null || webTxId.isBlank()) {
            throw new IdentityVerificationException(CODE, "MISSING_PARAM", "params." + PARAM_WEB_TX_ID + " 가 필요합니다");
        }
        NicePhoneGateway.Result r = gateway.result(webTxId, callback.txId());
        if (r == null || r.di() == null) {
            throw new IdentityVerificationException(CODE, "RESULT_FAILED", "NICE 인증 결과 조회 실패");
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        if (r.nationalInfo() != null) attrs.put("nationalInfo", r.nationalInfo());
        return new VerifiedIdentity(CODE, callback.txId(), r.di(), r.name(), r.birthdate(), r.gender(),
                r.mobileNo(), r.mobileCo(), level(), Instant.now(), attrs);
    }
}
