package io.github.hipstermin.idem.hub.broker;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.hub.auth.dto.AuthResult;
import io.github.hipstermin.idem.hub.auth.dto.im.QimMemberInfo;
import io.github.hipstermin.idem.hub.auth.dto.im.QimRegisterResponse;
import io.github.hipstermin.idem.hub.broker.dto.OidcCompleteRequest;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * q-sign → ido 내부 콜백 컨트롤러 (q-sign 모드 전용)
 *
 * <p>브로커 모드별 동작:
 * <ul>
 *   <li>{@code broker.mode=qsign}    : 활성 — q-sign이 인증 완료 후 이 엔드포인트 호출</li>
 *   <li>{@code broker.mode=keycloak} : 비활성 — Keycloak 콜백은 {@code KeycloakCallbackController}가 처리</li>
 * </ul>
 *
 * <p>q-sign 모드 흐름:
 * <pre>
 *   카카오 콜백 → q-sign (state 검증/token 교환/AuthResult 저장/Kafka 발행)
 *       → POST /api/internal/v1/oidc/complete
 *       → ido (Q-IM CI 조회 → qimUserId 획득 → FE 세션 발급 + feSessionId 쿠키 + redirectUrl 반환)
 *       → q-sign → 302 → returnUrl
 * </pre>
 *
 * <p>Keycloak 모드에서는 이 흐름이 더 이상 사용되지 않음.
 * Keycloak → GET /api/v1/broker/callback → KeycloakCallbackController가 직접 처리.
 *
 * <p>보안:
 * <ul>
 *   <li>X-Internal-Caller: q-sign — 내부 서비스 식별</li>
 *   <li>X-Internal-Sig: HMAC-SHA256 서명 검증 (§9.4)</li>
 *   <li>운영에서는 mTLS로 추가 보호</li>
 * </ul>
 *
 * <p>엔드포인트: POST /api/internal/v1/oidc/complete
 *
 * <p><b>P0 수정 (v0.8.7)</b>: {@code identifierHash} 를 {@code qimUserId} 대용으로 사용하던
 * PoC 코드를 제거. 이제 {@code QimClient.findByCi()} 로 실제 {@code qimUserId} 를 조회하며,
 * 미등록 사용자인 경우 {@code QimClient.registerUser()} 로 Q-IM 등록 후 {@code qimUserId} 획득.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/v1/oidc")
@RequiredArgsConstructor
public class OidcCompleteController {

    private static final String COOKIE_NAME      = "feSessionId";
    private static final String DEFAULT_MEMBER_TYPE = "INDIVIDUAL";

    private final FeSessionService     feSessionService;
    private final InternalSigVerifier  internalSigVerifier;
    private final QimClient            qimClient;

    @Value("${ido.broker.mode:qsign}")
    private String brokerMode;

    /**
     * OIDC 인증 완료 후 FE 세션 발급 (q-sign 모드 전용)
     *
     * <p>q-sign이 AuthResult를 DB에 저장한 뒤 이 엔드포인트를 호출.
     * ido는:
     * <ol>
     *   <li>브로커 모드 확인 (keycloak 모드면 409 반환 — 잘못된 경로)</li>
     *   <li>FE 세션 생성 (Redis 저장, feSessionId 발급)</li>
     *   <li>feSessionId 쿠키를 응답 헤더에 포함</li>
     *   <li>최종 redirectUrl 반환 → q-sign이 브라우저를 리다이렉트</li>
     * </ol>
     *
     * @param internalSig X-Internal-Sig 헤더 (서명 검증)
     * @param caller      X-Internal-Caller 헤더
     * @param req         OidcCompleteRequest 바디
     * @return { "redirectUrl": "...", "feSessionId": "..." }
     */
    @PostMapping("/complete")
    public ResponseEntity<Map<String, String>> complete(
            @RequestHeader(value = "X-Internal-Sig", required = false) String internalSig,
            @RequestHeader(value = "X-Internal-Caller", required = false) String caller,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody OidcCompleteRequest req,
            HttpServletResponse response) {

        String cid = req.getCorrelationId() != null
                ? req.getCorrelationId()
                : (correlationId != null ? correlationId : CorrelationIdHolder.get());
        CorrelationIdHolder.set(cid);

        // ── P1-03: X-Internal-Sig HMAC-SHA256 수신 측 검증 ───────────────
        // 설계서 §9.4 — q-sign → ido 내부 서명 검증 (재계산 + ±60초 타임스탬프 유효성)
        // q-sign 모드에서만 서명 검증 수행 (keycloak 모드는 아래에서 409 반환)
        if (!"keycloak".equals(brokerMode)) {
            if (!internalSigVerifier.verify(internalSig, cid)) {
                log.warn("[OidcComplete] X-Internal-Sig 검증 실패: caller={} correlationId={}", caller, cid);
                throw new PlatformException(PlatformErrorCode.IDP_SIGNATURE_MISMATCH, cid);
            }
            log.debug("[OidcComplete] X-Internal-Sig 검증 통과: caller={} correlationId={}", caller, cid);
        }

        // ── Keycloak 모드 확인 ────────────────────────────────────────────
        if ("keycloak".equals(brokerMode)) {
            log.warn("[OidcComplete] keycloak 모드에서 내부 콜백 수신 (잘못된 경로): caller={} correlationId={}",
                    caller, cid);
            return ResponseEntity.status(409)
                    .body(Map.of(
                            "error",   "BROKER_MODE_MISMATCH",
                            "message", "keycloak 모드에서는 /api/v1/broker/callback을 사용하세요"
                    ));
        }

        log.info("[OidcComplete] q-sign 내부 콜백 수신: authResultId={} caller={}",
                req.getAuthResultId(), caller);

        // ── returnUrl 화이트리스트 검증 ────────────────────────────────────
        String returnUrl = req.getReturnUrl();
        if (returnUrl != null && !returnUrl.isBlank()
                && !feSessionService.isValidReturnUrl(returnUrl)) {
            log.warn("[OidcComplete] returnUrl 화이트리스트 거부: {}", returnUrl);
            return ResponseEntity.badRequest()
                    .body(Map.of("redirectUrl", "/error?code=INVALID_RETURN_URL"));
        }

        // ── [P0] Q-IM 실제 qimUserId 조회 ────────────────────────────────
        // CI(연계정보)로 Q-IM에서 영구 사용자 식별자(qimUserId)를 조회.
        // CI가 없으면(소셜 로그인 전용) identifierHash 기반 임시 식별자로 폴백하고
        // 감사 로그에 경고를 남긴다.
        String qimUserId = resolveQimUserId(req, cid);

        // ── FE 세션 생성 ──────────────────────────────────────────────────
        FeSession session = feSessionService.create(
                qimUserId,
                req.getAuthResultId(),
                req.getAuthLevel(),
                returnUrl
        );

        // ── feSessionId 쿠키 발급 ─────────────────────────────────────────
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, session.getFeSessionId())
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        // ── 최종 redirectUrl 결정 ─────────────────────────────────────────
        String redirectUrl = (returnUrl != null && !returnUrl.isBlank())
                ? returnUrl : "/conversion/complete";

        log.info("[OidcComplete] FE 세션 발급 완료: feSessionId={}... qimUserId={}... redirectUrl={} correlationId={}",
                session.getFeSessionId().substring(0, Math.min(8, session.getFeSessionId().length())),
                qimUserId.substring(0, Math.min(8, qimUserId.length())),
                redirectUrl, cid);

        return ResponseEntity.ok(Map.of(
                "redirectUrl", redirectUrl,
                "feSessionId", session.getFeSessionId()   // q-sign 로깅용
        ));
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────

    /**
     * [P0] CI → 실제 qimUserId 해석
     *
     * <p><b>흐름</b>:
     * <ol>
     *   <li>req.ci 가 있으면 → {@code QimClient.findByCi()} 로 Q-IM 조회</li>
     *   <li>기존 사용자 → {@code qimUserId} 반환</li>
     *   <li>미등록 사용자 → {@code QimClient.registerUser()} 로 Q-IM 자동 등록 후 {@code qimUserId} 반환</li>
     *   <li>CI 없음 → {@code identifierHash} 기반 임시 식별자로 폴백 + 경고 로그
     *       (소셜 로그인 전용 PoC 경로 — 운영 배포 전 CI 연동 완료 필수)</li>
     * </ol>
     *
     * @param req 요청 DTO (ci, memberType, identifierHash 포함)
     * @param cid correlationId (감사 로그)
     * @return 실제 qimUserId (또는 임시 identifierHash 폴백)
     */
    private String resolveQimUserId(OidcCompleteRequest req, String cid) {
        String ci = req.getCi();

        if (ci == null || ci.isBlank()) {
            // CI 없음: 소셜 로그인 전용 경로 또는 PoC 환경
            // 운영 배포 전 NICE/OACX 본인인증 연동으로 CI 확보 필수
            log.warn("[OidcComplete][P0-FALLBACK] CI 미포함 요청 — identifierHash를 임시 qimUserId로 사용. " +
                     "운영 배포 전 반드시 CI 연동 완료 필요. " +
                     "authResultId={} correlationId={}", req.getAuthResultId(), cid);
            return req.getIdentifierHash();
        }

        String memberType = (req.getMemberType() != null && !req.getMemberType().isBlank())
                ? req.getMemberType()
                : DEFAULT_MEMBER_TYPE;

        // Q-IM CI 조회 시도
        Optional<QimMemberInfo> memberOpt = qimClient.findByCi(ci, memberType, cid);

        if (memberOpt.isPresent()) {
            // 기존 사용자
            String qimUserId = memberOpt.get().getQimUserId();
            log.info("[OidcComplete] Q-IM 기존 사용자 확인 완료: qimUserId={}... correlationId={}",
                     qimUserId.substring(0, Math.min(8, qimUserId.length())), cid);
            return qimUserId;
        }

        // 미등록 사용자 → Q-IM 자동 등록
        log.info("[OidcComplete] Q-IM 미등록 사용자 — 자동 등록 진행: correlationId={}", cid);
        QimRegisterResponse registered =
                qimClient.registerUser(buildAuthResultForRegistration(req), cid);
        String newQimUserId = registered.getQimUserId();
        log.info("[OidcComplete] Q-IM 신규 등록 완료: qimUserId={}... isNew={} correlationId={}",
                 newQimUserId.substring(0, Math.min(8, newQimUserId.length())),
                 registered.getIsNew(), cid);
        return newQimUserId;
    }

    /**
     * Q-IM 신규 등록용 AuthResult 최소 구성 (CI 필드만 필수)
     *
     * <p>Q-Sign 모드에서 OidcCompleteRequest는 CI만 포함하므로,
     * 나머지 필드(name, birthday 등)는 Q-IM이 본인인증 원문으로 보완한다.
     * (Q-IM이 CI 등록 API에서 CI 외 필드는 선택사항으로 처리)
     */
    private AuthResult buildAuthResultForRegistration(OidcCompleteRequest req) {
        return AuthResult.builder()
                .ci(req.getCi())
                .build();
    }
}
