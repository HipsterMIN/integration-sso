package kr.go.smes.authz.domain;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** {@link AuthzRoleEntity}의 복합 키 (agency_code, role_code). */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class AuthzRoleId implements Serializable {
    private String agencyCode;
    private String roleCode;
}
