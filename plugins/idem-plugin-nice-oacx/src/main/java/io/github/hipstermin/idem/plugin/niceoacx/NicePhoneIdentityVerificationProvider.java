package io.github.hipstermin.idem.plugin.niceoacx;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
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
 * <p>S5a 부터 코어(idem-hub)에는 NICE 코드가 없다. 결과의 subjectKey 는 CI(registry {@code SubjectScheme#CI}),
 * DI·내외국인 구분은 속성으로 싣는다. CI 는 FE 로 나가지 않는다 — 코어 컨트롤러가 registry 등록에만 쓴다.
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
        if (r == null) {
            throw new IdentityVerificationException(CODE, "5002", "NICE 인증 결과 조회 실패");
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        if (r.nationalInfo() != null) attrs.put("nationalInfo", r.nationalInfo());
        if (r.di() != null) attrs.put("di", r.di());
        // 동일인 판정 키는 CI(KR registry 스킴). CI 가 없으면 등록 불가 — 실패로 본다 (종전 "CI 미포함 — 등록 건너뜀" 은 사용자 없는 인증)
        if (r.ci() == null || r.ci().isBlank()) {
            throw new IdentityVerificationException(CODE, "5002", "NICE 결과에 CI 가 없습니다");
        }
        return new VerifiedIdentity(CODE, callback.txId(), r.ci(), r.name(), r.birthdate(), r.gender(),
                r.mobileNo(), r.mobileCo(), level(), Instant.now(), attrs, SubjectScheme.CI);
    }
}
