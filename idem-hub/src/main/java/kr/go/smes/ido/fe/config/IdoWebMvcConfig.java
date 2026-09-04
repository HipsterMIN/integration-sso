package kr.go.smes.ido.fe.config;

import kr.go.smes.ido.config.HandoffAgencyKeyInterceptor;
import kr.go.smes.ido.ratelimit.AuthRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * IdO WebMvc CORS 설정
 * 설계서 §12.2 참조
 *
 * <p>onepass-fe Spring Boot BFF 제거에 따라
 * React SPA(port 3000/3001) ↔ IdO(port 8083) 간 CORS 허용.
 *
 * <p>운영 환경에서는 Nginx 리버스 프록시를 통해 same-origin 으로
 * 서빙하므로 CORS 불필요 (ido.cors.enabled=false).
 *
 * <p><b>S7-T2 추가 (NICE/OACX 본인인증):</b>
 * {@code /api/v1/auth/**} CORS 매핑 추가.
 * Q1=B 결정: API Key 인터셉터 없음 — Nginx same-origin으로 보안 처리.
 * 개발환경: webpack proxy (FE port 3000 → ido port 8083) 통해 same-origin 처리.
 * 운영환경: Nginx가 FE와 ido를 같은 origin으로 묶음.
 */
@Configuration
@RequiredArgsConstructor
public class IdoWebMvcConfig implements WebMvcConfigurer {

    private final HandoffAgencyKeyInterceptor handoffAgencyKeyInterceptor;
    private final AuthRateLimitInterceptor authRateLimitInterceptor;
    private final InternalCallerAuthInterceptor internalCallerAuthInterceptor;

    @Value("${ido.cors.enabled:true}")
    private boolean corsEnabled;

    @Value("${ido.cors.allowed-origins:http://localhost:3000,http://localhost:3001}")
    private List<String> allowedOrigins;

    /**
     * X-Agency-Key 검증 인터셉터 등록
     *
     * <p>적용 경로:
     * <ul>
     *   <li>{@code POST /api/v1/handoff/verify}  — 기관이 티켓 소비 시 API Key 필수</li>
     *   <li>{@code POST /api/v1/handoff/issue}   — 내부 연동 발급 시 API Key 필수</li>
     *   <li>{@code GET  /api/v1/agency/events}   — P1-06 기관 이벤트 폴링 (API Key 필수)</li>
     *   <li>{@code POST /api/v1/agency/events/**} — 읽음 처리 (API Key 필수)</li>
     * </ul>
     *
     * <p>제외 경로: {@code /api/v1/handoff/{ticketId}} (DELETE 취소는 관리 API 별도 인증)
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(handoffAgencyKeyInterceptor)
                .addPathPatterns(
                        "/api/v1/handoff/verify",
                        "/api/v1/handoff/issue",
                        // P1-06: 기관 이벤트 폴링 API — X-Agency-Key 검증 필수
                        "/api/v1/agency/**"
                );

        // S9-T7: auth 엔드포인트 IP 기반 Rate Limiting
        // - /api/v1/auth/** 경로 전체 적용
        // - OPTIONS(CORS preflight)는 인터셉터 내부에서 제외 처리
        registry.addInterceptor(authRateLimitInterceptor)
                .addPathPatterns("/api/v1/auth/**");

        // ────────────────────────────────────────────────────────────────
        // F4.8 (Sprint β-2) — 내부 호출자 인증
        //
        // /api/v1/fe-session (POST) 및 /api/v1/fe-session/conversion (POST) 은
        // Q-Sign 등 내부 서비스에서만 호출되어야 한다. 본 인터셉터로
        // X-Internal-Caller + X-Internal-Api-Key 헤더 검증을 강제한다.
        //
        // 단, /check (GET) 와 /logout (POST) 은 최종 사용자(쿠키 보유자)가 직접
        // 호출하므로 본 인터셉터 대상이 아니다 — 명시적으로 excludePathPatterns 적용.
        registry.addInterceptor(internalCallerAuthInterceptor)
                .addPathPatterns(
                        "/api/v1/fe-session",
                        "/api/v1/fe-session/",
                        "/api/v1/fe-session/conversion"
                )
                .excludePathPatterns(
                        "/api/v1/fe-session/check",
                        "/api/v1/fe-session/logout"
                );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (!corsEnabled) return;

        String[] origins = allowedOrigins.stream()
                .map(String::trim)
                .toArray(String[]::new);

        // /api/v1/fe-session/**: FE 세션 API (feSessionId 쿠키 포함)
        registry.addMapping("/api/v1/fe-session/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)   // feSessionId 쿠키 허용 (필수)
                .maxAge(3600);

        // /api/v1/handoff/**: Handoff Ticket API
        registry.addMapping("/api/v1/handoff/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);

        // /api/v1/agency/**: 기관 이벤트 폴링 API (P1-06)
        registry.addMapping("/api/v1/agency/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)   // 기관 서버 간 통신 — 쿠키 불필요
                .maxAge(3600);

        // ── S7-T2: NICE/OACX 본인인증 API ─────────────────────────────────────
        // /api/v1/auth/**: 본인인증 API (NICE 휴대폰, OACX 간편서명, 기업인증 콜백)
        //
        // Q1=B 결정: API Key 인터셉터 없음
        // - 운영: Nginx same-origin 프록시로 보안 처리
        // - 개발: webpack proxy (port 3000 → 8083)로 same-origin 처리
        //
        // allowCredentials=true: feSessionId 쿠키 포함 (세션 연동 가능성 대비)
        // 향후 NICE/OACX 결과를 FE 세션과 연결할 경우 쿠키 공유 필요
        registry.addMapping("/api/v1/auth/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);

        // /actuator/**: 헬스체크 (읽기 전용)
        registry.addMapping("/actuator/**")
                .allowedOrigins(origins)
                .allowedMethods("GET")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
