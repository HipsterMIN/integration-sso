package kr.go.smes.qsign.api;

import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.qsign.api.dto.OidcAuthRequest;
import kr.go.smes.qsign.api.dto.IdOAuthInputRequest;
import kr.go.smes.qsign.application.AuthService;
import kr.go.smes.common.domain.IdOAuthInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.Instant;

/**
 * Q-Sign 인증 API
 * 설계서 17.1 / 9.7절 참조
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService          authService;
    private final InternalSigVerifier  internalSigVerifier;

    /**
     * 표준 OIDC 인증 결과 발급
     * POST /api/v1/auth/oidc
     */
    @PostMapping("/oidc")
    public ResponseEntity<AuthResult> authenticateOidc(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody OidcAuthRequest request) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

        AuthResult result = authService.issueFromOidc(
                cid, request.getProviderCode(), request.getIdToken(), request.getRequestedLevel());

        return ResponseEntity.ok(result);
    }

    /**
     * GAP-QS-04: IdO → Q-Sign 비OIDC/반표준 인증 정규화 입력
     * POST /api/v1/auth/broker-input
     * 설계서 11.7절 — 내부 전용 (mTLS 보호 구간)
     *
     * <p>X-Internal-Sig 헤더를 HMAC-SHA256으로 검증한다.
     * strict-mode=true(운영): 불일치 시 403 반환.
     * strict-mode=false(PoC): 경고 로그 후 통과.
     */
    @PostMapping("/broker-input")
    public ResponseEntity<AuthResult> authenticateFromBroker(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Internal-Sig", required = true) String internalSig,
            @Valid @RequestBody IdOAuthInputRequest request) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

        // ── GAP-QS-04: X-Internal-Sig HMAC-SHA256 검증 ───────────────────
        // 설계서 §9.4 — IdO → Q-Sign 내부 서명 검증 (재계산 + ±60초 타임스탬프 유효성)
        if (!internalSigVerifier.verify(internalSig, cid)) {
            log.warn("[AuthController] X-Internal-Sig 검증 실패: correlationId={}", cid);
            throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, cid);
        }
        log.debug("[AuthController] X-Internal-Sig 검증 통과: correlationId={}", cid);

        IdOAuthInput input = IdOAuthInput.builder()
                .correlationId(cid)
                .providerCode(request.getProviderCode())
                .providerTxId(request.getProviderTxId())
                .requestedAuthLevel(AuthResult.AuthLevel.valueOf(request.getRequestedAuthLevel()))
                .identifierHash(request.getIdentifierHash())
                .providerVerified(request.isProviderVerified())
                .claims(request.getClaims())
                .internalSignature(internalSig)
                .createdAt(Instant.now())
                .build();

        AuthResult result = authService.issueFromIdOAuthInput(input);
        return ResponseEntity.ok(result);
    }

    /**
     * AuthResult 조회
     * GET /api/v1/auth/{authResultId}
     */
    @GetMapping("/{authResultId}")
    public ResponseEntity<AuthResult> getAuthResult(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @PathVariable String authResultId) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        AuthResult result = authService.findById(authResultId, cid);
        return ResponseEntity.ok(result);
    }
}
