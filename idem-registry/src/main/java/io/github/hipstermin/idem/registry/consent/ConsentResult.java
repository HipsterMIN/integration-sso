package io.github.hipstermin.idem.registry.consent;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 동의 처리 결과 / 동의 현황 응답 DTO
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConsentResult {

    private final String  recordId;
    private final String  qimUserId;
    private final String  consentType;
    private final String  versionId;
    private final String  versionTag;
    /** AGREED / WITHDRAWN */
    private final String  consentStatus;
    private final boolean required;
    private final Instant agreedAt;
    private final Instant withdrawnAt;
}
