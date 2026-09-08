package io.github.hipstermin.idem.authz.api.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * SCIM 2.0 Group 리소스 — 연합 인가의 역할(role)에 매핑.
 *
 * <p>매핑 규약:
 * <ul>
 *   <li>{@code id} / {@code displayName} = {@code "{agencyCode}:{roleCode}"}</li>
 *   <li>{@code members[].value} = {@code qimUserId} (ACTIVE 부여 보유자)</li>
 * </ul>
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7643#section-4.2">RFC 7643 §4.2 Group</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScimGroup(
        List<String> schemas,
        String id,
        String displayName,
        List<ScimMember> members
) {
    public static final String SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    public static ScimGroup of(String id, String displayName, List<ScimMember> members) {
        return new ScimGroup(List.of(SCHEMA), id, displayName, members);
    }
}
