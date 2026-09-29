package io.github.hipstermin.idem.authz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 1.1 규칙 할당 ({@code idem_authz.authz_assignment_rule}).
 *
 * <p>"이 조건을 만족하는 사용자는 이 Service 에 할당된 것으로 본다". 조건은 {@link AssignmentRuleType} 두 가지뿐이고
 * 평가는 {@code AssignmentRuleService} 가 hub 의 접근 평가 시점에 한다(할당을 실체화 — source=RULE, rule_id).
 */
@Entity
@Table(name = "authz_assignment_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthzAssignmentRuleEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "agency_code", nullable = false, length = 50)
    private String agencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 16)
    private AssignmentRuleType ruleType;

    @Column(name = "match_key", nullable = false, length = 160)
    private String matchKey;

    /** ATTRIBUTE 전용 — 콤마 구분 허용 값. {@code *} 는 "값이 있으면 통과". */
    @Column(name = "match_values")
    private String matchValues;

    @Column(name = "expires_days")
    private Integer expiresDays;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "disabled_by", length = 128)
    private String disabledBy;

    public List<String> matchValueList() {
        if (matchValues == null || matchValues.isBlank()) return List.of();
        return Arrays.stream(matchValues.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    /** ATTRIBUTE 규칙 판정 — 값 없음은 항상 불일치, {@code *} 는 값이 있으면 통과, 그 외 정확히 일치(대소문자 구분). */
    public boolean matchesAttribute(String value) {
        if (value == null || value.isBlank()) return false;
        for (String allowed : matchValueList()) {
            if ("*".equals(allowed) || allowed.equals(value)) return true;
        }
        return false;
    }

    public String summary() {
        return ruleType + " " + matchKey + (matchValues != null ? " in [" + matchValues + "]" : "")
                + (expiresDays != null ? " expires=" + expiresDays + "d" : "");
    }
}
