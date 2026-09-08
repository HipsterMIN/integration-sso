package io.github.hipstermin.idem.authz.api.scim;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * SCIM 2.0 ListResponse 메시지.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7644#section-3.4.2">RFC 7644 §3.4.2</a>
 */
public record ScimListResponse<T>(
        List<String> schemas,
        int totalResults,
        int startIndex,
        int itemsPerPage,
        @JsonProperty("Resources") List<T> resources
) {
    public static final String SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";

    public static <T> ScimListResponse<T> of(List<T> resources) {
        return new ScimListResponse<>(List.of(SCHEMA), resources.size(), 1, resources.size(), resources);
    }
}
