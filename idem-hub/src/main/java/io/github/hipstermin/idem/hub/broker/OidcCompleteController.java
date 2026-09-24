package io.github.hipstermin.idem.hub.broker;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.hub.broker.dto.OidcCompleteRequest;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.identity.SubjectRegistration;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.QimMemberInfo;
import io.github.hipstermin.idem.hub.infrastructure.QimRegisterResponse;
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
 * PoC 코드를 제거. 이제 {@code QimClient.findByIdentifierHash()} 로 실제 {@code qimUserId} 를 조회하며,
 * 미등록 사용자인 경우 {@code QimClient.registerSubject()} 로 Q-IM 등록 후 {@code qimUserId} 획득.
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/v1/oidc")
@RequiredArgsConstructor
public class OidcCompleteController {

    private static final String COOKIE_NAME      = "feSessionId";

    private final FeSessionService     feSessionService;
    private final InternalSigVerifier  internalSigVerifier;
    private final QimClient            qimClient;

    @Value("${ido.broker.mode:qsign}")
    private String brokerMode;

    /**
     * D2 fail-secure: CI 가 없는 요청을 identifierHash 로 "임시 사용자" 처리하던 PoC 폴백은 기본 금지.
     * 요청 본문이 통제 가능한 값으로 영구 식별자·세션이 발급되는 경로였다. 로컬·테스트에서만 true.
     */
    @Value("${ido.broker.allow-ciless-identity:false}")
    private boolean allowCilessIdentity;

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
     * S8-a: 주체 스킴·키로 registry 사용자를 찾고, 없으면 등록한다 (스킴 중립).
     *
     * <ul>
     *   <li>{@code subjectScheme}+{@code subjectKey} — 정식 계약. 구 필드 {@code ci} 는 scheme=CI 의 별칭으로 받는다(gate 호환).</li>
     *   <li>키가 없으면 → 로컬 탈출구({@code ido.broker.allow-ciless-identity}) 가 켜진 경우에만 identifierHash 를 임시 ID 로.</li>
     *   <li>미등록 → {@code registerSubject} 로 자동 등록.</li>
     * </ul>
     */
    private String resolveQimUserId(OidcCompleteRequest req, String cid) {
        SubjectScheme scheme = req.resolvedSubjectScheme();
        String subjectKey = req.resolvedSubjectKey();
        if (scheme == null || subjectKey == null || subjectKey.isBlank()) {
            if (!allowCilessIdentity) {
                log.warn("[OidcComplete] 주체 키 미포함 요청 거부 (ido.broker.allow-ciless-identity=false): authResultId={} correlationId={}",
                        req.getAuthResultId(), cid);
                throw new PlatformException(PlatformErrorCode.IDO_IDENTITY_UNRESOLVED, cid,
                        "주체 키(subjectScheme/subjectKey) 없는 인증 결과로는 세션을 발급하지 않습니다");
            }
            log.warn("[OidcComplete][LOCAL-ONLY] 주체 키 미포함 요청 — identifierHash 를 임시 qimUserId 로 사용: authResultId={} correlationId={}",
                     req.getAuthResultId(), cid);
            return req.getIdentifierHash();
        }
        String identifierHash = scheme.identifierHash(subjectKey);
        Optional<QimMemberInfo> memberOpt = qimClient.findByIdentifierHash(identifierHash, cid);
        if (memberOpt.isPresent()) {
            String qimUserId = memberOpt.get().getQimUserId();
            log.info("[OidcComplete] registry 기존 사용자 확인: scheme={} qimUserId={}... correlationId={}",
                     scheme, qimUserId.substring(0, Math.min(8, qimUserId.length())), cid);
            return qimUserId;
        }
        log.info("[OidcComplete] registry 미등록 사용자 — 자동 등록: scheme={} correlationId={}", scheme, cid);
        QimRegisterResponse registered = qimClient.registerSubject(SubjectRegistration.builder()
                .scheme(scheme)
                .subjectKey(subjectKey)
                .identifierHash(identifierHash)
                .providerCode(req.getProviderCode())
                .authResultId(req.getAuthResultId())
                .correlationId(cid)
                .build());
        String newQimUserId = registered.getQimUserId();
        log.info("[OidcComplete] registry 신규 등록 완료: qimUserId={}... isNew={} correlationId={}",
                 newQimUserId.substring(0, Math.min(8, newQimUserId.length())),
                 registered.getIsNew(), cid);
        return newQimUserId;
    }
}
