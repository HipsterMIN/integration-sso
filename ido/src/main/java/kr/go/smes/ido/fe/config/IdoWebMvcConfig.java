package kr.go.smes.ido.fe.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
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
public class IdoWebMvcConfig implements WebMvcConfigurer {

    @Value("${ido.cors.enabled:true}")
    private boolean corsEnabled;

    @Value("${ido.cors.allowed-origins:http://localhost:3000,http://localhost:3001}")
    private List<String> allowedOrigins;

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
