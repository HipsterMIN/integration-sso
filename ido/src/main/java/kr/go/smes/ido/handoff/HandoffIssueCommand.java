package kr.go.smes.ido.handoff;

import kr.go.smes.common.domain.AuthResult;
import lombok.Builder;
import lombok.Getter;

/**
 * Handoff Issue 커맨드
 * 설계서 11.4 / 16.1절 참조
 */
@Getter
@Builder
public class HandoffIssueCommand {

    private final String correlationId;
    private final String agencyCode;
    private final String qimUserId;
    private final String authResultId;
    private final AuthResult.AuthLevel authLevel;
    private final String providerCode;

    /** returnUrl 화이트리스트 검증 후 전달 */
    private final String callbackUrl;
}
