package io.github.hipstermin.idem.hub.protocol.oidcrp;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.broker.keycloak.admin.KeycloakAdminClient;
import io.github.hipstermin.idem.hub.broker.keycloak.admin.KeycloakAdminException;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Service Profile → Keycloak client 프로비저닝 (S6).
 *
 * <p>사람이 Keycloak 콘솔에 손대는 단계를 없앤다. {@code protocol.type=OIDC_RP} 프로파일이 저장될 때 같은 트랜잭션 안에서
 * client {@code idem-svc-{code}} 를 만들거나 맞추고, 실패하면 {@link PlatformErrorCode#IDEM_HUB_OIDC_PROVISION_FAILED} 로
 * 저장 자체를 되돌린다. 유형이 OIDC_RP 에서 벗어나면 client 를 지우지 않고 비활성으로 남긴다(감사·복구).
 *
 * <p>프로비저닝되는 client 의 고정 규칙 — 기관이 바꿀 수 없다:
 * <ul>
 *   <li>confidential · Authorization Code 만 (implicit·password·device·CIBA·service account 없음)</li>
 *   <li>PKCE S256 강제 ({@code pkce.code.challenge.method})</li>
 *   <li>{@code fullScopeAllowed=false} — realm 역할이 토큰에 새지 않는다</li>
 *   <li>Back-Channel Logout 세션 필수 — {@code backchannelLogoutUri} 가 있을 때</li>
 *   <li>클레임 매퍼: {@code identity_provider}(브로커 세션 노트) · {@code idem_service}(고정값)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OidcRpClientProvisioner {

    public static final String AUDIT_CLIENT_PROVISIONED = "OIDC_CLIENT_PROVISIONED";
    public static final String AUDIT_CLIENT_DISABLED = "OIDC_CLIENT_DISABLED";
    public static final String AUDIT_SECRET_ROTATED = "OIDC_CLIENT_SECRET_ROTATED";
    static final String ATTR_SERVICE_CODE = "idem.service.code";
    static final String MAPPER_IDENTITY_PROVIDER = "idem-identity-provider";
    static final String MAPPER_SERVICE = "idem-service";

    private final KeycloakAdminClient keycloak;
    private final OidcRpProperties props;
    private final AuditLogPublisher auditLogPublisher;

    /** 저장 직후 호출 — OIDC_RP 면 upsert, 아니면(예전에 OIDC_RP 였던 경우) 비활성. */
    public OidcClientStatus sync(ServiceProfile profile, String adminId, String correlationId) {
        String code = profile.service().code();
        String clientId = props.clientIdFor(code);
        boolean wantsOidc = profile.protocol() != null && profile.protocol().type() == IntegrationType.OIDC_RP;
        try {
            if (wantsOidc && !props.isEnabled()) {
                throw new PlatformException(PlatformErrorCode.IDO_OIDC_PROVISION_FAILED, correlationId,
                        "idem.hub.oidc-rp.enabled=false — 이 설치본은 OIDC_RP 프로비저닝이 꺼져 있습니다");
            }
            Optional<Map<String, Object>> existing = wantsOidc || props.isEnabled()
                    ? keycloak.findClientByClientId(clientId) : Optional.empty();
            if (!wantsOidc) {
                if (existing.isPresent() && Boolean.TRUE.equals(existing.get().get("enabled"))) {
                    keycloak.updateClient(uuid(existing.get()), Map.of("enabled", false));
                    audit(AUDIT_CLIENT_DISABLED, code, adminId);
                    log.info("[OidcRp] client 비활성: service={} clientId={} cid={}", code, clientId, correlationId);
                    return status(code, clientId, existing.get()).withEnabled(false);
                }
                return existing.map(c -> status(code, clientId, c)).orElse(OidcClientStatus.absent(code, clientId, props));
            }
            Map<String, Object> rep = representation(profile, clientId);
            String uuid;
            if (existing.isPresent()) {
                uuid = uuid(existing.get());
                keycloak.updateClient(uuid, rep);
            } else {
                rep.put("protocolMappers", mappers(code));
                uuid = keycloak.createClient(rep);
            }
            ensureMappers(uuid, code);
            audit(AUDIT_CLIENT_PROVISIONED, code, adminId);
            log.info("[OidcRp] client {}: service={} clientId={} cid={}", existing.isPresent() ? "갱신" : "생성", code, clientId, correlationId);
            return new OidcClientStatus(code, clientId, props.getIssuer(), props.discoveryUrl(), true, profile.isActive(),
                    profile.protocol().oidc().redirectUris());
        } catch (PlatformException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("[OidcRp] 프로비저닝 실패 — 프로파일 저장을 되돌린다: service={} cid={} err={}", code, correlationId, e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_OIDC_PROVISION_FAILED, correlationId, e.getMessage());
        }
    }

    public OidcClientStatus status(String serviceCode, String correlationId) {
        String clientId = props.clientIdFor(serviceCode);
        try {
            return keycloak.findClientByClientId(clientId)
                    .map(c -> status(serviceCode, clientId, c))
                    .orElse(OidcClientStatus.absent(serviceCode, clientId, props));
        } catch (KeycloakAdminException e) {
            throw new PlatformException(PlatformErrorCode.IDO_OIDC_PROVISION_FAILED, correlationId, e.getMessage());
        }
    }

    /** secret 회전 — 새 값은 이 응답에서만 한 번 보인다. client 가 없으면 404 성격의 E-IDO-123. */
    public OidcClientSecret rotateSecret(String serviceCode, String adminId, String correlationId) {
        String clientId = props.clientIdFor(serviceCode);
        try {
            Map<String, Object> client = keycloak.findClientByClientId(clientId)
                    .orElseThrow(() -> new PlatformException(PlatformErrorCode.IDO_OIDC_CLIENT_UNKNOWN, correlationId,
                            "프로비저닝된 client 가 없습니다: " + clientId + " — 프로파일을 OIDC_RP 로 먼저 저장하세요"));
            String secret = keycloak.regenerateClientSecret(uuid(client));
            audit(AUDIT_SECRET_ROTATED, serviceCode, adminId);
            log.info("[OidcRp] secret 회전: service={} clientId={} adminId={} cid={}", serviceCode, clientId, adminId, correlationId);
            return new OidcClientSecret(serviceCode, clientId, secret, props.getIssuer(), props.discoveryUrl());
        } catch (PlatformException e) {
            throw e;
        } catch (KeycloakAdminException e) {
            throw new PlatformException(PlatformErrorCode.IDO_OIDC_PROVISION_FAILED, correlationId, e.getMessage());
        }
    }

    // ── representation ─────────────────────────────────────────────────────

    Map<String, Object> representation(ServiceProfile profile, String clientId) {
        ServiceProfile.Oidc oidc = profile.protocol().oidc();
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("clientId", clientId);
        rep.put("name", profile.service().name() != null ? profile.service().name() : profile.service().code());
        rep.put("description", "Idem 이 Service Profile 에서 프로비저닝 — 콘솔에서 직접 고치지 마세요");
        rep.put("enabled", profile.isActive());
        rep.put("protocol", "openid-connect");
        rep.put("publicClient", false);
        rep.put("bearerOnly", false);
        rep.put("clientAuthenticatorType", "client-secret");
        rep.put("standardFlowEnabled", true);
        rep.put("implicitFlowEnabled", false);
        rep.put("directAccessGrantsEnabled", false);
        rep.put("serviceAccountsEnabled", false);
        rep.put("fullScopeAllowed", false);
        rep.put("consentRequired", false);
        rep.put("redirectUris", new ArrayList<>(oidc.redirectUris()));
        rep.put("webOrigins", List.of());
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("pkce.code.challenge.method", "S256");
        attrs.put(ATTR_SERVICE_CODE, profile.service().code());
        attrs.put("oauth2.device.authorization.grant.enabled", "false");
        attrs.put("oidc.ciba.grant.enabled", "false");
        attrs.put("post.logout.redirect.uris", oidc.postLogoutRedirectUris() == null || oidc.postLogoutRedirectUris().isEmpty()
                ? "" : String.join("##", oidc.postLogoutRedirectUris()));
        attrs.put("backchannel.logout.url", oidc.backchannelLogoutUri() != null ? oidc.backchannelLogoutUri() : "");
        attrs.put("backchannel.logout.session.required", oidc.backchannelLogoutUri() != null ? "true" : "false");
        attrs.put("backchannel.logout.revoke.offline.tokens", "false");
        // token endpoint 인증 방식은 Keycloak 이 client-secret 인증기에서 basic/post 를 모두 받는다 — 프로파일 값은 안내용
        attrs.put("idem.client.auth.method", oidc.clientAuthMethodOrDefault());
        rep.put("attributes", attrs);
        return rep;
    }

    static List<Map<String, Object>> mappers(String serviceCode) {
        return List.of(
                Map.of("name", MAPPER_IDENTITY_PROVIDER, "protocol", "openid-connect",
                        "protocolMapper", "oidc-usersessionmodel-note-mapper",
                        "config", Map.of("user.session.note", "identity_provider", "claim.name", "identity_provider",
                                "jsonType.label", "String", "id.token.claim", "true", "access.token.claim", "true",
                                "userinfo.token.claim", "true")),
                Map.of("name", MAPPER_SERVICE, "protocol", "openid-connect",
                        "protocolMapper", "oidc-hardcoded-claim-mapper",
                        "config", Map.of("claim.name", "idem_service", "claim.value", serviceCode,
                                "jsonType.label", "String", "id.token.claim", "true", "access.token.claim", "true",
                                "userinfo.token.claim", "true")));
    }

    private void ensureMappers(String uuid, String serviceCode) {
        Set<String> present = new java.util.HashSet<>();
        for (Map<String, Object> m : keycloak.getProtocolMappers(uuid)) {
            Object n = m.get("name");
            if (n != null) present.add(n.toString());
        }
        for (Map<String, Object> m : mappers(serviceCode)) {
            if (!present.contains(m.get("name").toString())) keycloak.addProtocolMapper(uuid, m);
        }
    }

    @SuppressWarnings("unchecked")
    private OidcClientStatus status(String code, String clientId, Map<String, Object> client) {
        List<String> uris = client.get("redirectUris") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        return new OidcClientStatus(code, clientId, props.getIssuer(), props.discoveryUrl(), true,
                Boolean.TRUE.equals(client.get("enabled")), uris);
    }

    private static String uuid(Map<String, Object> client) {
        Object id = client.get("id");
        if (id == null) throw new KeycloakAdminException("Keycloak client 응답에 id 가 없음");
        return id.toString();
    }

    private void audit(String action, String serviceCode, String adminId) {
        try {
            auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                    .eventCategory(AuditLogEvent.CATEGORY_SYSTEM)
                    .eventAction(action)
                    .actorType(AuditLogEvent.ACTOR_SYSTEM)
                    .actorId(adminId)
                    .resourceType("OIDC_CLIENT")
                    .resourceId(props.clientIdFor(serviceCode))
                    .agencyCode(serviceCode)
                    .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                    .build());
        } catch (RuntimeException e) {
            log.warn("[OidcRp] 감사 로그 실패 (비치명적): action={} err={}", action, e.getMessage());
        }
    }
}
