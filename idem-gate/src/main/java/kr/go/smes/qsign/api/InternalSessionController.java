package kr.go.smes.qsign.api;

import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.qsign.keycloak.KeycloakLogoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Q-Sign 내부 세션 관리 API — IdO 전용
 * 설계서 §13.3 SLO 구현 / Sprint 2 P1-02
 *
 * <p><b>보안</b>: X-Internal-Sig HMAC-SHA256 헤더로 IdO 발신 검증.
 * 인터넷에 노출되지 않는 내부망 전용 엔드포인트.
 *
 * <p>기본 경로: {@code /api/v1/internal/session}
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/session")
@RequiredArgsConstructor
public class InternalSessionController {

    private final InternalSigVerifier    internalSigVerifier;
    private final KeycloakLogoutService  keycloakLogoutService;

    /**
     * SLO — Keycloak 세션 강제 종료 요청 (IdO → Q-Sign 내부 API)
     *
     * <p>IdO SloServiceImpl이 호출.
     * X-Internal-Sig 검증 후 KeycloakLogoutService.revokeKeycloakSession() 위임.
     *
     * <p><b>요청 Body</b>:
     * <pre>{@code
     * {
     *   "sub": "keycloak-user-sub-or-preferred_username",
     *   "correlationId": "..."
     * }
     * }</pre>
     *
     * <p><b>응답</b>:
     * <ul>
     *   <li>204 No Content — 세션 종료 성공 또는 비치명적 실패 (Keycloak 불응 시에도 204)</li>
     *   <li>401 Unauthorized — X-Internal-Sig 검증 실패</li>
     *   <li>400 Bad Request — sub 누락</li>
     * </ul>
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logoutSession(
            @RequestHeader(value = "X-Correlation-Id",  required = false) String correlationId,
            @RequestHeader(value = "X-Internal-Caller", required = false) String caller,
            @RequestHeader(value = "X-Internal-Sig",    required = false) String internalSig,
            @RequestBody Map<String, String> body) {

        String cid = resolveCorrelationId(correlationId);
        CorrelationIdHolder.set(cid);

        // ① X-Internal-Sig 검증
        if (!internalSigVerifier.verify(internalSig, cid)) {
            log.warn("[InternalSession] X-Internal-Sig 검증 실패: caller={} correlationId={}", caller, cid);
            return ResponseEntity.status(401).build();
        }

        // ② sub 파라미터 검증
        String sub = body.get("sub");
        if (sub == null || sub.isBlank()) {
            log.warn("[InternalSession] 요청 body에 sub 누락: correlationId={}", cid);
            return ResponseEntity.badRequest().build();
        }

        log.info("[InternalSession] Keycloak 세션 종료 요청: sub={} caller={} correlationId={}", sub, caller, cid);

        // ③ Keycloak 세션 강제 종료 (비치명적 — 실패해도 204 반환)
        keycloakLogoutService.revokeKeycloakSession(sub, cid);

        return ResponseEntity.noContent().build();
    }

    // ── private ────────────────────────────────────────────────────────────

    private String resolveCorrelationId(String correlationId) {
        return (correlationId != null && !correlationId.isBlank())
                ? correlationId
                : CorrelationIdHolder.generate();
    }
}
