package io.github.hipstermin.idem.plugin.niceoacx.oacx;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import io.github.hipstermin.idem.common.spi.identity.VerificationCallback;
import io.github.hipstermin.idem.common.spi.identity.VerificationRequest;
import io.github.hipstermin.idem.common.spi.identity.VerificationStart;
import io.github.hipstermin.idem.common.spi.identity.VerifiedIdentity;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * OACX 간편서명 제공자 ({@code OACX_EASYSIGN}, L2) — 이 패키지는 vendor-libs 에 OACX SDK 가 있을 때만 컴파일된다.
 *
 * <p>SPI 매핑 (종전 idem-hub {@code AuthService.getOacxAccessInfo / handleOacxEasysign}):
 * <ul>
 *   <li>initiate: params.fn(기본 simpleAuth) → SDK getAccessInfo → txId = fn, params {fn, accKey, accToken}</li>
 *   <li>complete: params {fn, status, res(JSON 문자열 또는 키별 평탄화)} → SDK jwtDecryptResult → CI 스킴 {@link VerifiedIdentity}.
 *       provider 별 키 차이(name/userNm, phone/phoneNo)를 통일한다. CI 는 subjectKey 로만 실리고 속성에는 넣지 않는다</li>
 * </ul>
 */
@Slf4j
public class OacxEasySignIdentityVerificationProvider implements IdentityVerificationProvider {

    public static final String CODE = "OACX_EASYSIGN";

    private final OacxClientAdapter client;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public OacxEasySignIdentityVerificationProvider(OacxClientAdapter client, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override public String code() { return CODE; }
    @Override public AuthResult.AuthLevel level() { return AuthResult.AuthLevel.L2; }

    @Override
    public VerificationStart initiate(VerificationRequest request) {
        String fn = request.param("fn") != null ? request.param("fn") : "simpleAuth";
        Map<String, String> acc = client.getAccessInfo(fn);
        return new VerificationStart(CODE, fn, null, new LinkedHashMap<>(acc));
    }

    @Override
    @SuppressWarnings("unchecked")
    public VerifiedIdentity complete(VerificationCallback callback) {
        String fn = callback.param("fn") != null ? callback.param("fn") : callback.txId();
        if (!"authComplete".equals(fn)) {
            throw new IdentityVerificationException(CODE, "4000", "유효하지 않은 fn 값: " + fn + " (예상: authComplete)");
        }
        Map<String, Object> res;
        try {
            String raw = callback.param("res");
            res = raw == null ? Map.of() : objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            throw new IdentityVerificationException(CODE, "4000", "res 파싱 실패: " + e.getMessage());
        }
        String oacxResultCode = res.get("resultCode") != null ? String.valueOf(res.get("resultCode")) : null;
        if (!"200".equals(oacxResultCode)) {
            throw new IdentityVerificationException(CODE, "4001", "OACX 인증 실패: resultCode=" + oacxResultCode);
        }
        Map<String, Object> callbackMap = new HashMap<>();
        callbackMap.put("fn", fn);
        callbackMap.put("status", callback.param("status"));
        callbackMap.put("res", res);
        Map<String, String> decrypted = client.decryptEasysignResult(callbackMap);
        if (!"success".equals(decrypted.get("status"))) {
            throw new IdentityVerificationException(CODE, "5002", "OACX 인증 결과 복호화 실패: " + decrypted.get("message"));
        }
        String ci = decrypted.get("ci");
        if (ci == null || ci.isBlank()) {
            throw new IdentityVerificationException(CODE, "5002", "OACX 결과에 CI 가 없습니다 (provider=" + decrypted.get("provider") + ")");
        }
        String name = decrypted.getOrDefault("name", decrypted.get("userNm"));
        String phone = decrypted.getOrDefault("phone", decrypted.get("phoneNo"));
        Map<String, String> attrs = new LinkedHashMap<>();
        if (decrypted.get("provider") != null) attrs.put("provider", decrypted.get("provider"));
        if (decrypted.get("di") != null) attrs.put("di", decrypted.get("di"));
        return new VerifiedIdentity(CODE, callback.txId(), ci, name, decrypted.get("birthday"), decrypted.get("gender"),
                phone, decrypted.get("mobileCorp"), level(), Instant.now(), attrs, SubjectScheme.CI);
    }
}
