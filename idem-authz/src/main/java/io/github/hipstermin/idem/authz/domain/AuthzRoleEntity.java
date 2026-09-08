package io.github.hipstermin.idem.authz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 역할 카탈로그 엔티티 — 기관별 네임스페이스의 역할 정의.
 *
 * <p>역할 코드의 의미는 플랫폼이 해석하지 않는 불투명 문자열이다.
 * {@code agency_code='PLATFORM'} 은 전역 역할(소수 고정)을 의미한다.
 */
@Entity
@Table(name = "authz_role")
@IdClass(AuthzRoleId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthzRoleEntity {

    @Id
    @Column(name = "agency_code", nullable = false, length = 50)
    private String agencyCode;

    @Id
    @Column(name = "role_code", nullable = false, length = 64)
    private String roleCode;

    @Column(name = "name", length = 128)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "is_assignable", nullable = false)
    private boolean assignable;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 128)
    private String createdBy;
}
