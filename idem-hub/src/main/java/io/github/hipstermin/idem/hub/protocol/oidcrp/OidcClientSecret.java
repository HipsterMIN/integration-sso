package io.github.hipstermin.idem.hub.protocol.oidcrp;

/** secret 회전 응답 — 이 응답 밖에서는 secret 을 다시 볼 수 없다(Idem 은 저장하지 않는다). */
public record OidcClientSecret(String serviceCode, String clientId, String clientSecret, String issuer, String discoveryUrl) {}
