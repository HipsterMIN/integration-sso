package io.github.hipstermin.idem.authz.api.scim;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * SCIM 2.0 PatchOp 요청 (멤버 add/remove).
 *
 * <p>지원하는 부분집합:
 * <pre>
 * { "schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
 *   "Operations":[
 *     { "op":"add",    "path":"members", "value":[{"value":"user-1"}] },
 *     { "op":"remove", "path":"members", "value":[{"value":"user-2"}] }
 *   ] }
 * </pre>
 * {@code path}가 {@code members[value eq "user-x"]} 형태인 remove도 허용한다.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7644#section-3.5.2">RFC 7644 §3.5.2</a>
 */
public record ScimPatchOp(
        List<String> schemas,
        @JsonProperty("Operations") List<Operation> operations
) {
    public static final String SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    public record Operation(
            String op,
            String path,
            List<ScimMember> value
    ) {}
}
