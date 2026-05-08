package kr.go.smes.ido.fe.config;

import kr.go.smes.ido.config.HandoffAgencyKeyInterceptor;
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
 */
@Configuration
@RequiredArgsConstructor
public class IdoWebMvcConfig implements WebMvcConfigurer {

    private final HandoffAgencyKeyInterceptor handoffAgencyKeyInterceptor;

    @Value("${ido.cors.enabled:true}")
    private boolean corsEnabled;

    @Value("${ido.cors.allowed-origins:http://localhost:3000,http://localhost:3001}")
    private List<String> allowedOrigins;

    /**
     * X-Agency-Key 검증 인터셉터 등록
     *
     * <p>적용 경로:
     * <ul>
     *   <li>{@code POST /api/v1/handoff/verify} — 기관이 티켓 소비 시 API Key 필수</li>
     *   <li>{@code POST /api/v1/handoff/issue}  — 내부 연동 발급 시 API Key 필수</li>
     * </ul>
     *
     * <p>제외 경로: {@code /api/v1/handoff/{ticketId}} (DELETE 취소는 관리 API 별도 인증)
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(handoffAgencyKeyInterceptor)
                .addPathPatterns(
                        "/api/v1/handoff/verify",
                        "/api/v1/handoff/issue"
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

        // /actuator/**: 헬스체크 (읽기 전용)
        registry.addMapping("/actuator/**")
                .allowedOrigins(origins)
                .allowedMethods("GET")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
