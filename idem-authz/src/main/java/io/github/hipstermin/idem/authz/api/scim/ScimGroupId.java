package io.github.hipstermin.idem.authz.api.scim;

import io.github.hipstermin.idem.authz.api.AuthzErrorCode;
import io.github.hipstermin.idem.authz.api.AuthzException;

/**
 * SCIM Group id ↔ (agencyCode, roleCode) 변환.
 * 형식: {@code "{agencyCode}:{roleCode}"} (첫 ':' 기준 분리).
 */
public record ScimGroupId(String agencyCode, String roleCode) {

    public static ScimGroupId parse(String id) {
        if (id == null || id.isBlank()) {
            throw new AuthzException(AuthzErrorCode.INVALID_REQUEST, "Group id가 비어 있습니다.");
        }
        int sep = id.indexOf(':');
        if (sep <= 0 || sep == id.length() - 1) {
            throw new AuthzException(AuthzErrorCode.INVALID_REQUEST,
                    "Group id 형식 오류(기대: 'agencyCode:roleCode'): " + id);
        }
        return new ScimGroupId(id.substring(0, sep), id.substring(sep + 1));
    }

    public String value() {
        return agencyCode + ":" + roleCode;
    }
}
