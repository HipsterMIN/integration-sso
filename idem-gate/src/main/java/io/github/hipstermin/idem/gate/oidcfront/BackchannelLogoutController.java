package io.github.hipstermin.idem.gate.oidcfront;

import io.github.hipstermin.idem.gate.keycloak.KeycloakJwksVerifier;
import io.github.hipstermin.idem.gate.keycloak.KeycloakProperties;
import io.github.hipstermin.idem.gate.keycloak.dto.KeycloakIdTokenClaims;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OIDC Back-Channel Logout 1.0 수신기 (S6 PR-2) — Keycloak 이 Idem 의 내부 client({@code idem-gate}·{@code idem-hub})가
 * 참여한 세션을 끝낼 때 {@code logout_token} 을 보낸다(관리자 종료·RP-Initiated Logout·SLO 모두).
 *
 * <p>검증(§2.6): RS256 서명(JWKS) · iss = Keycloak issuer · aud 원소 중 하나가 내부 client 와 <b>정확 일치</b> · {@code iat}·{@code jti} 필수 ·
 * {@code iat} 가 미래가 아니고 {@code exp}(있으면) 가 지나지 않았으며 exp 가 없으면 iat 로부터 {@link OidcFrontProperties#getLogoutTokenMaxAgeSeconds()} 안 ·
 * {@code events} 에 backchannel-logout URI · {@code nonce} 없음 · sub 또는 sid 존재 · {@code jti} 첫 사용({@link LogoutTokenReplayGuard}).
 * 통과하면 hub 에 알려 FE 세션을 만료하고 gate 의 판정 캐시를 비운다. 응답은 200/400(스펙: 실패는 400·501).
 * 1.0.1(3차 점검 M4): 종전의 aud 부분 일치({@code contains})·jti 미검사·exp 선택을 고쳤다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class BackchannelLogoutController {

    private static final Set<String> INTERNAL_CLIENTS_DEFAULT = Set.of("idem-gate", "idem-hub");
    /** Keycloak 과 gate 의 시계 차 허용 */
    static final long CLOCK_SKEW_SECONDS = 60;

    private final KeycloakJwksVerifier jwksVerifier;
    private final KeycloakProperties keycloak;
    private final OidcFrontProperties props;
    private final HubSessionClient hubSessionClient;
    private final AccessDecisionCache cache;
    private final LogoutTokenReplayGuard replayGuard;

    @PostMapping(value = "/api/v1/oidc/backchannel-logout", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> receive(@RequestParam("logout_token") String logoutToken) {
        String cid = UUID.randomUUID().toString();
        KeycloakIdTokenClaims claims;
        try {
            claims = jwksVerifier.verify(logoutToken, cid);
        } catch (Exception e) {
            log.warn("[BC-LOGOUT] logout_token 서명 검증 실패: {}", e.getMessage());
            return bad("invalid_signature");
        }
        String expectedIssuer = props.getIssuer();
        String internalIssuer = keycloak.getBaseUrl() + "/realms/" + keycloak.getRealm();
        if (claims.getIssuer() == null || !(claims.getIssuer().equals(expectedIssuer) || claims.getIssuer().equals(internalIssuer))) {
            return bad("invalid_issuer");
        }
        if (!claims.isBackchannelLogoutToken()) return bad("not_logout_token");
        if (claims.getNonce() != null) return bad("nonce_present");
        Set<String> internal = new java.util.HashSet<>(INTERNAL_CLIENTS_DEFAULT);
        if (keycloak.getClientId() != null) internal.add(keycloak.getClientId());
        boolean audOk = claims.getAudiences().stream().anyMatch(internal::contains);
        if (!audOk) return bad("invalid_audience");
        String aud = String.join(",", claims.getAudiences());
        String sub = claims.getSubject();
        String sid = claims.getSessionId();
        if ((sub == null || sub.isBlank()) && (sid == null || sid.isBlank())) return bad("missing_sub_sid");
        long now = System.currentTimeMillis() / 1000L;
        long iat = claims.getIssuedAt();
        long exp = claims.getExpiresAt();
        if (iat <= 0) return bad("missing_iat");
        if (iat > now + CLOCK_SKEW_SECONDS) return bad("iat_in_future");
        if (exp > 0 && exp + CLOCK_SKEW_SECONDS < now) return bad("expired");
        if (exp <= 0 && now - iat > props.getLogoutTokenMaxAgeSeconds()) return bad("expired");
        String jti = claims.getJwtId();
        if (jti == null || jti.isBlank()) return bad("missing_jti");
        long rememberFor = exp > 0 ? Math.max(1, exp + CLOCK_SKEW_SECONDS - now) : Math.max(1, iat + props.getLogoutTokenMaxAgeSeconds() - now);
        if (!replayGuard.firstUse(jti, java.time.Duration.ofSeconds(rememberFor))) {
            log.warn("[BC-LOGOUT] logout_token 재사용 거부 jti={} cid={}", jti, cid);
            return bad("replayed");
        }

        int evicted = cache.evictBySub(sub);
        int expired = hubSessionClient.notifyIdpLogout(sub, sid, "BACKCHANNEL_LOGOUT", cid);
        log.info("[BC-LOGOUT] 수신 처리: aud={} sid={} feExpired={} cacheEvicted={} cid={}", aud, sid != null, expired, evicted, cid);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(Map.of("ok", true));
    }

    private static ResponseEntity<Map<String, Object>> bad(String error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", error));
    }
}
