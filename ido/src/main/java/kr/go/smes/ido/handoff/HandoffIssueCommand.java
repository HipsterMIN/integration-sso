package kr.go.smes.ido.handoff;

import kr.go.smes.common.domain.AuthResult;
import lombok.Builder;
import lombok.Getter;

/**
 * Handoff Issue 커맨드 (v2.0)
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

    /** Callback URL — 화이트리스트 검증 대상 */
    private final String callbackUrl;

    /** Redirect URI — 화이트리스트 검증 대상 (callbackUrl alias) */
    private final String redirectUri;
}
