package io.github.hipstermin.idem.hub.protocol.oidcrp;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.hub.broker.InternalSigVerifier;
import jakarta.validation.Valid;
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
 * gate → hub 내부 API (S6): 표준 OIDC 토큰 교환 시점의 접근 판정.
 *
 * <p>{@code POST /api/internal/v1/oidc-rp/access} — {@code X-Internal-Sig}(HMAC, gate↔hub 공유 비밀) 검증은 항상 strict.
 * 거부도 200 으로 내려간다(본문 {@code allowed=false, denyCode}); 401 은 서명 실패뿐이다.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/v1/oidc-rp")
@RequiredArgsConstructor
public class OidcRpAccessController {

    private final InternalSigVerifier internalSigVerifier;
    private final OidcRpAccessService accessService;

    @PostMapping("/access")
    public ResponseEntity<OidcRpAccessResponse> access(
            @RequestHeader(value = "X-Internal-Sig", required = false) String internalSig,
            @RequestHeader(value = "X-Internal-Caller", required = false) String caller,
            @RequestHeader(value = "X-Correlation-Id", required = false) String headerCid,
            @Valid @RequestBody OidcRpAccessRequest req) {
        String cid = req.correlationId() != null ? req.correlationId()
                : (headerCid != null ? headerCid : UUID.randomUUID().toString());
        CorrelationIdHolder.set(cid);
        if (!internalSigVerifier.verify(internalSig, cid)) {
            log.warn("[OidcRpAccess] X-Internal-Sig 검증 실패: caller={} cid={}", caller, cid);
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, cid);
        }
        return ResponseEntity.ok(accessService.evaluate(req, cid));
    }
}
