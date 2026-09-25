package io.github.hipstermin.idem.hub.sso;

import io.github.hipstermin.idem.common.domain.CastToken;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.handoff.HandoffIssueCommand;
import io.github.hipstermin.idem.hub.handoff.HandoffService;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
 *      응답: { redirectUrl: "https://agency-b.example.org/sso-entry" } (진입점은 프로파일 protocol.endpoints.ssoEntry)
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
    private final ServiceProfileService serviceProfileService;

    // ── DTO 내부 클래스 ────────────────────────────────────────────────────

    /**
     * CAST 토큰 발급 응답
     *
     * <p><b>Sprint α-3 / F4.4 변경</b>:
     * <ul>
     *   <li>{@code redirectUrl} — 기관 B의 SSO 진입점 URL <b>only</b> (castToken 미포함). Referer/브라우저 히스토리/Access-Log 유출 차단.</li>
     *   <li>{@code formHtml} — 신규. 클라이언트가 즉시 렌더하면 자동으로 castToken을 POST로 제출하는 자동 폼 HTML.
     *       (input[type=hidden]에만 castToken 포함; URL 라인엔 절대 노출되지 않음.)</li>
     *   <li>{@code ssoEntryUrl} — 신규. {@code redirectUrl}과 동일하지만 의미를 명확히 한 alias (deprecation 단계).</li>
     * </ul>
     *
     * <p>프론트엔드 사용 권장 패턴:
     * <pre>
     *   const res = await fetch('/api/v1/agency/cast/issue', ...);
     *   document.open(); document.write(res.formHtml); document.close();  // 자동 POST 제출
     * </pre>
     */
    public record CastIssueResponse(
            String jti,
            String targetAgency,
            String redirectUrl,    // F4.4: castToken 미포함. 기관 B의 SSO 진입점 URL only.
            String ssoEntryUrl,    // F4.4 신규: redirectUrl과 동일 (의미 명확화)
            String formHtml,       // F4.4 신규: castToken을 hidden field로 POST 제출하는 자동 폼
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

        // ── Sprint α-3 / F4.4 — castToken을 URL 쿼리에 싣지 않는다 ────────────
        // 이전: redirectUrl = "https://x.example.org/sso-entry?onepass_sso=<JWT>"
        //       → Referer 헤더/브라우저 히스토리/HTTPS access-log에 JWT가 누설.
        // 현재:
        //   • redirectUrl/ssoEntryUrl = 기관 B 진입점 URL only (castToken 미포함)
        //   • formHtml = castToken을 hidden field로 담아 자동 POST 제출하는 HTML
        String ssoEntryUrl = buildSsoEntryUrl(targetAgency, cid);
        String formHtml    = buildAutoSubmitForm(ssoEntryUrl, castToken.token(), castToken.jti());

        CastIssueResponse response = new CastIssueResponse(
                castToken.jti(),
                castToken.targetAgency(),
                ssoEntryUrl,          // redirectUrl (legacy 호환 alias) — JWT 미포함
                ssoEntryUrl,          // ssoEntryUrl — JWT 미포함
                formHtml,             // formHtml — castToken은 hidden field에만 존재
                castToken.token(),
                CastToken.TTL_SECONDS
        );

        log.info("[CrossAgencySSO] CAST 발급 완료 jti={} targetAgency={} cid={} (F4.4: URL에 JWT 미포함)",
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
        io.github.hipstermin.idem.common.domain.AuthResult.AuthLevel authLevel =
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

    /**
     * Sprint α-3 / F4.4 — 기관 B의 SSO 진입점 URL 생성 (castToken 미포함).
     *
     * <p>실제 운영에서는 agency_endpoint_registry에서 기관의 SSO 진입점 URL을 조회.
     * Sprint 14에서 registry 구현 후 연동 예정. 현재는 플레이스홀더 형식 반환.
     *
     * <p><b>주의</b>: 절대 castToken/JWT을 쿼리 스트링에 포함하지 말 것 (F4.4).
     */
    private String buildSsoEntryUrl(String targetAgency, String cid) {
        // D3: 진입점은 대상 Service 프로파일(protocol.endpoints.ssoEntry)이 정한다 — 종전에는 코드가 고정 도메인으로 지어냈다. 없으면 거부
        String entry = serviceProfileService.find(targetAgency)
                .map(ServiceProfile::protocol)
                .map(ServiceProfile.Protocol::endpoints)
                .map(ServiceProfile.Endpoints::ssoEntry)
                .orElse(null);
        if (entry == null || entry.isBlank()) {
            throw new PlatformException(PlatformErrorCode.IDO_INVALID_TENANT_PROFILE, cid,
                    "대상 Service 프로파일에 protocol.endpoints.ssoEntry(CAST 진입점)가 없습니다: " + targetAgency);
        }
        return entry;
    }

    /**
     * Sprint α-3 / F4.4 — castToken 자동 POST 제출 HTML 폼 생성.
     *
     * <p>프론트엔드가 응답의 {@code formHtml}을 그대로 페이지에 렌더(예: document.write)하면
     * 브라우저가 즉시 form.submit()을 실행하여 castToken을 <b>POST body</b>로 기관 B에 전송한다.
     *
     * <p><b>보안 효과</b>:
     * <ul>
     *   <li>URL 쿼리/Referer/access-log/브라우저 히스토리에 castToken 미노출</li>
     *   <li>JS 비활성 환경에서는 사용자가 "계속" 버튼을 직접 눌러야 진행 (XSS·자동제출 콤보 완화)</li>
     *   <li>제출 후 브라우저 표시 URL은 기관 B 진입점만 남음</li>
     * </ul>
     *
     * <p>HTML 인코딩 주의: castToken/url은 모두 HTML entity escape 처리하여
     * 폼 HTML이 의도치 않게 깨지거나 주입(injection)되지 않도록 한다.
     */
    private String buildAutoSubmitForm(String ssoEntryUrl, String castJwt, String jti) {
        String escapedUrl   = htmlEscape(ssoEntryUrl);
        String escapedToken = htmlEscape(castJwt);
        String escapedJti   = htmlEscape(jti);
        return "<!DOCTYPE html>\n" +
               "<html lang=\"ko\"><head><meta charset=\"UTF-8\">" +
               "<title>OnePass SSO 진입</title></head>" +
               "<body onload=\"document.forms[0].submit()\">" +
               "<noscript><p>JavaScript가 비활성화되어 있습니다. 아래 버튼을 눌러 진행하세요.</p></noscript>" +
               "<form method=\"POST\" action=\"" + escapedUrl + "\" autocomplete=\"off\">" +
               "<input type=\"hidden\" name=\"onepass_sso\" value=\"" + escapedToken + "\"/>" +
               "<input type=\"hidden\" name=\"jti\" value=\"" + escapedJti + "\"/>" +
               "<noscript><button type=\"submit\">계속</button></noscript>" +
               "</form></body></html>";
    }

    /**
     * 최소한의 HTML escape — buildAutoSubmitForm 전용.
     * castToken/URL은 영숫자·일부 기호로 구성되지만 안전을 위해 5종 entity 변환.
     */
    private String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&",  "&amp;")
                .replace("<",  "&lt;")
                .replace(">",  "&gt;")
                .replace("\"", "&quot;")
                .replace("'",  "&#39;");
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
    private io.github.hipstermin.idem.common.domain.AuthResult.AuthLevel mapAuthLevel(String castAuthLevel) {
        // S3 어휘 통일 — 정규 L1/L2/L3 와 구 LOW/MEDIUM/HIGH 모두 해석, 미지 값은 L1
        return io.github.hipstermin.idem.common.domain.AuthResult.AuthLevel.parseOrDefault(
                castAuthLevel, io.github.hipstermin.idem.common.domain.AuthResult.AuthLevel.L1);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
