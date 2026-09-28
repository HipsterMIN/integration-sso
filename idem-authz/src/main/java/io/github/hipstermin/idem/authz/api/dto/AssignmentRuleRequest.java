package io.github.hipstermin.idem.authz.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 1.1 규칙 할당 생성 요청.
 *
 * @param ruleType    GROUP | ATTRIBUTE
 * @param matchKey    GROUP: {@code agencyCode:roleCode}, ATTRIBUTE: 속성명(authLevel·providerCode …)
 * @param matchValues ATTRIBUTE 허용 값 목록({@code *} = 값이 있으면 통과). GROUP 은 무시
 * @param expiresDays 실체화된 할당의 유효 일수(재평가 강제). null = 무기한
 */
public record AssignmentRuleRequest(
        @NotBlank(message = "agencyCode는 필수입니다.") String agencyCode,
        @NotBlank(message = "ruleType은 필수입니다.") String ruleType,
        @NotBlank(message = "matchKey는 필수입니다.") String matchKey,
        List<String> matchValues,
        @Positive(message = "expiresDays는 양수여야 합니다.") Integer expiresDays,
        String description
) {}
