package kr.go.smes.ido.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 보안 HTTP 응답 헤더 필터 (CSP 포함)
 *
 * <p>모든 HTTP 응답에 보안 헤더를 추가하여 주요 웹 취약점을 방어한다.
 *
 * <p><b>적용 헤더</b>:
 * <ul>
 *   <li><b>Content-Security-Policy</b>: XSS 방어 — 허가된 출처만 스크립트/리소스 로드 허용</li>
 *   <li><b>X-Content-Type-Options</b>: MIME 스니핑 방지</li>
 *   <li><b>X-Frame-Options</b>: Clickjacking 방어 (DENY)</li>
 *   <li><b>Strict-Transport-Security</b>: HTTPS 강제 (max-age=1년)</li>
 *   <li><b>Referrer-Policy</b>: 외부 요청 시 Origin만 전달 (PII 보호)</li>
 *   <li><b>Permissions-Policy</b>: 불필요한 브라우저 기능 비활성화</li>
 *   <li><b>Cache-Control</b>: API 응답 캐시 비활성화 (개인정보 보호)</li>
 * </ul>
 *
 * <p><b>CSP 정책 설계 원칙</b>:
 * <ul>
 *   <li>인증 API 서버이므로 스크립트/스타일 외부 로드 불허 ({@code 'none'})</li>
 *   <li>API 응답만 하므로 form-action, frame-ancestors도 차단</li>
 *   <li>운영 환경 배포 시 {@code ido.csp.report-uri}로 위반 보고 설정 권장</li>
 * </ul>
 *
 * <p><b>참고</b>: onepass-fe (React SPA) 전용 CSP는
 * Nginx 설정 파일({@code infra/nginx/nginx-fe.conf})에서 별도 관리한다.
 * 이 필터는 IdO API 서버 응답 헤더 전용이다.
 */
@Component
@Order(1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /**
     * CSP 위반 보고 URI (운영 환경에서 설정. 기본 비활성화).
     * 예: https://csp-report.example.com/report
     */
    @Value("${ido.csp.report-uri:}")
    private String cspReportUri;

    /**
     * 허용된 API 도메인 Origin (CORS + CSP connect-src 일치).
     * 예: https://onepass.example.go.kr
     */
    @Value("${ido.csp.allowed-origin:}")
    private String allowedOrigin;

    // ── 필터 실행 ──────────────────────────────────────────────────────────

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // ① Content-Security-Policy
        response.setHeader("Content-Security-Policy", buildCspDirective());

        // ② MIME 스니핑 방지 (브라우저가 Content-Type을 임의 변경하지 못하도록)
        response.setHeader("X-Content-Type-Options", "nosniff");

        // ③ Clickjacking 방어 — iframe 내 로드 전면 차단
        response.setHeader("X-Frame-Options", "DENY");

        // ④ HSTS — HTTPS 강제 1년 + 서브도메인 포함 (PRELOAD 대비)
        response.setHeader("Strict-Transport-Security",
                "max-age=31536000; includeSubDomains");

        // ⑤ Referrer-Policy — 외부 사이트 이동 시 Origin만 전달 (URL 경로/파라미터 노출 방지)
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // ⑥ Permissions-Policy — 불필요한 브라우저 기능 비활성화
        //    인증 API 서버에서는 카메라/마이크/위치정보 API 접근 불필요
        response.setHeader("Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=(), usb=()");

        // ⑦ Cache-Control — API 응답 캐시 비활성화 (개인정보/세션 응답 보호)
        //    Actuator/헬스체크 엔드포인트는 캐시 허용 대상에서 제외
        if (!isHealthCheckRequest(request)) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
        }

        filterChain.doFilter(request, response);
    }

    // ── private ────────────────────────────────────────────────────────────

    /**
     * CSP 지시어 문자열 조합.
     *
     * <p>IdO API 서버 특성에 맞는 엄격한 정책:
     * <ul>
     *   <li>default-src 'none': 기본적으로 모든 리소스 로드 차단</li>
     *   <li>connect-src: API 자기 자신 및 허용된 Origin만 허용</li>
     *   <li>frame-ancestors 'none': iframe 삽입 전면 차단</li>
     *   <li>form-action 'none': API 서버에서 form POST 대상 없음</li>
     *   <li>base-uri 'none': base 태그 조작 방지</li>
     * </ul>
     */
    private String buildCspDirective() {
        StringBuilder csp = new StringBuilder();

        // default-src: 기본 모든 리소스 로드 차단
        csp.append("default-src 'none'");

        // connect-src: fetch/XHR 허용 대상 (API 자기 자신 + 허용 Origin)
        csp.append("; connect-src 'self'");
        if (allowedOrigin != null && !allowedOrigin.isBlank()) {
            csp.append(" ").append(allowedOrigin.trim());
        }

        // frame-ancestors: Clickjacking 방어 (X-Frame-Options 보완)
        csp.append("; frame-ancestors 'none'");

        // form-action: form 제출 대상 제한 (CSRF 방어 보완)
        csp.append("; form-action 'none'");

        // base-uri: base 태그 조작 방지
        csp.append("; base-uri 'none'");

        // CSP 위반 보고 URI (운영 시 설정)
        if (cspReportUri != null && !cspReportUri.isBlank()) {
            csp.append("; report-uri ").append(cspReportUri.trim());
        }

        return csp.toString();
    }

    /**
     * 헬스체크/Actuator 요청 여부 판별.
     * 헬스체크 응답은 캐시 헤더를 설정하지 않는다.
     */
    private boolean isHealthCheckRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && (
                path.equals("/actuator/health") ||
                path.equals("/health") ||
                path.startsWith("/actuator/")
        );
    }
}
