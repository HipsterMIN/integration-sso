package kr.go.smes.ido.sso;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import kr.go.smes.common.domain.CastToken;
import kr.go.smes.common.domain.HandoffTicket;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.ido.handoff.HandoffIssueCommand;
import kr.go.smes.ido.handoff.HandoffService;
import kr.go.smes.ido.fe.session.FeSession;
import kr.go.smes.ido.fe.session.FeSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-Agency SSO API 컨트롤러 (Sprint 13)
 *
 * <h3>엔드포인트</h3>
 * <ul>
 *   <li>{@code POST /api/v1/agency/cast/issue}   — 기관 B 대상 CAST 토큰 발급</li>
 *   <li>{@code POST /api/v1/agency/cast/verify}  — 기관 B SDK의 CAST 토큰 검증 및 Handoff 발급</li>
 *   <li>{@code GET  /api/v1/agency/cast/public-key} — Ed25519 공개키 조회 (SDK 키핀닝용)</li>
 * </ul>
 *
 * <h3>Cross-Agency SSO 흐름</h3>
 * <pre>
 *   1. 사용자: 기관 A에서 로그인 완료 (feSession 존재)
 *   2. 사용자: 기관 B 링크 클릭
 *   3. 기관 B 프론트: POST /api/v1/agency/cast/issue?targetAgency=AGENCY_B
 *      (Fe-Session-Id 쿠키 자동 전송)
 *   4. OnePass: CAST JWT 발급 → 기관 B 리디렉션 URL 반환
 *      응답: { redirectUrl: "https://agency-b.go.kr/sso-entry?onepass_sso=<JWT>" }
 *   5. 기관 B 서버: POST /api/v1/agency/cast/verify
 *      (X-Agency-Api-Key + body.castToken)
 *   6. OnePass: CAST 검증 + Handoff Ticket 즉시 발급
 *      응답: { handoffTicket, payload }
 * </pre>
 *
 * @see CastTokenService
 * @see HandoffService
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agency/cast")
@RequiredArgsConstructor
public class CrossAgencySsoController {

    private static final String FE_SESSION_COOKIE_NAME = "Fe-Session-Id";

    private final CastTokenService  castTokenService;
    private final HandoffService    handoffService;
    private final FeSessionService  feSessionService;

    // ── DTO 내부 클래스 ────────────────────────────────────────────────────

    /** CAST 토큰 발급 응답 */
    public record CastIssueResponse(
            String jti,
            String targetAgency,
            String redirectUrl,
            String castToken,
            long   expiresInSeconds
    ) {}

    /** CAST 토큰 검증 요청 */
    public record CastVerifyRequest(
            @NotBlank(message = "castToken은 필수입니다.")
            String castToken,
            @NotBlank(message = "targetAgencyCode는 필수입니다.")
            String targetAgencyCode,
            String redirectUri,
            String correlationId
    ) {}

    /** CAST 토큰 검증 응답 (Handoff Ticket 포함) */
    public record CastVerifyResponse(
            String qimUserId,
            String sourceAgency,
            String authLevel,
            HandoffTicket handoffTicket
    ) {}

    // ── 1. CAST 토큰 발급 ─────────────────────────────────────────────────

    /**
     * CAST 토큰 발급
     * {@code POST /api/v1/agency/cast/issue}
     *
     * <p>FE 세션 쿠키(Fe-Session-Id)에서 사용자를 식별하고 대상 기관 B에 대한
     * CAST JWT를 발급한다. 브라우저 기반 호출 — feSession 쿠키 필수.
     *
     * @param targetAgency 대상 기관 코드 (쿼리 파라미터)
     * @param request      HttpServletRequest (Fe-Session-Id 쿠키 추출용)
     * @return 발급된 CAST 토큰 + 기관 B 리디렉션 URL
     */
    @PostMapping("/issue")
    public ResponseEntity<CastIssueResponse> issue(
            @RequestParam("targetAgency")                          String targetAgency,
            @RequestHeader(value = "X-Correlation-Id",
                           required = false)                       String correlationId,
            HttpServletRequest request) {

        String cid = resolveCorrelationId(correlationId);
        log.info("[CrossAgencySSO] CAST 발급 요청 targetAgency={} cid={}", targetAgency, cid);

        // Fe-Session-Id 쿠키 추출
        String feSessionId = extractFeSessionId(request, cid);

        // CAST 토큰 발급
        CastToken castToken = castTokenService.issue(feSessionId, targetAgency, cid);

        // 기관 B 리디렉션 URL 구성 (기관 B의 SSO 진입점 + onepass_sso 파라미터)
        String redirectUrl = buildRedirectUrl(targetAgency, castToken.token());

        CastIssueResponse response = new CastIssueResponse(
                castToken.jti(),
                castToken.targetAgency(),
                redirectUrl,
                castToken.token(),
                CastToken.TTL_SECONDS
        );

        log.info("[CrossAgencySSO] CAST 발급 완료 jti={} targetAgency={} cid={}",
                castToken.jti(), targetAgency, cid);
        return ResponseEntity.ok(response);
    }

    // ── 2. CAST 토큰 검증 + Handoff 즉시 발급 ────────────────────────────

    /**
     * CAST 토큰 검증 및 Handoff Ticket 즉시 발급
     * {@code POST /api/v1/agency/cast/verify}
     *
     * <p>기관 B SDK에서 서버 사이드로 호출하는 검증 엔드포인트.
     * CAST 토큰 검증 성공 시 즉시 Handoff Ticket을 발급하여 반환한다.
     * 기관 B는 반환된 Handoff Ticket으로 사용자를 내부 시스템에 로그인시킨다.
     *
     * <p><b>보안</b>: X-Agency-Api-Key 헤더 필수 (기관 B API Key 인증).
     * 실제 인증은 Spring Security 필터 체인에서 처리됨.
     *
     * @param req          CAST 검증 요청 (castToken, targetAgencyCode)
     * @param agencyCode   X-Agency-Code 헤더 (기관 코드 — API Key와 일치 검증)
     * @param correlationId X-Correlation-Id 헤더
     * @param request      HttpServletRequest (소비자 IP 추출용)
     * @return 검증된 사용자 정보 + Handoff Ticket
     */
    @PostMapping("/verify")
    public ResponseEntity<CastVerifyResponse> verify(
            @Valid @RequestBody                                     CastVerifyRequest req,
            @RequestHeader(value = "X-Agency-Code", required = false) String agencyCode,
            @RequestHeader(value = "X-Correlation-Id",
                           required = false)                        String correlationId,
            HttpServletRequest request) {

        String cid         = resolveCorrelationId(correlationId);
        String consumerIp  = extractClientIp(request);
        String targetAgency = req.targetAgencyCode() != null ? req.targetAgencyCode()
                            : (agencyCode != null ? agencyCode : "UNKNOWN");

        log.info("[CrossAgencySSO] CAST 검증 요청 targetAgency={} cid={}", targetAgency, cid);

        // CAST 토큰 검증 및 1회 소비
        CastToken cast = castTokenService.verify(req.castToken(), targetAgency, consumerIp, cid);

        // Handoff Ticket 즉시 발급 (기관 B 진입용)
        HandoffTicket handoffTicket = issueHandoffForCastToken(cast, req.redirectUri(), cid);

        CastVerifyResponse response = new CastVerifyResponse(
                cast.qimUserId(),
                cast.sourceAgency(),
                cast.authLevel(),
                handoffTicket
        );

        log.info("[CrossAgencySSO] CAST 검증 성공 + Handoff 발급 jti={} qimUserId={} cid={}",
                cast.jti(), cast.qimUserId(), cid);
        return ResponseEntity.ok(response);
    }

    // ── 3. Ed25519 공개키 조회 (SDK 키핀닝용) ────────────────────────────

    /**
     * CAST 서명 공개키 조회
     * {@code GET /api/v1/agency/cast/public-key}
     *
     * <p>기관 SDK가 CAST 토큰 서명을 독립 검증하기 위한 Ed25519 공개키를 반환.
     * JWK 형식(RFC 7517) 반환으로 표준 JWT 라이브러리와 호환.
     *
     * @return Ed25519 공개키 (Base64url 인코딩)
     */
    @GetMapping("/public-key")
    public ResponseEntity<Map<String, String>> publicKey() {
        return ResponseEntity.ok(Map.of(
            "note", "공개키 엔드포인트 — Sprint 13 Phase 2에서 JWK 형식으로 구현 예정",
            "algorithm", "EdDSA",
            "curve", "Ed25519"
        ));
    }

    // ── private helpers ────────────────────────────────────────────────────

    private String extractFeSessionId(HttpServletRequest request, String cid) {
        if (request.getCookies() == null) {
            throw new PlatformException(PlatformErrorCode.SSO_CAST_SESSION_NOT_FOUND, cid);
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> FE_SESSION_COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("[CrossAgencySSO] Fe-Session-Id 쿠키 없음 cid={}", cid);
                    return new PlatformException(PlatformErrorCode.SSO_CAST_SESSION_NOT_FOUND, cid);
                });
    }

    private HandoffTicket issueHandoffForCastToken(CastToken cast, String redirectUri, String cid) {
        // CAST 토큰 authLevel(LOW/MEDIUM/HIGH) → HandoffTicket AuthLevel(L1/L2/L3) 매핑
        kr.go.smes.common.domain.AuthResult.AuthLevel authLevel =
                mapAuthLevel(cast.authLevel());

        HandoffIssueCommand cmd = HandoffIssueCommand.builder()
                .correlationId(cid)
                .agencyCode(cast.targetAgency())
                .qimUserId(cast.qimUserId())
                .authResultId("CAST-" + cast.jti())
                .authLevel(authLevel)
                .providerCode("CROSS_AGENCY_SSO")
                .redirectUri(redirectUri != null ? redirectUri : "")
                .build();

        return handoffService.issue(cmd);
    }

    private String buildRedirectUrl(String targetAgency, String castJwt) {
        // 실제 운영에서는 agency_endpoint_registry에서 기관의 SSO 진입점 URL을 조회
        // Sprint 14에서 agency_endpoint_registry 구현 후 연동 예정
        // 현재는 플레이스홀더 형식 반환
        return String.format("https://%s.agency.go.kr/sso-entry?onepass_sso=%s",
                targetAgency.toLowerCase().replace("_", "-"), castJwt);
    }

    private String resolveCorrelationId(String headerValue) {
        if (headerValue != null && !headerValue.isBlank()) return headerValue;
        String fromHolder = CorrelationIdHolder.get();
        return (fromHolder != null) ? fromHolder : UUID.randomUUID().toString();
    }

    /**
     * CAST 토큰 authLevel(LOW/MEDIUM/HIGH) → Handoff AuthLevel(L1/L2/L3) 변환
     * LOW=L1, MEDIUM=L2, HIGH=L3
     */
    private kr.go.smes.common.domain.AuthResult.AuthLevel mapAuthLevel(String castAuthLevel) {
        if (castAuthLevel == null) return kr.go.smes.common.domain.AuthResult.AuthLevel.L1;
        return switch (castAuthLevel.toUpperCase()) {
            case "HIGH"   -> kr.go.smes.common.domain.AuthResult.AuthLevel.L3;
            case "MEDIUM" -> kr.go.smes.common.domain.AuthResult.AuthLevel.L2;
            default       -> kr.go.smes.common.domain.AuthResult.AuthLevel.L1;
        };
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
