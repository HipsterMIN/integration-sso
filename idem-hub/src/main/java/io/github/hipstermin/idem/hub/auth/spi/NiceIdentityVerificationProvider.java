package io.github.hipstermin.idem.hub.auth.spi;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import io.github.hipstermin.idem.common.spi.identity.VerificationCallback;
import io.github.hipstermin.idem.common.spi.identity.VerificationRequest;
import io.github.hipstermin.idem.common.spi.identity.VerificationStart;
import io.github.hipstermin.idem.common.spi.identity.VerifiedIdentity;
import io.github.hipstermin.idem.hub.auth.dto.NicePhoneAuthResultRequest;
import io.github.hipstermin.idem.hub.auth.dto.NicePhoneAuthResultResponse;
import io.github.hipstermin.idem.hub.auth.dto.NicePhoneAuthUrlResponse;
import io.github.hipstermin.idem.hub.auth.service.NiceAuthService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * NICE 휴대폰 본인인증을 {@link IdentityVerificationProvider} SPI 로 감싼 어댑터 (P1).
 *
 * <p>기존 {@code /api/v1/auth/nice/phone/*} 엔드포인트와 {@link NiceAuthService} 는 그대로 두고,
 * 표준 흐름({@code /api/v1/auth/providers/NICE_PHONE/…})으로도 같은 서비스를 쓸 수 있게 한다.
 * P2 에서 이 어댑터·서비스·DTO 가 통째로 {@code idem-plugin-nice-oacx} 로 이동한다.
 *
 * <p>매핑:
 * <ul>
 *   <li>initiate: returnUrl → {@link NiceAuthService#getNicePhoneAuthUrl} → txId = requestNo, redirectUrl = authUrl</li>
 *   <li>complete: params[web_transaction_id] + txId(requestNo) → {@link NiceAuthService#getNicePhoneAuthResult}
 *       → subjectKey = DI (NICE 는 CI 를 FE 로 반환하지 않는 설계 Q3=B 를 그대로 따른다)</li>
 * </ul>
 * 비활성화: {@code ido.auth.nice.provider-enabled=false}.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ido.auth.nice", name = "provider-enabled", havingValue = "true", matchIfMissing = true)
public class NiceIdentityVerificationProvider implements IdentityVerificationProvider {

    public static final String CODE = "NICE_PHONE";
    static final String PARAM_WEB_TX_ID = "web_transaction_id";

    private final NiceAuthService niceAuthService;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public AuthResult.AuthLevel level() {
        return AuthResult.AuthLevel.L2;
    }

    @Override
    public VerificationStart initiate(VerificationRequest request) {
        NicePhoneAuthUrlResponse res = niceAuthService.getNicePhoneAuthUrl(request.returnUrl());
        if (res == null || res.getAuthUrl() == null || res.getRequestNo() == null) {
            throw new IdentityVerificationException(CODE, res == null ? null : res.getResultCode(),
                    "NICE 인증 URL 발급 실패" + (res == null ? "" : ": " + res.getResultMsg()));
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("requestNo", res.getRequestNo());
        return new VerificationStart(CODE, res.getRequestNo(), res.getAuthUrl(), params);
    }

    @Override
    public VerifiedIdentity complete(VerificationCallback callback) {
        String webTxId = callback.param(PARAM_WEB_TX_ID);
        if (webTxId == null || webTxId.isBlank()) {
            throw new IdentityVerificationException(CODE, "MISSING_PARAM", "params." + PARAM_WEB_TX_ID + " 가 필요합니다");
        }
        NicePhoneAuthResultRequest req = NicePhoneAuthResultRequest.builder()
                .webTransactionId(webTxId)
                .requestNo(callback.txId())
                .build();
        NicePhoneAuthResultResponse res = niceAuthService.getNicePhoneAuthResult(req);
        NicePhoneAuthResultResponse.ResultData d = res == null ? null : res.getResultData();
        if (d == null) {
            throw new IdentityVerificationException(CODE, res == null ? null : res.getResultCode(),
                    "NICE 인증 결과 조회 실패" + (res == null ? "" : ": " + res.getResultMsg()));
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        if (d.getNationalInfo() != null) attrs.put("nationalInfo", d.getNationalInfo());
        return new VerifiedIdentity(CODE, callback.txId(), d.getDi(), d.getName(), d.getBirthdate(), d.getGender(),
                d.getMobileNo(), d.getMobileCo(), level(), Instant.now(), attrs);
    }
}
