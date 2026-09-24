package io.github.hipstermin.idem.hub.protocol.oidcrp;

import jakarta.validation.constraints.NotBlank;

/**
 * gate 가 토큰 교환(또는 userinfo) 시점에 묻는다 — "이 Keycloak 사용자가 이 client(서비스)에 들어가도 되는가".
 *
 * @param clientId         Keycloak client_id ({@code idem-svc-{code}})
 * @param sub              Keycloak id_token {@code sub}
 * @param identityProvider id_token {@code identity_provider} 클레임 (브로커 IdP alias, 없을 수 있음 — 로컬 계정)
 * @param acr              id_token {@code acr} (없으면 L1)
 * @param sid              Keycloak 세션 ID (감사·캐시 키용, 선택)
 */
public record OidcRpAccessRequest(@NotBlank String clientId, @NotBlank String sub, String identityProvider, String acr,
                                  String sid, String correlationId) {}
