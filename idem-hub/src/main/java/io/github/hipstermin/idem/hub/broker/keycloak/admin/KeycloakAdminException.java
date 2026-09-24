package io.github.hipstermin.idem.hub.broker.keycloak.admin;

/** Keycloak Admin REST 호출 실패 — 호출자가 E-IDO-122 등으로 바꿔 던진다. */
public class KeycloakAdminException extends RuntimeException {
    public KeycloakAdminException(String message) {
        super(message);
    }

    public KeycloakAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
