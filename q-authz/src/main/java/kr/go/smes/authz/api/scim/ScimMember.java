package kr.go.smes.authz.api.scim;

import com.fasterxml.jackson.annotation.JsonInclude;

/** SCIM Group 멤버 — {@code value}는 qimUserId. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScimMember(
        String value,
        String display
) {
    public static ScimMember of(String value) {
        return new ScimMember(value, null);
    }
}
