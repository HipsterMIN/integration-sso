package io.github.hipstermin.idem.registry.conversion;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 전환 세션 결과 / 상태 응답 DTO
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversionSessionResult {

    private final String                 sessionId;
    private final String                 qimUserId;
    private final ConversionSessionState state;
    private final List<CandidateMember>  candidateMembers;
    private final List<String>           selectedAgencyCodes;
    private final List<String>           linkedAgencyCodes;
    private final Instant                expiresAt;
    private final Instant                createdAt;
    private final Instant                updatedAt;
}
