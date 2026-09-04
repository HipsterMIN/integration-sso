package kr.go.smes.ido.sso;

import jakarta.servlet.http.Cookie;
import kr.go.smes.common.domain.CastToken;
import kr.go.smes.common.domain.HandoffTicket;
import kr.go.smes.ido.fe.session.FeSessionService;
import kr.go.smes.ido.handoff.HandoffService;
import kr.go.smes.ido.sso.CrossAgencySsoController.CastIssueResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * Sprint α-3 / F4.4 — CrossAgencySsoController POST 자동 제출 폼 회귀 테스트
 *
 * <p>검증 범위:
 * <ul>
 *   <li>{@code redirectUrl}/{@code ssoEntryUrl} 응답 필드에 castToken(JWT) 미포함</li>
 *   <li>{@code formHtml} 응답 필드는 POST form + hidden input으로 castToken 전달</li>
 *   <li>HTML escape 적용 (XSS 방지)</li>
 *   <li>castToken 자체는 응답 body의 {@code castToken} 필드로 별도 노출 (legacy SDK 호환)</li>
 * </ul>
 *
 * <p>위협 모델:
 * <ul>
 *   <li>이전: {@code https://x.agency.go.kr/sso-entry?onepass_sso=<JWT>} — Referer/history/access-log 유출</li>
 *   <li>현재: URL에는 JWT 미포함, hidden POST body로 전달 → 위 채널로 유출 차단</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("[F4.4] CrossAgencySsoController POST 자동 제출 폼")
class CrossAgencySsoControllerTest {

    @Mock CastTokenService  castTokenService;
    @Mock HandoffService    handoffService;
    @Mock FeSessionService  feSessionService;

    private static final String FE_SESSION_ID  = "fe-session-001";
    private static final String CORRELATION_ID = "corr-cast-001";
    private static final String TARGET_AGENCY  = "AGENCY_B";
    private static final String FAKE_JWT       = "eyJhbGciOiJFZERTQSJ9.eyJqdGkiOiJqdGktMTIzIn0.signature-abc";
    private static final String JTI            = "jti-cast-001";

    private CastToken buildCastToken(String jwt) {
        Instant now = Instant.now();
        return new CastToken(
                JTI, "qim-user-001", "AGENCY_A", TARGET_AGENCY, "MEDIUM",
                now, now.plusSeconds(300), jwt, java.util.List.of()
        );
    }

    private CrossAgencySsoController newSut() {
        return new CrossAgencySsoController(castTokenService, handoffService, feSessionService);
    }

    private MockHttpServletRequest requestWithFeSessionCookie() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("Fe-Session-Id", FE_SESSION_ID));
        return req;
    }

    // ════════════════════════════════════════════════════════════════════════
    // [F4.4] URL 쿼리에 JWT 미포함
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("[F4.4] castToken은 URL에 노출되지 않는다")
    class UrlDoesNotLeakToken {

        @Test
        @DisplayName("[F4.4] redirectUrl/ssoEntryUrl에 castToken JWT 부분이 포함되지 않는다")
        void issue_redirectUrl_doesNotContainJwt() {
            given(castTokenService.issue(anyString(), anyString(), anyString()))
                    .willReturn(buildCastToken(FAKE_JWT));

            ResponseEntity<CastIssueResponse> resp = newSut().issue(
                    TARGET_AGENCY, CORRELATION_ID, requestWithFeSessionCookie());

            CastIssueResponse body = resp.getBody();
            assertThat(body).isNotNull();
            assertThat(body.redirectUrl())
                    .as("redirectUrl에 castToken JWT가 포함되면 Referer/history/log로 유출됨 (F4.4 위반)")
                    .doesNotContain(FAKE_JWT)
                    .doesNotContain("onepass_sso=");
            assertThat(body.ssoEntryUrl())
                    .doesNotContain(FAKE_JWT)
                    .doesNotContain("onepass_sso=");
        }

        @Test
        @DisplayName("[F4.4] redirectUrl과 ssoEntryUrl은 동일 값 (alias)")
        void issue_redirectUrl_equalsSsoEntryUrl() {
            given(castTokenService.issue(anyString(), anyString(), anyString()))
                    .willReturn(buildCastToken(FAKE_JWT));

            ResponseEntity<CastIssueResponse> resp = newSut().issue(
                    TARGET_AGENCY, CORRELATION_ID, requestWithFeSessionCookie());

            CastIssueResponse body = resp.getBody();
            assertThat(body.redirectUrl()).isEqualTo(body.ssoEntryUrl());
        }

        @Test
        @DisplayName("[F4.4] ssoEntryUrl은 기관 B의 /sso-entry 진입점만 가리킨다 (쿼리 파라미터 없음)")
        void issue_ssoEntryUrl_isCleanEntrypoint() {
            given(castTokenService.issue(anyString(), anyString(), anyString()))
                    .willReturn(buildCastToken(FAKE_JWT));

            ResponseEntity<CastIssueResponse> resp = newSut().issue(
                    "AGENCY_B", CORRELATION_ID, requestWithFeSessionCookie());

            String url = resp.getBody().ssoEntryUrl();
            assertThat(url)
                    .startsWith("https://")
                    .endsWith("/sso-entry")
                    .doesNotContain("?")
                    .doesNotContain("=");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [F4.4] formHtml은 POST + hidden field로 castToken 전달
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("[F4.4] formHtml — POST 자동 제출 폼")
    class FormHtmlAutoSubmit {

        @Test
        @DisplayName("[F4.4] formHtml은 method=POST + onepass_sso hidden field 포함")
        void formHtml_isAutoSubmittingPost() {
            given(castTokenService.issue(anyString(), anyString(), anyString()))
                    .willReturn(buildCastToken(FAKE_JWT));

            ResponseEntity<CastIssueResponse> resp = newSut().issue(
                    TARGET_AGENCY, CORRELATION_ID, requestWithFeSessionCookie());

            String html = resp.getBody().formHtml();
            assertThat(html)
                    .as("formHtml은 자동 POST 제출되어야 함")
                    .containsIgnoringCase("method=\"POST\"")
                    .contains("document.forms[0].submit()")
                    .contains("type=\"hidden\"")
                    .contains("name=\"onepass_sso\"")
                    .contains("value=\"" + FAKE_JWT + "\"");
        }

        @Test
        @DisplayName("[F4.4] formHtml의 form action은 기관 B 진입점 URL (castToken 미포함)")
        void formHtml_actionPointsToSsoEntryUrl() {
            given(castTokenService.issue(anyString(), anyString(), anyString()))
                    .willReturn(buildCastToken(FAKE_JWT));

            ResponseEntity<CastIssueResponse> resp = newSut().issue(
                    TARGET_AGENCY, CORRELATION_ID, requestWithFeSessionCookie());

            CastIssueResponse body = resp.getBody();
            String html = body.formHtml();
            assertThat(html)
                    .contains("action=\"" + body.ssoEntryUrl() + "\"");
        }

        @Test
        @DisplayName("[F4.4] formHtml은 noscript fallback 버튼 포함 (JS 비활성 환경 지원)")
        void formHtml_containsNoscriptFallback() {
            given(castTokenService.issue(anyString(), anyString(), anyString()))
                    .willReturn(buildCastToken(FAKE_JWT));

            ResponseEntity<CastIssueResponse> resp = newSut().issue(
                    TARGET_AGENCY, CORRELATION_ID, requestWithFeSessionCookie());

            String html = resp.getBody().formHtml();
            assertThat(html)
                    .contains("<noscript>")
                    .containsIgnoringCase("<button type=\"submit\"");
        }

        @Test
        @DisplayName("[F4.4] HTML escape — castToken에 특수문자가 있어도 폼이 깨지지 않는다")
        void formHtml_escapesSpecialChars() {
            // 실제 JWT는 base64url이지만 방어적으로 특수문자 포함 토큰을 가정
            String maliciousToken = "tok\"><script>alert(1)</script>";
            given(castTokenService.issue(anyString(), anyString(), anyString()))
                    .willReturn(buildCastToken(maliciousToken));

            ResponseEntity<CastIssueResponse> resp = newSut().issue(
                    TARGET_AGENCY, CORRELATION_ID, requestWithFeSessionCookie());

            String html = resp.getBody().formHtml();
            // 원본 스크립트 문자열이 그대로 들어가면 XSS
            assertThat(html)
                    .as("HTML escape 실패 시 hidden field 탈출(XSS) 가능 — F4.4 핵심 가드")
                    .doesNotContain("<script>alert(1)</script>")
                    .contains("&lt;script&gt;")
                    .contains("&quot;");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [F4.4] castToken은 응답 JSON body의 castToken 필드로만 전달 (SDK용)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("[F4.4] castToken 필드 — SDK용 별도 노출")
    class CastTokenFieldExposure {

        @Test
        @DisplayName("[F4.4] castToken 필드는 형식상 유지 (기관 SDK 서버사이드 검증용)")
        void issue_castToken_isExposedForSdkConsumption() {
            given(castTokenService.issue(anyString(), anyString(), anyString()))
                    .willReturn(buildCastToken(FAKE_JWT));

            ResponseEntity<CastIssueResponse> resp = newSut().issue(
                    TARGET_AGENCY, CORRELATION_ID, requestWithFeSessionCookie());

            assertThat(resp.getBody().castToken()).isEqualTo(FAKE_JWT);
        }

        @Test
        @DisplayName("[F4.4] expiresInSeconds = CastToken.TTL_SECONDS")
        void issue_expiresInSeconds_isTtl() {
            given(castTokenService.issue(anyString(), anyString(), anyString()))
                    .willReturn(buildCastToken(FAKE_JWT));

            ResponseEntity<CastIssueResponse> resp = newSut().issue(
                    TARGET_AGENCY, CORRELATION_ID, requestWithFeSessionCookie());

            assertThat(resp.getBody().expiresInSeconds()).isEqualTo(CastToken.TTL_SECONDS);
        }
    }
}
