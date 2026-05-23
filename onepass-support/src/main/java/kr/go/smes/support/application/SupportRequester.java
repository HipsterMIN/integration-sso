package kr.go.smes.support.application;

public record SupportRequester(
    String userId,
    boolean authenticated,
    boolean admin
) {

    public static SupportRequester of(String userIdHeader, String roleHeader) {
        String trimmed = userIdHeader == null ? null : userIdHeader.trim();
        boolean authenticated = trimmed != null && !trimmed.isBlank();
        boolean admin = roleHeader != null && roleHeader.toUpperCase().contains("ADMIN");
        return new SupportRequester(trimmed, authenticated, admin);
    }
}

