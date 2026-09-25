package io.github.hipstermin.idem.hub.slo;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.hub.broker.InternalSigVerifier;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * gate → hub (S6 PR-2): IdP(Keycloak) 세션이 끝났다 — OIDC Back-Channel Logout 수신 또는 RP-Initiated Logout 통과 시.
 * {@code sid} 가 있으면 그 세션에서 난 FE 세션만, 없으면 {@code sub} 의 FE 세션 전부를 만료한다.
 *
 * <p>{@code POST /api/internal/v1/session/idp-logout} — X-Internal-Sig 필수. 응답 {@code {expired: n}}.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/v1/session")
@RequiredArgsConstructor
public class IdpSessionLogoutController {

    private final InternalSigVerifier internalSigVerifier;
    private final FeSessionService feSessionService;

    @PostMapping("/idp-logout")
    public ResponseEntity<Map<String, Object>> idpLogout(
            @RequestHeader(value = "X-Internal-Sig", required = false) String internalSig,
            @RequestHeader(value = "X-Internal-Caller", required = false) String caller,
            @RequestHeader(value = "X-Correlation-Id", required = false) String headerCid,
            @RequestBody Map<String, String> body) {
        String cid = body.getOrDefault("correlationId", headerCid != null ? headerCid : UUID.randomUUID().toString());
        CorrelationIdHolder.set(cid);
        if (!internalSigVerifier.verify(internalSig, cid)) {
            log.warn("[IdpLogout] X-Internal-Sig 검증 실패: caller={} cid={}", caller, cid);
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, cid);
        }
        String sub = body.get("sub");
        String sid = body.get("sid");
        if ((sub == null || sub.isBlank()) && (sid == null || sid.isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "sub 또는 sid 가 필요합니다"));
        }
        int expired = feSessionService.invalidateByIdpSession(sub, sid, body.getOrDefault("reason", "IDP_LOGOUT"));
        log.info("[IdpLogout] FE 세션 만료: expired={} sid={} caller={} cid={}", expired, sid != null, caller, cid);
        return ResponseEntity.ok(Map.of("expired", expired));
    }
}
