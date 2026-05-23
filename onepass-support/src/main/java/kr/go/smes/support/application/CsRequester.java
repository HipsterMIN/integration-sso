package kr.go.smes.support.application;

import java.util.Locale;
import java.util.Set;

public record CsRequester(
    String agentId,
    String role,
    boolean authenticated
) {

    private static final Set<String> STAFF_ROLES = Set.of("CS_AGENT", "CS_LEAD", "SUPPORT_ADMIN", "AUDITOR");
    private static final Set<String> WRITE_ROLES = Set.of("CS_AGENT", "CS_LEAD", "SUPPORT_ADMIN");
    private static final Set<String> LEAD_ROLES = Set.of("CS_LEAD", "SUPPORT_ADMIN");

    public static CsRequester of(String agentIdHeader, String roleHeader) {
        String agentId = normalize(agentIdHeader);
        String role = normalizeRole(roleHeader);
        return new CsRequester(agentId, role, agentId != null);
    }

    public boolean staff() {
        return authenticated && STAFF_ROLES.contains(role);
    }

    public boolean writable() {
        return authenticated && WRITE_ROLES.contains(role);
    }

    public boolean lead() {
        return authenticated && LEAD_ROLES.contains(role);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String normalizeRole(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }
}
