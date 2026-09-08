package io.github.hipstermin.idem.registry.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 사용자 조회/등록 응답 DTO
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponse {

    private final String  qimUserId;
    private final String  status;
    private final String  nameMasked;
    private final String  mobileMasked;
    private final String  nationalityType;
    private final Short   birthYear;
    private final String  gender;
    private final boolean isNew;        // true = 신규 생성, false = 기존 사용자 반환
    private final Instant createdAt;
    private final Instant updatedAt;
}
