package io.github.hipstermin.idem.authz.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 1.1: hub 발급 경로의 접근 평가 요청 — {@code POST /users/{qimUserId}/access}.
 * {@code attributes} 는 규칙(ATTRIBUTE) 판정에 쓰는 <b>비-PII 발급 컨텍스트</b>(authLevel·providerCode …)만 싣는다.
 */
public record AccessEvaluateRequest(
        @NotBlank(message = "agencyCode는 필수입니다.") String agencyCode,
        Map<String, String> attributes
) {}
